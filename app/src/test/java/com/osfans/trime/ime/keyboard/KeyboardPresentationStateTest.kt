/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class KeyboardPresentationStateTest : FunSpec({
    test("docked keyboard and view are published together") {
        val state = KeyboardPresentationState<String, String>()
        val window = Any()
        state.begin(window)
        state.visible.shouldBeNull()
        state.attachDocked(window, "letters", "letter view") shouldBe true
        state.visible?.keyboard shouldBe "letters"
        state.visible?.view shouldBe "letter view"
    }
    test("floating is the action source until its owner dismisses") {
        val state = KeyboardPresentationState<String, String>()
        val window = Any(); val dialog = Any()
        state.begin(window)
        state.attachDocked(window, "docked", "docked view")
        state.attachFloating(window, dialog, "floating", "floating view") shouldBe true
        state.attachDocked(window, "new docked", "new docked view")
        state.visible?.keyboard shouldBe "floating"
        state.detachFloating(dialog)
        state.visible?.keyboard shouldBe "new docked"
        state.visible?.view shouldBe "new docked view"
    }
    test("resize replaces both floating model and view") {
        val state = KeyboardPresentationState<String, String>()
        val window = Any(); val dialog = Any()
        state.begin(window); state.attachDocked(window, "dock", "view")
        state.attachFloating(window, dialog, "small", "small view")
        state.attachFloating(window, dialog, "large", "large view")
        state.visible?.keyboard shouldBe "large"
        state.visible?.view shouldBe "large view"
    }
    test("late dismissal of an old dialog leaves new dialog intact") {
        val state = KeyboardPresentationState<String, String>()
        val window = Any(); val old = Any(); val replacement = Any()
        state.begin(window); state.attachDocked(window, "dock", "view")
        state.attachFloating(window, old, "old", "old view")
        state.attachFloating(window, replacement, "new", "new view")
        state.detachFloating(old)
        state.visible?.keyboard shouldBe "new"
    }
    test("theme replacement rejects old window publication and cleanup") {
        val state = KeyboardPresentationState<String, String>()
        val old = Any(); val replacement = Any()
        state.begin(old); state.attachDocked(old, "old", "view")
        state.begin(replacement); state.attachDocked(replacement, "new", "new view")
        state.attachDocked(old, "stale", "stale view") shouldBe false
        state.attachFloating(old, Any(), "stale floating", "stale view") shouldBe false
        state.end(old) shouldBe false
        state.visibleFor(old).shouldBeNull()
        state.visibleFor(replacement)?.keyboard shouldBe "new"
        state.visible?.keyboard shouldBe "new"
    }
    test("session end drops model and view references") {
        val state = KeyboardPresentationState<String, String>(); val window = Any()
        state.begin(window); state.attachDocked(window, "dock", "view")
        state.attachFloating(window, Any(), "float", "float view")
        state.end(window) shouldBe true
        state.visible.shouldBeNull()
        state.attachDocked(window, "stale", "view") shouldBe false
    }
    test("floating cannot appear without an attached docked keyboard") {
        val state = KeyboardPresentationState<String, String>(); val window = Any()
        state.begin(window)
        state.attachFloating(window, Any(), "float", "view") shouldBe false
    }
    test("repeating begin for the same owner preserves presentation") {
        val state = KeyboardPresentationState<String, String>(); val window = Any()
        state.begin(window); state.attachDocked(window, "dock", "view"); state.begin(window)
        state.visible?.keyboard shouldBe "dock"
    }
})
