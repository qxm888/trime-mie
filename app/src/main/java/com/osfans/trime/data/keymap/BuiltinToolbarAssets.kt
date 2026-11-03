/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import android.util.AtomicFile
import com.osfans.trime.R
import java.io.File
import java.util.zip.ZipInputStream

/** Only files owned by the stock icon pack are migrated or removed. */
object BuiltinToolbarAssets {
    private val names = listOf("menu", "theme", "layout", "float", "onehand", "clipboard", "lang", "hide", "emoji", "switch", "symbols")
    private val replacements = names.associate { "icons/tb_$it.png" to "icons/mie2_tb_$it.png" }
    fun path(value: String): String = replacements[value] ?: value

    private fun pickerAction(button: ToolbarOverrides.Entry): String = if (
        button.icon.substringAfterLast('/').let { it == "tb_theme.png" || it.endsWith("_tb_theme.png") } &&
        button.action.startsWith("Keyboard_") && button.action.endsWith("_themes")) "__mie_theme_picker" else button.action

    private fun icon(button: ToolbarOverrides.Entry): String {
        val migrated = path(button.icon)
        return if (button.action == "__mie_floating_keyboard" && migrated == "icons/mie2_tb_onehand.png")
            "icons/mie2_tb_float.png" else migrated
    }

    fun upgrade(keyboard: CustomKeyboards.CustomKeyboard): CustomKeyboards.CustomKeyboard {
        if (keyboard.toolbar.none { icon(it) != it.icon || pickerAction(it) != it.action } &&
            keyboard.rows.none { row -> row.keys.any { path(it.appearance.icon) != it.appearance.icon } }) return keyboard
        return keyboard.copy(
            toolbar = keyboard.toolbar.map { button -> button.copy(icon = icon(button), action = pickerAction(button)) }.toMutableList(),
            rows = keyboard.rows.map { row -> row.copy(keys = row.keys.map { key ->
                key.copy(appearance = key.appearance.copy(icon = path(key.appearance.icon)))
            }.toMutableList()) }.toMutableList(),
        )
    }

    fun install(context: Context) {
        val dir = File(CustomKeyAssets.root(context), "icons")
        if (!dir.isDirectory) check(dir.mkdirs()) { "无法创建内置图标目录" }
        val marker = File(dir, ".builtin_v4")
        if (!marker.isFile || names.any { !File(dir, "mie2_tb_$it.png").isFile }) {
            context.resources.openRawResource(R.raw.mie_builtin_icons).use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        require(name == File(name).name && !entry.isDirectory) { "内置图标文件名无效" }
                        val file = File(dir, name)
                        if (name.startsWith("mie2_tb_") || !file.isFile) {
                            val atomic = AtomicFile(file); val output = atomic.startWrite()
                            try { zip.copyTo(output); atomic.finishWrite(output) }
                            catch (error: Exception) { atomic.failWrite(output); throw error }
                        }
                        zip.closeEntry()
                    }
                }
            }
            check(names.all { File(dir, "mie2_tb_$it.png").isFile })
            marker.writeText("4\n")
        }
        // The resolver accepts these old relative names as aliases, including in
        // user copies/old exports. Unrelated or user-named assets stay untouched.
        replacements.keys.forEach { File(dir, it.substringAfterLast('/')).delete() }
        File(dir, ".builtin_v1").delete()
        File(dir, ".builtin_v2").delete()
        File(dir, ".builtin_v3").delete()
    }
}
