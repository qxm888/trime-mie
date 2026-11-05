/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import com.osfans.trime.ime.appearance.SurfaceStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.serialization.json.*

class KeyboardSetsTest : FunSpec({
    fun obj(text: String) = KeyboardSets.json.parseToJsonElement(text).jsonObject
    test("nested page font size follows future sibling font changes") {
        val root = CustomKeyboard(setConfig = KeyboardSetConfig(defaults = obj("""{"typography":{"key":{"font":"A.ttf","size":20}}}""")))
        val child = CustomKeyboard(secondaryOf = "r", setOverrides = obj("""{"typography":{"key":{"size":30}}}"""))
        val pages = mapOf("r" to root, "c" to child)
        KeyboardSets.resolve("c", pages, emptyMap())!!.typography["key"] shouldBe FontSpec("A.ttf",30f)
        root.setConfig = root.setConfig!!.copy(defaults = obj("""{"typography":{"key":{"font":"B.ttf","size":24}}}"""))
        KeyboardSets.resolve("c", pages, emptyMap())!!.typography["key"] shouldBe FontSpec("B.ttf",30f)
    }
    test("empty nested maps toolbar false and explicit transparent survive roundtrip") {
        val before = obj("""{"typography":{"key":{"font":"A"}},"toolbar":[{"action":"paste"}],"unifiedBackground":true}""")
        val after = obj("""{"typography":{},"toolbar":[],"unifiedBackground":false}""")
        val patch = obj(KeyboardSets.difference(before,after).toString())
        KeyboardSets.overlay(before,patch) shouldBe after
        val root = CustomKeyboard(setConfig=KeyboardSetConfig(defaults=before), setOverrides=patch)
        val result=KeyboardSets.resolve("r",mapOf("r" to root),emptyMap())!!
        result.typography shouldBe emptyMap(); result.toolbar shouldBe emptyList(); result.unifiedBackground shouldBe false
    }
    test("legacy pages keep exact values before consolidation") {
        val root=CustomKeyboard(setConfig=KeyboardSetConfig(defaults=obj("""{"typography":{"key":{"font":"new"}}}""")))
        val child=CustomKeyboard(secondaryOf="r",typography=mapOf("key" to FontSpec("old",17f)))
        KeyboardSets.resolve("c",mapOf("r" to root,"c" to child),emptyMap()) shouldBe child
    }
    test("shared profile changes only referenced group and honors independent size") {
        val profile=KeyboardSharedProfile("p","字体","fonts",obj("""{"typography":{"key":{"font":"shared","size":21}}}"""))
        val root=CustomKeyboard(setConfig=KeyboardSetConfig(defaults=obj("""{"toolbar":[]}"""),profiles=mapOf("fonts" to "p")),setOverrides=obj("""{"typography":{"key":{"size":27}}}"""))
        val result=KeyboardSets.resolve("r",mapOf("r" to root),mapOf("p" to profile))!!
        result.typography["key"] shouldBe FontSpec("shared",27f); result.toolbar shouldBe emptyList()
    }
    test("shared binding removal restores base after unrelated edits and respects explicit unbind") {
        val key=CustomKey("C",keyId="c",identity="char:c",bindings=mutableMapOf("click" to "c"),setBaseBindings=mapOf("click" to "c"),setBindingOverrides=emptySet())
        val defaults=KeyboardSets.values(CustomKeyboard(setBindings=mapOf("char:c" to mapOf("longClick" to "copy"))))
        val raw=CustomKeyboard(rows=mutableListOf(CustomRow(mutableListOf(key))),setConfig=KeyboardSetConfig(defaults=defaults),setOverrides=obj("{}"))
        val before=KeyboardSets.resolve("r",mapOf("r" to raw),emptyMap())!!
        before.rows[0].keys[0].bindings["longClick"] shouldBe "copy"
        val changed=KeyboardSets.captureMutation(raw,before,before.copy(rowSpacing=9))
        changed.setConfig=changed.setConfig!!.copy(defaults=KeyboardSets.values(CustomKeyboard()))
        KeyboardSets.resolve("r",mapOf("r" to changed),emptyMap())!!.rows[0].keys[0].bindings.containsKey("longClick") shouldBe false
        val pinned=key.copy(bindings=mutableMapOf("click" to "c","longClick" to ""),setBindingOverrides=setOf("longClick"))
        raw.rows[0].keys[0]=pinned
        KeyboardSets.resolve("r",mapOf("r" to raw),emptyMap())!!.rows[0].keys[0].bindings["longClick"] shouldBe ""
    }
    test("single key role background and symbol overrides remain independent") {
        val key=CustomKey("a",setRole="normal",setStyleOverrides=obj("""{"labelSymbol":"@","keyBackColor":"#0000FF"}"""))
        val kb=CustomKeyboard(rows=mutableListOf(CustomRow(mutableListOf(key))),setOverrides=obj("{}"),setConfig=KeyboardSetConfig(defaults=KeyboardSets.values(CustomKeyboard(setKeyStyles=mapOf("normal" to KeyAppearance.Style(keyBackColor="#FFFFFF",keyTextColor="#112233"))))))
        val resolved=KeyboardSets.resolve("r",mapOf("r" to kb),emptyMap())!!.rows[0].keys[0]
        resolved.appearance.labelSymbol shouldBe "@"; resolved.appearance.keyBackColor shouldBe "#0000FF"
        resolved.appearance.keyTextColor shouldBe "#112233"; resolved.resolvedBackground().color shouldBe "#0000FF"
    }
    test("nine key groups and ordinary letters have different identities") {
        KeyboardSets.identity(CustomKey("ABC",bindings=mutableMapOf("click" to "a"))) shouldBe "group:abc"
        KeyboardSets.identity(CustomKey("A",bindings=mutableMapOf("click" to "a"))) shouldBe "char:a"
    }
    test("root traversal rejects cycles and includes nested pages once") {
        val pages=mapOf("a" to CustomKeyboard(),"b" to CustomKeyboard(secondaryOf="a"),"c" to CustomKeyboard(secondaryOf="b"))
        KeyboardSets.family("a",pages) shouldBe listOf("a","b","c");KeyboardSets.rootOf("c",pages) shouldBe "a"
        shouldThrow<IllegalStateException> { KeyboardSets.rootOf("a",mapOf("a" to CustomKeyboard(secondaryOf="b"),"b" to CustomKeyboard(secondaryOf="a"))) }
    }
    test("set push only clears the fields it changed, page keeps the rest") {
        val page = obj("""{"keyAppearance":{"roundCorner":6,"keyBackColor":"#FF0000"}}""")
        val patch = obj("""{"keyAppearance":{"roundCorner":20.0}}""")
        KeyboardSets.prune(page, patch).toString() shouldBe """{"keyAppearance":{"keyBackColor":"#FF0000"}}"""
    }
    test("corner radius and border width are pushed into both stores") {
        // 一条参数两处存储：按键默认外观的 roundCorner/keyBorder 与全局外观的 radius/borderWidth。
        val shape = mapOf("radius" to 20f, "borderWidth" to 2f)
        val page = obj("""{"keyAppearance":{"roundCorner":6,"keyBorder":1,"keyBackColor":"#FF0000"},"surfaceAppearance":{"keys":{"radius":11,"background":"#22FFFFFF"}}}""")
        KeyboardSets.prune(page, KeyboardSets.shapeProbe(shape)).toString() shouldBe
            """{"keyAppearance":{"keyBackColor":"#FF0000"},"surfaceAppearance":{"keys":{"background":"#22FFFFFF"}}}"""

        val defaults = KeyboardSets.overlay(KeyboardSets.values(CustomKeyboard()),
            obj("""{"surfaceAppearance":{"keys":{"background":"#22FFFFFF"}}}"""))
        val synced = KeyboardSets.json.decodeFromJsonElement(CustomKeyboard.serializer(), KeyboardSets.syncShape(defaults, shape))
        synced.keyAppearance.roundCorner shouldBe 20f
        synced.keyAppearance.keyBorder shouldBe 2
        synced.surfaceAppearance!!.keys.radius shouldBe 20f
        synced.surfaceAppearance!!.keys.borderWidth shouldBe 2f
        synced.surfaceAppearance!!.keys.background shouldBe "#22FFFFFF"
        // 回到继承要清掉表面层那份，否则它永远压住按键默认外观。
        KeyboardSets.syncShape(defaults, mapOf("radius" to null))
            .getValue("surfaceAppearance").jsonObject.getValue("keys").jsonObject.containsKey("radius") shouldBe false
        // 这一层本来没有表面外观就不写第二份，免得把该层的按键背景等设置挡掉。
        KeyboardSets.syncShape(KeyboardSets.values(CustomKeyboard()), shape).getValue("surfaceAppearance") shouldBe JsonNull
    }
    test("page stopped overriding the field after the set pushed it") {
        val root = CustomKeyboard(setConfig = KeyboardSetConfig(defaults = KeyboardSets.values(CustomKeyboard())),
            setOverrides = obj("{}"))
        val page = CustomKeyboard(secondaryOf = "r", setOverrides = obj("""{"keyAppearance":{"roundCorner":6}}"""))
        val patch = obj("""{"keyAppearance":{"roundCorner":20.0}}""")
        val shape = mapOf<String, Float?>("radius" to 20f)
        root.setConfig = root.setConfig!!.copy(defaults = KeyboardSets.syncShape(KeyboardSets.overlay(root.setConfig!!.defaults, patch), shape))
        page.setOverrides = KeyboardSets.prune(page.setOverrides!!, KeyboardSets.overlay(patch, KeyboardSets.shapeProbe(shape)))
        page.setOverrides!!.containsKey("keyAppearance") shouldBe false
        KeyboardSets.resolve("p", mapOf("r" to root, "p" to page), emptyMap())!!.keyAppearance.roundCorner shouldBe 20f
    }
    test("page-level key appearance writes the radius where the keys are drawn") {
        val kb = CustomKeyboard(surfaceAppearance = SurfaceAppearanceConfig(keys = SurfaceStyle(radius = 11f, background = "#22FFFFFF"), blurRadius = 20),
            keyAppearance = KeyAppearance.Style(roundCorner = 6f))
        val shaped = KeyboardSetOperations.shapeKeys(kb, 8f, 2)
        shaped.keyAppearance.roundCorner shouldBe 8f; shaped.keyAppearance.keyBorder shouldBe 2
        shaped.surfaceAppearance!!.keys.radius shouldBe 8f
        shaped.surfaceAppearance!!.keys.background shouldBe "#22FFFFFF"; shaped.surfaceAppearance!!.blurRadius shouldBe 20
        KeyboardSetOperations.shapeKeys(kb, -1f, -1).surfaceAppearance!!.keys.radius shouldBe null
        // 页面没有表面外观时只写按键默认外观，硬造一份空配置会把它的按键背景冲掉。
        KeyboardSetOperations.shapeKeys(CustomKeyboard(keyAppearance = KeyAppearance.Style(roundCorner = 6f)), 8f, 2)
            .surfaceAppearance shouldBe null
    }
    test("page push clears the touched field on every key and keeps each key's own content") {
        fun key(index: Int) = CustomKey(label = "k$index", bindings = mutableMapOf("click" to "a"),
            appearance = KeyAppearance.Style(roundCorner = 11f, popup = listOf("à"), icon = "a.png", labelSymbol = "à"),
            setStyleOverrides = obj("""{"roundCorner":11.0,"keyTextColor":"#FF0000"}"""))
        val kb = CustomKeyboard(rows = mutableListOf(CustomRow(keys = mutableListOf(key(0), key(1)))))
        val pushed = KeyboardSetOperations.pushAppearance(kb, setOf("roundCorner"))
        pushed.rows[0].keys.forEach {
            it.appearance.roundCorner shouldBe -1f                       // 回到本页统一值
            it.appearance.popup shouldBe listOf("à")                      // 内容不动
            it.appearance.icon shouldBe "a.png"
            it.appearance.labelSymbol shouldBe "à"
            it.setStyleOverrides!!.containsKey("roundCorner") shouldBe false
            it.setStyleOverrides!!["keyTextColor"]!!.jsonPrimitive.content shouldBe "#FF0000"
        }
        // 符号文字、弹出备选属于每个键自己的内容，批量推外观时不能被刷成一样。
        KeyboardSetOperations.pushAppearance(kb, listOf("labelSymbol", "popup")).rows[0].keys
            .forEach { it.appearance.labelSymbol shouldBe "à" }
        // 差量表被清空时内容字段要固化下来，否则角色样式重建会把这个键的弹出备选和图标丢掉。
        val emptied = KeyboardSetOperations.pushAppearance(kb, setOf("roundCorner", "keyTextColor"))
        emptied.rows[0].keys.forEach {
            it.setStyleOverrides!!.containsKey("roundCorner") shouldBe false
            it.setStyleOverrides!!.containsKey("keyTextColor") shouldBe false
            it.setStyleOverrides!!["popup"]!!.jsonArray.map { cell -> cell.jsonPrimitive.content } shouldBe listOf("à")
            it.setStyleOverrides!!["icon"]!!.jsonPrimitive.content shouldBe "a.png"
        }
    }
    test("only a key's own corner beats the surface layer") {
        val page = KeyAppearance.Style(roundCorner = 6f)
        val explicit = CustomKey(appearance = KeyAppearance.Style(roundCorner = 20f),
            setStyleOverrides = obj("""{"roundCorner":20.0}"""))
        val inherited = CustomKey(appearance = KeyAppearance.Style(roundCorner = 20f),
            setStyleOverrides = obj("""{"keyTextColor":"#FF0000"}"""))
        with(CustomKeyboards) {
            explicit.toTextKey(CustomBackground(), page).ownRoundCorner shouldBe true
            inherited.toTextKey(CustomBackground(), page).ownRoundCorner shouldBe false
            // 页面统一值作为键盘级默认，键没自己设时由 Key 回落到它。
            CustomKeyboard(keyAppearance = page).toTextKeyboard().roundCorner shouldBe 6f
        }
    }
    test("set-level appearance panel reaches every page of the skin") {
        // 整套里用同一个「外观」面板改一项，这套皮肤里每一页都应读到同一个值，
        // 包括自己另存过表面外观的页——整套是统一面板，不是又一层覆盖。
        val setSurface = SurfaceAppearanceConfig(
            keyboardCorners = listOf(18f, 18f, 0f, 0f),
            keys = SurfaceStyle(radius = 18f, background = "#112233"))
        val root = CustomKeyboard(name = "杏子橙键盘", setOverrides = obj("{}"), setConfig = KeyboardSetConfig(
            defaults = JsonObject(mapOf("surfaceAppearance" to KeyboardSets.json.encodeToJsonElement(SurfaceAppearanceConfig.serializer(), setSurface)))))
        val own = CustomKeyboard(name = "英文", secondaryOf = "r", setPageRole = "en",
            surfaceAppearance = SurfaceAppearanceConfig(keys = SurfaceStyle(radius = 4f)), setOverrides = obj("{}"))
        val other = CustomKeyboard(name = "数字", secondaryOf = "r", setPageRole = "zh_14", setOverrides = obj("{}"))
        val pages = mapOf("r" to root, "r_en" to own, "r_14" to other)
        listOf("r", "r_en", "r_14").forEach { id ->
            val resolved = KeyboardSets.resolve(id, pages, emptyMap())!!
            resolved.surfaceAppearance!!.keys.radius shouldBe 18f
            resolved.surfaceAppearance!!.keys.background shouldBe "#112233"
            // 四角是同一份整体外观里的字段，也必须一起下发，否则面板改了等于没改。
            resolved.surfaceAppearance!!.keyboardCorners shouldBe listOf(18f, 18f, 0f, 0f)
        }
    }
})
