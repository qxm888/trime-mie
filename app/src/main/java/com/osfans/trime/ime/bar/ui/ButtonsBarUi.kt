/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.view.children
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.theme.model.ToolBar
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    private val onButtonClick: ((String) -> Unit)? = null,
    initialToolBar: ToolBar = ToolbarOverrides.forKeyboard(scope.theme),
    private val allButtons: Boolean = false,
) : Ui {
    private val theme: Theme get() = scope.theme

    /** 用户编辑过的工具栏（顺序/启停），主题文件不受影响 */
    private var toolBar: ToolBar = initialToolBar

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.FLEX_START
        flexDirection = FlexDirection.ROW_REVERSE
    }

    /**
     * 按钮一律按设定的尺寸绘制，放不下就横向滑动，不再挤压。
     *
     * 贴底模式下以前直接拿 [root] 当视口、也没关掉 flexShrink，图标一多每个按钮就
     * 被压窄，图标跟着缩小（悬浮模式反而是对的：可滑动 + 不压缩）。现在两种模式
     * 共用同一套视口，图标数量和圆角让位都不会再改变图标大小。
     */
    /** 「整排显示」开着时，视口不再响应滑动，改成整排压缩。 */
    private fun fitAll(): Boolean =
        com.osfans.trime.ime.appearance.SurfaceAppearance.toolbarFitAll(theme.isCustom)

    val viewport: android.view.View = object : android.widget.HorizontalScrollView(ctx) {
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean =
            if (fitAll()) false else super.onInterceptTouchEvent(ev)

        override fun onTouchEvent(ev: android.view.MotionEvent): Boolean =
            if (fitAll()) false else super.onTouchEvent(ev)
    }.apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = android.view.View.OVER_SCROLL_NEVER
        clipToPadding = true
        addView(root, android.widget.FrameLayout.LayoutParams(-2, -1))
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) post { applyFit() }
        }
    }

    /** 每个按钮的原始尺寸（未压缩前），整排缩放时以它为基准，避免反复累积缩小。 */
    private val baseSizes = java.util.WeakHashMap<android.view.View, Pair<Int, Int>>()
    private var lastSpacing = -1
    private var lastScale = 0f

    var firstButton: ToolButton? = null
        private set

    private val defaultButtonSize = theme.style.run { candidateViewHeight + commentHeight }

    /**
     * 上/下留白生效时，容器内容区只有 TOOLBAR_ICON_ROW_DP 高；
     * 按钮若还按主题默认的 40–46dp 画，就会溢出/被裁，看起来像「留白没生效」。
     *
     * 留白失效时，按钮高度必须跟工具栏总高（[InputBarDelegate.toolbarHeightPx]）
     * 完全一致：该总高是 `toolbarHeightDp`（若设了）否则主题默认。内置键盘默认
     * `toolbarHeightDp=36` 而主题默认是 46，以前按钮一律回 46，塞进 36 的总高里
     * 上下被裁掉 5dp，图标看起来就「变小」；切到没设 toolbarHeightDp 的页又变回 46，
     * 于是「切一次键盘就变大」。
     */
    private fun currentButtonSize(): Int {
        if (!theme.isCustom) return defaultButtonSize
        val runtimeId = com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId
        // 工具栏上/下留白是整套（全局）外观，读整套根键盘；切中英文/符号页不再跳变。
        val (top, bottom) = com.osfans.trime.data.keymap.CustomKeyboards.setToolbarPads(runtimeId)
        if (top > 0 || bottom > 0) return com.osfans.trime.data.keymap.TOOLBAR_ICON_ROW_DP
        // 留白失效：跟随工具栏总高（当前页 toolbarHeightDp 或主题默认），与 toolbarHeightPx 一致。
        val kb = com.osfans.trime.data.keymap.CustomKeyboards.get(
            com.osfans.trime.data.keymap.CustomKeyboards.resolveId(runtimeId),
        )
        return kb?.toolbarHeightDp?.takeIf { it > 0 } ?: defaultButtonSize
    }

    private fun toolButton(
        buttonConfig: ToolBar.Button?,
        @DrawableRes icon: Int = 0,
    ): ToolButton = if (buttonConfig != null) {
        ToolButton(ctx, buttonConfig, scope).apply {
            bindActions(onButtonClick)
        }
    } else {
        ToolButton(ctx, icon, scope).apply {
            setOnClickListener { onButtonClick?.invoke("") }
        }
    }

    init {
        applyToolbar(toolBar)
    }

    internal fun getButtonSize(config: ToolBar.Button?, customDefaultSize: Int = currentButtonSize()): Pair<Int, Int> {
        val sizeList = config?.size?.takeIf { it.size == 2 } ?: List(2) { customDefaultSize }
        val (width, height) = sizeList

        val finalWidth = if (width >= 0) ctx.dp(width) else width
        val finalHeight = if (height >= 0) ctx.dp(height) else height

        return finalWidth to finalHeight
    }

    fun applyToolbar(config: ToolBar) {
        toolBar = config
        firstButton = if (allButtons) null else config.buttons.firstOrNull()?.let { toolButton(it) }
        if (allButtons) {
            root.flexDirection = FlexDirection.ROW
            root.justifyContent = when (config.alignment) {
                "center" -> JustifyContent.CENTER
                "right" -> JustifyContent.FLEX_END
                "distributed" -> JustifyContent.SPACE_BETWEEN
                else -> JustifyContent.FLEX_START
            }
        }
        root.removeAllViews()
        // 按钮重建后上一轮的缩放/间距防抖标记必须作废，否则新的原始尺寸按钮
        // 会被 applyFit 误判成「已经缩放过」而跳过写回，停在大/小错误尺寸。
        lastSpacing = -1
        lastScale = 0f
        val primary = if (config.primaryVisible) listOf(config.primaryButton ?: ToolBar.Button(
            action = "__mie_menu", foreground = ToolBar.Button.Foreground(style="•••"))) else emptyList()
        val buttons = if (allButtons) primary + config.buttons.asReversed() else config.buttons.drop(1)
        buttons.forEachIndexed { index, buttonConfig ->
            val button = toolButton(buttonConfig)
            val size = getButtonSize(buttonConfig)
            baseSizes[button] = size
            root.addView(button, FlexboxLayout.LayoutParams(size.first, size.second).apply {
                marginEnd = if (allButtons && index == buttons.lastIndex) 0 else ctx.dp(toolBar.buttonSpacing)
                // 不参与压缩：挤不下的部分由视口滚动承担，否则图标会被压小。
                flexShrink = 0f
            })
        }
        viewport.post { applyFit() }
    }

    /**
     * 整排显示：先用剩余宽度算间距，压到 2dp 仍放不下就整排等比缩小。
     * 关闭时把尺寸/间距还原成原始值（滑动的那种形态）。
     */
    private fun applyFit() {
        val count = root.childCount
        if (count == 0 || viewport.width <= 0) return
        val available = viewport.width - viewport.paddingLeft - viewport.paddingRight
        val defaultSpacing = ctx.dp(toolBar.buttonSpacing)
        val minSpacing = ctx.dp(2)
        var sum = 0
        for (i in 0 until count) {
            val child = root.getChildAt(i)
            sum += (baseSizes[child] ?: (child.layoutParams.width to child.layoutParams.height)).first
        }
        var spacing = defaultSpacing
        var scale = 1f
        if (fitAll() && count > 1 && sum + defaultSpacing * (count - 1) > available) {
            val needed = (available - sum) / (count - 1)
            if (needed >= minSpacing) {
                spacing = needed
            } else {
                spacing = minSpacing
                val usable = available - minSpacing * (count - 1)
                scale = if (sum > 0) (usable.toFloat() / sum).coerceIn(0.4f, 1f) else 1f
            }
        }
        if (spacing == lastSpacing && scale == lastScale) return
        lastSpacing = spacing
        lastScale = scale
        for (i in 0 until count) {
            val child = root.getChildAt(i)
            val base = baseSizes[child] ?: continue
            val lp = child.layoutParams as? FlexboxLayout.LayoutParams ?: continue
            // 自适应（-1 等负尺寸）按钮不参与等比缩放，否则 scale<1 时会把 -1 乘成 0→1px。
            val w = base.first
            val h = base.second
            lp.width = if (w >= 0) (w * scale).toInt().coerceAtLeast(1) else w
            lp.height = if (h >= 0) (h * scale).toInt().coerceAtLeast(1) else h
            lp.marginEnd = if (i == count - 1) 0 else spacing
            child.layoutParams = lp
        }
        root.requestLayout()
    }

    fun updateStyle(option: String, enabled: Boolean) {
        root.children.forEach { (it as ToolButton).updateStyle(option, enabled) }
    }

    /** Restyles the toolbar buttons after a scheme switch. */
    fun refreshColors() {
        firstButton?.refreshColors()
        root.children.forEach { (it as ToolButton).refreshColors() }
    }
}
