/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyboardPresentationOwnerTest : FunSpec({
    test("queued callbacks must check ownership after a new window begins") {
        val state = KeyboardPresentationState<String, String>(); val old = Any(); val next = Any()
        state.begin(old); state.owns(old) shouldBe true
        val callback = { if (state.owns(old)) state.attachDocked(old, "old", "old") else false }
        state.begin(next); state.attachDocked(next, "next", "next") shouldBe true
        state.owns(old) shouldBe false
        callback() shouldBe false
        state.visible?.keyboard shouldBe "next"
    }
    test("ended owners cannot publish later queued work") {
        val state = KeyboardPresentationState<String, String>(); val owner = Any()
        state.begin(owner); state.end(owner) shouldBe true
        state.owns(owner) shouldBe false
    }
})
