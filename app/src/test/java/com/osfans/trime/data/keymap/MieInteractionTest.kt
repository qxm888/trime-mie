/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.popup.candidateItemScale

class MieInteractionTest : FunSpec({
    beforeTest { com.osfans.trime.ime.keyboard.KeyCode.androidKeyNameToCode = { 0 } }
    test("video background is exclusive and normalizes unused color") {
        CustomBackground(CustomBackground.Type.VIDEO, color = "#FFFFFF", image = "/video.mp4").normalized() shouldBe
            CustomBackground(CustomBackground.Type.VIDEO, image = "/video.mp4")
        CustomBackground(CustomBackground.Type.VIDEO).normalized().type shouldBe CustomBackground.Type.TRANSPARENT
    }
    test("video background survives editable YAML round trip") {
        val k = CustomKeyboards.CustomKeyboard(name = "视频", rows = mutableListOf(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey(label = "a", bindings = mutableMapOf("click" to "a"))))), background = CustomBackground(CustomBackground.Type.VIDEO, image = "/a b/背景.mp4"))
        CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(mapOf("custom_video" to k), functions = emptyList())).getValue("custom_video").resolvedBackground() shouldBe k.background
    }
    test("window commands resolve with no theme preset definitions") {
        val theme = Theme(name = "empty")
        Theme.BUILTIN_ACTIONS.forEach { (name, preset) ->
            val action = theme.resolveAction(name)
            action.command shouldBe preset.command
            action.code shouldBe android.view.KeyEvent.KEYCODE_FUNCTION
        }
        // Mode actions use internal commands; symbol/hint visibility keeps its existing command names.
        Theme.BUILTIN_ACTIONS.keys.none { it.startsWith("switch_") } shouldBe true
        listOf("__mie_floating_keyboard", "__mie_one_hand").all {
            Theme.BUILTIN_ACTIONS.getValue(it).command.startsWith("__mie_")
        } shouldBe true
    }
    test("single row shrink is bounded and unknown width is unchanged") {
        candidateItemScale(100, 0) shouldBe 1f
        candidateItemScale(100, 200) shouldBe 1f
        candidateItemScale(1000, 100) shouldBe 0.5f
        candidateItemScale(200, 100) shouldBe 0.5f
    }
})
