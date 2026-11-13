/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.stats

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

private fun CompositionCodeCounter.type(code: String) {
    code.forEach { processed(rawInput + it, committed = false) }
}

class CompositionCodeCounterTest : FunSpec({
    test("我用巧码 uses nine code keys and one final space across a prefix commit") {
        val counter = CompositionCodeCounter()
        // The selected prefix 我用 (ywpy) is popped; km and the new m remain for 巧码.
        counter.type("ywpykm")
        val first = counter.commit("ywpykm", "kmm", inputKey = true)
        counter.processed("kmm", committed = true)
        first shouldBe CompositionCodeCounter.Commit(4, 4, 4)
        counter.pendingKeys shouldBe 3L
        counter.type("zr")
        val last = counter.commit("kmmzr", "", inputKey = false)
        counter.processed("", committed = true)
        last shouldBe CompositionCodeCounter.Commit(5, 6, 5)
        first.rawLength + last.rawLength shouldBe 9
        (first.codeLength + last.codeLength) / 4.0 shouldBe 2.5
        first.keyTouches + last.keyTouches shouldBe 9L
        counter.pendingKeys shouldBe 0L
    }

    test("full-code automatic commit counts its last code without adding a space") {
        val counter = CompositionCodeCounter()
        counter.type("ywpykmmz")
        counter.commit("ywpykmmz", "", inputKey = true) shouldBe
            CompositionCodeCounter.Commit(9, 9, 9)
    }

    test("manual space number selection and candidate tap keep one confirmation key") {
        val counter = CompositionCodeCounter()
        counter.type("ywpykmmzr")
        counter.commit("ywpykmmzr", "", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(9, 10, 9)
    }

    test("a one-code prefix can pop while the remaining input length stays unchanged") {
        val counter = CompositionCodeCounter()
        counter.type("ab")
        counter.commit("ab", "bc", inputKey = true) shouldBe
            CompositionCodeCounter.Commit(1, 1, 1)
        counter.processed("bc", committed = true)
        counter.commit("bc", "", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(2, 3, 2)
    }

    test("top-out key becomes the first code of the next word") {
        val counter = CompositionCodeCounter()
        counter.type("a")
        counter.commit("a", "b", inputKey = true) shouldBe
            CompositionCodeCounter.Commit(1, 1, 1)
        counter.pendingKeys shouldBe 1L
        counter.processed("b", committed = true)
        counter.type("c")
        counter.commit("bc", "", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(2, 3, 2)
    }

    test("manual partial commit includes confirmation but leaves the suffix keys pending") {
        val counter = CompositionCodeCounter()
        counter.type("ywpykmmzr")
        counter.commit("ywpykmmzr", "kmmzr", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(4, 5, 4)
        counter.pendingKeys shouldBe 5L
    }

    test("corrections increase key touches without inflating effective code length") {
        val counter = CompositionCodeCounter()
        counter.type("ywpx")
        counter.processed("ywp", committed = false)
        counter.type("y")
        counter.commit("ywpy", "", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(4, 5, 5)
    }

    test("discarded compositions and schema resets cannot leak pending keys") {
        val counter = CompositionCodeCounter()
        counter.type("abc")
        counter.processed("", committed = false)
        counter.type("yw")
        counter.commit("yw", "", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(2, 3, 2)
        counter.type("abc")
        counter.reset()
        counter.rawInput shouldBe ""
        counter.pendingKeys shouldBe 0L
    }

    test("excluded commits can settle their keys before subsequent Chinese input") {
        val counter = CompositionCodeCounter()
        counter.type("abc")
        counter.commit("abc", "", inputKey = false)
        counter.processed("", committed = true)
        counter.type("yw")
        counter.commit("yw", "", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(2, 3, 2)
    }

    test("a commit without observed codes cannot invent a confirmation key") {
        CompositionCodeCounter().commit(null, "", inputKey = false) shouldBe
            CompositionCodeCounter.Commit(0, 0, 0)
    }

    test("slash commands do not leave a code baseline") {
        val counter = CompositionCodeCounter()
        "/rtj".indices.forEach { counter.processed("/rtj".take(it + 1), committed = false) }
        counter.rawInput shouldBe ""
        counter.pendingKeys shouldBe 0L
    }

    test("replay real librime events with the unmodified qiaoma auto-pop script") {
        val trace = requireNotNull(javaClass.getResourceAsStream("/stats/qiaoma-auto-pop.tsv"))
            .bufferedReader().use { it.readLines() }.drop(1).groupBy { it.substringBefore('\t') }
        for ((scenario, lines) in trace) {
            val counter = CompositionCodeCounter()
            var raw = 0
            var codes = 0
            var touches = 0L
            var chars = 0
            val lengths = mutableListOf<Int>()
            for (line in lines) {
                val (_, key, before, after, text) = line.split('\t').map { if (it == "-") "" else it }
                if (text.isNotEmpty()) {
                    val commit = counter.commit(before, after, inputKey = key != "SPACE")
                    raw += commit.rawLength
                    codes += commit.codeLength
                    touches += commit.keyTouches
                    chars += text.codePointCount(0, text.length)
                    lengths += commit.codeLength
                }
                counter.processed(after, committed = text.isNotEmpty())
            }
            if (scenario == "single") {
                listOf(raw, codes, chars) shouldBe listOf(9, 10, 4)
                lengths shouldBe listOf(10)
            } else {
                listOf(raw, codes, chars) shouldBe listOf(27, 28, 12)
                lengths shouldBe listOf(4, 5, 4, 5, 10)
            }
            touches shouldBe raw.toLong()
            counter.rawInput shouldBe ""
            counter.pendingKeys shouldBe 0L
        }
    }

    test("repeated auto-pop conserves codes and keys across arbitrary prefix sizes") {
        val random = Random(20261113)
        repeat(200) {
            val counter = CompositionCodeCounter()
            var typed = 0
            var consumed = 0
            var codeLength = 0
            var keyTouches = 0L
            repeat(50) {
                val newCode = "a".repeat(random.nextInt(1, 8))
                counter.type(newCode)
                typed += newCode.length
                val before = counter.rawInput
                val prefix = random.nextInt(1, before.length + 1)
                val after = before.drop(prefix) + "b"
                val result = counter.commit(before, after, inputKey = true)
                typed++
                counter.processed(after, committed = true)
                consumed += result.rawLength
                codeLength += result.codeLength
                keyTouches += result.keyTouches
                consumed + counter.rawInput.length shouldBe typed
                keyTouches + counter.pendingKeys shouldBe typed.toLong()
            }
            val last = counter.commit(counter.rawInput, "", inputKey = false)
            consumed + last.rawLength shouldBe typed
            codeLength + last.codeLength shouldBe typed + 1
            keyTouches + last.keyTouches shouldBe typed.toLong()
        }
    }
})
