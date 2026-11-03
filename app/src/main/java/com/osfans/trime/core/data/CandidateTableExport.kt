/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Human-readable tables for dictionaries and other tools. Lossless backups remain separate. */
object CandidateTableExport {
    private val json=Json { prettyPrint=true }
    fun encode(rules: Map<String,CandidatePreferences.Rule>, format: CandidateRecordFormats.Format, wordsOnly: Boolean): String {
        val groups=rules.entries.sortedBy { it.key }.mapNotNull { (key,rule) ->
            val pair=json.decodeFromString<List<String>>(key)
            val words=if(wordsOnly) rule.aliases.distinct() else (rule.order+rule.aliases+rule.forgotten).distinct().filterNot { it in rule.hidden }
            if(words.isEmpty()) return@mapNotNull null
            val rows=words.mapIndexed { i,word -> buildJsonObject {
                put("text",word)
                if(wordsOnly) put("code",pair[1]) else put("weight",if(word in rule.forgotten) 1 else (1000000-i).coerceAtLeast(2))
            } }
            Triple(pair[0],pair[1],rows)
        }
        val output=when(format) {
            CandidateRecordFormats.Format.JSON -> json.encodeToString(buildJsonArray {
                groups.forEach { (schema,code,rows) -> add(buildJsonObject {
                    put("schema",schema); if(!wordsOnly) put("code",code); put("entries",JsonArray(rows))
                }) }
            })
            CandidateRecordFormats.Format.YAML -> buildString {
                append("# ").append(if(wordsOnly) "字词、编码" else "字词、权重（应用内排序权重，非 Rime 学习词频）").append('\n')
                groups.forEach { (schema,code,rows) ->
                    append("- schema: ").append(JsonPrimitive(schema)).append('\n')
                    if(!wordsOnly) append("  code: ").append(JsonPrimitive(code)).append('\n')
                    append("  entries:\n")
                    rows.forEach { row ->
                        append("    - text: ").append(row.getValue("text")).append('\n')
                        val column=if(wordsOnly) "code" else "weight"
                        append("      ").append(column).append(": ").append(row.getValue(column)).append('\n')
                    }
                }
            }
            CandidateRecordFormats.Format.TXT -> buildString {
                append(if(wordsOnly) "# 字词\t编码\n" else "# 字词\t权重（应用内排序权重，非原生词频）\n")
                append("# 特殊字符以 \\t / \\n / \\r / \\\\ 转义\n")
                fun escape(s:String)=s.replace("\\","\\\\").replace("\t","\\t").replace("\n","\\n").replace("\r","\\r")
                groups.forEach { (schema,code,rows) ->
                    append("# schema=").append(escape(schema));if(!wordsOnly) append(" code=").append(escape(code));append('\n')
                    rows.forEach { row -> append(escape(row.getValue("text").jsonPrimitive.content)).append('\t')
                        .append(escape(row.getValue(if(wordsOnly) "code" else "weight").jsonPrimitive.content)).append('\n') }
                }
            }
        }
        require(output.toByteArray(Charsets.UTF_8).size <= 32*1024*1024) { "导出内容超过 32MB" }
        return output
    }
}
