/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.BindingActions
import com.osfans.trime.data.keymap.NamedFunctions
import java.util.UUID

object NamedFunctionsEditor {
    fun show(activity: FragmentActivity) {
        val definitions = NamedFunctions.list()
        MaterialAlertDialogBuilder(activity).setTitle("自定义功能")
            .setItems((listOf("＋ 新建功能") + definitions.map { "${it.name} · ${it.actions.size}个动作" }).toTypedArray()) { _, index ->
                edit(activity, definitions.getOrNull(index - 1))
            }.setNegativeButton("关闭", null).show()
    }
    private fun edit(activity: FragmentActivity, original: NamedFunctions.Definition?) {
        fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
        val actions = original?.actions?.toMutableList() ?: mutableListOf()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 24) }
        val name = TextInputEditText(activity).apply { setSingleLine(); setText(original?.name.orEmpty()) }
        body.addView(TextInputLayout(activity).apply { hint = "功能名称（中文、英文、数字）"; addView(name) })
        body.addView(android.widget.TextView(activity).apply { text = "按顺序执行下列动作；在按键绑定的“自定义输入”中输入此名称即可使用。"; setPadding(0,16,0,16) })
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(list)
        fun render() {
            list.removeAllViews()
            actions.forEachIndexed { index, token ->
                val row = LinearLayout(activity)
                row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "${index+1}. ${BindingActions.label(token)}"; isAllCaps = false
                    setOnClickListener { KeyTokenPicker.show(activity, "替换动作", true) { next ->
                        if (next.isNullOrEmpty()) actions.removeAt(index) else actions[index] = next
                        render()
                    } }
                }, LinearLayout.LayoutParams(0,-2,1f))
                row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "↑"; contentDescription = "上移动作"; isEnabled = index>0; minWidth = 0; minimumWidth = 0; setPadding(0,0,0,0)
                    setOnClickListener { java.util.Collections.swap(actions,index,index-1); render() }
                }, LinearLayout.LayoutParams(dp(44),-2))
                row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "×"; contentDescription = "删除动作"; minWidth = 0; minimumWidth = 0; setPadding(0,0,0,0)
                    setOnClickListener { actions.removeAt(index); render() }
                }, LinearLayout.LayoutParams(dp(44),-2))
                list.addView(row)
            }
        }
        render()
        body.addView(MaterialButton(activity).apply { text = "＋ 添加动作"; setOnClickListener {
            KeyTokenPicker.show(activity,"添加动作",false) { next -> if (!next.isNullOrEmpty()) { actions.add(next); render() } }
        } })
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (original==null) "新建功能" else "编辑功能")
            .setView(ScrollView(activity).apply { addView(body) }).setPositiveButton("保存",null).setNegativeButton("取消",null)
            .apply { if(original!=null) setNeutralButton("删除") { _, _ ->
                runCatching { NamedFunctions.save(NamedFunctions.list().filterNot { it.id==original.id }) }
                    .onFailure { Toast.makeText(activity,it.message,Toast.LENGTH_LONG).show() }
                show(activity)
            } }.create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            runCatching {
                val definition=NamedFunctions.Definition(original?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(),actions.toList())
                NamedFunctions.save(NamedFunctions.list().filterNot { it.id==definition.id } + definition)
            }.onSuccess { dialog.dismiss(); show(activity) }.onFailure { name.error=it.message }
        } }
        dialog.show()
    }
}
