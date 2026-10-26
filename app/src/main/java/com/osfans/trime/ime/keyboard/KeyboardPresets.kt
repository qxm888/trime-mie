/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.TextKeyboard

/** Shared by docked and floating keyboards, including cycle diagnostics for hand-edited YAML. */
internal fun resolveKeyboardPreset(presets: Map<String, TextKeyboard>, id: String): TextKeyboard? {
    var config = presets[id] ?: presets["default"]
    val visited = linkedSetOf<String>()
    while (!config?.importPreset.isNullOrEmpty()) {
        val next = config!!.importPreset
        require(visited.add(next)) { "键盘布局循环引用：${(visited + next).joinToString(" → ")}" }
        config = presets[next]
    }
    return config
}

/** Invalid hand edits fall back to a valid preset without taking down the input service. */
internal fun selectKeyboardPreset(presets: Map<String, TextKeyboard>, id: String): TextKeyboard? = try {
    resolveKeyboardPreset(presets, id)
} catch (error: IllegalArgumentException) {
    timber.log.Timber.w(error, "Invalid keyboard preset: $id")
    (listOf("default") + presets.keys).asSequence().filter { it != id }
        .mapNotNull { runCatching { resolveKeyboardPreset(presets, it) }.getOrNull() }.firstOrNull()
}
