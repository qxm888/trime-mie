/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class PremiumBuiltinThemesTest : FunSpec({
    val roots = listOf("silver", "titanium", "cloudsea", "inkweave", "celadon").map { "custom_$it" }
    val raw = File("src/main/res/raw")
    val pages by lazy { CustomKeyboardYaml.decode(File(raw, "mie_builtin_effects.yaml").readText()) }
    val suffixes = listOf("", "_9", "_14", "_18", "_7_1", "_7_2", "_en", "_num", "_symbols", "_symbols_cn", "_symbols_math", "_symbols_more", "_themes", "_layouts", "_hub")
    val manifest by lazy { File(raw, "mie_premium_materials_manifest.txt").readLines().filter { it.isNotBlank() } }
    test("five complete stock families have consistent owners local targets and preserved geometry") {
        val old = listOf("mie_builtin_keyboards", "mie_builtin_extra").flatMap { CustomKeyboardYaml.decode(File(raw, "$it.yaml").readText()).entries }.associate { it.toPair() }
        roots.forEach { root ->
            suffixes.forEach { suffix ->
                val id = root + suffix; val kb = pages.getValue(id)
                kb.secondaryOf shouldBe if (suffix.isEmpty()) "" else root
                if (suffix != "_themes") {
                    kb.rows.map { it.keys.map { key -> key.width } } shouldBe old.getValue("custom_pink$suffix").rows.map { it.keys.map { key -> key.width } }
                    kb.keyboardHeightDp shouldBe old.getValue("custom_pink$suffix").keyboardHeightDp
                    kb.rowSpacing shouldBe old.getValue("custom_pink$suffix").rowSpacing
                    kb.rowMargin shouldBe old.getValue("custom_pink$suffix").rowMargin
                    kb.rows.map { Triple(it.height, it.margin, it.alignment) } shouldBe old.getValue("custom_pink$suffix").rows.map { Triple(it.height, it.margin, it.alignment) }
                }
                kb.rows.flatMap { it.keys }.flatMap { it.bindings.values }.filter { it.startsWith("Keyboard_") }.forEach { token ->
                    val target = token.removePrefix("Keyboard_")
                    (target == "default" || target in suffixes.map { root + it }) shouldBe true
                }
            }
            suffixes.count { root + it in pages } shouldBe 15
        }
    }
    test("standard 26 key pages center the shorter second row and use stock height and spacing") {
        roots.forEach { root -> listOf("", "_en").forEach { suffix ->
            val kb = pages.getValue(root+suffix)
            kb.keyboardHeightDp shouldBe 251
            kb.rowSpacing shouldBe 8
            kb.rows[1].keys.size shouldBe 9
            kb.rows[1].alignment shouldBe "center"
            kb.rows[1].keys.sumOf { it.width.toDouble() } shouldBe 90.0
        } }
    }
    test("toolbar and theme helpers retain dynamic style picker and semantic language switch") {
        roots.forEach { root -> suffixes.forEach { suffix ->
            val kb = pages.getValue(root + suffix)
            kb.toolbar.first { it.icon.endsWith("tb_lang.png") }.action shouldBe "Mode_switch"
            kb.toolbar.first { it.icon.endsWith("tb_theme.png") }.action shouldBe "__mie_theme_picker"
            kb.surfaceAppearance!!.retainToolbarBackground shouldBe true
            kb.floatingCandidates!!.enabled shouldBe true
            kb.options.symbolAlignment shouldBe "right"
            kb.options.effectiveHintAlignment shouldBe "center"
            kb.typography shouldBe emptyMap()
        } }
    }
    test("all 108 PNGs exactly match manifest decode and fit the bounded installer") {
        manifest.size shouldBe 108
        manifest.toSet().size shouldBe manifest.size
        ZipFile(File(raw, "mie_premium_materials.zip")).use { zip ->
            val entries = zip.entries().asSequence().toList()
            entries.map { it.name }.toSet() shouldBe manifest.toSet()
            var total = 0L
            entries.forEach { entry ->
                entry.isDirectory shouldBe false
                entry.name.startsWith("内置材质/精雕一辑/") shouldBe true
                entry.name.split('/').none { it == ".." } shouldBe true
                val bytes = zip.getInputStream(entry).use { it.readBytes() };total += bytes.size
                (bytes.size < 4 * 1024 * 1024) shouldBe true
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                (image != null && image.width > 0 && image.height > 0 && image.width.toLong() * image.height < 4_194_304) shouldBe true
            }
            (total <= 32 * 1024 * 1024) shouldBe true
        }
    }
    test("preedit and selected preedit stay readable on each floating panel") {
        fun luminance(color: String): Double {
            val rgb = color.takeLast(6).toLong(16)
            fun channel(shift: Int): Double {
                val value = ((rgb shr shift) and 255).toDouble()/255
                return if(value <= 0.04045) value/12.92 else Math.pow((value+0.055)/1.055,2.4)
            }
            return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
        }
        roots.forEach { root -> suffixes.forEach { suffix ->
            val style = pages.getValue(root+suffix).floatingCandidates!!
            val background = luminance(style.panel.background)
            listOf("text", "highlight.text").forEach { role ->
                val foreground = luminance(style.colors.getValue(role))
                ((maxOf(background,foreground)+.05)/(minOf(background,foreground)+.05) >= 4.5) shouldBe true
            }
        } }
    }
    test("every material and icon reference resolves to stock artwork") {
        val oldIcons = ZipFile(File(raw,"mie_builtin_icons.zip")).use { it.entries().asSequence().map { e -> "icons/" + e.name }.toSet() }
        val available = manifest.toSet() + oldIcons
        roots.forEach { root -> suffixes.forEach { suffix ->
            val kb = pages.getValue(root + suffix)
            val files = listOfNotNull(kb.resolvedBackground().image, kb.toolbarBackground?.image) +
                kb.toolbar.map { it.icon } + kb.rows.flatMap { it.keys }.flatMap { listOf(it.resolvedBackground().image, it.appearance.keyBackColor, it.appearance.icon) } + kb.keyAppearance.keyBackColor
            files.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { path -> (path in available) shouldBe true }
        } }
    }
    test("read-only headings and symbol helpers never retain the source pink artwork or text colors") {
        val legacy = setOf("#FBF7F8", "#5E4B50", "#E3C2C8", "#F4AEB8")
        roots.forEach { root -> suffixes.forEach { suffix ->
            pages.getValue(root+suffix).rows.flatMap { it.keys }.filter { it.label.isNotBlank() || it.bindings.values.any(String::isNotBlank) || it.appearance.icon.isNotBlank() }.forEach { key ->
                listOf(key.appearance.keyBackColor, key.appearance.keyTextColor, key.appearance.keyBorderColor,
                    key.appearance.hilitedKeyTextColor).any { it in legacy } shouldBe false
            }
        } }
    }
    test("runtime binding profiles preserve material artwork and independently aligned annotations") {
        val defaults = BuiltinBindingDefaults(File(raw,"mie_builtin_bindings.json").readText())
        roots.forEach { root -> suffixes.take(8).forEach { suffix ->
            val before = pages.getValue(root + suffix)
            val after = defaults.template(root + suffix, before)
            after.rows.flatMap { it.keys }.map { it.resolvedBackground() } shouldBe before.rows.flatMap { it.keys }.map { it.resolvedBackground() }
            after.rows.flatMap { it.keys }.map { it.appearance.keyBackColor } shouldBe before.rows.flatMap { it.keys }.map { it.appearance.keyBackColor }
            after.options.symbolAlignment shouldBe "right"
            after.options.effectiveHintAlignment shouldBe "center"
        } }
    }
    test("premium family YAML export preserves all assets geometry and actions") {
        roots.forEach { root ->
            val source = pages.filterKeys { it == root || it.startsWith(root + "_") }
            val restored = CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(source, emptyList()))
            restored.keys shouldBe source.keys
            source.forEach { (id,kb) ->
                restored.getValue(id).resolvedBackground() shouldBe kb.resolvedBackground()
                restored.getValue(id).toolbar shouldBe kb.toolbar
                restored.getValue(id).rows.flatMap { it.keys }.map { it.bindings } shouldBe kb.rows.flatMap { it.keys }.map { it.bindings }
                restored.getValue(id).rows.flatMap { it.keys }.map { it.appearance.keyBackColor } shouldBe kb.rows.flatMap { it.keys }.map { it.appearance.keyBackColor }
            }
        }
    }
})
