/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyboardModeBinding
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.*

object KeyboardModeBindingEditor {
    fun show(activity: FragmentActivity, oneHand: Boolean, rime: RimeSession, changed: () -> Unit) {
        val value = KeyboardModeBinding.read(oneHand)
        fun save(next: KeyboardModeBinding) { KeyboardModeBinding.save(oneHand, next); changed() }
        MaterialAlertDialogBuilder(activity).setTitle(if(oneHand) "单手键盘绑定" else "悬浮键盘绑定")
            .setItems(arrayOf("方案 · ${value.schemaName.ifBlank { value.schemaId }.ifBlank { "不绑定" }}",
                "布局 · ${value.layoutName.ifBlank { value.layoutId }.ifBlank { "不绑定" }}")) { _, index ->
                if(index == 0) activity.lifecycleScope.launch {
                    try {
                        val schemas = withTimeout(4000) { rime.runOnReady { selectedSchemata().toList() } }
                        MaterialAlertDialogBuilder(activity).setTitle("绑定已部署方案")
                            .setItems((listOf("不绑定方案") + schemas.map { it.name }).toTypedArray()) { _, position ->
                                val entry = schemas.getOrNull(position - 1)
                                save(value.copy(schemaId = entry?.id.orEmpty(), schemaName = entry?.name.orEmpty()))
                            }.setNegativeButton("取消", null).show()
                    } catch(error: TimeoutCancellationException) {
                        android.widget.Toast.makeText(activity, "方案暂未就绪，请先手动部署并启用方案", 1).show()
                    } catch(error: CancellationException) { throw error }
                    catch(error: Exception) { android.widget.Toast.makeText(activity, error.message ?: "读取方案失败", 1).show() }
                } else chooseLayout(activity, value, ::save)
            }.setNeutralButton("清除绑定") { _, _ -> save(KeyboardModeBinding()) }.setPositiveButton("完成", null).show()
    }

    private fun chooseLayout(activity: FragmentActivity, value: KeyboardModeBinding, save: (KeyboardModeBinding) -> Unit) {
        val theme = ThemeManager.activeThemeOrNull()?.takeUnless { it.isCustom }
        val choices = listOf("不绑定布局", "内置／自定义整套") + if(theme != null) listOf("当前主题布局") else emptyList()
        MaterialAlertDialogBuilder(activity).setTitle("选择绑定布局").setItems(choices.toTypedArray()) { _, index ->
            when(index) {
                0 -> save(value.copy(layoutId = "", layoutName = "", layoutMode = "", themeId = ""))
                1 -> activity.lifecycleScope.launch {
                    CustomKeyboards.awaitBuiltinLayouts()
                    val roots = CustomKeyboards.list().filter { it.secondaryOf.isBlank() }
                    MaterialAlertDialogBuilder(activity).setTitle("选择整套键盘").setItems(roots.map { it.name }.toTypedArray()) { _, root ->
                        val pages = CustomKeyboards.familyIds(listOf(roots[root].id)).mapNotNull { id -> CustomKeyboards.get(id)?.let { id to it.name } }
                        MaterialAlertDialogBuilder(activity).setTitle("选择具体布局").setItems(pages.map { it.second }.toTypedArray()) { _, page ->
                            save(value.copy(layoutId = pages[page].first, layoutName = pages[page].second,
                                layoutMode = CustomKeyboards.MODE_CUSTOM, themeId = ""))
                        }.setNegativeButton("取消", null).show()
                    }.setNegativeButton("取消", null).show()
                }
                else -> theme?.let { current ->
                    val ids = current.presetKeyboards.keys.toList()
                    MaterialAlertDialogBuilder(activity).setTitle("当前主题布局").setItems(ids.toTypedArray()) { _, page ->
                        save(value.copy(layoutId = ids[page], layoutName = ids[page], layoutMode = CustomKeyboards.MODE_THEME,
                            themeId = ThemeManager.prefs.selectedTheme.getValue()))
                    }.setNegativeButton("取消", null).show()
                }
            }
        }.setNegativeButton("取消", null).show()
    }
}
