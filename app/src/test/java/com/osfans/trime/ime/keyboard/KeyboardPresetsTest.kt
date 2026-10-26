/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard
import com.osfans.trime.data.theme.model.TextKeyboard
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe

class KeyboardPresetsTest : FunSpec({
    test("chains and default fallback resolve to the final layout") {
        val leaf = TextKeyboard(name = "leaf")
        val presets = mapOf("default" to TextKeyboard(importPreset = "a"), "a" to TextKeyboard(importPreset = "b"), "b" to leaf)
        resolveKeyboardPreset(presets, "unknown") shouldBe leaf
    }
    test("UI selection falls back from invalid edits to a valid default") {
        val valid = TextKeyboard(name = "valid")
        selectKeyboardPreset(mapOf("a" to TextKeyboard(importPreset = "a"), "default" to valid), "a") shouldBe valid
    }
    test("cycles fail before stack overflow") {
        shouldThrow<IllegalArgumentException> { resolveKeyboardPreset(mapOf("a" to TextKeyboard(importPreset = "b"), "b" to TextKeyboard(importPreset = "a")), "a") }
    }
})
