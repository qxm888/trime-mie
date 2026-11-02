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
        val encoded = CustomKeyboardYaml.encode(mapOf("custom_style" to keyboard), functions = emptyList())
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
    test("upper symbol and lower hint align independently and survive YAML") {
        val style = KeyAppearance.parse(mapOf("symbolAlign" to "靠右", "hintAlign" to "靠左"))
        style.toTextKey().symbolAlign shouldBe "right"
        style.toTextKey().hintAlign shouldBe "left"
        val keyboard = CustomKeyboards.CustomKeyboard(options = KeyboardOptions(symbolAlignment = "right", hintAlignment = "center"),
            rows = mutableListOf(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey("a", appearance = style)))))
        val restored = CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(mapOf("custom_alignment" to keyboard), emptyList())).getValue("custom_alignment")
        restored.rows[0].keys[0].appearance shouldBe style
        restored.options.effectiveHintAlignment shouldBe "center"
        KeyAppearance.merge(TextKey(hintAlign = "right"), TextKey(hintAlign = "")).hintAlign shouldBe ""
        shouldThrow<IllegalArgumentException> { KeyAppearance.parse(mapOf("hintAlign" to "invalid")) }
    }
    test("default symbols align right and hints center without following symbol changes") {
        TextKey(symbolAlign = "right").toTextKey().hintAlign shouldBe ""
        TextKey(symbolAlign = "right", hintAlign = "center").copy(symbolAlign = "left").toTextKey().hintAlign shouldBe "center"
        KeyboardOptions().symbolAlignment shouldBe "right"
        KeyboardOptions(symbolAlignment = "right").effectiveHintAlignment shouldBe "center"
        KeyboardOptions(symbolAlignment = "right", hintAlignment = "left").copy(symbolAlignment = "center").effectiveHintAlignment shouldBe "left"
    }
    test("individual bindings may explicitly override a disabled keyboard default") {
        KeyAppearance.merge(TextKey(sendBindings = false), TextKey()).toTextKey().sendBindings shouldBe false
        KeyAppearance.merge(TextKey(sendBindings = false), TextKey(sendBindings = true)).toTextKey().sendBindings shouldBe true
    }
    test("per keyboard layout and navigation survive export without changing row geometry") {
        val options = KeyboardOptions(horizontalGap = 9, heightPercent = 150, landscapeHeightPercent = 80,
            asciiMode = 1, resetAsciiMode = true, lock = true, uppercaseLabels = true,
            asciiKeyboard = "custom_en", landscapeKeyboard = "custom_land")
        val keyboard = CustomKeyboards.CustomKeyboard(name = "geometry", options = options,
            rows = mutableListOf(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey("a")), 60)))
        val restored = CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(mapOf("custom_options" to keyboard), functions = emptyList())).getValue("custom_options")
        restored.options shouldBe options
        restored.rows.first().height shouldBe 60
        CustomKeyboards.run { restored.toTextKeyboard() }.let {
            it.keyboardHeight shouldBe 90
            it.keyboardHeightLand shouldBe 48
            it.horizontalGap shouldBe 9
            // An unresolved target is suppressed at runtime, but retained in the export.
            it.asciiKeyboard shouldBe ""
        }
    }
})
