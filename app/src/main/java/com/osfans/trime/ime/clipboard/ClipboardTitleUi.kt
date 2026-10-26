/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.core.view.isVisible
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.core.InputTabLayout
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.wrapContent

class ClipboardTitleUi(override val ctx: Context, private val scope: ThemeScope) : Ui {
    private val theme: Theme get() = scope.theme

    val tabLayout = InputTabLayout(ctx)

    lateinit var tabScroll: HorizontalScrollView


    /** One fixed trailing action slot; tabs scroll only within the remaining width. */
    val actionsButton = ToolButton(ctx, R.drawable.ic_baseline_more_horiz_24, scope).apply {
        contentDescription = ctx.getString(R.string.clipboard_actions)
    }

    /** 退出搜索按钮。 */
    val clearSearchButton = ToolButton(ctx, R.drawable.ic_outline_cancel_24, scope)

    /** 搜索输入提示行（实际输入经 ClipboardSearchBridge 由键盘路由进来）。 */
    val searchInput =
        android.widget.TextView(ctx).apply {
            hint = ctx.getString(R.string.dahi_clipboard_search_hint)
            isSingleLine = true
            textSize = theme.style.candidateTextSize
            setTextColor(ColorManager.getColor("key_text_color"))
            setHintTextColor(ColorManager.getColor("key_symbol_color"))
            background = null
            isVisible = false
        }

    private val size = theme.style.run { candidateViewHeight + commentHeight }

    override val root = constraintLayout {
        // Categories can outnumber the available width: keep the tab strip
        // scrollable instead of letting it overflow off screen.
        tabScroll =
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    tabLayout,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(size)),
                )
            }
        add(
            tabScroll,
            lParams(0, dp(size)) {
                startOfParent()
                endToStartOf(actionsButton)
                centerVertically()
                marginEnd = dp(4)
            },
        )
        add(
            searchInput,
            lParams(0, wrapContent) {
                startOfParent(dp(8))
                endOfParent(dp(size))
                centerVertically()
            },
        )
        add(
            clearSearchButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endOfParent()
            },
        )
        add(
            actionsButton,
            lParams(dp(size), dp(size)) {
                centerVertically()
                endOfParent()
            },
        )
    }

    init {
        clearSearchButton.isVisible = false
    }

    /** 切换搜索模式：隐藏标签栏与常规按钮、显示搜索提示行。 */
    fun setSearchActive(active: Boolean) {
        tabScroll.isVisible = !active
        actionsButton.isVisible = !active
        searchInput.isVisible = active
        clearSearchButton.isVisible = active
    }
}
