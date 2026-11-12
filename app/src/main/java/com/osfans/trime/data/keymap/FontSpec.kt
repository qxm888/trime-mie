/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import kotlinx.serialization.Serializable

/** 文字颜色（`#AARRGGBB`/`#RRGGBB`），空 = 跟随主题配色。 */
@Serializable
data class FontSpec(val font: String = "", val size: Float = 0f, val color: String = "")

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
            // 「工具栏」是整套共享的外观，读整套根键盘；其余角色（按键/候选/注释等）
            // 仍按当前页读取，避免误伤每键盘独立的字体覆盖。
            if (role == "toolbar")
                CustomKeyboards.setTypography(
                    com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId, role,
                )
            else
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
    /**
     * 自定义文字颜色；未设置或色值非法时返回 [fallback]（跟随主题配色）。
     *
     * 两套来源严格分开：自定义键盘/内置皮肤读本套布局存的 `typography[role].color`，
     * 主题模式读「主题键盘字体与字号」全局页存的 `color.<role>`。
     */
    fun colorOr(role: String, fallback: Int, custom: Boolean = CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM): Int {
        val raw = if (custom) {
            spec(role)?.color.orEmpty().trim()
        } else {
            com.osfans.trime.data.theme.FontOverrides.color(role).trim()
        }
        return if (raw.isEmpty()) fallback else runCatching { android.graphics.Color.parseColor(raw) }.getOrDefault(fallback)
    }
}
