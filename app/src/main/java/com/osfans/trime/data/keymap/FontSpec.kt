/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import kotlinx.serialization.Serializable

@Serializable
data class FontSpec(val font: String = "", val size: Float = 0f)

object LayoutTypography {
    const val GLOBAL = "global"
    private var globalVersion = -1L
    private var globalId = ""
    private var globalValue = ""
    @Synchronized private fun currentGlobal(): String {
        val id = com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId
        val version = CustomKeyboards.version
        if (version != globalVersion || id != globalId) {
            globalValue = CustomKeyboards.globalFont(id)
            globalVersion = version; globalId = id
        }
        return globalValue
    }
    private fun spec(role: String): FontSpec? =
        if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM)
            com.osfans.trime.ime.appearance.SurfaceAppearance.currentLayout()?.typography?.get(role)
        else null
    fun typeface(role: String, custom: Boolean) = if (custom && CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM)
        com.osfans.trime.data.theme.FontOverrides.fromSelection(spec(role)?.font.orEmpty())
            ?: com.osfans.trime.data.theme.FontOverrides.fromSelection(currentGlobal())
            ?: android.graphics.Typeface.DEFAULT else null
    fun size(role: String, fallback: Float, custom: Boolean = CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM): Float =
        (if (custom) spec(role)?.size
        else com.osfans.trime.data.theme.FontOverrides.size(role))
            ?.takeIf { it.isFinite() && it in 1f..200f } ?: fallback
}
