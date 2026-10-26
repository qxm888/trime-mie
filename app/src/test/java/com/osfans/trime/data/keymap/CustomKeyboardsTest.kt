/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.orAbsent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Custom layout conversion semantics: one editor row = one runtime row
 * (trailing spacer pads to 100), bindings map onto TextKey behavior fields,
 * and the layout mode decides whether theme presets are replaced.
 */
class CustomKeyboardsTest : FunSpec({
    fun kb(vararg rows: List<CustomKey>) =
        CustomKeyboard(
            name = "测试",
            rows = rows.map { ks -> CustomRow(ks.toMutableList()) }.toMutableList(),
        )

    test("row spacing accepts zero and preserves configured row heights") {
        val keyboard = CustomKeyboard(rows = mutableListOf(CustomRow(height = 40), CustomRow(height = 80)))
        keyboard.rowSpacing shouldBe 4
        keyboard.rowSpacing = 0
        CustomKeyboards.run { keyboard.toTextKeyboard() }.verticalGap shouldBe 0
        keyboard.rowSpacing = 16
        val text = CustomKeyboards.run { keyboard.toTextKeyboard() }
        text.verticalGap shouldBe 16
        text.keyboardHeight shouldBe 120
        keyboard.rowSpacing = 999
        CustomKeyboards.run { keyboard.toTextKeyboard() }.verticalGap shouldBe 20
    }

    test("row spacing persists in JSON and old layouts keep the four dp default") {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val keyboard = CustomKeyboard(rowSpacing = 14)
        val encoded = json.encodeToString(CustomKeyboard.serializer(), keyboard)
        json.decodeFromString(CustomKeyboard.serializer(), encoded).rowSpacing shouldBe 14
        json.decodeFromString(CustomKeyboard.serializer(), """{"name":"legacy","rows":[]}""").rowSpacing shouldBe 4
    }

    test("row heights survive conversion including empty rows") {
        val keyboard = CustomKeyboard(rows = mutableListOf(
            CustomRow(mutableListOf(CustomKey("a", 100f)), height = 40),
            CustomRow(height = 80),
        ))
        val text = CustomKeyboards.run { keyboard.toTextKeyboard() }
        text.keyboardHeight shouldBe 120
        text.keyboardHeightLand shouldBe 120
        text.keys.map { it.height } shouldBe listOf(40f, 80f)
        text.keys.map { it.newRow } shouldBe listOf(true, true)
        CustomRow().height shouldBe 60
    }

    test("explicit unbind suppresses the default space voice action") {
        val key = CustomKey("space", bindings = mutableMapOf("click" to "space", "longClick" to ""))
        val text = CustomKeyboards.run { CustomKeyboard(rows = mutableListOf(CustomRow(mutableListOf(key)))).toTextKeyboard() }
        text.keys.first().allowDefaultVoice shouldBe false
        text.keys.first().longClick.orAbsent shouldBe null
    }

    test("rows map 1:1 with spacer padding") {
        val keyboard = kb(
            listOf(
                CustomKey("q", 10f, mutableMapOf("click" to "q")),
                CustomKey("w", 10f, mutableMapOf("click" to "w")),
            ),
            listOf(CustomKey(label = "", width = 60f, bindings = mutableMapOf("click" to "space"))),
        )
        val text = CustomKeyboards.run { keyboard.toTextKeyboard() }
        // row1: q + w + spacer(80) ; row2: space key + spacer(40)
        text.keys.size shouldBe 5
        (text.keys[0].click.orAbsent as KeyActionToken.Plain).token shouldBe "q"
        text.keys[2].click shouldBe null // spacer
        text.keys[2].width shouldBe 80f
        (text.keys[3].click.orAbsent as KeyActionToken.Plain).token shouldBe "space"
        text.keys[4].width shouldBe 40f
    }

    test("bindings cover all editable gestures") {
        val key =
            CustomKey(
                label = "k",
                bindings =
                    mutableMapOf(
                        "click" to "a",
                        "longClick" to "A",
                        "swipeUp" to "Up",
                        "swipeDown" to "Down",
                        "swipeLeft" to "Left",
                        "swipeRight" to "Right",
                        "doubleClick" to "b",
                    ),
            )
        val text = CustomKeyboards.run { CustomKeyboard(name = "n", rows = mutableListOf(CustomRow(mutableListOf(key)))).toTextKeyboard() }.keys[0]
        (text.click.orAbsent as KeyActionToken.Plain).token shouldBe "a"
        (text.longClick.orAbsent as KeyActionToken.Plain).token shouldBe "A"
        (text.swipeUp.orAbsent as KeyActionToken.Plain).token shouldBe "Up"
        (text.swipeDown.orAbsent as KeyActionToken.Plain).token shouldBe "Down"
        (text.swipeLeft.orAbsent as KeyActionToken.Plain).token shouldBe "Left"
        (text.swipeRight.orAbsent as KeyActionToken.Plain).token shouldBe "Right"
        (text.doubleClick.orAbsent as KeyActionToken.Plain).token shouldBe "b"
    }

    test("mode switch replaces theme presets in custom mode") {
        val id = CustomKeyboards.create("布局")
        CustomKeyboards.mutate(id) { k ->
            k.rows.add(CustomRow(mutableListOf(CustomKey("a", 10f))))
        }
        CustomKeyboards.setMode(CustomKeyboards.MODE_CUSTOM)

        val theme = Theme(name = "t")
        val merged = CustomKeyboards.mergeInto(theme)
        merged.presetKeyboards.keys.size shouldBe 2 // custom_<ts> + default alias
        merged.presetKeyboards.containsKey("default") shouldBe true

        // theme mode: untouched
        CustomKeyboards.setMode(CustomKeyboards.MODE_THEME)
        CustomKeyboards.mergeInto(theme).presetKeyboards.size shouldBe 0

        CustomKeyboards.delete(id)
        CustomKeyboards.setMode(CustomKeyboards.MODE_THEME)
    }

    test("export/import round trip: merge and replace, mode untouched") {
        val id1 = CustomKeyboards.create("A")
        CustomKeyboards.mutate(id1) { k ->
            k.rows.clear()
            k.rows.add(CustomRow(mutableListOf(CustomKey("q", 10f, mutableMapOf("click" to "q")))))
        }
        CustomKeyboards.setMode(CustomKeyboards.MODE_CUSTOM)

        val json = CustomKeyboards.exportJson()
        CustomKeyboards.resetAllForTest()
        CustomKeyboards.list().isEmpty() shouldBe true
        // mode survived the wipe? resetForTest only clears keyboards
        CustomKeyboards.mode shouldBe CustomKeyboards.MODE_CUSTOM

        CustomKeyboards.importJson(json, replace = true)
        CustomKeyboards.list().size shouldBe 1
        CustomKeyboards.get(id1)?.rows?.get(0)?.keys?.get(0)?.label shouldBe "q"

        // merge keeps others; same id overwritten
        val id2 = CustomKeyboards.create("B")
        CustomKeyboards.importJson(json, replace = false)
        CustomKeyboards.list().size shouldBe 2

        runCatching { CustomKeyboards.importJson("not json", replace = true) }.isSuccess shouldBe false

        CustomKeyboards.resetAllForTest()
        CustomKeyboards.setMode(CustomKeyboards.MODE_THEME)
    }
})
