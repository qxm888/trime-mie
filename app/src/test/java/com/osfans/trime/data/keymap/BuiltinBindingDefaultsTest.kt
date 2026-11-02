/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.serialization.json.JsonObject

class BuiltinBindingDefaultsTest : FunSpec({
    fun defaults() = BuiltinBindingDefaults(File("src/main/res/raw/mie_builtin_bindings.json").readText())
    test("all builtin families receive frost gestures with local navigation and original artwork") {
        val defaults = defaults()
        val original = listOf("mie_builtin_keyboards", "mie_builtin_extra", "mie_builtin_effects").flatMap { file ->
            CustomKeyboardYaml.decode(File("src/main/res/raw/$file.yaml").readText()).entries
        }.associate { it.toPair() }
        val roots = listOf("custom_mint", "custom_pink", "custom_blue", "custom_orange", "custom_purple", "custom_porcelain", "custom_obsidian", "custom_champagne", "custom_frost")
        roots.forEach { root ->
            val before = original.getValue(root)
            val result = defaults.template(root, before)
            val c = result.rows.flatMap { it.keys }.first { it.bindings["click"] == "c" }
            c.bindings["longClick"] shouldBe "Control+c"
            c.appearance.labelSymbol shouldBe "-"
            c.appearance.hint shouldBe "复制"
            result.background shouldBe before.background
            result.rows.flatMap { it.keys }.map { it.appearance.icon } shouldBe before.rows.flatMap { it.keys }.map { it.appearance.icon }
            result.options.symbolAlignment shouldBe "right"
            result.options.effectiveHintAlignment shouldBe "center"
            listOf("", "_9", "_14", "_18", "_num", "_en", "_7_1", "_7_2").forEach { suffix ->
                val page = defaults.template(root + suffix, original.getValue(root + suffix))
                page.rows.flatMap { it.keys }.flatMap { it.bindings.values }.forEach { token ->
                    token.startsWith("__mie_function:") shouldBe false
                    if (token.startsWith("Keyboard_custom_")) token.startsWith("Keyboard_$root") shouldBe true
                }
                page.rows.flatMap { it.keys }.filter { it.label == "返回" }.forEach { key -> key.bindings["click"] shouldBe "Keyboard_default" }
            }
        }
    }
    test("migration follows moved keys and preserves edited gestures annotations and set-managed bindings") {
        val defaults = defaults()
        val original = CustomKeyboard(rows = mutableListOf(CustomRow(mutableListOf(
            CustomKey("C", bindings = mutableMapOf("click" to "c"), appearance = KeyAppearance.Style(hint = "old")),
            CustomKey("V", bindings = mutableMapOf("click" to "v")),
        ))))
        defaults.template("custom_blue", original)
        val existing = original.copy(rows = mutableListOf(CustomRow(mutableListOf(
            original.rows[0].keys[1].copy(bindings = mutableMapOf("click" to "v", "longClick" to "user_action"), appearance = KeyAppearance.Style(hint = "用户注解")),
            original.rows[0].keys[0].copy(setBindingOverrides = emptySet(), setBaseBindings = mapOf("click" to "c"), setStyleOverrides = JsonObject(emptyMap())),
        ))))
        val result = defaults.migrate("custom_blue", existing)
        result.rows[0].keys[0].bindings["longClick"] shouldBe "user_action"
        result.rows[0].keys[0].appearance.hint shouldBe "用户注解"
        val copied = result.rows[0].keys[1]
        copied.bindings["longClick"] shouldBe "Control+c"
        copied.setBaseBindings?.get("longClick") shouldBe "Control+c"
        copied.appearance.hint shouldBe "复制"
        copied.appearance.labelSymbol shouldBe "-"
    }
    test("symbol editing on adopted pink and frost retains function and Enter colors through save and reload") {
        val templates = listOf("mie_builtin_keyboards", "mie_builtin_effects").flatMap { file ->
            CustomKeyboardYaml.decode(File("src/main/res/raw/$file.yaml").readText()).entries
        }.associate { it.toPair() }
        listOf("custom_pink", "custom_frost").forEach { root ->
            val pages = templates.filter { (id, _) -> id == root || id.startsWith(root + "_") }.toMutableMap()
            KeyboardSetOperations.adoptBuiltinData(root, pages, emptyMap(), templates)
            val raw = pages.getValue(root)
            val before = KeyboardSets.resolve(root, pages, emptyMap())!!
            val originalColors = before.rows.flatMap { it.keys }.map { it.appearance.keyBackColor }
            val after = before.copy(rows = before.rows.map { row -> row.copy(keys = row.keys.map { key ->
                key.copy(appearance = key.appearance.copy(labelSymbol = "符", hint = "注"))
            }.toMutableList()) }.toMutableList())
            pages[root] = KeyboardSets.captureMutation(raw, before, after)
            val restored = KeyboardSets.resolve(root, pages, emptyMap())!!
            restored.rows.flatMap { it.keys }.map { it.appearance.keyBackColor } shouldBe originalColors
            val expectedEnter = if(root == "custom_pink") "#F4AEB8" else "#507F97"
            restored.rows.flatMap { it.keys }.first { it.bindings["click"] == "Return" }.appearance.keyBackColor shouldBe expectedEnter
            // An old version could bake the wrong normal color into a raw key after an unrelated edit.
            // With no explicit color override its role must still restore the designed accent.
            val broken = pages.getValue(root)
            val normal = broken.setKeyStyles.getValue("normal").keyBackColor
            broken.rows.forEach { row -> row.keys.replaceAll { key -> key.copy(appearance = key.appearance.copy(keyBackColor = normal)) } }
            KeyboardSets.resolve(root, pages, emptyMap())!!.rows.flatMap { it.keys }.first { it.bindings["click"] == "Return" }.appearance.keyBackColor shouldBe expectedEnter
        }
    }
    test("layout choice surface contains six Chinese layouts and no internal helper pages") {
        val pages = CustomKeyboardYaml.decode(File("src/main/res/raw/mie_builtin_keyboards.yaml").readText())
        val root = "custom_pink"
        val choices = KeyboardSetLayoutChoices.items(root, pages, KeyboardSets.family(root, pages))
        choices.map { it.id }.toSet() shouldBe setOf(root, root+"_9", root+"_14", root+"_18", root+"_7_1", root+"_7_2")
        choices.first { it.id == root }.label shouldBe "26键 主键盘"
    }
})
