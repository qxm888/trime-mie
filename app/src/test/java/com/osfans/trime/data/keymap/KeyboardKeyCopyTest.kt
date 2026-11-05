/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyboardKeyCopyTest : FunSpec({
    fun single(label: String) = CustomKeyboards.CustomKey(label = label)

    test("only a full set of 26 standalone letter keys qualifies") {
        val full = CustomKeyboards.CustomKeyboard(rows = mutableListOf(
            CustomKeyboards.CustomRow(('a'..'z').map { single(it.uppercase()) }.toMutableList())))
        KeyboardKeyCopy.isLetterLayout(full) shouldBe true
        KeyboardKeyCopy.letterKeys(full).size shouldBe 26

        // 14 键这类一个键挤两个字母的布局必须被挡掉
        val merged = CustomKeyboards.CustomKeyboard(rows = mutableListOf(
            CustomKeyboards.CustomRow(listOf("qw", "er", "ty").map(::single).toMutableList())))
        KeyboardKeyCopy.isLetterLayout(merged) shouldBe false
    }

    test("gestures and symbols are copied while the primary input stays") {
        val source = CustomKeyboards.CustomKey(label = "A",
            bindings = mutableMapOf("click" to "a", "swipeUp" to "1", "longClick" to "select_all"),
            appearance = KeyAppearance.Style(labelSymbol = "!", hint = "数字"))
        val target = CustomKeyboards.CustomKey(label = "a", width = 20f,
            bindings = mutableMapOf("click" to "啊", "swipeUp" to "9"))

        val copied = KeyboardBindingCopy.copyGestures(target, source, source.appearance)

        copied.bindings["click"] shouldBe "啊"
        copied.bindings["swipeUp"] shouldBe "1"
        copied.bindings["longClick"] shouldBe "select_all"
        copied.label shouldBe "a"
        copied.width shouldBe 20f
        copied.appearance.labelSymbol shouldBe "!"
        copied.appearance.hint shouldBe "数字"
    }

    test("letters missing from the source are counted as skipped") {
        val source = CustomKeyboards.CustomKeyboard(rows = mutableListOf(
            CustomKeyboards.CustomRow(('a'..'y').map { single(it.uppercase()) }.toMutableList())))
        val target = CustomKeyboards.CustomKeyboard(rows = mutableListOf(
            CustomKeyboards.CustomRow(('a'..'z').map { single(it.uppercase()) }.toMutableList())))
        val sourceKeys = KeyboardKeyCopy.letterKeys(source)
        val missed = target.rows.flatMap { it.keys }.count { key ->
            KeyboardKeyCopy.letterOf(key)?.let { it !in sourceKeys } ?: false
        }
        missed shouldBe 1
    }
})
