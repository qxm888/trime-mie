/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.popup

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.isVisible
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.keyboard.isIconFont
import com.osfans.trime.ime.keyboard.toIconName
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter

class PopupEntryUi(
    override val ctx: Context,
    private val scope: ThemeScope,
    keyHeight: Int,
    radius: Float,
) : Ui {
    private val theme: Theme get() = scope.theme

    /** 「字体与字号 → 按键弹出预览」设了颜色就盖过主题配色。 */
    private val popupTextColor: Int
        get() = com.osfans.trime.data.keymap.LayoutTypography.colorOr("popup", scope.colors.popupTextColor)

    var lastShowTime = -1L

    val textView = view(::AutoScaleTextView) {
        scaleMode = AutoScaleTextView.Mode.Proportional
        textSize = com.osfans.trime.data.keymap.LayoutTypography.size("popup", theme.style.popupTextSize)
        gravity = gravityCenter
        setTextColor(popupTextColor)
        typeface = theme.fonts.popup
    }

    val imageView = view(::AppCompatImageView) {
        visibility = android.view.View.GONE
    }

    private val popupBackground = GradientDrawable().apply {
        cornerRadius = radius
        setColor(scope.colors.popupBackColor)
    }

    override val root = constraintLayout {
        background = popupBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(2f)
        add(
            textView,
            lParams(wrapContent, keyHeight) {
                topOfParent()
                centerHorizontally()
            },
        )
        add(
            imageView,
            lParams(wrapContent, keyHeight) {
                topOfParent()
                centerHorizontally()
            },
        )
    }

    /** Re-applies the scheme colors, as pooled popups outlive scheme switches. */
    fun refreshColors() {
        popupBackground.setColor(scope.colors.popupBackColor)
        textView.setTextColor(popupTextColor)
        imageView.drawable?.colorFilter =
            PorterDuffColorFilter(popupTextColor, PorterDuff.Mode.SRC_IN)
    }

    fun setText(text: String) {
        if (text.isIconFont) {
            imageView.setImageDrawable(
                IconicsDrawable(ctx, text.toIconName()).apply {
                    sizeDp = com.osfans.trime.data.keymap.LayoutTypography.size("popup", theme.style.popupTextSize).toInt()
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
