/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import android.content.Context
import com.osfans.trime.ime.ai.ThemeOverlay
import com.osfans.trime.ime.ai.YamlThemeApplier
import com.osfans.trime.ime.ai.YamlThemePatch
import com.osfans.trime.util.appContext
import com.osfans.trime.util.storageContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * AI edits of YAML themes live here instead of in the theme files.
 *
 * A theme is loaded, then this overlay is applied to the decoded [Theme]:
 * style fields, the active colour scheme and per-key colours. Nothing is
 * written to `.trime.yaml`, so a system/user theme can never be corrupted and
 * clearing the overlay restores the file's exact appearance.
 *
 * One level of undo is kept by the assistant itself; [restore] accepts the raw
 * JSON it captured before writing.
 */
object ThemeAiOverrides {
    private const val PREFS = "theme_ai_overrides"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    private fun prefs(context: Context = appContext) =
        storageContext(context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun raw(themeId: String): String? =
        runCatching { prefs().getString(themeId, null) }.getOrNull()

    fun has(themeId: String): Boolean = !raw(themeId).isNullOrBlank()

    fun overlay(themeId: String): ThemeOverlay? {
        val raw = raw(themeId) ?: return null
        return runCatching { json.decodeFromString(ThemeOverlay.serializer(), raw) }
            .onFailure { Timber.w(it, "AI theme overlay for %s is invalid", themeId) }
            .getOrNull()
    }

    /** Applied by [ThemeManager] after a theme is decoded, before the picker sees it. */
    fun apply(themeId: String, theme: Theme): Theme {
        val overlay = overlay(themeId) ?: return theme
        return runCatching { YamlThemeApplier.applyOverlay(theme, overlay) }
            .onFailure { Timber.w(it, "Could not apply AI theme overlay for %s", themeId) }
            .getOrDefault(theme)
    }

    fun merge(themeId: String, schemeId: String, patch: YamlThemePatch): ThemeOverlay {
        val merged = (overlay(themeId) ?: ThemeOverlay()).merge(schemeId, patch)
        write(themeId, merged)
        return merged
    }

    fun restore(themeId: String, raw: String?) {
        if (raw.isNullOrBlank()) clear(themeId) else writeRaw(themeId, raw)
    }

    fun clear(themeId: String) {
        runCatching { prefs().edit().remove(themeId).apply() }
    }

    private fun write(themeId: String, overlay: ThemeOverlay) {
        writeRaw(themeId, json.encodeToString(ThemeOverlay.serializer(), overlay))
    }

    private fun writeRaw(themeId: String, raw: String) {
        check(prefs().edit().putString(themeId, raw).commit()) { "AI 主题覆盖保存失败" }
    }
}
