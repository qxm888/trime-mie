// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi

fun rippleDrawable(
    @ColorInt color: Int,
) = RippleDrawable(ColorStateList.valueOf(color), null, ColorDrawable(Color.WHITE))

/** 和 [roundedRippleDrawable] 一样，只是四角走 G2 连续曲线（候选高亮用）。 */
fun squircleRippleDrawable(
    @ColorInt color: Int,
    radiusPx: Float,
    contentColor: Int = Color.TRANSPARENT,
): RippleDrawable {
    val content = contentColor.takeUnless { it == Color.TRANSPARENT }?.let {
        com.osfans.trime.ime.appearance.SquircleDrawable(it, radiusPx)
    }
    val mask = com.osfans.trime.ime.appearance.SquircleDrawable(Color.WHITE, radiusPx)
    return RippleDrawable(ColorStateList.valueOf(color), content, mask)
}

fun roundedRippleDrawable(
    @ColorInt color: Int,
    cornerRadius: Float,
    contentColor: Int = Color.TRANSPARENT,
): RippleDrawable {
    val contentDrawable = contentColor.takeUnless { it == Color.TRANSPARENT }?.let {
        GradientDrawable().apply {
            setColor(it)
            this.cornerRadius = cornerRadius
        }
    }
    val maskDrawable = GradientDrawable().apply {
        setColor(Color.WHITE)
        this.cornerRadius = cornerRadius
    }
    return RippleDrawable(ColorStateList.valueOf(color), contentDrawable, maskDrawable)
}

@RequiresApi(Build.VERSION_CODES.M)
fun borderlessRippleDrawable(
    @ColorInt color: Int,
    r: Int = RippleDrawable.RADIUS_AUTO,
) = RippleDrawable(ColorStateList.valueOf(color), null, null).apply {
    radius = r
}

fun pressHighlightDrawable(
    @ColorInt color: Int,
) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(color))
}

fun circlePressHighlightDrawable(
    @ColorInt color: Int,
) = StateListDrawable().apply {
    addState(
        intArrayOf(android.R.attr.state_pressed),
        ShapeDrawable(OvalShape()).apply { paint.color = color },
    )
}

fun borderDrawable(
    width: Int,
    @ColorInt stroke: Int,
    @ColorInt background: Int = Color.TRANSPARENT,
) = GradientDrawable().apply {
    setStroke(width, stroke)
    setColor(background)
}

fun borderDrawable(
    width: Int,
    radius: Float,
    alpha: Int,
    @ColorInt stroke: Int,
    @ColorInt background: Int = Color.TRANSPARENT,
) = GradientDrawable().apply {
    setStroke(width, stroke)
    cornerRadius = radius
    setColor(background)
    setAlpha(alpha)
}
