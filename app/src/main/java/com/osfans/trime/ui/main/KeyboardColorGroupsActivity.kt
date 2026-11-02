/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.theme.KeyboardColorGroup
import com.osfans.trime.data.theme.KeyboardColorGroups
import com.osfans.trime.data.theme.KeyboardStyleTarget
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class KeyboardColorGroupsActivity : PersonalizationActivity() {
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); page("配色组"); render() }
    override fun onStop() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) ThemeManager.syncToDeviceEncryptedStorage()
        super.onStop()
    }
    private fun render() {
        body.removeAllViews()
        note("每组选择日间和夜间主题。启用配色组并跟随系统夜间后，会自动切换；手动选择布局会退出配色组。")
        body.addView(MaterialSwitch(this).apply {
            text = "跟随系统夜间切换"; isChecked = ThemeManager.prefs.followSystemDayNight.getValue()
            setOnCheckedChangeListener { _, checked -> ThemeManager.prefs.followSystemDayNight.setValue(checked) }
        })
        button("＋ 新建配色组") { edit(null) }
        KeyboardColorGroups.list().forEach { group ->
            button("${group.name}${if (KeyboardColorGroups.activeId() == group.id) " · 已启用" else ""}\n日间 · ${group.day.name}\n夜间 · ${group.night.name}") {
                MaterialAlertDialogBuilder(this).setTitle(group.name).setItems(arrayOf("启用此配色组", "编辑", "删除")) { _, position ->
                    when (position) {
                        0 -> if (!busy) { busy = true; lifecycleScope.launch {
                            try { KeyboardColorGroups.activate(group.id); render() }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (error: Exception) { Toast.makeText(this@KeyboardColorGroupsActivity, error.message ?: "切换失败", Toast.LENGTH_LONG).show() }
                            finally { busy = false }
                        } }
                        1 -> edit(group)
                        2 -> MaterialAlertDialogBuilder(this).setTitle("删除 ${group.name}？").setMessage("当前键盘保持不变。")
                            .setPositiveButton("删除") { _, _ -> KeyboardColorGroups.delete(group.id); render() }.setNegativeButton("取消", null).show()
                    }
                }.setNegativeButton("取消", null).show()
            }
        }
        if (KeyboardColorGroups.activeId().isNotEmpty()) button("停用配色组") {
            KeyboardColorGroups.deactivate(); render()
        }
    }
    private fun edit(existing: KeyboardColorGroup?) {
        var day = existing?.day; var night = existing?.night
        val name = EditText(this).apply { setSingleLine(); setText(existing?.name.orEmpty()); selectAll() }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fields.addView(TextInputLayout(this).apply { hint = "配色组名称"; addView(name) })
        fun targetButton(label: String, current: () -> KeyboardStyleTarget?, save: (KeyboardStyleTarget) -> Unit) {
            fields.addView(com.google.android.material.button.MaterialButton(this).apply {
                fun update() { text = "$label · ${current()?.name ?: "选择主题"}" }
                update(); isAllCaps = false; minHeight = dp(52)
                setOnClickListener { lifecycleScope.launch {
                    ThemePickerDialog.build(lifecycleScope, this@KeyboardColorGroupsActivity, pickTarget = { save(it); update() }).show()
                } }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        targetButton("日间", { day }) { day = it }; targetButton("夜间", { night }) { night = it }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(if (existing == null) "新建配色组" else "编辑配色组")
            .setView(fields).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            try {
                val label = name.text.toString().trim(); require(label.isNotEmpty()) { "请输入名称" }; require(label.length <= 60) { "名称最多 60 个字符" }
                val dayTarget = day ?: error("请选择日间主题"); val nightTarget = night ?: error("请选择夜间主题")
                KeyboardColorGroups.save(existing?.copy(name = label, day = dayTarget, night = nightTarget)
                    ?: KeyboardColorGroup(name = label, day = dayTarget, night = nightTarget))
                dialog.dismiss(); render()
            } catch (error: Exception) { Toast.makeText(this, error.message ?: "保存失败", Toast.LENGTH_SHORT).show() }
        } }
        dialog.show()
    }
}
