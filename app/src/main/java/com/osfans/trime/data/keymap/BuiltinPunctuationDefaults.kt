/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

/** Stock offsets once compensated for a full-width glyph; KeyView now centers its actual ink. */
internal object BuiltinPunctuationDefaults {
    fun upgrade(key: CustomKeyboards.CustomKey): CustomKeyboards.CustomKey {
        val style = key.appearance
        if (key.label != "。" || key.bindings["click"] != "." ||
            style.keyTextOffsetX != 5f || style.keyTextOffsetY != -5f) return key
        // Adopting a stock set also recorded its original offsets as local differences.
        // Update that exact pair as well, leaving every other edited field intact.
        val offsets = mapOf("keyTextOffsetX" to 5f, "keyTextOffsetY" to -5f)
        val overrides = key.setStyleOverrides?.let { values ->
            if (offsets.any { (field, old) -> values[field]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() != old } == true }) return key
            kotlinx.serialization.json.JsonObject(values.mapValues { (field, value) ->
                if(field in offsets) kotlinx.serialization.json.JsonPrimitive(0f) else value
            })
        }
        return key.copy(appearance = style.copy(keyTextOffsetX = 0f, keyTextOffsetY = 0f), setStyleOverrides = overrides)
    }
}
