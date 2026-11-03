/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import com.charleskorn.kaml.Yaml
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class ExportRow(val text:String,val code:String?=null,val weight:Int?=null)
@Serializable private data class ExportGroup(val schema:String,val code:String?=null,val entries:List<ExportRow>)
class CandidateTableExportTest : FunSpec({
    val key=Json.encodeToString(listOf("方案甲","ab"))
    val words=listOf("💕","单字","a\tb\nc\\d")
    val rules=mapOf(key to CandidatePreferences.Rule(order=listOf("第一","隐藏")+words,aliases=words,hidden=setOf("隐藏")))
    for(format in CandidateRecordFormats.Format.entries) {
        test("created-word ${format.extension} contains only text and code data columns") {
            val raw=CandidateTableExport.encode(rules,format,true)
            if(format==CandidateRecordFormats.Format.TXT) {
                val rows=raw.lines().filter { it.isNotEmpty() && !it.startsWith('#') }
                rows.size shouldBe 3;rows.all { it.split('\t').size==2 } shouldBe true
                rows[2] shouldBe "a\\tb\\nc\\\\d\tab"
            } else {
                val groups=if(format==CandidateRecordFormats.Format.JSON) Json.decodeFromString<List<ExportGroup>>(raw)
                    else Yaml.default.decodeFromString(ListSerializer(ExportGroup.serializer()),raw)
                groups.single().entries.map { it.text } shouldBe words
                groups.single().entries.all { it.code=="ab" && it.weight==null } shouldBe true
            }
        }
        test("candidate ${format.extension} contains only text and decreasing weight data columns") {
            val raw=CandidateTableExport.encode(rules,format,false)
            if(format==CandidateRecordFormats.Format.TXT) {
                val rows=raw.lines().filter { it.isNotEmpty() && !it.startsWith('#') }
                rows.size shouldBe 4; rows.all { it.split('\t').size==2 } shouldBe true
                rows.first() shouldBe "第一\t1000000"
            } else {
                val groups=if(format==CandidateRecordFormats.Format.JSON) Json.decodeFromString<List<ExportGroup>>(raw)
                    else Yaml.default.decodeFromString(ListSerializer(ExportGroup.serializer()),raw)
                val group=groups.single();group.code shouldBe "ab";group.schema shouldBe "方案甲"
                group.entries.map { it.text } shouldBe listOf("第一")+words
                group.entries.map { it.weight } shouldBe listOf(1000000,999999,999998,999997)
                group.entries.all { it.code==null } shouldBe true
            }
        }
    }
    test("same word with different codes retains both contexts") {
        val extra=rules+(Json.encodeToString(listOf("方案乙","cd")) to CandidatePreferences.Rule(aliases=listOf("💕")))
        Json.decodeFromString<List<ExportGroup>>(CandidateTableExport.encode(extra,CandidateRecordFormats.Format.JSON,true)).size shouldBe 2
    }
    test("forgotten candidates use low weight and hidden entries stay excluded") {
        val r=mapOf(key to CandidatePreferences.Rule(order=listOf("高","低","删"),forgotten=setOf("低"),hidden=setOf("删")))
        val rows=Json.decodeFromString<List<ExportGroup>>(CandidateTableExport.encode(r,CandidateRecordFormats.Format.JSON,false)).single().entries
        rows.map { it.text to it.weight } shouldBe listOf("高" to 1000000,"低" to 1)
    }
})
