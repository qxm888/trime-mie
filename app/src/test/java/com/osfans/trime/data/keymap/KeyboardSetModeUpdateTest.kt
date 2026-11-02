/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject

class KeyboardSetModeUpdateTest : FunSpec({
    fun page(role: String, key: CustomKey) = CustomKeyboard(
        secondaryOf = if (role == "zh_26") "" else "root", setPageRole = role,
        rows = mutableListOf(CustomRow(mutableListOf(key))),
    )
    fun key(long: String = "", popup: List<String> = emptyList(), send: Boolean? = null) = CustomKey(
        "C", keyId = "c", identity = "char:c",
        bindings = mutableMapOf("click" to "c", "ascii" to "local_ascii").apply { if (long.isNotEmpty()) put("longClick", long) },
        appearance = KeyAppearance.Style(popup = popup, sendBindings = send),
    )
    test("English follows main functions popup and send while preserving character and ascii input") {
        val primary = key("copy", listOf("paste"), true).copy(bindings = mutableMapOf("click" to "x", "ascii" to "source_ascii", "longClick" to "copy", "swipeUp" to "cut"))
        val pages = mapOf("root" to page("zh_26", primary), "english" to page("en", key()))
        val actual = KeyboardSets.resolve("english", pages, emptyMap())!!.rows[0].keys[0]
        actual.bindings shouldBe mapOf("click" to "c", "ascii" to "local_ascii", "longClick" to "copy", "swipeUp" to "cut")
        actual.appearance.popup shouldBe listOf("paste")
        actual.appearance.sendBindings shouldBe true
    }
    test("unrelated edits do not bake inherited functions into English") {
        val chinese = page("zh_26", key("copy", listOf("paste"), true))
        val raw = page("en", key())
        val pages = mutableMapOf("root" to chinese, "english" to raw)
        val before = KeyboardSets.resolve("english", pages, emptyMap())!!
        pages["english"] = KeyboardSets.captureMutation(raw, before, before.copy(rowSpacing = 9))
        pages["root"] = page("zh_26", key())
        val actual = KeyboardSets.resolve("english", pages, emptyMap())!!.rows[0].keys[0]
        actual.bindings.containsKey("longClick") shouldBe false
        actual.appearance.popup shouldBe emptyList()
        actual.appearance.sendBindings shouldBe null
    }
    test("explicit English unbind popup and send overrides survive main updates") {
        val raw = page("en", key())
        val pages = mutableMapOf("root" to page("zh_26", key("copy", listOf("paste"), true)), "english" to raw)
        val before = KeyboardSets.resolve("english", pages, emptyMap())!!
        val editedKey = before.rows[0].keys[0].copy(bindings = before.rows[0].keys[0].bindings.toMutableMap().apply { put("longClick", "") },
            appearance = before.rows[0].keys[0].appearance.copy(popup = emptyList(), sendBindings = false))
        val after = before.copy(rows = mutableListOf(CustomRow(mutableListOf(editedKey))))
        pages["english"] = KeyboardSets.captureMutation(raw, before, after)
        pages["root"] = page("zh_26", key("cut", listOf("undo"), true))
        val actual = KeyboardSets.resolve("english", pages, emptyMap())!!.rows[0].keys[0]
        actual.bindings["longClick"] shouldBe ""
        actual.appearance.popup shouldBe emptyList()
        actual.appearance.sendBindings shouldBe false
    }
    test("English inheritance metadata and page role survive standalone YAML") {
        val original = page("en", key().copy(primaryBindingOverrides = setOf("longClick", "@popup"),
            primaryBaseBindings = mapOf("click" to "c"), primaryBasePopup = listOf("copy"), primaryBaseSendBindings = false))
        val actual = CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(mapOf("custom_english" to original), emptyList())).getValue("custom_english")
        actual.setPageRole shouldBe "en"
        val result = actual.rows[0].keys[0]
        result.primaryBindingOverrides shouldBe original.rows[0].keys[0].primaryBindingOverrides
        result.primaryBaseBindings shouldBe mapOf("click" to "c")
        result.primaryBasePopup shouldBe listOf("copy")
        result.primaryBaseSendBindings shouldBe false
    }
    test("geometry defaults follow the set but seven column overrides remain independent") {
        val root = CustomKeyboard(keyboardHeightDp = 260, landscapeHeightDp = 180, rowSpacing = 10, rowMargin = 5)
        root.setConfig = KeyboardSetConfig(defaults = KeyboardSets.values(root))
        root.setOverrides = JsonObject(emptyMap())
        val child = CustomKeyboard(secondaryOf = "root", setOverrides = JsonObject(emptyMap()))
        val seven = CustomKeyboard(secondaryOf = "root", keyboardHeightDp = 320, rowSpacing = 4)
        seven.setOverrides = KeyboardSets.difference(KeyboardSets.values(root, "geometry"), KeyboardSets.values(seven, "geometry"))
        val pages = mapOf("root" to root, "child" to child, "seven" to seven)
        val actual = KeyboardSets.resolve("child", pages, emptyMap())!!
        listOf(actual.keyboardHeightDp, actual.landscapeHeightDp, actual.rowSpacing, actual.rowMargin) shouldBe listOf(260, 180, 10, 5)
        val independent = KeyboardSets.resolve("seven", pages, emptyMap())!!
        independent.keyboardHeightDp shouldBe 320
        independent.rowSpacing shouldBe 4
        root.setConfig = root.setConfig!!.copy(defaults = KeyboardSets.values(root.copy(keyboardHeightDp = 280, rowSpacing = 12)))
        KeyboardSets.resolve("child", pages, emptyMap())!!.rowSpacing shouldBe 12
        KeyboardSets.resolve("seven", pages, emptyMap())!!.keyboardHeightDp shouldBe 320
    }
    test("accent role wins over legacy white default and explicit per-key color wins over role") {
        val enter = CustomKey("Enter", bindings = mutableMapOf("click" to "Return"), setRole = "accent", setStyleOverrides = JsonObject(emptyMap()))
        val root = page("zh_26", enter).copy(keyAppearance = KeyAppearance.Style(keyBackColor = "#FFFFFF"),
            setKeyStyles = mapOf("accent" to KeyAppearance.Style(keyBackColor = "#507F97", keyTextColor = "#FFFFFF")), setOverrides = JsonObject(emptyMap()))
        root.setConfig = KeyboardSetConfig(defaults = KeyboardSets.values(root))
        KeyboardSets.resolve("root", mapOf("root" to root), emptyMap())!!.rows[0].keys[0].appearance.keyBackColor shouldBe "#507F97"
        root.rows[0].keys[0] = enter.copy(setStyleOverrides = KeyAppearance.values(KeyAppearance.Style(keyBackColor = "#112233")))
        KeyboardSets.resolve("root", mapOf("root" to root), emptyMap())!!.rows[0].keys[0].appearance.keyBackColor shouldBe "#112233"
    }
    test("builtin dimension migration preserves nondefault user sizes and seven column defaults") {
        val template = CustomKeyboard(keyboardHeightDp = 260, rowSpacing = 10)
        val migrated = CustomKeyboards.upgradeSymbols(CustomKeyboard(keyboardHeightDp = 0, rowSpacing = 4), template, emptySet())
        migrated.keyboardHeightDp shouldBe 260
        migrated.rowSpacing shouldBe 10
        val custom = CustomKeyboards.upgradeSymbols(CustomKeyboard(keyboardHeightDp = 290, rowSpacing = 8), template, emptySet())
        custom.keyboardHeightDp shouldBe 290
        custom.rowSpacing shouldBe 8
        val seven = CustomKeyboards.upgradeSymbols(CustomKeyboard(keyboardHeightDp = 0, rowSpacing = 4), CustomKeyboard(keyboardHeightDp = 320, rowSpacing = 4), emptySet())
        seven.keyboardHeightDp shouldBe 0
        seven.rowSpacing shouldBe 4
    }
    test("adopted builtin set migrates effective defaults while retaining inherited seven column dimensions") {
        val root = CustomKeyboard(keyboardHeightDp = 0, rowSpacing = 5, setOverrides = JsonObject(emptyMap()))
        root.setConfig = KeyboardSetConfig(defaults = KeyboardSets.values(root))
        val seven = CustomKeyboard(secondaryOf = "root", rowSpacing = 5, setOverrides = JsonObject(emptyMap()))
        val old = mapOf("root" to root, "seven" to seven)
        val preserved = KeyboardSets.values(KeyboardSets.resolve("seven", old, emptyMap())!!, "geometry")
        val upgraded = mapOf(
            "root" to CustomKeyboards.upgradeSymbols(root, CustomKeyboard(keyboardHeightDp = 260, rowSpacing = 10), old.keys),
            "seven" to CustomKeyboards.upgradeSymbols(seven, CustomKeyboard(rowSpacing = 5), old.keys, preserved),
        )
        KeyboardSets.resolve("root", upgraded, emptyMap())!!.keyboardHeightDp shouldBe 260
        KeyboardSets.resolve("root", upgraded, emptyMap())!!.rowSpacing shouldBe 10
        KeyboardSets.resolve("seven", upgraded, emptyMap())!!.keyboardHeightDp shouldBe 0
        KeyboardSets.resolve("seven", upgraded, emptyMap())!!.rowSpacing shouldBe 5
    }
    test("Chinese layouts recover their English sibling when an old editor erased the saved link") {
        val root = CustomKeyboard(setPageRole = "zh_26")
        val eighteen = CustomKeyboard(secondaryOf = "root", setPageRole = "zh_18")
        val english = CustomKeyboard(secondaryOf = "root", setPageRole = "en")
        val pages = mapOf("root" to root, "eighteen" to eighteen, "english" to english)
        CustomKeyboards.effectiveAsciiTarget("root", root, pages) shouldBe "english"
        CustomKeyboards.effectiveAsciiTarget("eighteen", eighteen, pages) shouldBe "english"
        CustomKeyboards.effectiveAsciiTarget("english", english, pages) shouldBe ""
        CustomKeyboards.effectiveAsciiTarget("root", root.copy(options = KeyboardOptions(asciiKeyboard = "different")), pages + ("different" to english)) shouldBe "different"
        CustomKeyboards.effectiveAsciiTarget("root", root, mapOf("root" to root)) shouldBe ""
    }
})
