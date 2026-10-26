/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.model.orAbsent
import com.osfans.trime.data.theme.model.KeyActionToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ModeIsolationTest : FunSpec({
    beforeTest { CustomKeyboards.setMode(CustomKeyboards.MODE_THEME); CustomKeyboards.resetAllForTest(); ToolbarOverrides.resetAll() }
    afterTest { CustomKeyboards.setMode(CustomKeyboards.MODE_THEME); CustomKeyboards.resetAllForTest(); ToolbarOverrides.resetAll() }

    test("fully custom runtime ignores every YAML theme field") {
        val id = CustomKeyboards.create("isolated")
        CustomKeyboards.mutate(id) { it.rows[0].keys += CustomKey("a", bindings = mutableMapOf("click" to "a")) }
        CustomKeyboards.setMode(CustomKeyboards.MODE_CUSTOM)
        val a = Theme(name = "A", style = GeneralStyle(keyboardHeight = 999), presetKeys = mapOf("a" to PresetKey(commit = "WRONG")))
        val b = Theme(name = "B", toolBar = ToolBar(buttons = listOf(ToolBar.Button(action = "WRONG"))))
        CustomKeyboards.mergeInto(a) shouldBe CustomKeyboards.mergeInto(b)
        CustomKeyboards.mergeInto(a).isCustom shouldBe true
        CustomKeyboards.mergeInto(a).presetKeys.containsKey("a") shouldBe false
        CustomKeyboards.setMode(CustomKeyboards.MODE_THEME)
        CustomKeyboards.mergeInto(a) shouldBe a
        CustomKeyboards.resolveId("default") shouldBe "default"
        CustomKeyboards.isCustom("default") shouldBe false
        CustomKeyboards.isCustom(id) shouldBe false
    }

    test("background normalizes to exactly one source including old mixed data") {
        val image = CustomBackground.fromLegacy("#FF0000", "/skin.png", CustomBackground.KEY_COLOR)
        image.type shouldBe CustomBackground.Type.IMAGE
        image.color shouldBe ""
        CustomBackground(CustomBackground.Type.COLOR, "#123456", "/ignored.png").normalized().image shouldBe ""
        val clear = CustomBackground(CustomBackground.Type.TRANSPARENT, "#123456", "/ignored.png").normalized()
        clear.color shouldBe ""
        clear.image shouldBe ""
        CustomBackground(color = "#00000000").normalized().type shouldBe CustomBackground.Type.TRANSPARENT
    }

    test("empty custom toolbar stays empty and theme toolbar edits stay in theme mode") {
        val catalog = StandaloneKeyboardTheme.base().toolBar
        ToolbarOverrides.setEntries(listOf(ToolbarOverrides.Entry("paste", icon = "THEME")))
        ToolbarOverrides.applyForCustomKeyboard(catalog, emptyList()).buttons shouldBe emptyList()
        val custom = ToolbarOverrides.applyForCustomKeyboard(catalog, listOf(ToolbarOverrides.Entry("copy", icon = "CUSTOM")))
        custom.buttons.map { it.action } shouldBe listOf("copy")
        custom.buttons.single().foreground.style shouldBe "CUSTOM"
        ToolbarOverrides.apply(catalog).buttons.single().foreground.style shouldBe "THEME"
        ToolbarOverrides.setEntries(emptyList())
        ToolbarOverrides.apply(catalog).buttons shouldBe emptyList()
        ToolbarOverrides.resetAll()
        ToolbarOverrides.apply(catalog) shouldBe catalog
    }

    test("editing a snapshot and canceling cannot mutate persisted key bindings") {
        val id = CustomKeyboards.create("snapshot")
        CustomKeyboards.mutate(id) { it.rows[0].keys += CustomKey("a", bindings = mutableMapOf("click" to "a")) }
        val snapshot = CustomKeyboards.get(id)!!
        snapshot.rows[0].keys[0].bindings["click"] = "b"
        snapshot.rows.clear()
        CustomKeyboards.get(id)!!.rows[0].keys[0].bindings["click"] shouldBe "a"
    }

    test("row boundaries survive oversized weights and empty rows") {
        val kb = CustomKeyboard(rows = mutableListOf(
            CustomRow(mutableListOf(CustomKey("a", 80f), CustomKey("b", 80f))),
            CustomRow(), CustomRow(mutableListOf(CustomKey("c", 20f))),
        ))
        val text = CustomKeyboards.run { kb.toTextKeyboard() }
        text.keys.take(2).map { it.width } shouldBe listOf(50f, 50f)
        text.keys[0].newRow shouldBe true
        text.keys[1].newRow shouldBe false
        text.keys[2].newRow shouldBe true
        text.keys[2].width shouldBe 100f
        text.keys[3].newRow shouldBe true
        text.columns shouldBe -1
    }

    test("bad backup is rejected before existing layouts are cleared") {
        val id = CustomKeyboards.create("keep")
        runCatching { CustomKeyboards.importJson("{}", true) }.isFailure shouldBe true
        runCatching { CustomKeyboards.importJson("""{"format":"wrong","keyboards":{}}""", true) }.isFailure shouldBe true
        CustomKeyboards.get(id)!!.name shouldBe "keep"
    }
    test("layout backup cannot overwrite the separate theme binding file") {
        KeyBindingOverrides.setBinding("default", 0, "click", "a")
        val before = KeyBindingOverrides.binding("default", 0, "click")
        runCatching { KeyBindingOverrides.importJson(CustomKeyboards.exportJson(), true) }.isFailure shouldBe true
        KeyBindingOverrides.binding("default", 0, "click") shouldBe before
        KeyBindingOverrides.clearKey("default", 0)
    }

    test("toolbar can bind a function absent from the source theme toolbar") {
        ToolbarOverrides.setEntries(listOf(ToolbarOverrides.Entry("Control+c", icon = "COPY")))
        val result = ToolbarOverrides.apply(ToolBar())
        result.buttons.single().action shouldBe "Control+c"
        result.buttons.single().foreground.style shouldBe "COPY"
    }

})
