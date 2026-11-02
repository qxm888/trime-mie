/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import com.charleskorn.kaml.MultiLineStringStyle
import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Lossless, portable exports of app-owned candidate rules, not native Rime frequencies. */
object CandidateRecordFormats {
    enum class Format(val extension: String, val title: String) {
        YAML("yaml", "YAML（.yaml）"), JSON("json", "JSON（.json）"), TXT("txt", "TXT（.txt，制表符分列）"),
    }

    @Serializable data class Record(
        val schema: String,
        val code: String,
        val order: List<String> = emptyList(),
        val hidden: Set<String> = emptySet(),
        val aliases: List<String> = emptyList(),
        val searchLimit: Int = 512,
        val forgotten: Set<String> = emptySet(),
    ) {
        fun rule() = CandidatePreferences.Rule(order, hidden, aliases, searchLimit, forgotten)
    }
    @Serializable data class Document(
        val format: String = "trime-mie-candidate-records",
        val version: Int = 1,
        val scope: String = "candidates",
        val records: List<Record>,
    )
    // JSON keeps the previous rules-map structure so existing full backups remain readable.
    @Serializable private data class JsonBackup(
        val version: Int = 1,
        val rules: Map<String, CandidatePreferences.Rule>,
        val scope: String = "candidates",
    )
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val yaml = Yaml(configuration = YamlConfiguration(
        strictMode = false, encodeDefaults = true, codePointLimit = 32 * 1024 * 1024,
        singleLineStringStyle = SingleLineStringStyle.DoubleQuoted,
        multiLineStringStyle = MultiLineStringStyle.DoubleQuoted,
    ))
    private const val TXT_PREFIX = "# trime-mie-candidate-records v1 scope="
    private const val TXT_COLUMNS = "type\tschema\tcode\tposition\ttext\tsearch_limit"

    private fun id(schema: String, code: String) = Json.encodeToString(listOf(schema, code))

    fun encode(rules: Map<String, CandidatePreferences.Rule>, format: Format, createdWordsOnly: Boolean = false): String {
        val records = rules.mapNotNull { (key, rule) ->
            val parts = json.decodeFromString<List<String>>(key)
            require(parts.size == 2) { "候选编码格式无效" }
            if (createdWordsOnly && rule.aliases.isEmpty()) return@mapNotNull null
            val aliases = rule.aliases.distinct()
            Record(parts[0], parts[1],
                if (createdWordsOnly) (rule.order.filter { it in aliases } + aliases).distinct() else rule.order,
                if (createdWordsOnly) rule.hidden.intersect(aliases.toSet()) else rule.hidden,
                aliases, rule.searchLimit, if (createdWordsOnly) emptySet() else rule.forgotten)
        }.sortedWith(compareBy<Record> { it.schema }.thenBy { it.code })
        val document = Document(scope = if (createdWordsOnly) "createdWords" else "candidates", records = records)
        validate(document)
        val text = when (format) {
            // Older apps must reject a words-only file, rather than silently replace all rules.
            Format.JSON -> json.encodeToString(JsonBackup(version = if (createdWordsOnly) 2 else 1, rules = rules(document), scope = document.scope))
            Format.YAML -> "# 同文咩版造词与候选调整备份；不是 Rime 词典文件。\n" + yaml.encodeToString(Document.serializer(), document)
            Format.TXT -> encodeText(document)
        }
        require(text.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024) { "导出内容超过 32MB，请先减少候选记录" }
        return text
    }

    fun decode(raw: String): Document {
        require(raw.length <= 32 * 1024 * 1024) { "候选备份文件过大" }
        val text = raw.removePrefix("\uFEFF").trimStart()
        val document = when {
            text.startsWith(TXT_PREFIX) -> decodeText(text)
            text.startsWith('{') -> {
                if ("rules" in json.parseToJsonElement(text).jsonObject) {
                    val backup = json.decodeFromString<JsonBackup>(text)
                    require((backup.version == 1 && backup.scope == "candidates") ||
                        (backup.version == 2 && backup.scope == "createdWords")) { "不支持的备份版本或范围" }
                    Document(scope = backup.scope, records = backup.rules.map { (key, rule) ->
                        val parts = json.decodeFromString<List<String>>(key)
                        require(parts.size == 2) { "候选编码格式无效" }
                        Record(parts[0], parts[1], rule.order, rule.hidden, rule.aliases, rule.searchLimit, rule.forgotten)
                    })
                } else json.decodeFromString<Document>(text)
            }
            else -> yaml.decodeFromString(Document.serializer(), text)
        }
        validate(document)
        return document
    }

    fun rules(document: Document): Map<String, CandidatePreferences.Rule> = document.records.associate { id(it.schema, it.code) to it.rule() }

