/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import kotlinx.serialization.json.*

/** User-supplied frost gestures are shared defaults; each family retains its own artwork. */
internal class BuiltinBindingDefaults(raw: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pages = json.decodeFromString<Map<String, CustomKeyboard>>(raw)
    private data class Update(val old: CustomKey, val new: CustomKey)
    private val updates = mutableMapOf<String, List<Update>>()
    private val alignmentDefaults = mutableMapOf<String, Pair<KeyboardOptions, KeyboardOptions>>()
    private val annotationFields = setOf("labelSymbol", "hint", "symbolTextSize", "hintTextSize", "keySymbolOffsetX", "keySymbolOffsetY",
        "keyHintOffsetX", "keyHintOffsetY", "showSymbol", "showHint", "symbolAlign", "hintAlign", "popup", "sendBindings")

    @Synchronized fun template(id: String, original: CustomKeyboard): CustomKeyboard {
        val root = original.secondaryOf.ifBlank { id }
        val profile = pages[id.removePrefix(root)] ?: return original
        fun token(value: String): String {
            if (value.startsWith("Keyboard_custom_frost")) return "Keyboard_" + root + value.removePrefix("Keyboard_custom_frost")
            if (value.startsWith("__mie_action:")) return BindingActions.resolve(value)?.let { action ->
                BindingActions.token(action.copy(select = if(action.select.startsWith("custom_frost")) root + action.select.removePrefix("custom_frost") else action.select))
            } ?: value
            return value
        }
        val source = profile.copy(rows = profile.rows.map { row -> row.copy(keys = row.keys.map { key ->
            key.copy(bindings = key.bindings.mapValues { token(it.value) }.toMutableMap(), appearance = key.appearance.copy(popup = key.appearance.popup.map(::token)))
        }.toMutableList()) }.toMutableList())
        val matches = KeyboardBindingCopy.matchPages(source, original).first
        val result = original.copy(options = original.options.copy(symbolAlignment = profile.options.symbolAlignment, hintAlignment = profile.options.effectiveHintAlignment),
            rows = original.rows.map { it.copy(keys = it.keys.toMutableList()) }.toMutableList())
        val changes = mutableListOf<Update>()
        matches.forEach { match ->
            val key = original.rows[match.row].keys[match.column]
            val next = key.copy(bindings = match.bindings.toMutableMap(), appearance = match.annotations.applyTo(key.appearance).copy(popup = match.popup, sendBindings = match.sendBindings))
            result.rows[match.row].keys[match.column] = next
            changes += Update(key, next)
        }
        updates[id] = changes
        alignmentDefaults[id] = original.options to result.options
        return result
    }

    /** Only untouched legacy fields change. A moved or edited key keeps its identity and styling. */
    @Synchronized fun migrate(id: String, existing: CustomKeyboard): CustomKeyboard {
        val changes = updates[id] ?: return existing
        val byIdentity = changes.groupBy { KeyboardSets.identity(it.old) }
        val options = alignmentDefaults[id]?.let { (old, next) -> existing.options.copy(
            symbolAlignment = if(existing.options.symbolAlignment == old.symbolAlignment) next.symbolAlignment else existing.options.symbolAlignment,
            hintAlignment = if(existing.options.hintAlignment == null && existing.options.symbolAlignment != old.symbolAlignment) existing.options.symbolAlignment
                else if(existing.options.effectiveHintAlignment == old.effectiveHintAlignment) next.effectiveHintAlignment else existing.options.hintAlignment)
        } ?: existing.options
        return existing.copy(options = options, rows = existing.rows.map { row -> row.copy(keys = row.keys.map { key ->
            val candidates = byIdentity[KeyboardSets.identity(key)].orEmpty()
            val update = candidates.singleOrNull() ?: candidates.singleOrNull { it.old.label == key.label } ?: return@map key
            val replaceBindings = key.bindings == update.old.bindings
            val before = KeyAppearance.values(update.old.appearance)
            val after = KeyAppearance.values(update.new.appearance)
            val current = KeyAppearance.values(key.appearance)
            val patch = JsonObject(after.filter { (field, value) -> field in annotationFields && before[field] != value && current[field] == before[field] })
            key.copy(bindings = if(replaceBindings) update.new.bindings.toMutableMap() else key.bindings.toMutableMap(),
                setBaseBindings = if(replaceBindings && key.setBaseBindings == update.old.bindings) update.new.bindings.toMap() else key.setBaseBindings,
                appearance = json.decodeFromJsonElement(KeyAppearance.Style.serializer(), KeyboardSets.overlay(current, patch)),
                setStyleOverrides = key.setStyleOverrides?.let { KeyboardSets.overlay(it, patch) })
        }.toMutableList()) }.toMutableList())
    }
}
