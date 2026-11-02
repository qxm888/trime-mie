/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BuiltinSymbolMigrationTest : FunSpec({
    val target = "custom_pink_cat_math_p2"
    fun page() = CustomKeyboard(rows = mutableListOf(CustomRow(mutableListOf(
        CustomKey("数学", bindings = mutableMapOf("click" to "Keyboard_$target", "longClick" to "copy")),
    ))))
    test("retained user symbol page remains reachable and original data is untouched") {
        val original = page()
        val upgraded = CustomKeyboards.upgradeSymbols(original, CustomKeyboard(), setOf(target))
        upgraded.rows[0].keys[0].bindings shouldBe original.rows[0].keys[0].bindings
        original.rows[0].keys[0].bindings["click"] shouldBe "Keyboard_$target"
    }
    test("removed stock page redirects to matching catalog category") {
        val upgraded = CustomKeyboards.upgradeSymbols(page(), CustomKeyboard(), emptySet())
        val bindings = upgraded.rows[0].keys[0].bindings
        BindingActions.resolve(bindings.getValue("click"))?.command shouldBe "__mie_symbols"
        BindingActions.resolve(bindings.getValue("click"))?.option shouldBe "math"
        bindings["longClick"] shouldBe "copy"
    }
})
