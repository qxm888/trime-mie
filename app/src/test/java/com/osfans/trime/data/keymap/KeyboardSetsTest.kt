/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
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
})
