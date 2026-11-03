/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.stats
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class WordCloudVocabularyTest : FunSpec({
    test("count commit phrase plus individual Unicode codepoints") {
        WordCloudVocabulary.terms("你好") shouldBe listOf("你好","你","好")
        WordCloudVocabulary.terms("𠮷") shouldBe listOf("𠮷")
    }
    test("long commits do bounded sampling without retaining a full sentence") {
        val terms=WordCloudVocabulary.terms("长".repeat(10000));terms.size shouldBe 64;terms.all { it=="长" } shouldBe true
    }
    test("bounded vocabulary retains high frequency entries and updates existing values") {
        val counts=(0 until 512).associate { "词$it" to 2L }.toMutableMap();counts["词0"]=99
        WordCloudVocabulary.add(counts,listOf("新词","词0"));counts.size shouldBe 512
        counts["词0"] shouldBe 100L;counts["新词"] shouldBe 1L
    }
    test("frequency saturation never wraps to a negative value") {
        val counts=mutableMapOf("字" to Long.MAX_VALUE);WordCloudVocabulary.add(counts,listOf("字"));counts["字"] shouldBe Long.MAX_VALUE
    }
    test("password and no-personalized-learning editors do not collect words") {
        listOf(0x81,0x91,0xe1,0x12).forEach { WordCloudVocabulary.allows(it,0) shouldBe false }
        WordCloudVocabulary.allows(1,0x1000000) shouldBe false
        WordCloudVocabulary.allows(0,0) shouldBe false;WordCloudVocabulary.allows(1,0) shouldBe true
    }
    test("legacy period files decode empty word cloud and new frequencies roundtrip") {
        Json.decodeFromString<InputStats.PeriodStats>("{\"count\":5}").wordCounts shouldBe emptyMap()
        val period=InputStats.PeriodStats(wordCounts=mutableMapOf("你好" to 7))
        Json.decodeFromString<InputStats.PeriodStats>(Json.encodeToString(period)).wordCounts shouldBe period.wordCounts
    }
})
