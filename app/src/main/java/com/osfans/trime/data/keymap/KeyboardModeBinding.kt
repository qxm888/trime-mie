/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class KeyboardModeBinding(
    val schemaId: String = "", val schemaName: String = "",
    val layoutId: String = "", val layoutName: String = "",
    val layoutMode: String = "", val themeId: String = "",
) {
    val configured get() = schemaId.isNotBlank() || layoutId.isNotBlank()
    fun summary() = "${schemaName.ifBlank { schemaId }.ifBlank { "方案不绑定" }} · ${layoutName.ifBlank { layoutId }.ifBlank { "布局不绑定" }}"
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun read(oneHand: Boolean): KeyboardModeBinding = runCatching {
            json.decodeFromString<KeyboardModeBinding>(preference(oneHand).getValue())
        }.getOrDefault(KeyboardModeBinding())
        fun save(oneHand: Boolean, value: KeyboardModeBinding) { preference(oneHand).setValue(json.encodeToString(value)) }
        private fun preference(oneHand: Boolean) = AppPrefs.defaultInstance().keyboard.run { if(oneHand) oneHandBinding else floatingBinding }
    }
}
