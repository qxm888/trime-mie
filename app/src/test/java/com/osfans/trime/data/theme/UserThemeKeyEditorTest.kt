/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.orAbsent
import io.kotest.core.spec.style.FunSpec
import com.osfans.trime.util.mapping
import java.io.File
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Key-editor smoke test against the user's real theme source:
 * the DSL-expanded parse must expose editable keyboards and keys, and the
 * override overlay must swap behavior tokens exactly where the editor stores them.
 */
class UserThemeKeyEditorTest : FunSpec({
    // Deployed artifact path (same as ThemeLoader.loadDeployedTheme): no DSL
    // expansion, straight kaml decode — mirrors what the editor sees at runtime
    // for themes whose source uses kaml-incompatible syntax.
    val theme = ThemeTestSupport.yaml.decodeFromYamlNode<Theme>(
        ThemeTestSupport.yaml
            .parseToYamlNode(File("src/test/resources/user_theme.trime.yaml").readText())
            .mapping!!,
    )

    test("user theme has preset keyboards with keys") {
        theme.presetKeyboards.isEmpty() shouldBe false
        val default = theme.presetKeyboards["default"].shouldNotBeNull()
        default.keys.isEmpty() shouldBe false
        default.keys.count { it.click.orAbsent != null } shouldBe 33 // 2 spacers among 35
    }

    test("override changes the click token of the first clickable key") {
        val default = theme.presetKeyboards["default"]!!
        val index = default.keys.indexOfFirst { it.click.orAbsent != null }
        index shouldNotBe -1
        KeyBindingOverrides.setBinding("default", index, "click", "BackSpace")
        val overridden = KeyBindingOverrides.applyTo("default", index, default.keys[index])
        (overridden.click as KeyActionToken.Plain).token shouldBe "BackSpace"
        // other keys untouched
        KeyBindingOverrides.applyTo("default", index + 1, default.keys[index + 1]) shouldBe default.keys[index + 1]
        KeyBindingOverrides.setBinding("default", index, "click", null)
    }

    test("empty override token unbinds, null restores theme default") {
        val default = theme.presetKeyboards["default"]!!
        val index = default.keys.indexOfFirst { it.click.orAbsent != null }
        KeyBindingOverrides.setBinding("default", index, "click", "")
        KeyBindingOverrides.applyTo("default", index, default.keys[index]).click.orAbsent shouldBe null
        KeyBindingOverrides.setBinding("default", index, "click", null)
        KeyBindingOverrides.applyTo("default", index, default.keys[index]) shouldBe default.keys[index]
    }

    test("export/import round trip: merge and replace") {
        val default = theme.presetKeyboards["default"]!!
        val index = default.keys.indexOfFirst { it.click.orAbsent != null }
        KeyBindingOverrides.setBinding("default", index, "longClick", "Up")
        KeyBindingOverrides.setBinding("number", 1, "click", "BackSpace")

        val json = KeyBindingOverrides.exportJson()
        // wipe, then import back
        KeyBindingOverrides.resetAll()
        KeyBindingOverrides.binding("default", index, "longClick") shouldBe null
        KeyBindingOverrides.importJson(json, replace = true)
        KeyBindingOverrides.binding("default", index, "longClick") shouldBe "Up"
        KeyBindingOverrides.binding("number", 1, "click") shouldBe "BackSpace"

        // merge keeps unrelated current bindings
        KeyBindingOverrides.setBinding("symbolsCN", 2, "click", "Space")
        KeyBindingOverrides.importJson(json, replace = false)
        KeyBindingOverrides.binding("symbolsCN", 2, "click") shouldBe "Space"
        KeyBindingOverrides.binding("default", index, "longClick") shouldBe "Up"

        // invalid file rejected
        runCatching { KeyBindingOverrides.importJson("not json", replace = true) }.isSuccess shouldBe false

        KeyBindingOverrides.resetAll()
    }
})
