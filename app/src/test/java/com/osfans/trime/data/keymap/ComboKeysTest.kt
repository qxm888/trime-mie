/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.view.KeyEvent
import com.osfans.trime.data.keymap.ComboKeys.Definition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ComboKeysTest : FunSpec({
    val ctrlEnter = Definition("1", KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_ENTER, "__mie_action:x")
    val ctrlAltDel = Definition(
        "2", KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON, KeyEvent.KEYCODE_FORWARD_DEL, "__mie_action:y",
    )

    test("exact modifier set matches") {
        ComboKeys.matchIn(listOf(ctrlEnter), KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_ENTER) shouldBe ctrlEnter
    }

    test("extra pressed modifiers do not fire a smaller combo") {
        val both = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
        ComboKeys.matchIn(listOf(ctrlEnter), both, KeyEvent.KEYCODE_ENTER).shouldBeNull()
    }

    test("superset combo fires when all its modifiers are pressed") {
        val both = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
        ComboKeys.matchIn(listOf(ctrlAltDel), both, KeyEvent.KEYCODE_FORWARD_DEL) shouldBe ctrlAltDel
        ComboKeys.matchIn(listOf(ctrlAltDel), KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_FORWARD_DEL).shouldBeNull()
    }

    test("base key must match") {
        ComboKeys.matchIn(listOf(ctrlEnter), KeyEvent.META_CTRL_ON, KeyEvent.KEYCODE_SPACE).shouldBeNull()
    }

    test("left and right modifiers both satisfy the generic combo") {
        val left = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON
        ComboKeys.matchIn(listOf(ctrlEnter), left, KeyEvent.KEYCODE_ENTER) shouldBe ctrlEnter
    }

    test("describe joins modifier labels with the base key") {
        ComboKeys.describe(ctrlEnter) shouldBe "Ctrl+Enter"
        ComboKeys.describe(ctrlAltDel) shouldBe "Ctrl+Alt+Delete"
    }
})
