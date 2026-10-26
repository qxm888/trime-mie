/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.widget.ViewAnimator
import androidx.annotation.DrawableRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.theme.model.ToolBar
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class AlwaysUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val onButtonClick: ((String) -> Unit)? = null,
) : Ui {
    private val theme: Theme get() = scope.theme

    /** 用户编辑过的工具栏（顺序/启停），主题文件不受影响 */
    private var toolBar: ToolBar = ToolbarOverrides.forKeyboard(theme)

    enum class State {
        Toolbar,
        Clipboard,
        InlineSuggestion,
    }

    var currentState = State.Toolbar
        private set

    private fun toolButton(
        buttonConfig: ToolBar.Button?,
        @DrawableRes icon: Int = 0,
    ): ToolButton = if (buttonConfig != null) {
        ToolButton(ctx, buttonConfig, scope).apply {
            setOnClickListener { onButtonClick?.invoke(buttonConfig.action) }
            val longPressAction = buttonConfig.longPressAction
            if (longPressAction.isNotEmpty()) {
                setOnLongClickListener {
                    onButtonClick?.invoke(longPressAction)
                    true
                }
            }
        }
    } else {
        ToolButton(ctx, icon, scope).apply {
            setOnClickListener { onButtonClick?.invoke("") }
        }
    }

    val buttonsUi = ButtonsBarUi(ctx, scope, onButtonClick, toolBar)

    val clipboardUi = ClipboardSuggestionUi(ctx, scope)

    val inlineSuggestionsUi = InlineSuggestionsUi(ctx)

    val hideKeyboardButton = ToolButton(ctx, R.drawable.ic_baseline_arrow_drop_down_24, scope)
    private val rightMostButton =
        ViewAnimator(ctx).apply {
            add(hideKeyboardButton, lParams(matchParent, matchParent))
            buttonsUi.firstButton?.let { add(it, lParams(matchParent, matchParent)) }
        }

    private val leftMostButton = toolButton(
        toolBar.primaryButton,
        R.drawable.ic_baseline_more_horiz_24,
    )

    /**
     * 悬浮/单手模式下最左侧的收放按钮：工具图标默认收进这里，点击展开/收起。
     * 收起时工具栏中间为空白，按住空白拖动可移动键盘（[onToolbarBlankDrag]）。
     */
    private val floating = com.osfans.trime.ime.keyboard.KeyboardPrefs.run { ctx.isFloatingEffective() && !ctx.oneHandActive() }
    private var toolbarCollapsed = floating && !AppPrefs.defaultInstance().keyboard.floatingToolbarExpanded.getValue()

    /** 收起时展示展开图标，展开时展示收起图标。 */
    private fun collapseIcon() =
        if (toolbarCollapsed) R.drawable.ic_baseline_expand_more_24 else R.drawable.ic_baseline_expand_less_24

    val collapseButton = ToolButton(ctx, collapseIcon(), scope)

    /** 输入栏空白区拖动回调（悬浮收起态），由 InputBarDelegate 转发到 InputView。 */
    var onToolbarBlankDrag: ((android.view.MotionEvent) -> Boolean)? = null

    private val animator =
        ViewAnimator(ctx).apply {
            add(buttonsUi.viewport, lParams(matchParent, matchParent))
            add(clipboardUi.root, lParams(matchParent, matchParent))
            add(inlineSuggestionsUi.root, lParams(matchParent, matchParent))
            // 收起态工具栏中间为空白：触摸转发给悬浮拖动；其余状态/展开态不消费。
            setOnTouchListener { _, event ->
                if (floating && toolbarCollapsed && currentState == State.Toolbar) {
                    onToolbarBlankDrag?.invoke(event) ?: false
                } else {
                    false
                }
            }
        }

    override val root: ConstraintLayout = constraintLayout {
        val (leftWidth, leftHeight) = buttonsUi.getButtonSize(toolBar.primaryButton)
        val (rightWidth, rightHeight) = buttonsUi.getButtonSize(toolBar.buttons.firstOrNull())

        add(
            collapseButton,
            lParams(leftWidth, leftHeight) {
                startOfParent()
                centerVertically()
            },
        )
        add(
            leftMostButton,
            lParams(leftWidth, leftHeight) {
                if (floating) after(collapseButton) else startOfParent()
                centerVertically()
            },
        )
        add(
            rightMostButton,
            lParams(rightWidth, rightHeight) {
                endOfParent()
                centerVertically()
            },
        )
        add(
            animator,
            lParams(matchConstraints, matchParent) {
                after(leftMostButton)
                before(rightMostButton)
                centerVertically()
            },
        )
    }.apply {
        updateRightMostButton(State.Toolbar)
    }

    init {
        // 非悬浮模式不显示收放按钮（占位清零），悬浮模式点击切换收放并持久化。
        collapseButton.visibility = if (floating) android.view.View.VISIBLE else android.view.View.GONE
        collapseButton.setOnClickListener { setToolbarCollapsed(!toolbarCollapsed) }
        applyCollapseState()
    }

    fun applyToolbar(config: ToolBar) {
        toolBar = config
        buttonsUi.applyToolbar(config)
        while (rightMostButton.childCount > 1) rightMostButton.removeViewAt(1)
        buttonsUi.firstButton?.let { rightMostButton.addView(it, android.widget.FrameLayout.LayoutParams(-1, -1)) }
        val (width, height) = buttonsUi.getButtonSize(config.buttons.firstOrNull())
        rightMostButton.updateLayoutParams { this.width = width; this.height = height }
        updateRightMostButton(currentState)
        updateLeftMostButton(currentState)
        applyCollapseState()
    }

    fun updateButtonsStyle(option: String, enabled: Boolean) {
        leftMostButton.updateStyle(option, enabled)
        collapseButton.updateStyle(option, enabled)
        buttonsUi.firstButton?.updateStyle(option, enabled)
        buttonsUi.updateStyle(option, enabled)
    }

    fun toggleOptions(): Set<String> = buildSet {
        leftMostButton.option?.let { add(it) }
        buttonsUi.firstButton?.option?.let { add(it) }
        buttonsUi.root.children.forEach { (it as ToolButton).option?.let { add(it) } }
    }

    fun updateState(state: State) {
        Timber.d("Switch always ui to $state")
        animator.displayedChild = state.ordinal
        currentState = state
        updateRightMostButton(state)
        updateLeftMostButton(state)
        applyCollapseState()
    }

    /** 切换收放并落盘；重建 AlwaysUi 时按偏好恢复。 */
    fun setToolbarCollapsed(collapsed: Boolean) {
        toolbarCollapsed = collapsed
        AppPrefs.defaultInstance().keyboard.floatingToolbarExpanded.setValue(!collapsed)
        collapseButton.setIcon(collapseIcon())
        applyCollapseState()
    }

    /** 收起且处于工具栏态时：藏起工具按钮与主按钮，中间留白供拖动移动键盘。 */
    private fun applyCollapseState() {
        val hideButtons = floating && toolbarCollapsed && currentState == State.Toolbar
        buttonsUi.viewport.visibility = if (hideButtons) android.view.View.GONE else android.view.View.VISIBLE
        leftMostButton.visibility = if (hideButtons) android.view.View.GONE else android.view.View.VISIBLE
        if (hideButtons) rightMostButton.displayedChild = 0 else updateRightMostButton(currentState)
    }

    /** Restyles the toolbar area after a scheme switch. */
    fun refreshColors() {
        leftMostButton.refreshColors()
        collapseButton.refreshColors()
        hideKeyboardButton.refreshColors()
        buttonsUi.refreshColors()
        clipboardUi.refreshColors()
    }

    private fun updateRightMostButton(state: State) {
        val hasFirstButton = buttonsUi.firstButton != null
        val showFirst = hasFirstButton && (toolBar.buttons.isNotEmpty() || state != State.Toolbar)
        rightMostButton.displayedChild = if (showFirst) 1 else 0
    }

    private fun updateLeftMostButton(state: State) {
        val buttonConfig =
            if (state == State.Toolbar) {
                toolBar.primaryButton
            } else {
                toolBar.buttons.firstOrNull()
            }

        val (buttonWidth, buttonHeight) = buttonsUi.getButtonSize(buttonConfig)
        leftMostButton.layoutParams = leftMostButton.layoutParams.apply {
            width = buttonWidth
            height = buttonHeight
        }
    }
}