    private fun validate(document: Document) {
        require(document.format == "trime-mie-candidate-records" && document.version == 1 &&
            document.scope in setOf("candidates", "createdWords")) { "不支持的候选备份格式或版本" }
        require(document.records.size <= 10000) { "候选规则过多" }
        val seen = HashSet<Pair<String, String>>()
        var words = 0
        document.records.forEach { record ->
            require(record.schema.isNotBlank() && record.code.isNotBlank() && record.schema.length <= 256 && record.code.length <= 256) { "候选编码格式无效" }
            require(seen.add(record.schema to record.code)) { "备份包含重复的方案和编码" }
            words += record.order.size + record.hidden.size + record.aliases.size + record.forgotten.size
            require(record.order.size <= 20000 && record.order.distinct().size == record.order.size) { "候选顺序包含重复项或记录过多" }
            require(words <= 500000 && record.searchLimit in 1..20000) { "候选记录过大或搜索范围无效" }
            require((record.order + record.hidden + record.aliases + record.forgotten).all { it.isNotEmpty() && it.length <= 1024 }) { "候选内容无效" }
            if (document.scope == "createdWords") require((record.order + record.hidden + record.forgotten).all { it in record.aliases }) { "造词备份包含非造词规则" }
        }
    }

    private fun escaped(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
    private fun unescaped(value: String): String = buildString {
        var i = 0
        while (i < value.length) {
            val char = value[i++]
            if (char != '\\') append(char) else {
                require(i < value.length) { "TXT 包含未完成的转义" }
                append(when (value[i++]) { '\\' -> '\\'; 't' -> '\t'; 'r' -> '\r'; 'n' -> '\n'; else -> error("TXT 包含不支持的转义") })
            }
        }
    }

    private fun encodeText(document: Document) = buildString {
        append(TXT_PREFIX).append(document.scope).append('\n')
        append("# UTF-8；position 从 1 开始表示顺序；created 为造词，hidden 为隐藏。\n")
        append("# 文字中的制表符/换行/反斜杠分别保存为 \\t / \\n / \\\\，导入时还原。\n")
        append(TXT_COLUMNS).append('\n')
        document.records.forEach { record ->
            fun row(type: String, position: Int, word: String) {
                append(listOf(type, record.schema, record.code, position.toString(), word, record.searchLimit.toString()).joinToString("\t", transform = ::escaped)).append('\n')
            }
            row("rule", 0, "")
            listOf("order" to record.order, "created" to record.aliases, "hidden" to record.hidden.toList(), "forgotten" to record.forgotten.toList()).forEach { (type, values) ->
                values.forEachIndexed { index, word -> row(type, index + 1, word) }
            }
        }
    }

    private fun decodeText(text: String): Document {
        val lines = text.lineSequence().iterator()
        val scope = lines.next().removePrefix(TXT_PREFIX)
        data class Rows(val schema: String, val code: String, val limit: Int, val values: MutableMap<String, MutableList<String>> = mutableMapOf())
        val records = linkedMapOf<Pair<String, String>, Rows>()
        var header = false
        var count = 0
        lines.forEach { line ->
            if (line.isBlank() || line.startsWith('#')) return@forEach
            if (!header) { require(line == TXT_COLUMNS) { "TXT 列名不匹配" }; header = true; return@forEach }
            require(++count <= 510000) { "TXT 候选记录过多" }
            val fields = line.split('\t').map(::unescaped)
            require(fields.size == 6) { "TXT 每行应包含 6 列" }
            val (type, schema, code, position, word) = fields
            val searchLimit = fields[5]
            val rank = position.toIntOrNull() ?: error("TXT 顺序无效")
            val limit = searchLimit.toIntOrNull() ?: error("TXT 搜索范围无效")
            val key = schema to code
            if (type == "rule") {
                require(rank == 0 && word.isEmpty() && key !in records && records.size < 10000) { "TXT 规则重复或格式无效" }
                records[key] = Rows(schema, code, limit)
            } else {
                require(type in setOf("order", "created", "hidden", "forgotten")) { "TXT 记录类型无效" }
                val record = records[key] ?: error("TXT 词条之前缺少 rule 记录")
                require(record.limit == limit) { "TXT 同一编码的搜索范围不一致" }
                val values = record.values.getOrPut(type) { mutableListOf() }
                require(rank == values.size + 1) { "TXT 顺序需从 1 开始连续排列" }
                values.add(word)
            }
        }
        require(header) { "TXT 缺少列名" }
        return Document(scope = scope, records = records.values.map { row ->
            Record(row.schema, row.code, row.values["order"].orEmpty(), row.values["hidden"].orEmpty().toSet(),
                row.values["created"].orEmpty(), row.limit, row.values["forgotten"].orEmpty().toSet())
        })
    }
}
