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
    private val floating = com.osfans.trime.ime.keyboard.KeyboardPrefs.run { ctx.isFloatingEffective() }

    /** 用户编辑过的工具栏（顺序/启停），主题文件不受影响 */
    private var toolBar: ToolBar = initialToolBar

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.FLEX_START
        flexDirection = FlexDirection.ROW_REVERSE
    }

    val viewport: android.view.View = if (floating) android.widget.HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = android.view.View.OVER_SCROLL_NEVER
        clipToPadding = true
        addView(root, android.widget.FrameLayout.LayoutParams(-2, -1))
    } else root

    var firstButton: ToolButton? = null
        private set

    private val defaultButtonSize = theme.style.run { candidateViewHeight + commentHeight }

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

    internal fun getButtonSize(config: ToolBar.Button?, customDefaultSize: Int = defaultButtonSize): Pair<Int, Int> {
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
        val primary = if (config.primaryVisible) listOf(config.primaryButton ?: ToolBar.Button(
            action = "__mie_menu", foreground = ToolBar.Button.Foreground(style="•••"))) else emptyList()
        val buttons = if (allButtons) primary + config.buttons.asReversed() else config.buttons.drop(1)
        buttons.forEachIndexed { index, buttonConfig ->
            val button = toolButton(buttonConfig)
            val size = getButtonSize(buttonConfig)
            root.addView(button, FlexboxLayout.LayoutParams(size.first, size.second).apply {
                marginEnd = if (allButtons && index == buttons.lastIndex) 0 else ctx.dp(toolBar.buttonSpacing)
                if (floating) flexShrink = 0f
            })
        }
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
