/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object KeyboardSetDialogs {
    fun name(activity: FragmentActivity, title: String, initial: String = "", done: (String) -> Unit) {
        val box = TextInputLayout(activity).apply { hint = "名称"; setPadding(32, 12, 32, 12) }
        val input = TextInputEditText(box.context).apply { setSingleLine(); setText(initial) }; box.addView(input)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(title).setView(box).setPositiveButton("继续", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val value = input.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) box.error = "请输入名称" else { dialog.dismiss(); done(value) }
        } }; dialog.show()
    }
    fun run(activity: FragmentActivity, done: () -> Unit = {}, block: () -> Unit) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            result.onSuccess { com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh(); done() }
                .onFailure { MaterialAlertDialogBuilder(activity).setTitle("未能完成").setMessage(it.message ?: "操作失败").setPositiveButton("确定", null).show() }
        }
    }
    fun create(activity: FragmentActivity, done: (String) -> Unit) {
        MaterialAlertDialogBuilder(activity).setTitle("新建整套键盘")
            .setItems(arrayOf("从内置样式开始", "复制已有整套", "从空白开始")) { _, which ->
                if (which == 1) chooseSet(activity, "选择来源整套") { id -> name(activity, "新整套名称", CustomKeyboards.get(id)!!.name + " 副本") { value ->
                    var created = ""; run(activity, { done(created) }) { created = KeyboardSetOperations.copySet(id, value) }
                } } else if (which == 2) name(activity, "新整套名称") { value ->
                    var id = ""; run(activity, { done(id) }) { id = KeyboardSetOperations.newBlank(value) }
                } else chooseTemplate(activity) { template -> name(activity, "新整套名称") { value ->
                    selectLayouts(activity) { roles -> var id = ""
                        run(activity, { done(id) }) { id = KeyboardSetOperations.newSet(value, template, roles) }
                    }
                } }
            }.setNegativeButton("取消", null).show()
    }
    fun chooseTemplate(activity: FragmentActivity, done: (String) -> Unit) {
        activity.lifecycleScope.launch {
            val roots = withContext(Dispatchers.Default) { KeyboardLayoutCatalog.roots(activity, KeyboardLayoutCatalog.BUILTIN) }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (roots.isEmpty()) { android.widget.Toast.makeText(activity, "请先恢复内置键盘", 1).show(); return@launch }
            MaterialAlertDialogBuilder(activity).setTitle("选择内置样式")
                .setItems(roots.map { it.name }.toTypedArray()) { _, index -> done(roots[index].id) }.setNegativeButton("取消", null).show()
        }
    }
    fun selectLayouts(activity: FragmentActivity, done: (List<String>) -> Unit) {
        val entries = KeyboardSetOperations.layoutNames.entries.toList()
        val selected = BooleanArray(entries.size) { entries[it].key in setOf("zh_26", "en", "num") }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle("选择布局")
            .setMultiChoiceItems(entries.map { it.value + if(it.key == "zh_9") "（需九键方案）" else "" }.toTypedArray(), selected) { _, i, checked -> selected[i] = checked }
            .setMessage("自动配置工具栏、跳转和符号／表情面板入口。")
            .setPositiveButton("创建整套", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val roles = entries.indices.filter { selected[it] }.map { entries[it].key }
            if (roles.none { it.startsWith("zh_") }) android.widget.Toast.makeText(activity, "至少选择一个中文布局", 0).show()
            else { dialog.dismiss(); done(roles) }
        } }; dialog.show()
    }
    fun chooseSet(activity: FragmentActivity, title: String, exclude: String = "", done: (String) -> Unit) {
        val entries = CustomKeyboards.list().filter { it.secondaryOf.isEmpty() && it.id != exclude }
        if (entries.isEmpty()) { android.widget.Toast.makeText(activity, "没有其它整套键盘", 0).show(); return }
        MaterialAlertDialogBuilder(activity).setTitle(title).setItems(entries.map { it.name }.toTypedArray()) { _, i -> done(entries[i].id) }.setNegativeButton("取消", null).show()
    }
}
