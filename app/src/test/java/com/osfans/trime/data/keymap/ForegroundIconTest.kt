/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** 前景图标字段：文本往返、合并继承、YAML 名称与导出数值。 */
class ForegroundIconTest : FunSpec({
    test("icon fields survive text round trip") {
        val style = KeyAppearance.Style(icon = "icons/shift.png", iconSize = 28, iconOffsetX = 1f, iconOffsetY = -2f)
        val values = KeyAppearance.textValues(style)
        values["icon"] shouldBe "icons/shift.png"
        values["iconSize"] shouldBe "28"
        values["iconOffsetX"] shouldBe "1.0"
        values["iconOffsetY"] shouldBe "-2.0"

        val parsed = KeyAppearance.parse(values)
        parsed.icon shouldBe "icons/shift.png"
        parsed.iconSize shouldBe 28
        parsed.iconOffsetX shouldBe 1f
        parsed.iconOffsetY shouldBe -2f
    }

    test("blank icon inherits keyboard default; set icon overrides") {
        val keyboardDefault = KeyAppearance.Style(icon = "icons/default.png")
        val keyOverride = KeyAppearance.Style(icon = "icons/key.png")
        val merged = KeyAppearance.merge(keyboardDefault, keyOverride)
        merged.icon shouldBe "icons/key.png"

        val inherited = KeyAppearance.merge(keyboardDefault, KeyAppearance.Style(iconSize = 24))
        inherited.icon shouldBe "icons/default.png"
        inherited.iconSize shouldBe 24
    }

    test("toTextKey carries icon configuration") {
        val textKey = KeyAppearance.Style(icon = "icons/a.png", iconSize = 28, iconOffsetX = 2f, iconOffsetY = 3f).toTextKey()
        textKey.icon shouldBe "icons/a.png"
        textKey.iconSize shouldBe 28
        textKey.iconOffsetX shouldBe 2f
        textKey.iconOffsetY shouldBe 3f
    }

    test("yaml names stay snake_case") {
        KeyAppearance.yamlName("icon") shouldBe "icon"
        KeyAppearance.yamlName("iconSize") shouldBe "icon_size"
        KeyAppearance.yamlName("iconOffsetX") shouldBe "icon_offset_x"
        KeyAppearance.yamlName("iconOffsetY") shouldBe "icon_offset_y"
    }
})
