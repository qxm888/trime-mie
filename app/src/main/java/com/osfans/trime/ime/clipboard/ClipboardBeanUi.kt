/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.view.Gravity
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.osfans.trime.R
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.GestureFrame
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp

class ClipboardBeanUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    private val theme: Theme get() = scope.theme

    val textView =
        textView {
            minLines = 1
            maxLines = 4
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            typeface = theme.fonts.key
            // Left inset clears the raised (3D) edge of the key background;
            // asymmetric vertical padding nudges the text slightly upward.
            setPaddingDp(22, 2, 8, 10)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(scope.colors.keyTextColor)
        }

    val dragHandle =
        imageView {
            imageDrawable =
                drawable(R.drawable.ic_baseline_drag_handle_24)!!.apply {
                    setTint(scope.colors.keySymbolColor)
                }
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

    val pin =
        imageView {
            imageDrawable =
                drawable(R.drawable.ic_baseline_push_pin_24)!!.apply {
                    setTint(scope.colors.keySymbolColor)
                    setAlpha(0.3f)
                }
        }

    val categoryChip =
        textView {
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPaddingDp(6, 1, 6, 1)
            setTextColor(scope.colors.keySymbolColor)
        }

    val layout =
        constraintLayout {
            add(
                textView,
                lParams(matchParent, wrapContent) {
                    // Constrain both edges so the parent (wrap_content) takes the
                    // text height; centering the text inside itself then keeps the
                    // row visually balanced.
                    topOfParent()
                    bottomOfParent()
                },
            )
            add(
                pin,
                lParams(dp(12), dp(12)) {
                    bottomOfParent(dp(2))
                    endOfParent(dp(2))
                },
            )
            add(
                categoryChip,
                lParams(wrapContent, wrapContent) {
                    topOfParent(dp(2))
                    endOfParent(dp(4))
                },
            )
            add(
                dragHandle,
                lParams(dp(16), dp(16)) {
                    centerVertically()
                    startOfParent(dp(2))
                },
            )
        }

    override val root =
        GestureFrame(ctx).apply {
            isClickable = true
            minimumHeight = dp(30)
            background =
                scope.decorDrawable(
                    "key_back_color",
                    "key_border_color",
                    dp(theme.style.keyBorder),
                    dp(theme.style.roundCorner),
                )
            add(
                layout,
                FrameLayout.LayoutParams(matchParent, wrapContent).apply {
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
            layoutParams = lParams(matchParent, wrapContent)
        }

    fun setBean(
        text: String,
        pinned: Boolean,
        categoryLabel: String? = null,
    ) {
        textView.text = text
        pin.visibility = if (pinned) View.VISIBLE else View.GONE
        categoryChip.text = categoryLabel
        categoryChip.visibility = if (categoryLabel.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    /** Restyles the row after a scheme switch. */
    fun refreshColors() {
        textView.setTextColor(scope.colors.keyTextColor)
        pin.drawable?.setTint(scope.colors.keySymbolColor)
        categoryChip.setTextColor(scope.colors.keySymbolColor)
        root.background =
            scope.decorDrawable(
                "key_back_color",
                "key_border_color",
                ctx.dp(theme.style.keyBorder),
                ctx.dp(theme.style.roundCorner),
            )
    }
}
