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
import splitties.views.dsl.constraintlayout.before
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
    private val styleProvider: () -> com.osfans.trime.ime.appearance.FloatingCandidateStyle? = { com.osfans.trime.ime.appearance.FloatingCandidateStyle.current(scope.theme.isCustom) },
) : Ui {
    private fun createIcon(
        @DrawableRes icon: Int,
    ) = imageView {
        imageTintList = ColorStateList.valueOf(scope.colors.keyTextColor)
        imageDrawable = drawable(icon)
        scaleType = ImageView.ScaleType.CENTER_CROP
        setPadding(0, 0, 0, 0)
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
            val w = dp(10)
            val h = dp(20)
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
                    before(nextIcon)
                },
            )
        }

    private var bottomLayout: Boolean? = null
    private var iconSize = -1
    private var appliedStyle: com.osfans.trime.ime.appearance.FloatingCandidateStyle? = null
    fun configure(bottom: Boolean) {
        val size = styleProvider()?.arrowSize ?: if(bottom) 36 else 20
        if(bottomLayout==bottom && iconSize==size) return
        bottomLayout=bottom; iconSize=size
        val w = if(bottom) { if(styleProvider()==null) ctx.dp(44) else maxOf(ctx.dp(size),ctx.dp(32)) } else ctx.dp(size/2)
        val h = ctx.dp(size)
        nextIcon.layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(w,h).apply {
            endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = 0; bottomToBottom = 0
        }
        prevIcon.layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(w,h).apply {
            if (bottom) startToStart = 0 else endToStart = nextIcon.id
            topToTop = 0; bottomToBottom = 0
        }
        prevIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        nextIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    fun update(paged: Candidates.Paged) {
        // Re-apply the tint as well: a scheme switch rebinds the pagination holder
        // (see PagedCandidatesUi.refreshColors), which only calls update().
        val style=styleProvider()
        val tint = ColorStateList.valueOf(style?.color("arrow",scope.colors.keyTextColor) ?: scope.colors.keyTextColor)
        if(style != appliedStyle) {
            listOf(prevIcon,nextIcon).forEach { icon -> icon.background=style?.let {
                com.osfans.trime.ime.appearance.FloatingCandidateRenderer.surface(icon,it.pager,com.osfans.trime.ime.appearance.KeyEffects(),android.graphics.Color.TRANSPARENT,6f,it.glassOpacity)
            } }
            appliedStyle=style
        }
        prevIcon.imageTintList = tint
        nextIcon.imageTintList = tint
        prevIcon.isEnabled = paged.hasPrevPage
        nextIcon.isEnabled = paged.hasNextPage
        prevIcon.alpha = if (paged.hasPrevPage) 1f else disabledAlpha
        nextIcon.alpha = if (paged.hasNextPage) 1f else disabledAlpha
    }
}
