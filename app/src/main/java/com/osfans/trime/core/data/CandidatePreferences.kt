/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import com.osfans.trime.util.appContext
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Explicit per-schema/per-code choices, independent of the engine's learned dictionary. */
object CandidatePreferences {
    @Serializable data class Rule(
        val order: List<String> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val aliases: List<String> = emptyList(),
        val searchLimit: Int = 512, // Legacy JSON omitted 512; retain its decode default.
        val forgotten: Set<String> = emptySet(),
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val file get() = File(appContext.filesDir, "candidate_preferences.json")
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val writeLock = Any()
    @Serializable private data class Backup(val version: Int = 1, val rules: Map<String, Rule>)
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
            val target = updated[id] ?: Rule(searchLimit = 64)
            updated[id] = target.copy(aliases = (listOf(word) + target.aliases).distinct(), order = (listOf(word) + target.order).distinct(), hidden = target.hidden - word)
        }
        cache = updated
        // Selecting a forgotten word is on the typing path. Persistence runs on IO;
        // each writer captures the latest immutable map, so snapshots cannot go backwards.
        ioScope.launch { persist() }
    }
    private fun persist() = synchronized(writeLock) {
        val snapshot = synchronized(this) { data() }
        runCatching { file.writeAtomicText(json.encodeToString(snapshot)) }
            .onFailure { timber.log.Timber.e(it, "Candidate order persistence failed") }
    }

    /** Save a user phrase without deleting or demoting the original candidate. */
    fun createWord(schema: String, code: String, word: String) {
        require(schema.isNotBlank() && code.isNotBlank() && code.length <= 128 && !code.any(Char::isWhitespace)) { "方案或编码无效" }
        require(word.isNotEmpty() && word.length <= 1024) { "词条需为1–1024个字符" }
        synchronized(writeLock) {
            synchronized(this) {
                val updated = data().toMutableMap()
                val id = key(schema, code)
                val old = updated[id] ?: Rule(searchLimit = 64)
                updated[id] = old.copy(aliases = (listOf(word) + old.aliases).distinct(), order = (listOf(word) + old.order).distinct(),
                    hidden = old.hidden - word, forgotten = old.forgotten - word)
                file.writeAtomicText(json.encodeToString(updated))
                cache = updated
            }
        }
    }

    @Synchronized fun selected(schema: String, code: String, text: String) {
        val rule = get(schema, code) ?: return
        if (text !in rule.forgotten) return
        save(schema, code, rule.copy(forgotten = rule.forgotten - text,
            order = listOf(text) + rule.order.filterNot { it == text }))
    }

    fun exportJson(): String = synchronized(this) { Backup(rules = data()) }.let { json.encodeToString(it) }
    fun importJson(raw: String): Int {
        val backup = json.decodeFromString<Backup>(raw)
        require(backup.version == 1 && backup.rules.size <= 10000) { "不支持的备份版本或规则过多" }
        var words = 0
        backup.rules.forEach { (id, rule) ->
            val parts = json.decodeFromString<List<String>>(id)
            require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts.all { it.length <= 256 }) { "候选编码格式无效" }
            words += rule.order.size + rule.hidden.size + rule.aliases.size + rule.forgotten.size
            require(rule.order.size <= 20000 && rule.order.distinct().size == rule.order.size) { "候选顺序包含重复项或记录过多" }
            require(words <= 500000 && rule.searchLimit in 1..20000) { "候选记录过大或搜索范围无效" }
            require((rule.order + rule.hidden + rule.aliases + rule.forgotten).all { it.isNotEmpty() && it.length <= 1024 }) { "候选内容无效" }
        }
        synchronized(writeLock) {
            synchronized(this) {
                File(file.parentFile, "candidate_preferences-before-import-${System.currentTimeMillis()}.json").writeAtomicText(json.encodeToString(data()))
                file.writeAtomicText(json.encodeToString(backup.rules))
                cache = backup.rules
            }
        }
        return backup.rules.size
    }

    /** Input spelling may differ from a translator's dictionary code: export a draft, not an automatic schema patch. */
    fun dictionaryDrafts(): Map<String, String> {
        val snapshot = synchronized(this) { data() }
        val grouped = snapshot.entries.groupBy { json.decodeFromString<List<String>>(it.key)[0] }
        return grouped.mapValues { (schema, entries) ->
            val name = "mie_order_" + schema.replace(Regex("[^a-zA-Z0-9_]"), "_") + "_" +
                java.security.MessageDigest.getInstance("SHA-256").digest(schema.toByteArray()).take(4).joinToString("") { "%02x".format(it) }
            name to buildString {
                append("# Trime Mie 候选调序词典草稿\n# 记录的是输入编码；接入前请确认与方案词典编码一致。\n")
                append("# 不包含删除规则；忘记词以低权重导出。JSON备份可完整恢复行为。\n---\nname: $name\nversion: \"1\"\nsort: by_weight\ncolumns: [text, code, weight]\n...\n")
                entries.forEach { (id, rule) ->
                    val code = json.decodeFromString<List<String>>(id)[1]
                    if (code.any { it.isWhitespace() }) return@forEach
                    (rule.order + rule.aliases + rule.forgotten).distinct().filterNot { it in rule.hidden }.forEachIndexed { index, word ->
                        if (word.none { it == '\t' || it == '\n' || it == '\r' }) {
                            val weight = if (word in rule.forgotten) 1 else (1000000 - index).coerceAtLeast(2)
                            append(word).append('\t').append(code).append('\t').append(weight).append('\n')
                        }
                    }
                }
            }
        }.values.associate { it.first + ".dict.yaml" to it.second }
    }
}
