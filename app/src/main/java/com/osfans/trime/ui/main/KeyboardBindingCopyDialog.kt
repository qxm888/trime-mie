/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyboardFamilyBindingCopy
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object KeyboardBindingCopyDialog {
    fun show(activity: FragmentActivity, target: String, onSaved: () -> Unit) {
        val targetRoot = runCatching { KeyboardFamilyBindingCopy.rootOf(target) }.getOrElse {
            android.widget.Toast.makeText(activity, it.message ?: "键盘分组无效", 1).show(); return
        }
        val choices = CustomKeyboards.list().filter { it.secondaryOf.isBlank() && it.id != targetRoot }
        if (choices.isEmpty()) { android.widget.Toast.makeText(activity, "没有可复制的其它键盘", 0).show(); return }
        MaterialAlertDialogBuilder(activity).setTitle("选择来源整套键盘")
            .setItems(choices.map { entry ->
                entry.name + " · " + CustomKeyboards.familyIds(listOf(entry.id)).size + " 页"
            }.toTypedArray()) { _, index ->
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) { runCatching { KeyboardFamilyBindingCopy.plan(choices[index].id, targetRoot) } }
                    result.onSuccess { plan ->
                        MaterialAlertDialogBuilder(activity).setTitle("复制整套按键绑定")
                            .setMessage("来源：${choices[index].name}\n目标：${CustomKeyboards.get(targetRoot)?.name.orEmpty()}\n\n可复制 ${plan.updates.size} 页、${plan.keyCount} 个按键；${plan.skippedKeys} 个未匹配按键保留。\n目标有 ${plan.unmatchedTargets.size} 页未匹配；来源有 ${plan.unmatchedSources.size} 页在目标中无对应页。\n\n复制手势、弹出备选、发送开关，以及主标签、前景图标、上方符号和下方注解的文字、单键字号、位置与显示设置。来源为空时也会清空对应内容。套内跳转对应目标页面；目标布局、背景、配色和字体保持原设置。")
                            .setPositiveButton("复制整套绑定") { _, _ ->
                                activity.lifecycleScope.launch {
                                    val copied = withContext(Dispatchers.IO) { runCatching { KeyboardFamilyBindingCopy.apply(plan) } }
                                    copied.onSuccess { ThemeManager.requestCustomizationRefresh(); onSaved(); android.widget.Toast.makeText(activity, "已复制整套 $it 个按键的绑定、标签、符号与注解", 0).show() }
                                        .onFailure { android.widget.Toast.makeText(activity, it.message ?: "复制失败", 1).show() }
                                }
                            }.setNegativeButton("取消", null).show().also { it.getButton(-1).isEnabled = plan.updates.isNotEmpty() }
                    }.onFailure { android.widget.Toast.makeText(activity, it.message ?: "无法读取键盘", 1).show() }
                }
            }.setNegativeButton("取消", null).show()
    }
}
