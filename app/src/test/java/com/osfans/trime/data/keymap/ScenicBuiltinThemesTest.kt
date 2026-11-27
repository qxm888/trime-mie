/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class ScenicBuiltinThemesTest : FunSpec({
    val slugs = listOf("black_gold", "ink_lotus", "moon_sea", "cherry_creek", "koi_pond", "spring_steed", "crimson_wall")
    val roots = slugs.map { "custom_scenic_$it" }
    val suffixes = listOf("", "_9", "_14", "_18", "_num", "_en", "_symbols", "_symbols_cn",
        "_symbols_math", "_symbols_more", "_themes", "_layouts", "_7_1", "_7_2", "_hub")
    val raw = File("src/main/res/raw")
    val pages by lazy { CustomKeyboardYaml.decode(File(raw, "mie_builtin_scenic.yaml").readText()) }
    val manifest by lazy { File(raw, "mie_scenic_materials_manifest.txt").readLines().filter(String::isNotBlank) }

    test("seven scenic families each own fifteen complete pages") {
        pages.size shouldBe roots.size * suffixes.size
        roots.forEach { root ->
            val family = pages.getValue(root)
            family.secondaryOf shouldBe ""
            family.name shouldBe family.name.trim()
            suffixes.drop(1).forEach { suffix ->
                val page = pages.getValue(root + suffix)
                page.secondaryOf shouldBe root
                page.name shouldBe "${family.name} · ${page.name.substringAfter(" · ")}"
            }
        }
    }

    test("scenic ids stay separate from the existing stock families") {
        val existing = listOf("mie_builtin_keyboards", "mie_builtin_extra", "mie_builtin_effects", "mie_builtin_glass")
            .flatMap { CustomKeyboardYaml.decode(File(raw, "$it.yaml").readText()).keys }
        pages.keys.count { it in existing } shouldBe 0
    }

    test("every scenic material reference has a matching packed PNG") {
        val available = manifest.toSet()
        manifest.size shouldBe 256
        manifest.toSet().size shouldBe manifest.size
        pages.forEach { (id, kb) ->
            val files = listOfNotNull(kb.resolvedBackground().image, kb.toolbarBackground?.image) +
                kb.toolbar.map { it.icon } +
                kb.rows.flatMap { it.keys }.flatMap { listOf(it.resolvedBackground().image, it.appearance.keyBackColor, it.appearance.icon) } +
                kb.keyAppearance.keyBackColor
            files.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { path ->
                (path in available) shouldBe true
            }
        }
    }

    test("all 256 PNGs decode and fit the bounded installer") {
        manifest.all { it.startsWith("keyling/") && it.endsWith(".png") && ".." !in it.split('/') } shouldBe true
        ZipFile(File(raw, "mie_scenic_materials.zip")).use { zip ->
            val entries = zip.entries().asSequence().toList()
            entries.map { it.name }.toSet() shouldBe manifest.toSet()
            var total = 0L
            entries.forEach { entry ->
                entry.isDirectory shouldBe false
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                total += bytes.size
                (bytes.size <= 4 * 1024 * 1024) shouldBe true
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                (image != null && image.width > 0 && image.height > 0 && image.width.toLong() * image.height < 4_194_304) shouldBe true
            }
            (total <= 48 * 1024 * 1024) shouldBe true
        }
    }

    test("stock scenic themes keep their toolbar, actions and geometry through export") {
        roots.forEach { root ->
            val source = pages.filterKeys { it == root || it.startsWith("$root") }
            val restored = CustomKeyboardYaml.decode(CustomKeyboardYaml.encode(source, emptyList()))
            restored.keys shouldBe source.keys
            source.forEach { (id, kb) ->
                val copy = restored.getValue(id)
                copy.resolvedBackground() shouldBe kb.resolvedBackground()
                copy.toolbar shouldBe kb.toolbar
                copy.toolbarBackground shouldBe kb.toolbarBackground
                copy.rows.map { row -> row.keys.map { key -> key.bindings } } shouldBe
                    kb.rows.map { row -> row.keys.map { key -> key.bindings } }
                copy.rows.map { row -> row.keys.map { key -> key.appearance.keyBackColor } } shouldBe
                    kb.rows.map { row -> row.keys.map { key -> key.appearance.keyBackColor } }
            }
        }
    }

    test("every scenic page keeps the eight button toolbar") {
        pages.forEach { (_, kb) ->
            kb.toolbar.size shouldBe 8
            kb.toolbar.count { it.action == "Mode_switch" } shouldBe 1
            kb.toolbar.count { it.action == "__mie_theme_picker" } shouldBe 1
        }
    }
})
