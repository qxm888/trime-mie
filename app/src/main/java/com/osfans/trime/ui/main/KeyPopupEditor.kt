/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import androidx.fragment.app.FragmentActivity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Edits the complete ordered popup list; no trigger action is inserted implicitly. */
object KeyPopupEditor {
    fun show(context: FragmentActivity, title: String, choices: List<String>, save: (List<String>) -> Unit) {
        val density=context.resources.displayMetrics.density
        val input=EditText(context).apply { hint="每行一个符号、表情或动作"; minLines=4; maxLines=8; setText(choices.joinToString("\n")) }
        val body=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; setPadding((20*density).toInt(),(8*density).toInt(),(20*density).toInt(),0)
            addView(TextView(context).apply { text="长按打开小窗，滑动选择，松手输入。列表按完整备选保存；留空关闭。" })
            addView(input)
            addView(MaterialButton(context).apply {
                text="添加绑定动作"
                setOnClickListener { KeyTokenPicker.show(context,"添加弹出动作",false) { token ->
                    if(!token.isNullOrEmpty()) input.setText(input.text.toString().let { if(it.isEmpty()) token else "$it\n$token" })
                } }
            })
            addView(MaterialButton(context).apply {
                text="选择常用符号组"
                setOnClickListener {
                    val groups=linkedMapOf("括号与引号" to listOf("[","(","「","“","『","【","{","'","《","\"","】","』","”","」",")","]","}"),
                        "常用标点" to listOf("，","。","、","？","！","：","；","…","—","·"),
                        "常用表情" to listOf("😀","😊","😂","🥰","👍","🙏","❤️","💕","✨","🎉"))
                    MaterialAlertDialogBuilder(context).setTitle("选择符号组").setItems(groups.keys.toTypedArray()) { _,i -> input.setText(groups.values.elementAt(i).joinToString("\n")) }.show()
                }
            })
        }
        val dialog=MaterialAlertDialogBuilder(context).setTitle("$title · 长按 popup").setView(body)
            .setNeutralButton("清空") { _,_ -> save(emptyList()) }.setNegativeButton("取消",null).setPositiveButton("保存",null).create()
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val values=input.text.toString().lines().filter { it.isNotEmpty() }
            if(values.size>64 || values.any { it.length>512 }) input.error="最多 64 项，每项不超过 512 字符"
            else runCatching { save(values) }.onSuccess { dialog.dismiss() }.onFailure { input.error=it.message }
        } }
        dialog.show()
    }
}
