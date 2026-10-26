/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.TextKeyboard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyboardNavigationTest : FunSpec({
    val presets = linkedMapOf("default" to TextKeyboard(), "qwerty" to TextKeyboard(landscapeKeyboard = "wide"), "number" to TextKeyboard(), "wide" to TextKeyboard(), "qwerty_" to TextKeyboard(), "qwerty0" to TextKeyboard())
    test("schema-specific layout wins over alphabet matching") {
        KeyboardNavigation(presets, false).select(".default", KeyboardNavigation.State(schema = "number", alphabet = "abc")) shouldBe "number"
    }
    test("alphabet matching preserves existing qwerty variants") {
        val nav = KeyboardNavigation(presets, false)
        listOf("abc" to "qwerty", "abc,." to "qwerty_", "abc123" to "qwerty0", "a-" to "default").forEach { (alphabet, expected) ->
            nav.select(".default", KeyboardNavigation.State(alphabet = alphabet)) shouldBe expected
        }
    }
    test("custom next and prior wrap while excluding default alias") {
        val nav = KeyboardNavigation(presets, true)
        nav.select(".prior", KeyboardNavigation.State(current = "default", resolvedCurrent = "qwerty")) shouldBe "qwerty0"
        nav.select(".next", KeyboardNavigation.State(current = "qwerty0")) shouldBe "qwerty"
    }
    test("theme next and prior stop at list boundaries") {
        val nav = KeyboardNavigation(presets, false)
        nav.select(".prior", KeyboardNavigation.State(current = "default")) shouldBe "default"
        nav.select(".next", KeyboardNavigation.State(current = "qwerty0")) shouldBe "qwerty0"
    }
    test("empty request uses lock policy") {
        val nav = KeyboardNavigation(presets, false)
        nav.select("", KeyboardNavigation.State(current = "number", locked = "qwerty", currentIsLocked = true)) shouldBe "number"
        nav.select("", KeyboardNavigation.State(current = "number", locked = "qwerty")) shouldBe "qwerty"
    }
    test("ascii selection uses declared layout then locked layout then current") {
        val nav = KeyboardNavigation(presets, false)
        nav.select(".ascii", KeyboardNavigation.State(current = "default", ascii = "number")) shouldBe "number"
        nav.select(".ascii", KeyboardNavigation.State(current = "default", locked = "qwerty")) shouldBe "qwerty"
        nav.select(".ascii", KeyboardNavigation.State(current = "default", ascii = "missing", locked = "qwerty")) shouldBe "default"
    }
    test("last keyboard and last locked keyboard remain distinct") {
        val nav = KeyboardNavigation(presets, false); val state = KeyboardNavigation.State(last = "number", locked = "qwerty")
        nav.select(".last", state) shouldBe "number"
        nav.select(".last_lock", state) shouldBe "qwerty"
    }
    test("landscape alias only applies if its target exists") {
        val nav = KeyboardNavigation(presets, false)
        nav.select("qwerty", KeyboardNavigation.State(landscape = true)) shouldBe "wide"
        nav.select("qwerty", KeyboardNavigation.State()) shouldBe "qwerty"
        KeyboardNavigation(mapOf("qwerty" to TextKeyboard(landscapeKeyboard = "missing")), false)
            .select("qwerty", KeyboardNavigation.State(landscape = true)) shouldBe "qwerty"
    }
    test("empty custom preset list safely returns default") {
        KeyboardNavigation(mapOf("default" to TextKeyboard()), true).select(".next", KeyboardNavigation.State()) shouldBe "default"
    }
})
