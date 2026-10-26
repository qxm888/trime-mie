/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class CustomKeyboardYamlTest : FunSpec({
    test("selected layouts round trip backgrounds heights and gestures") {
        val keyboard = CustomKeyboards.CustomKeyboard(name = "花:#🌸", rowSpacing = 12,
            rows = mutableListOf(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey("a", 60f,
                mutableMapOf("click" to "a", "longClick" to "", "swipeUp" to "Up"))), 80), CustomKeyboards.CustomRow(height = 40)))
        val encoded = CustomKeyboardYaml.encode(mapOf("custom_one" to keyboard))
        val result = CustomKeyboardYaml.decode(encoded)
        result.keys shouldBe setOf("custom_one")
        result.getValue("custom_one").name shouldBe keyboard.name
        result.getValue("custom_one").rows.map { it.height } shouldBe listOf(80, 40)
        result.getValue("custom_one").rowSpacing shouldBe 12
        result.getValue("custom_one").rows[0].keys[0].bindings shouldBe keyboard.rows[0].keys[0].bindings
    }
    test("ordinary themes are not mistaken for standalone backups") {
        shouldThrow<IllegalArgumentException> { CustomKeyboardYaml.decode("name: test\npreset_keyboards: {}") }
    }
})
