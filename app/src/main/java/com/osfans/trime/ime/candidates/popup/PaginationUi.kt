/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.styledFloat
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.imageDrawable

class PaginationUi(
    override val ctx: Context,
    private val scope: ThemeScope,
) : Ui {
    private fun createIcon(
        @DrawableRes icon: Int,
    ) = imageView {
        imageTintList = ColorStateList.valueOf(scope.colors.keyTextColor)
        imageDrawable = drawable(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    val prevIcon = createIcon(R.drawable.ic_baseline_arrow_left_24)
    val nextIcon = createIcon(R.drawable.ic_baseline_arrow_right_24)

    init {
        prevIcon.contentDescription = "上一页"
        nextIcon.contentDescription = "下一页"
    }
    private val disabledAlpha = ctx.styledFloat(android.R.attr.disabledAlpha)

    override val root =
        constraintLayout {
            minimumWidth = dp(144)
            val w = dp(44)
            val h = dp(44)
            add(
                nextIcon,
                lParams(w, h) {
                    centerVertically()
                    endOfParent()
                },
            )
            add(
                prevIcon,
                lParams(w, h) {
                    centerVertically()
                    startOfParent()
                },
            )
        }

    fun update(paged: Candidates.Paged) {
        // Re-apply the tint as well: a scheme switch rebinds the pagination holder
        // (see PagedCandidatesUi.refreshColors), which only calls update().
        val tint = ColorStateList.valueOf(scope.colors.keyTextColor)
        prevIcon.imageTintList = tint
        nextIcon.imageTintList = tint
        prevIcon.isEnabled = paged.hasPrevPage
        nextIcon.isEnabled = paged.hasNextPage
        prevIcon.alpha = if (paged.hasPrevPage) 1f else disabledAlpha
        nextIcon.alpha = if (paged.hasNextPage) 1f else disabledAlpha
    }
}
