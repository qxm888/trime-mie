/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.osfans.trime.ime.appearance.PanelBackdrop

class DynamicGlassThemeTest : FunSpec({
    val base = "custom_liquid_glass"
    val live = "custom_liquid_glass_live"
    val pages by lazy { CustomKeyboardYaml.decode(File("src/main/res/raw/mie_builtin_glass.yaml").readText()) }
    test("retained and dynamic glass are two separate complete families") {
        pages.size shouldBe 30
        KeyboardSets.family(base, pages).size shouldBe 15
        KeyboardSets.family(live, pages).size shouldBe 15
        (KeyboardSets.family(base, pages).toSet() intersect KeyboardSets.family(live, pages).toSet()).isEmpty() shouldBe true
        KeyboardSets.family(base, pages).forEach { id ->
            val original=pages.getValue(id); val dynamic=pages.getValue(id.replace(base, live))
            dynamic.rows.map { it.keys.map { k -> k.width } } shouldBe original.rows.map { it.keys.map { k -> k.width } }
            dynamic.rows.flatMap { it.keys }.map { k -> k.bindings.mapValues { it.value.replace(live, base) } } shouldBe original.rows.flatMap { it.keys }.map { it.bindings }
            original.surfaceAppearance!!.backdrop.publicWindow shouldBe false
            dynamic.surfaceAppearance!!.backdrop.publicWindow shouldBe true
            dynamic.surfaceAppearance!!.backdrop.resolvedImageOpacity(true) shouldBe 0
            dynamic.surfaceAppearance!!.backdrop.resolvedImageOpacity(false) shouldBe 100
            dynamic.keyAppearance shouldBe original.keyAppearance
            dynamic.floatingCandidates!!.panelEffects shouldBe original.floatingCandidates!!.panelEffects
        }
    }
    test("public window selection is opt in and survives a saved theme round trip") {
        Json.decodeFromString<PanelBackdrop>("""{"enabled":true}""").publicWindow shouldBe false
        val cfg=PanelBackdrop(enabled=true, publicWindow=true)
        Json.decodeFromString<PanelBackdrop>(Json.encodeToString(cfg)) shouldBe cfg
        val restored=CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(pages, emptyList()))
        pages.forEach { (id, kb) -> restored.getValue(id).surfaceAppearance shouldBe kb.surfaceAppearance }
    }
})
