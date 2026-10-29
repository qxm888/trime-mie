/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.drawable.GradientDrawable

/** Drawable setters invalidate their host, even when assigning the same geometry. */
internal class KeyBackgroundStyle {
    private var drawable: GradientDrawable? = null
    private var radius = Float.NaN
    private var strokeWidth = -1
    private var strokeColor = 0

    fun apply(background: GradientDrawable, corner: Float, width: Int, color: Int) {
        val changed = drawable !== background
        if (changed || radius != corner) {
            background.cornerRadius = corner
            radius = corner
        }
        if (changed || strokeWidth != width || strokeColor != color) {
            background.setStroke(width, color)
            strokeWidth = width
            strokeColor = color
        }
        drawable = background
    }
}
