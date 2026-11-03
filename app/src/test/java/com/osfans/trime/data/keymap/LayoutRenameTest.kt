/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LayoutRenameTest : FunSpec({
    fun pages()=mapOf("root" to CustomKeyboards.CustomKeyboard(name="我的整套",setPageRole="zh_26"),
        "root_14" to CustomKeyboards.CustomKeyboard(name="我的整套 · 14 键",secondaryOf="root",setPageRole="zh_14"))
    test("explicit root layout rename wins over its fixed role title") {
        val p=pages().toMutableMap();p["root"]=p.getValue("root").copy(name="主布局甲",layoutDisplayName="主布局甲")
        KeyboardSetLayoutChoices.items("root",p,p.keys.toList()).first().label shouldBe "主布局甲"
    }
    test("explicit secondary rename is shown without changing navigation role") {
        val p=pages().toMutableMap();p["root_14"]=p.getValue("root_14").copy(name="十四键双拼",layoutDisplayName="十四键双拼")
        KeyboardSetLayoutChoices.items("root",p,p.keys.toList()).last().label shouldBe "十四键双拼"
        KeyboardSets.pageRole("root_14","root",p.getValue("root_14")) shouldBe "zh_14"
    }
    test("old renamed secondary survives without the new display marker") {
        val p=pages().toMutableMap();p["root_14"]=p.getValue("root_14").copy(name="自定义名称")
        KeyboardSetLayoutChoices.items("root",p,p.keys.toList()).last().label shouldBe "自定义名称"
    }
    test("YAML roundtrip retains explicit selector name") {
        val kb=pages().getValue("root").copy(layoutDisplayName="自定主键盘",rows=mutableListOf(CustomKeyboards.CustomRow(keys=mutableListOf(CustomKeyboards.CustomKey(label="A",bindings=mutableMapOf("click" to "a"))))))
        CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(mapOf("custom_root" to kb),emptyList())).getValue("custom_root").layoutDisplayName shouldBe "自定主键盘"
    }
})
