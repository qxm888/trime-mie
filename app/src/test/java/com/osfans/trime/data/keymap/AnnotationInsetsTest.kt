/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AnnotationInsetsTest : FunSpec({
    val fields = listOf("symbolInsetX", "symbolInsetY", "hintInsetX", "hintInsetY")
    test("independent insets parse and reach runtime definitions") {
        val style = KeyAppearance.parse(fields.zip(listOf("8", "4", "6", "3.5")).toMap())
        val key = style.toTextKey()
        listOf(key.symbolInsetX, key.symbolInsetY, key.hintInsetX, key.hintInsetY) shouldBe listOf(8f,4f,6f,3.5f)
    }
    test("blank inherits and explicit zero overrides a nonzero inset") {
        val defaults = KeyAppearance.Style(symbolInsetX=8f, symbolInsetY=4f,hintInsetX=6f,hintInsetY=4f)
        KeyAppearance.merge(defaults,KeyAppearance.parse(mapOf("symbolInsetX" to "0", "hintInsetY" to ""))) shouldBe defaults.copy(symbolInsetX=0f)
        KeyAppearance.parse(KeyAppearance.textValues(defaults)) shouldBe defaults
    }
    test("invalid or nonfinite inset values are rejected") {
        fields.forEach { field -> listOf("-1","49","NaN","Infinity","text").forEach { invalid ->
            shouldThrow<IllegalArgumentException> { KeyAppearance.parse(mapOf(field to invalid)) }
        } }
    }
    test("YAML roundtrip retains defaults and per-key bounds") {
        val page = CustomKeyboards.CustomKeyboard(name="测试", keyAppearance=KeyAppearance.Style(symbolInsetX=8f,hintInsetY=4f),
            rows=mutableListOf(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey(label="A",width=100f,
                bindings=mutableMapOf("click" to "a"),appearance=KeyAppearance.Style(symbolInsetX=0f,hintInsetX=3f))))))
        val restored=CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(mapOf("custom_bounds" to page),emptyList())).getValue("custom_bounds")
        restored.keyAppearance shouldBe page.keyAppearance
        restored.rows.single().keys.single().appearance.symbolInsetX shouldBe 0f
        restored.rows.single().keys.single().appearance.hintInsetX shouldBe 3f
    }
    test("binding copy includes source inherited display range without copying target colors") {
        fun page(color: String)=CustomKeyboards.CustomKeyboard(name="测试",keyAppearance=KeyAppearance.Style(symbolInsetX=8f,hintInsetY=4f),
            rows=mutableListOf(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey(label="A",width=100f,
                bindings=mutableMapOf("click" to "a"),appearance=KeyAppearance.Style(keyBackColor=color))))))
        val source=page("#FFFFFF"); val target=page("#000000")
        val match=KeyboardBindingCopy.matchPages(source,target).first.single()
        val copied=KeyboardBindingCopy.copyKey(target.rows.single().keys.single(),match)
        copied.appearance.symbolInsetX shouldBe 8f
        copied.appearance.hintInsetY shouldBe 4f
        copied.appearance.keyBackColor shouldBe "#000000"
    }
    test("upgrade fills only missing template bounds") {
        val template=CustomKeyboards.CustomKeyboard(name="测试",keyAppearance=KeyAppearance.Style(symbolInsetX=8f,symbolInsetY=4f,hintInsetX=6f,hintInsetY=4f))
        val edited=template.copy(keyAppearance=KeyAppearance.Style(symbolInsetX=0f,hintInsetY=7f,keyBackColor="#112233"))
        val upgraded=CustomKeyboards.upgradeSymbols(edited,template,emptySet())
        upgraded.keyAppearance shouldBe template.keyAppearance.copy(symbolInsetX=0f,hintInsetY=7f,keyBackColor="#112233")
    }
})
