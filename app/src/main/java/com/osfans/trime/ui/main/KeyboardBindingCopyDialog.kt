/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyboardBindingCopy
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object KeyboardBindingCopyDialog {
    fun show(activity: FragmentActivity, target: String, onSaved: () -> Unit) {
        val choices = CustomKeyboards.list().filter { it.id != target }
        if (choices.isEmpty()) { android.widget.Toast.makeText(activity, "没有可复制的其它键盘", 0).show(); return }
        MaterialAlertDialogBuilder(activity).setTitle("从其它键盘复制绑定")
            .setItems(choices.map { entry ->
                entry.name + entry.secondaryOf.takeIf { it.isNotBlank() }?.let { " · " + (CustomKeyboards.get(it)?.name ?: "次级键盘") }.orEmpty()
            }.toTypedArray()) { _, index ->
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.Default) { runCatching { KeyboardBindingCopy.plan(choices[index].id, target) } }
                    result.onSuccess { plan ->
                        MaterialAlertDialogBuilder(activity).setTitle("复制按键绑定")
                            .setMessage("来源：${choices[index].name}\n\n可匹配 ${plan.matches.size} 个按键，${plan.skipped} 个未匹配按键保留。按相同点击动作或唯一标签匹配，复制点击、长按、滑动、弹出备选与绑定发送开关；保留当前键盘的布局、背景、字体与外观。此操作只复制一次。")
                            .setPositiveButton("复制") { _, _ ->
                                runCatching { KeyboardBindingCopy.apply(plan) }
                                    .onSuccess { ThemeManager.requestCustomizationRefresh(); onSaved(); android.widget.Toast.makeText(activity, "已复制 $it 个按键的绑定", 0).show() }
                                    .onFailure { android.widget.Toast.makeText(activity, it.message ?: "复制失败", 1).show() }
                            }.setNegativeButton("取消", null).show().also { it.getButton(-1).isEnabled = plan.matches.isNotEmpty() }
                    }.onFailure { android.widget.Toast.makeText(activity, it.message ?: "无法读取键盘", 1).show() }
                }
            }.setNegativeButton("取消", null).show()
    }
}
