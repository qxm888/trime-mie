/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.ToolBar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull

class ModeToolbarTest : FunSpec({
    test("mode actions and theme aliases are removed from primary and regular toolbar buttons") {
        val theme = Theme(name = "mode regression", presetKeys = mapOf(
            "floatAlias" to PresetKey(command = "switch_floating_keyboard"),
            "handAlias" to PresetKey(command = "switch_one_hand_mode"),
        ), toolBar = ToolBar(primaryButton = ToolBar.Button(action = "floatAlias"), buttons = listOf(
            ToolBar.Button(action = "__floating"), ToolBar.Button(action = "handAlias"), ToolBar.Button(action = "copy"),
        )))
        val result = ToolbarOverrides.forKeyboard(theme)
        result.primaryButton.shouldBeNull()
        result.buttons.none { it.action == "__floating" || it.action == "floatAlias" || it.action == "handAlias" } shouldBe true
        theme.toolBar.primaryButton?.action shouldBe "floatAlias"
        theme.toolBar.buttons.size shouldBe 3
    }

    test("renamed internal mode commands are filtered like the legacy names") {
        val theme = Theme(name = "renamed", presetKeys = mapOf(
            "mieFloat" to com.osfans.trime.data.theme.model.PresetKey(command = "__mie_toggle_floating_keyboard"),
            "mieHand" to com.osfans.trime.data.theme.model.PresetKey(command = "__mie_toggle_one_hand"),
        ), toolBar = ToolBar(primaryButton = ToolBar.Button(action = "__mie_floating_keyboard"), buttons = listOf(
            ToolBar.Button(action = "mieFloat"), ToolBar.Button(action = "mieHand"), ToolBar.Button(action = "copy"),
        )))
        val result = ToolbarOverrides.forKeyboard(theme)
        result.primaryButton.shouldBeNull()
        result.buttons.map { it.action } shouldBe listOf("copy")
        result.buttons.none { ToolbarOverrides.isModeAction(it.action) } shouldBe true
    }
})
