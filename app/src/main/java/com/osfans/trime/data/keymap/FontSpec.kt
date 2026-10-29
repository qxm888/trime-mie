/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import kotlinx.serialization.Serializable

@Serializable
data class FontSpec(val font: String = "", val size: Float = 0f)

object LayoutTypography {
    private fun spec(role: String): FontSpec? =
        if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM)
            com.osfans.trime.ime.appearance.SurfaceAppearance.currentLayout()?.typography?.get(role)
        else null
    fun typeface(role: String, custom: Boolean) = if (custom)
        com.osfans.trime.data.theme.FontOverrides.fromSelection(spec(role)?.font.orEmpty()) else null
    fun size(role: String, fallback: Float): Float =
        (if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) spec(role)?.size
        else com.osfans.trime.data.theme.FontOverrides.size(role))
            ?.takeIf { it.isFinite() && it in 1f..200f } ?: fallback
}
