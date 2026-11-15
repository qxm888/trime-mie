/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.zip.ZipFile

class LiquidGlassThemeTest : FunSpec({
    val raw = File("src/main/res/raw")
    val root = "custom_liquid_glass"
    val suffixes = listOf("", "_9", "_14", "_18", "_7_1", "_7_2", "_en", "_num", "_symbols", "_symbols_cn", "_symbols_math", "_symbols_more", "_themes", "_layouts", "_hub")
    val pages by lazy { CustomKeyboardYaml.decode(File(raw, "mie_builtin_glass.yaml").readText()).filter { (id, kb) -> id == root || kb.secondaryOf == root } }
    test("all fifteen pages retain navigation geometry language and dynamic picker") {
        val previous = CustomKeyboardYaml.decode(File(raw, "mie_builtin_effects.yaml").readText())
        pages.keys shouldBe suffixes.map { root + it }.toSet()
        suffixes.forEach { suffix ->
            val kb = pages.getValue(root + suffix)
            val old = previous.getValue("custom_cloudsea$suffix")
            kb.secondaryOf shouldBe if (suffix.isEmpty()) "" else root
            kb.rows.map { it.keys.map { key -> key.width } } shouldBe old.rows.map { it.keys.map { key -> key.width } }
            kb.rows.map { Triple(it.height, it.margin, it.alignment) } shouldBe old.rows.map { Triple(it.height, it.margin, it.alignment) }
            kb.keyboardHeightDp shouldBe old.keyboardHeightDp
            kb.rowSpacing shouldBe old.rowSpacing
            kb.toolbar.any { it.action == "Mode_switch" } shouldBe true
            kb.toolbar.any { it.action == "__mie_theme_picker" } shouldBe true
            kb.rows.flatMap { it.keys }.flatMap { it.bindings.values }.filter { it.startsWith("Keyboard_") }.forEach {
                (it == "Keyboard_default" || it.removePrefix("Keyboard_") in pages) shouldBe true
            }
        }
    }
    test("native translucent caps use shared panel frost with a readable fallback") {
        pages.values.forEach { kb ->
            val cfg = kb.surfaceAppearance!!
            cfg.keyBlur shouldBe 0
            cfg.blurFrameRate shouldBe 10
            cfg.backdrop.enabled shouldBe true
            cfg.backdrop.systemBlur shouldBe true
            cfg.backdrop.resolvedImageOpacity(true) shouldBe 8
            cfg.backdrop.resolvedImageOpacity(false) shouldBe 100
            kb.floatingCandidates!!.panel.blur shouldBe true
            kb.floatingCandidates!!.panelEffects.borderGradient.enabled shouldBe true
            cfg.effects.borderGradient.enabled shouldBe true
            cfg.effects.gloss shouldBe true
            kb.rows.flatMap { it.keys }.filter { it.bindings["click"].orEmpty().isNotBlank() }.forEach { key ->
                key.resolvedBackground().type shouldBe CustomBackground.Type.COLOR
                (key.resolvedBackground().color.length == 9) shouldBe true
                val alpha = key.resolvedBackground().color.substring(1, 3).toInt(16)
                (alpha in 48..199) shouldBe true
            }
        }
    }
    test("every background and semantic icon ships and label contrast stays readable") {
        val materials = ZipFile(File(raw, "mie_premium_materials.zip")).use { it.entries().asSequence().map { e -> e.name }.toSet() }
        val icons = ZipFile(File(raw, "mie_builtin_icons.zip")).use { it.entries().asSequence().map { e -> "icons/" + e.name }.toSet() }
        fun luminance(hex: String): Double {
            val value = hex.takeLast(6).toLong(16)
            fun c(n: Int): Double {
                val v = ((value shr n) and 255).toDouble() / 255
                return if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
            }
            return .2126*c(16) + .7152*c(8) + .0722*c(0)
        }
        pages.values.forEach { kb ->
            (kb.resolvedBackground().image in materials) shouldBe true
            (kb.toolbar.map { it.icon } + kb.rows.flatMap { it.keys }.map { it.appearance.icon }).filter(String::isNotBlank).forEach { (it in icons) shouldBe true }
            val floating = kb.floatingCandidates!!
            listOf("text", "candidate", "comment", "highlight.text", "highlight.candidate").forEach { role ->
                val bg = luminance(if (role == "highlight.candidate") floating.highlighted.background else floating.panel.background)
                val fg = luminance(floating.colors.getValue(role))
                ((maxOf(bg, fg)+.05)/(minOf(bg, fg)+.05) >= 4.5) shouldBe true
            }
        }
    }
    test("runtime gesture overlay and exported family preserve glass settings") {
        val defaults = BuiltinBindingDefaults(File(raw, "mie_builtin_bindings.json").readText())
        pages.forEach { (id, kb) ->
            val after = defaults.template(id, kb)
            after.surfaceAppearance shouldBe kb.surfaceAppearance
            after.resolvedBackground() shouldBe kb.resolvedBackground()
            after.rows.flatMap { it.keys }.map { it.resolvedBackground() } shouldBe kb.rows.flatMap { it.keys }.map { it.resolvedBackground() }
        }
        val restored = CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(pages, emptyList()))
        restored.keys shouldBe pages.keys
        pages.forEach { (id, kb) ->
            restored.getValue(id).surfaceAppearance shouldBe kb.surfaceAppearance
            restored.getValue(id).toolbar shouldBe kb.toolbar
            restored.getValue(id).rows.flatMap { it.keys }.map { it.bindings } shouldBe kb.rows.flatMap { it.keys }.map { it.bindings }
        }
    }
})
