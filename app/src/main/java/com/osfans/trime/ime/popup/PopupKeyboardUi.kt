/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.popup

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.isIconFont
import com.osfans.trime.ime.keyboard.toIconName
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.gravityCenter
import splitties.views.gravityEnd
import splitties.views.gravityStart
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * @param ctx [Context]
 * @param scope [ThemeScope]
 * @param outerBounds bound [Rect] of [PopupDelegate] root view.
 * @param triggerBounds bound [Rect] of popup trigger view. Used to calculate free space of both sides and
 * determine column order. See [focusColumn] and [columnOrder].
 * @param onDismissSelf callback when popup keyboard wants to close
 * @param radius popup keyboard and key radius
 * @param keyWidth key width in popup keyboard
 * @param keyHeight key height in popup keyboard
 * @param popupHeight popup preview view height. Used to transform gesture coordinate from
 * trigger view to popup keyboard view. See [offsetX] and [offsetY].
 * @param keys character to commit when triggered
 * @param labels symbols to show on keys
 */
class PopupKeyboardUi(
    override val ctx: Context,
    scope: ThemeScope,
    outerBounds: Rect,
    triggerBounds: Rect,
    onDismissSelf: PopupContainerUi.() -> Unit = {},
    private val radius: Float,
    private val keyWidth: Int,
    private val keyHeight: Int,
    private val popupHeight: Int,
    private val keys: List<String>,
    private val labels: List<String>,
) : PopupContainerUi(ctx, scope, outerBounds, triggerBounds, onDismissSelf) {

    val theme: Theme get() = scope.theme

    /** 「字体与字号 → 按键弹出预览」设了颜色就盖过主题配色。 */
    private val popupTextColor: Int
        get() = com.osfans.trime.data.keymap.LayoutTypography.colorOr("popup", scope.colors.popupTextColor)

    inner class PopupKeyUi(val text: String) : Ui {
        override val ctx: Context = this@PopupKeyboardUi.ctx

        val textView = view(::AutoScaleTextView) {
            scaleMode = AutoScaleTextView.Mode.Proportional
            textSize = com.osfans.trime.data.keymap.LayoutTypography.size("popup", theme.style.popupTextSize).let { if(it <= 0f) 22f else it }
            setTextColor(popupTextColor)
            typeface = theme.fonts.popup
        }

        val imageView = view(::AppCompatImageView) {}

        override val root = frameLayout {
            add(
                textView,
                lParams {
                    gravity = gravityCenter
                },
            )
            add(
                imageView,
                lParams {
                    gravity = gravityCenter
                },
            )
        }

        init {
            if (text.isIconFont) {
                imageView.setImageDrawable(
                    IconicsDrawable(ctx, text.toIconName()).apply {
                        sizeDp = com.osfans.trime.data.keymap.LayoutTypography.size("popup", theme.style.popupTextSize).let { if(it <= 0f) 22f else it }.toInt()
                        colorFilter = PorterDuffColorFilter(popupTextColor, PorterDuff.Mode.SRC_IN)
                    },
                )
                imageView.isVisible = true
                textView.isVisible = false
            } else {
                textView.text = text
                textView.isVisible = true
                imageView.isVisible = false
            }
        }
    }

    private val inactiveBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(scope.colors.popupBackColor)
    }

    private val focusBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(scope.colors.hilitedPopupBackColor)
    }

    private val rowCount: Int
    private val columnCount: Int

    // those 2 variables meas initial focus row/column during initialization
    private val focusRow: Int
    private val focusColumn: Int

    init {
        val keyCount: Float = keys.size.toFloat()
        rowCount = PopupChoices.rows(keys.size)
        columnCount = PopupChoices.columns(keys.size)

        focusRow = 0
        focusColumn = calcInitialFocusedColumn(columnCount, keyWidth, outerBounds, triggerBounds)
    }

    /**
     * Offset on X axis made up of 2 parts:
     *  1. from trigger view bounds left to popup entry view left
     *  2. from left-most column to initial focused column
     *
     * Offset on Y axis made up of 2 parts as well:
     *  1. from trigger view top to popup entry view top
     *  2. from top-most row to initial focused row (bottom row)
     *
     * ```
     *                    c───┬───┬───┐
     *                    │   │ 4 │ 5 │
     *                 ┌─ ├───p───┼───┤ ─┐
     *   popupKeyHeight│  │ 3 │ 1 │ 2 │  │
     *                 └─ └───┼───┼───┘  │
     *                        │   │      │popupHeight
     *                 ┌───── │o─┐│      │
     *  bounds.height()│      ││a││      │
     *                 └───── └┴─┴┘ ─────┘
     * ```
     * o: trigger view top-left origin
     *
     * p: popup preview ([PopupEntryUi]) top-left origin
     *
     * c: container view top-left origin
     *
     * Applying only `1.` parts of both X and Y offset, the origin should transform from `o` to `p`.
     * `2.` parts of both offset transform it from `p` to `c`.
     */
    override val offsetX = ((triggerBounds.width() - keyWidth) / 2) - (keyWidth * focusColumn)
    override val offsetY = (triggerBounds.height() - popupHeight) - (keyHeight * (rowCount - 1))

    private val columnOrder = createColumnOrder(columnCount, focusColumn)

    /**
     * row with smaller index displays at bottom.
     * for example, keyOrders array:
     * ```
     * [[2, 0, 1, 3], [6, 4, 5, 7]]
     * ```
     * displays as
     * ```
     * | 6 | 4 | 5 | 7 |
     * | 2 | 0 | 1 | 3 |
     * ```
     * in which `0` indicates default focus
     */
    private val keyOrders = Array(rowCount) { row ->
        IntArray(columnCount) { col -> row * columnCount + columnOrder[col] }
    }

    private var focusedIndex = keyOrders[focusRow][focusColumn]

    private val keyUis = labels.map { label -> PopupKeyUi(displayLabel(label)) }

    /**
     * 备选项可能是符号、表情、动作名，也可能是 `[]{Left}`、`""{Left}` 这种「打一段字再把光标退回去」
     * 的键序列。键序列只能显示可见文字，绝不能把 `{Left}` 这类占位符当成标签画出来。
     */
    private fun displayLabel(raw: String): String {
        if (raw.isEmpty()) return ""
        val keyboard = KeyboardWindow.currentKeyboard
        val named = raw in theme.presetKeys || raw in Theme.BUILTIN_ACTIONS ||
            com.osfans.trime.data.keymap.BindingActions.resolve(raw) != null
        // 动作名、绑定 token、带占位符的键序列都交给 KeyAction 取标签（它会把 {…} 去掉）
        if (named || raw.contains('{')) {
            val resolved = runCatching { theme.resolveAction(raw).getLabel(keyboard) }.getOrNull().orEmpty()
            if (resolved.isIconFont) return resolved
            if (resolved.isNotEmpty()) return shorten(resolved)
            // 整条就是一个占位符，例如 `{Left}`：退回占位符本身的名字
            val inner = PopupChoices.BRACED.matchEntire(raw)?.groupValues?.get(1)
            if (inner != null) {
                val label = runCatching { theme.resolveAction(inner).getLabel(keyboard) }.getOrNull().orEmpty()
                if (label.isIconFont) return label
                if (label.isNotEmpty()) return shorten(label)
            }
            return shorten(raw.replace(PopupChoices.BRACED, ""))
        }
        // 纯字面量（中文、表情、用户自己输入的文字）
        return shorten(raw)
    }

    /** 超过 4 个字符就只留一个字素，避免一格塞不下糊成一片。 */
    private fun shorten(text: String): String =
        if (text.length <= 4) text else PopupChoices.compactActionLabel(text)

    init {
        markFocus(focusedIndex)
    }

    override val root = verticalLayout root@{
        background = inactiveBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        // add rows in reverse order, because newly added view shows at bottom
        for (i in rowCount - 1 downTo 0) {
            val order = keyOrders[i]
            add(
                horizontalLayout row@{
                    for (j in 0 until columnCount) {
                        val keyUi = keyUis.getOrNull(order[j])
                        if (keyUi == null) {
                            // align columns to right (end) when first column is empty, eg.
                            // |   | 6 | 5 | 4 |(no free space)
                            // | 3 | 2 | 1 | 0 |(no free space)
                            gravity = if (j == 0) gravityEnd else gravityStart
                        } else {
                            add(keyUi.root, lParams(keyWidth, keyHeight))
                        }
                    }
                },
                lParams(width = matchParent),
            )
        }
    }

    private fun markFocus(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = focusBackground
            val color = scope.colors.hilitedPopupTextColor
            textView.setTextColor(color)
            imageView.drawable?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun markInactive(index: Int) {
        keyUis.getOrNull(index)?.apply {
            root.background = null
            val color = popupTextColor
            textView.setTextColor(color)
            imageView.drawable?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
    }

    override fun refreshColors() {
        inactiveBackground.setColor(scope.colors.popupBackColor)
        focusBackground.setColor(scope.colors.hilitedPopupBackColor)
        keyUis.forEachIndexed { index, _ ->
            if (index == focusedIndex) markFocus(index) else markInactive(index)
        }
    }

    /** 按下点（相对弹窗左上角），用来把「手指位移」换算成「走了几格」。 */
    private var anchorX = Float.NaN
    private var anchorY = Float.NaN

    override fun onChangeFocus(x: Float, y: Float): Boolean {
        // 跟手滑选：以按下点为原点算位移，手指移动半格就切一格，
        // 不会因为按在键面偏上/偏下的位置而一上来就选错行。
        if (anchorX.isNaN()) {
            anchorX = x
            anchorY = y
        }
        val stepColumn = ((x - anchorX) / keyWidth).roundToInt()
        val stepRow = ((anchorY - y) / keyHeight).roundToInt()
        var newColumn = focusColumn + stepColumn
        var newRow = focusRow + stepRow
        // 滑出格子太多才关闭，避免稍微甩一下就断；留一格余量方便回拉。
        if (newRow < -3 || newRow > rowCount + 3 || newColumn < -3 || newColumn > columnCount + 3) {
            onDismissSelf(this)
            return true
        }
        newRow = limitIndex(newRow, rowCount)
        newColumn = limitIndex(newColumn, columnCount)
        val newFocus = keyOrders[newRow][newColumn]
        if (newFocus < keyUis.size) {
            markInactive(focusedIndex)
            markFocus(newFocus)
            focusedIndex = newFocus
        }
        return false
    }

    override fun onTrigger(): String? = keys.getOrNull(focusedIndex)
}
