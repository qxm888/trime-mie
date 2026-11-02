/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

class BuiltinPunctuationDefaultsTest : FunSpec({
    fun legacy() = CustomKeyboards.CustomKey("。", bindings = mutableMapOf("click" to "."),
        appearance = KeyAppearance.Style(keyTextOffsetX = 5f, keyTextOffsetY = -5f, labelSymbol = "!"))
    test("stock punctuation offsets clear independently of bindings symbols colors or moved position") {
        val before = legacy().copy(bindings = mutableMapOf("click" to ".", "longClick" to "user"))
        val next = BuiltinPunctuationDefaults.upgrade(before)
        next shouldBe before.copy(appearance = before.appearance.copy(keyTextOffsetX = 0f, keyTextOffsetY = 0f))
        BuiltinPunctuationDefaults.upgrade(next) shouldBe next
    }
    test("adopted stock offsets migrate and different custom positions remain intact") {
        val adopted = legacy().copy(setStyleOverrides = JsonObject(mapOf("keyTextOffsetX" to JsonPrimitive(5f), "hint" to JsonPrimitive("用户注解"))))
        BuiltinPunctuationDefaults.upgrade(adopted).setStyleOverrides shouldBe JsonObject(mapOf("keyTextOffsetX" to JsonPrimitive(0f), "hint" to JsonPrimitive("用户注解")))
        val explicit = adopted.copy(setStyleOverrides = JsonObject(mapOf("keyTextOffsetX" to JsonPrimitive(6f))))
        BuiltinPunctuationDefaults.upgrade(explicit) shouldBe explicit
        val moved = legacy().copy(appearance = legacy().appearance.copy(keyTextOffsetX = 6f))
        BuiltinPunctuationDefaults.upgrade(moved) shouldBe moved
        val other = legacy().copy(label = "O")
        BuiltinPunctuationDefaults.upgrade(other) shouldBe other
    }
    test("all current builtin templates center punctuation and every binding profile defaults to right top and centered bottom") {
        listOf("mie_builtin_keyboards", "mie_builtin_extra", "mie_builtin_effects").forEach { file ->
            CustomKeyboardYaml.decode(File("src/main/res/raw/$file.yaml").readText()).values.forEach { kb ->
                kb.rows.flatMap { it.keys }.filter { it.label == "。" && it.bindings["click"] == "." }.forEach { key ->
                    key.appearance.keyTextOffsetX shouldBe 0f
                    key.appearance.keyTextOffsetY shouldBe 0f
                }
            }
        }
        val defaults = BuiltinBindingDefaults(File("src/main/res/raw/mie_builtin_bindings.json").readText())
        listOf("", "_9", "_14", "_18", "_num", "_en", "_7_1", "_7_2").forEach { suffix ->
            val kb = defaults.template("custom_pink$suffix", CustomKeyboards.CustomKeyboard(secondaryOf = if(suffix.isEmpty()) "" else "custom_pink"))
            kb.options.symbolAlignment shouldBe "right"
            kb.options.effectiveHintAlignment shouldBe "center"
        }
    }
})
