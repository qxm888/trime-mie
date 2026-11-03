/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.model.PresetKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.io.File

class Builtin324DefaultsTest : FunSpec({
    test("all non-seven stock pages default to 251 and 8 while seven pages keep their geometry") {
        val pages=listOf("mie_builtin_keyboards","mie_builtin_extra","mie_builtin_effects").flatMap {
            CustomKeyboardYaml.decode(File("src/main/res/raw/$it.yaml").readText()).entries
        }.associate { it.toPair() }
        pages.size shouldBe 210
        pages.forEach { (id,page) ->
            if(id.endsWith("_7_1") || id.endsWith("_7_2")) {
                (page.keyboardHeightDp != 251) shouldBe true
                (page.rowSpacing != 8) shouldBe true
            } else { page.keyboardHeightDp shouldBe 251; page.rowSpacing shouldBe 8 }
        }
    }
    test("date retirement removes only the R double tap and its inherited baseline") {
        val date=BindingActions.token(PresetKey(command="date",option="YYYY-MM-DD",send="FUNCTION"))
        val original=CustomKeyboards.CustomKey(label="R",bindings=mutableMapOf("click" to "r","doubleClick" to date,"longClick" to "4"),
            setBaseBindings=mapOf("click" to "r","doubleClick" to date),primaryBaseBindings=mapOf("doubleClick" to date),setBindingOverrides=emptySet())
        val changed=BuiltinRDoubleTapDefaults.upgrade(original)
        changed.bindings shouldBe mapOf("click" to "r","longClick" to "4")
        changed.setBaseBindings shouldBe mapOf("click" to "r")
        changed.primaryBaseBindings shouldBe emptyMap()
        changed.setBindingOverrides shouldBe setOf("doubleClick")
        val edited=original.copy(bindings=mutableMapOf("click" to "r","doubleClick" to "copy"),setBaseBindings=null,primaryBaseBindings=null)
        BuiltinRDoubleTapDefaults.upgrade(edited).bindings shouldBe edited.bindings
        val other=original.copy(bindings=mutableMapOf("click" to "t","doubleClick" to date))
        BuiltinRDoubleTapDefaults.upgrade(other) shouldBe other
    }
    test("251 and 8 upgrade only previous stock defaults and preserve seven and custom dimensions") {
        val stock=CustomKeyboards.CustomKeyboard(keyboardHeightDp=251,rowSpacing=8)
        val before=CustomKeyboards.CustomKeyboard(keyboardHeightDp=260,rowSpacing=10)
        val changed=CustomKeyboards.upgradeSymbols(before,stock,emptySet())
        changed.keyboardHeightDp shouldBe 251; changed.rowSpacing shouldBe 8
        val custom=CustomKeyboards.upgradeSymbols(before.copy(keyboardHeightDp=280,rowSpacing=12),stock,emptySet())
        custom.keyboardHeightDp shouldBe 280; custom.rowSpacing shouldBe 12
        val seven=CustomKeyboards.upgradeSymbols(before,stock.copy(keyboardHeightDp=0,rowSpacing=5),emptySet())
        seven.keyboardHeightDp shouldBe 260; seven.rowSpacing shouldBe 10
    }
    test("built-in binding seed has no R date double tap and retired set rule is cleared") {
        val seed=KeyboardSets.json.parseToJsonElement(File("src/main/res/raw/mie_builtin_bindings.json").readText())
        fun find(value: JsonElement): List<JsonObject> = when(value) {
            is JsonObject -> listOf(value)+value.values.flatMap(::find)
            is JsonArray -> value.flatMap(::find)
            else -> emptyList()
        }
        val rs=find(seed).mapNotNull { it["bindings"] as? JsonObject }.filter { it["click"]==JsonPrimitive("r") }
        rs.isNotEmpty() shouldBe true
        rs.forEach { it.containsKey("doubleClick") shouldBe false }
        val date=BindingActions.token(PresetKey(command="date",send="FUNCTION"))
        val rules=buildJsonObject { put("setBindings",buildJsonObject { put("char:r",buildJsonObject { put("doubleClick",date);put("longClick","4") }) }) }
        BuiltinRDoubleTapDefaults.settings(rules)["setBindings"]!!.jsonObject["char:r"]!!.jsonObject shouldBe buildJsonObject { put("longClick","4") }
    }
})
