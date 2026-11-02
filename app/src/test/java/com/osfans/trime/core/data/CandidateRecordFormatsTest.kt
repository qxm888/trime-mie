/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CandidateRecordFormatsTest : FunSpec({
    CandidateRecordFormats.Format.entries.forEach { format ->
        test("created words roundtrip ${format.extension} preserves unicode punctuation and whitespace") {
            val words = listOf("💕", "单字", "一句话，标点！", "a\tb\nc\\d")
            val id = Json.encodeToString(listOf("schema_a", "abcd"))
            val unrelated = Json.encodeToString(listOf("schema_b", "other"))
            val rules = mapOf(id to CandidatePreferences.Rule(order = listOf("普通候选") + words.reversed(), aliases = words, hidden = setOf("普通候选", "单字")),
                unrelated to CandidatePreferences.Rule(order = listOf("仅调序")))
            val document = CandidateRecordFormats.decode(CandidateRecordFormats.encode(rules, format, createdWordsOnly = true))
            document.scope shouldBe "createdWords"
            document.records.size shouldBe 1
            document.records[0].aliases shouldBe words
            document.records[0].order shouldBe words.reversed()
            document.records[0].hidden shouldBe setOf("单字")
            document.records[0].schema shouldBe "schema_a"
            document.records[0].code shouldBe "abcd"
        }
    }
})
