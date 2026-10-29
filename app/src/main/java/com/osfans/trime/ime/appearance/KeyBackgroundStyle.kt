/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.drawable.GradientDrawable
import java.util.IdentityHashMap

/** Keep geometry for normal/pressed/modifier drawables instead of forgetting on each press. */
internal class KeyBackgroundStyle {
    private data class Geometry(var radius: Float=Float.NaN,var width: Int=-1,var color: Int=0)
    private val entries=IdentityHashMap<GradientDrawable,Geometry>()
    fun apply(background: GradientDrawable, corner: Float, width: Int, color: Int) {
        val previous=entries[background] ?: run {
            if(entries.size>=8) entries.clear()
            Geometry().also { entries[background]=it }
        }
        if(previous.radius!=corner) { background.cornerRadius=corner;previous.radius=corner }
        if(previous.width!=width || previous.color!=color) {
            background.setStroke(width,color);previous.width=width;previous.color=color
        }
    }
}
