/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.keymap.CustomKeyboards

/** Stable ids keep schema switches independent from their changing display labels. */
object FunctionMenuEditor {
    val builtins = linkedMapOf("ThemeList" to "键盘样式", "SchemaList" to "方案", "UpdateConfig" to "更新配置",
        "Keyboard" to "系统键盘设置", "InputStats" to "输入统计", "FloatingKeyboard" to "悬浮键盘",
        "OneHandMode" to "单手模式", "CandidatesWindow" to "悬浮候选栏", "FloatingBall" to "悬浮球", "HideInputBar" to "隐藏状态栏")
    fun build(context: Context, id: String, onSaved: () -> Unit): Dialog {
        val keyboard = CustomKeyboards.get(id) ?: error("自定义键盘不存在")
        val items = builtins.map { "static:${it.key}" to it.value }.toMutableList()
        runCatching { RimeDaemon.getFirstSessionOrNull()?.run { schemaCached.switches } }.getOrNull()?.forEach { switch ->
            val key = "schema:" + switch.name.ifBlank { switch.options.joinToString(",") }
            items.add(key to switch.states.joinToString(" / ").ifBlank { switch.name })
        }
        val hidden = keyboard.hiddenMenuItems.toMutableSet()
        val checked = items.map { it.first !in hidden }.toBooleanArray()
        return AlertDialog.Builder(context).setTitle("功能菜单显示项目")
            .setMultiChoiceItems(items.map { it.second }.toTypedArray(), checked) { _, index, enabled ->
                if (enabled) hidden.remove(items[index].first) else hidden.add(items[index].first)
            }.setPositiveButton("保存") { _, _ ->
                CustomKeyboards.mutate(id) { it.hiddenMenuItems = hidden.toSet() }
                if (CustomKeyboards.flush()) onSaved() else android.widget.Toast.makeText(context, "保存失败，请检查数据目录", 1).show()
            }.setNeutralButton("全部显示") { _, _ ->
                CustomKeyboards.mutate(id) { it.hiddenMenuItems = emptySet() }; CustomKeyboards.flush(); onSaved()
            }.setNegativeButton("取消", null).create()
    }
}
