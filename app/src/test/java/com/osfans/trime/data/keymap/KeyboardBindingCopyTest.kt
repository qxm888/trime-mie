/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyboardBindingCopyTest : FunSpec({
    beforeTest {
        CustomKeyboards.setMode(CustomKeyboards.MODE_THEME)
        CustomKeyboards.resetAllForTest()
    }
    afterTest {
        CustomKeyboards.setMode(CustomKeyboards.MODE_THEME)
        CustomKeyboards.resetAllForTest()
    }

    fun key(label: String = "", popup: List<String> = emptyList(), sendBindings: Boolean? = null) = CustomKey(
        label = label,
        bindings = mutableMapOf("click" to "c", "longClick" to "Paste"),
        appearance = KeyAppearance.Style(popup = popup, sendBindings = sendBindings),
    )

    fun plan(source: List<CustomKey>, target: CustomKey = key()): KeyboardBindingCopy.Plan {
        fun layout(name: String, keys: List<CustomKey>): String = CustomKeyboards.create(name).also { id ->
            CustomKeyboards.mutate(id) {
                it.rows.clear()
                it.rows.add(CustomRow(keys.toMutableList()))
            }
        }
        return KeyboardBindingCopy.plan(layout("source", source), layout("target", listOf(target)))
    }

    test("duplicate click bindings with different popups are ambiguous") {
        val result = plan(listOf(key(popup = listOf("a")), key(popup = listOf("b"))))
        result.matches shouldBe emptyList()
        result.skipped shouldBe 1
    }

    test("duplicate click bindings with different send settings are ambiguous") {
        listOf(null to false, null to true, false to true).forEach { (first, second) ->
            val result = plan(listOf(key(sendBindings = first), key(sendBindings = second)))
            result.matches shouldBe emptyList()
            result.skipped shouldBe 1
        }
    }

    test("duplicates with identical copied fields can match despite different appearance") {
        val first = key(popup = listOf("a", "b"), sendBindings = false)
        val second = first.copy(width = 30f,
            appearance = first.appearance.copy(keyTextColor = "#123456"))
        val result = plan(listOf(first, second))
        result.skipped shouldBe 0
        result.matches.single().let {
            it.bindings shouldBe first.bindings
            it.popup shouldBe listOf("a", "b")
            it.sendBindings shouldBe false
        }
    }

    test("a unique label resolves ambiguous click candidates") {
        val result = plan(
            listOf(key("first", listOf("a"), false), key("chosen", listOf("b"), true)),
            key("chosen"),
        )
        result.skipped shouldBe 0
        result.matches.single().let {
            it.popup shouldBe listOf("b")
            it.sendBindings shouldBe true
        }
    }
    test("different main labels are ambiguous now that label display is copied") {
        plan(listOf(key("one"), key("two"))).matches shouldBe emptyList()
    }
    test("binding copy carries label display and independent annotation alignment while preserving key colors") {
        val source = key("复制").copy(appearance = KeyAppearance.Style(keyTextSize = 25f, keyTextOffsetX = 2f, keyTextOffsetY = 3f,
            labelSymbol = "-", hint = "copy", symbolAlign = "right", hintAlign = "left", icon = "icons/copy.png", iconSize = 22, keyTextColor = "#112233"))
        val target = key("C").copy(appearance = KeyAppearance.Style(keyTextColor = "#ABCDEF", keyBackColor = "#FFFFFF"))
        val match = plan(listOf(source), target).matches.single()
        val actual = KeyboardBindingCopy.copyKey(target, match)
        actual.label shouldBe "复制"
        actual.appearance.keyTextSize shouldBe 25f
        actual.appearance.keyTextOffsetX shouldBe 2f
        actual.appearance.keyTextOffsetY shouldBe 3f
        actual.appearance.icon shouldBe "icons/copy.png"
        actual.appearance.iconSize shouldBe 22
        actual.appearance.symbolAlign shouldBe "right"
        actual.appearance.hintAlign shouldBe "left"
        actual.appearance.keyTextColor shouldBe "#ABCDEF"
        actual.appearance.keyBackColor shouldBe "#FFFFFF"
    }
})
