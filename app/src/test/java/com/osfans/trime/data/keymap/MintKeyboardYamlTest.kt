/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** GPT 薄荷绿设计五色键盘 YAML：解码格式验证。 */
class MintKeyboardYamlTest : FunSpec({
    val yml = object {}.javaClass.classLoader.getResource("mint/薄荷绿键盘-五色方案.yaml")?.readText()
        ?: error("test resource missing")

    test("decodes five keyboards with icon appearance") {
        val layouts = CustomKeyboardYaml.decode(yml)
        layouts.size shouldBe 5
        val mint = layouts.getValue("custom_mint")
        mint.name shouldBe "薄荷绿键盘"
        mint.rows.size shouldBe 4
        mint.rows[0].keys.size shouldBe 10
        mint.rows[2].keys.size shouldBe 9
        mint.rows[3].keys.size shouldBe 5
        // 前景图标与底色
        val shift = mint.rows[2].keys.first()
        shift.appearance.icon shouldBe "icons/mint_shift.png"
        shift.appearance.iconSize shouldBe 28
        shift.background!!.type shouldBe com.osfans.trime.data.keymap.CustomBackground.Type.COLOR
        shift.bindings["click"] shouldBe "Shift_L"
        val enter = mint.rows[3].keys.last()
        enter.appearance.icon shouldBe "icons/enter_w.png"
        enter.background!!.color shouldBe "#72C99B"
        // 工具栏
        mint.toolbar.size shouldBe 5
        mint.toolbar[0].action shouldBe "menu_keyboard"
        mint.toolbar[4].action shouldBe "__mie_toggle_floating_keyboard"
        mint.toolbar[0].icon!!.endsWith("tb_switch.png") shouldBe true
        // 键盘级外观默认
        mint.keyAppearance.keyBackColor shouldBe "#FFFFFF"
        mint.keyAppearance.roundCorner shouldBe 14f
        // 其他方案
        layouts.getValue("custom_purple").rows[3].keys.last().background!!.color shouldBe "#C9A6ED"
    }
})
