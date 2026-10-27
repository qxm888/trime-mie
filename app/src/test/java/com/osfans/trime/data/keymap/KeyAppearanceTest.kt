/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.KeyAppearance.Style as TextKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class KeyAppearanceTest : FunSpec({
    test("default and individual styles survive YAML round trip and preserve gestures") {
        val defaults = TextKey(keyTextSize = 30f, roundCorner = 12f, keySymbolColor = "#FF00FF")
        val key = CustomKeyboards.CustomKey("a", bindings = mutableMapOf("click" to "a", "swipeUp" to "1"),
            appearance = TextKey(keyTextSize = 24f, hint = "提示", popup = listOf("á", "à"), hilitedKeyBackColor = "#FF0000"))
        val keyboard = CustomKeyboards.CustomKeyboard(name = "样式", keyAppearance = defaults,
            rows = mutableListOf(CustomKeyboards.CustomRow(mutableListOf(key))))
        val encoded = CustomKeyboardYaml.encode(mapOf("custom_style" to keyboard))
        val decoded = CustomKeyboardYaml.decode(encoded).getValue("custom_style")
        decoded.keyAppearance shouldBe defaults
        val restored = decoded.rows.first().keys.first()
        restored.bindings shouldBe key.bindings
        restored.appearance shouldBe key.appearance
        CustomKeyboards.run { restored.toTextKey(decoded.resolvedBackground(), decoded.keyAppearance) }.let {
            it.keyTextSize shouldBe 24f
            it.roundCorner shouldBe 12f
            it.popup shouldBe listOf("á", "à")
        }
    }
    test("invalid numeric styles cannot enter the layout store") {
        shouldThrow<IllegalArgumentException> { KeyAppearance.parse(mapOf("keyTextSize" to "NaN")) }
        shouldThrow<IllegalArgumentException> { KeyAppearance.parse(mapOf("keyBorder" to "1.5")) }
    }
    test("individual bindings may explicitly override a disabled keyboard default") {
        KeyAppearance.merge(TextKey(sendBindings = false), TextKey()).toTextKey().sendBindings shouldBe false
        KeyAppearance.merge(TextKey(sendBindings = false), TextKey(sendBindings = true)).toTextKey().sendBindings shouldBe true
    }
    test("per keyboard layout and navigation survive export without changing row geometry") {
        val options = KeyboardOptions(horizontalGap = 9, heightPercent = 150, landscapeHeightPercent = 80,
            asciiMode = 1, resetAsciiMode = true, lock = true, uppercaseLabels = true, asciiKeyboard = "custom_en")
        val keyboard = CustomKeyboards.CustomKeyboard(name = "geometry", options = options,
            rows = mutableListOf(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey("a")), 60)))
        val restored = CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(mapOf("custom_options" to keyboard))).getValue("custom_options")
        restored.options shouldBe options
        restored.rows.first().height shouldBe 60
        CustomKeyboards.run { restored.toTextKeyboard() }.let {
            it.keyboardHeight shouldBe 90
            it.keyboardHeightLand shouldBe 48
            it.horizontalGap shouldBe 9
            it.asciiKeyboard shouldBe "custom_en"
        }
    }
})
