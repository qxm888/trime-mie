/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TypingGameProgressTest : FunSpec({
    test("old reached-level records recover progress when the unlock key was never written") {
        TypingGameProgress.restore(1, 23) shouldBe 23
        TypingGameProgress.selected(23, TypingGameProgress.restore(1, 23)) shouldBe 23
    }
    test("clearing the first level immediately unlocks level two and survives a reload") {
        val saved = TypingGameProgress.cleared(1, 1)
        TypingGameProgress.restore(saved, 1) shouldBe 2
    }
    test("replaying a lower level never reduces progress") {
        TypingGameProgress.cleared(38, 2) shouldBe 38
        TypingGameProgress.restore(38, 2) shouldBe 38
    }
    test("locked levels cannot be selected and later unlocked levels are not capped at ten") {
        TypingGameProgress.selected(50, 3) shouldBe 3
        TypingGameProgress.selected(37, 40) shouldBe 37
    }
    test("the last level and corrupt saved progress stay inside the fifty-level range") {
        TypingGameProgress.cleared(50, 50) shouldBe 50
        TypingGameProgress.restore(-20, 0) shouldBe 1
        TypingGameProgress.restore(Int.MAX_VALUE, 1) shouldBe 50
        TypingGameProgress.selected(-1, 50) shouldBe 1
    }
})
