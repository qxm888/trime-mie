/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.keyboard.KeyCode
import kotlinx.serialization.Serializable

@Serializable
internal data class ThemeToolbarProfile(
    val configured: Boolean = true,
    val buttons: List<ToolbarOverrides.Entry> = emptyList(),
)

/** Legacy fields remain intact for backup; they are never a cross-theme overlay. */
@Serializable
internal data class ThemeToolbarStore(
    var version: Long = 1,
    var configured: Boolean = false,
    val hiddenMenus: MutableMap<String, Set<String>> = mutableMapOf(),
    val buttons: MutableList<ToolbarOverrides.Entry> = mutableListOf(),
    val themes: MutableMap<String, ThemeToolbarProfile> = mutableMapOf(),
    var legacyOwner: String = "",
) {
    fun key(theme: Theme): String = theme.fontConfigId.ifBlank { theme.name }

    fun profile(theme: Theme): ThemeToolbarProfile? = themes[key(theme)]

    /** Adopt once, only when every old action belongs to or resolves in this theme. */
    fun adoptLegacy(theme: Theme): Boolean {
        val id = key(theme)
        if (id in themes || legacyOwner.isNotEmpty() || !(configured || buttons.isNotEmpty())) return false
        val nativeActions = (listOfNotNull(theme.toolBar.primaryButton) + theme.toolBar.buttons).map { it.action }.toSet()
        val compatible = buttons.all { entry ->
            entry.id == "primary-hidden" || ToolbarOverrides.isModeAction(entry.action) ||
                (entry.action.isEmpty() && entry.icon.isNotEmpty()) ||
                entry.action in nativeActions || entry.action in theme.presetKeys || entry.action in Theme.BUILTIN_ACTIONS ||
                entry.action.startsWith(NamedFunctions.PREFIX) ||
                runCatching { BindingActions.resolve(entry.action) != null || KeyCode.parse(entry.action).first != 0 }.getOrDefault(false)
        }
        if (!compatible) return false
        themes[id] = ThemeToolbarProfile(buttons = buttons.toList())
        legacyOwner = id
        return true
    }

    fun set(theme: Theme, entries: List<ToolbarOverrides.Entry>) {
        themes[key(theme)] = ThemeToolbarProfile(buttons = entries.toList())
    }

    fun reset(theme: Theme) {
        // A tombstone prevents reset from silently re-adopting the legacy overlay.
        themes[key(theme)] = ThemeToolbarProfile(configured = false)
    }
}
