/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Color
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SurfaceStyle(
    val background: String = "",
    val borderWidth: Float? = null,
    val borderColor: String = "",
    val radius: Float? = null,
    val blur: Boolean = false,
) {
    val overridesBorder get() = borderWidth != null || borderColor.isNotBlank()
    val customized get() = background.isNotEmpty() || overridesBorder || radius != null || blur
    fun color(fallback: Int) = runCatching { Color.parseColor(borderColor) }.getOrDefault(fallback)
}

@Serializable
data class SurfaceAppearanceConfig(
    val keys: SurfaceStyle = SurfaceStyle(),
    val toolbar: SurfaceStyle = SurfaceStyle(),
    val linkToolbar: Boolean = false,
    val barBackground: String = "",
    val barBlur: Boolean = false,
    val blurRadius: Int = 16,
    val glassOpacity: Int = 25,
) {
    val effectiveToolbar get() = if (linkToolbar) keys else toolbar
    val needsBlur get() = keys.blur || effectiveToolbar.blur || barBlur
}

/** Cached preferences: never parse JSON or decode images in individual key draw calls. */
object SurfaceAppearance {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cacheKey: Pair<String, String>? = null
    private var cached = SurfaceAppearanceConfig()
    fun current(customMode: Boolean = isCustomMode()): SurfaceAppearanceConfig {
        val prefs = AppPrefs.defaultInstance().keyboard
        val raw = (if (customMode) prefs.customSurfaceAppearance else prefs.surfaceAppearance).getValue()
        val background = (if (customMode) prefs.customKeyBackground else prefs.keyBackground).getValue()
        if (cacheKey?.first != raw || cacheKey?.second != background) {
            val parsed = runCatching { json.decodeFromString<SurfaceAppearanceConfig>(raw) }.getOrDefault(SurfaceAppearanceConfig())
            cached = parsed.copy(keys = parsed.keys.copy(background = background),
                blurRadius = parsed.blurRadius.coerceIn(2, 32), glassOpacity = parsed.glassOpacity.coerceIn(0, 100))
            cacheKey = raw to background
        }
        return cached
    }
    fun isCustomMode() = com.osfans.trime.data.keymap.CustomKeyboards.mode == com.osfans.trime.data.keymap.CustomKeyboards.MODE_CUSTOM
    fun unifiedBackground(customMode: Boolean = isCustomMode()): Boolean {
        val prefs = AppPrefs.defaultInstance().keyboard
        return (if (customMode) prefs.customUnifiedBackground else prefs.unifiedBackground).getValue()
    }
    fun save(config: SurfaceAppearanceConfig, customMode: Boolean = isCustomMode()) {
        val prefs = AppPrefs.defaultInstance().keyboard
        val background = if (customMode) prefs.customKeyBackground else prefs.keyBackground
        if (background.getValue() != config.keys.background) background.setValue(config.keys.background)
        (if (customMode) prefs.customSurfaceAppearance else prefs.surfaceAppearance).setValue(json.encodeToString(config))
    }
}
