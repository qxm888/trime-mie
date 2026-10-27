/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import com.osfans.trime.util.appContext
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Explicit per-schema/per-code choices, independent of the engine's learned dictionary. */
object CandidatePreferences {
    @Serializable data class Rule(
        val order: List<String> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val aliases: List<String> = emptyList(),
        val searchLimit: Int = 512,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(appContext.filesDir, "candidate_preferences.json")
    private var cache: Map<String, Rule>? = null
    private fun key(schema: String, code: String) = json.encodeToString(listOf(schema, code))
    private fun data(): Map<String, Rule> = cache ?: runCatching {
        file.readAtomicText()?.let { json.decodeFromString<Map<String, Rule>>(it) } ?: emptyMap()
    }.getOrElse { error ->
        if (file.exists()) runCatching { file.copyTo(File(file.parentFile, "candidate_preferences-invalid-${System.currentTimeMillis()}.json")) }
        timber.log.Timber.e(error, "Cannot read candidate preferences; preserved backup")
        emptyMap()
    }.also { cache = it }

    @Synchronized fun get(schema: String, code: String): Rule? = data()[key(schema, code)]
    @Synchronized fun reload() { cache = null }

    @Synchronized fun save(schema: String, code: String, rule: Rule?, newCode: String? = null, word: String? = null) {
        val updated = data().toMutableMap()
        if (rule == null) updated.remove(key(schema, code)) else updated[key(schema, code)] = rule
        if (newCode != null && word != null) {
            val id = key(schema, newCode)
            val target = updated[id] ?: Rule()
            updated[id] = target.copy(aliases = (listOf(word) + target.aliases).distinct(), order = (listOf(word) + target.order).distinct(), hidden = target.hidden - word)
        }
        file.writeAtomicText(json.encodeToString(updated))
        cache = updated
    }
}
