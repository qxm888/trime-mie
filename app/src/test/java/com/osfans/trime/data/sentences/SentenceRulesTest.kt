/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.sentences

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SentenceRulesTest : FunSpec({
    test("literal prefix ranges include Chinese and do not interpret SQL wildcards") {
        SentenceRules.prefixEnd("感谢gpt") shouldBe "感谢gpu"
        SentenceRules.prefixEnd("中文%") shouldBe "中文&"
        SentenceRules.prefixEnd("中文_") shouldBe "中文`"
    }
    test("prefix range carries across maximal Unicode code points") {
        val maximum = String(Character.toChars(Character.MAX_CODE_POINT))
        SentenceRules.prefixEnd("a$maximum") shouldBe "b"
        SentenceRules.prefixEnd(maximum + maximum) shouldBe null
        SentenceRules.prefixEnd("\uD7FF") shouldBe "\uE000"
    }
    test("prefix range preserves supplementary characters") {
        SentenceRules.prefixEnd("感谢𠮷") shouldBe "感谢" + String(Character.toChars(0x20BB8))
    }
    test("three occurrences inside a rolling seven-day window qualify") {
        val now = 2_000_000_000_000L
        var hits = emptyList<Long>()
        for (time in listOf(now - 6 * 86400000L, now - 86400000L, now)) hits = SentenceRules.recent(hits, time)
        hits.size shouldBe 3
    }
    test("an occurrence exactly seven days old expires") {
        val now = 2_000_000_000_000L
        SentenceRules.recent(listOf(now - SentenceRules.WEEK_MS, now - 1), now).size shouldBe 2
    }
    test("future timestamps after a clock rollback cannot qualify") {
        SentenceRules.recent(listOf(2000, 3000), 1000) shouldBe listOf(1000L)
    }
    test("automatic commits and mixed Latin text form one sentence") {
        val result = mutableListOf<String>()
        val input = SentenceAccumulator { result += it }
        listOf("感谢", "g", "p", "t", "帮我", "做项目", "。").forEach(input::append)
        result shouldBe listOf("感谢gpt帮我做项目")
        input.prefix shouldBe ""
    }
    test("a completed sentence is emitted once, not on subsequent finish callbacks") {
        val result = mutableListOf<String>(); val input = SentenceAccumulator { result += it }
        input.append("感谢gpt帮我做项目"); input.finish(); input.finish()
        result.size shouldBe 1
    }
    test("backspace corrects a supplementary code point without splitting it") {
        val result = mutableListOf<String>(); val input = SentenceAccumulator { result += it }
        input.append("感谢gpt𠮷"); input.backspace(); input.append("帮我做项目！")
        result shouldBe listOf("感谢gpt帮我做项目")
    }
    test("overlong input never learns a truncated suffix") {
        val result = mutableListOf<String>(); val input = SentenceAccumulator { result += it }
        input.append("长".repeat(600)); input.append("不能学习这个后缀。"); input.append("感谢gpt帮我做项目。");
        result shouldBe listOf("感谢gpt帮我做项目")
    }
    test("paste-size work is bounded and resumes only after a boundary") {
        val result = mutableListOf<String>(); val input = SentenceAccumulator { result += it }
        input.append("长".repeat(100000)); input.append("后缀不要记！"); input.append("合法的新句子。");
        result shouldBe listOf("合法的新句子")
    }
    test("multiple completed sentences do not merge") {
        val result = mutableListOf<String>(); val input = SentenceAccumulator { result += it }
        input.append("感谢gpt帮我做项目！下次继续一起做项目？未完成")
        result shouldBe listOf("感谢gpt帮我做项目", "下次继续一起做项目")
        input.prefix shouldBe "未完成"
    }
    test("normalization strips final punctuation and rejects short or sensitive input") {
        SentenceRules.normalize("  感谢gpt帮我做项目。  ") shouldBe "感谢gpt帮我做项目"
        for (text in listOf("好的", "12345678", "联系我13812345678", "邮箱a@b.com", "打开https://x.cn")) {
            SentenceRules.normalize(text) shouldBe null
        }
    }
    test("completion inserts only what is missing from the committed prefix") {
        SentenceRules.suffix("感谢gpt帮我做项目", "感谢gpt", "") shouldBe "帮我做项目"
        SentenceRules.suffix("感谢gpt帮我做项目", "感谢", "gpt") shouldBe "gpt帮我做项目"
        SentenceRules.suffix("感谢gpt帮我做项目", "感谢GPT", "") shouldBe "帮我做项目"
        SentenceRules.suffix("感谢gpt帮我做项目", "其他前缀", "") shouldBe null
        SentenceRules.suffix("感谢gpt帮我做项目", "感谢gpt帮我做项目", "") shouldBe null
    }
    test("cancelling an editor discards unfinished text") {
        val result = mutableListOf<String>(); val input = SentenceAccumulator { result += it }
        input.append("不要接到别的输入框"); input.reset(); input.append("这是新的句子。");
        result shouldBe listOf("这是新的句子")
    }
})
