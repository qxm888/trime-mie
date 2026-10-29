/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.FontSpec
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.data.theme.ThemeManager

object LayoutTypographyEditor {
    fun show(activity: FragmentActivity, id: String) {
        val keyboard=CustomKeyboards.get(id) ?: return
        val draft=keyboard.typography.toMutableMap()
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(32,8,32,16) }
        val importer=activity.activityResultRegistry.register("font-import-${java.util.UUID.randomUUID()}",androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if(uri!=null) {
                val file=java.io.File(FontOverrides.folder(),"font-${java.util.UUID.randomUUID()}.ttf")
                runCatching {
                    activity.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取字体" }
                        file.outputStream().use { output ->
                            val buffer=ByteArray(8192); var total=0
                            while(true) { val count=input.read(buffer); if(count<0) break; total+=count; require(total<=20*1024*1024) { "字体不能超过20MB" }; output.write(buffer,0,count) }
                        }
                    }
                    android.graphics.Typeface.createFromFile(file)
                }.onSuccess { android.widget.Toast.makeText(activity,"已导入，请在对应文字的字体列表中选择",1).show() }
                    .onFailure { file.delete(); android.widget.Toast.makeText(activity,it.message ?: "字体导入失败",1).show() }
            }
        }
        body.addView(MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text="导入字体文件…"; setOnClickListener { importer.launch("*/*") } })
        val sizes=mutableMapOf<String,TextInputEditText>()
        FontOverrides.roles.forEach { (role,label) ->
            val original=draft[role] ?: FontSpec()
            val content=SettingsDrawers(body).section(label)
            val button=MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text="字体 · ${if(original.font.isBlank()) "默认字体" else FontOverrides.name(original.font)}"; isAllCaps=false
                setOnClickListener {
                    val choices=FontOverrides.choices()
                    MaterialAlertDialogBuilder(activity).setTitle("$label 字体").setItems(choices.map(FontOverrides::name).toTypedArray()) { _, index ->
                        draft[role]=(draft[role] ?: FontSpec()).copy(font=choices[index]); text="字体 · ${if(choices[index].isBlank()) "默认字体" else FontOverrides.name(choices[index])}"
                    }.show()
                }
            }
            content.addView(button)
            val size=TextInputEditText(activity).apply { setSingleLine(); setText(original.size.toString()); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
            content.addView(TextInputLayout(activity).apply { hint="字号 sp（0 跟随默认，最大200）"; addView(size) }); sizes[role]=size
        }
        val dialog=MaterialAlertDialogBuilder(activity).setTitle("${keyboard.name} · 字体与字号").setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("保存",null).setNegativeButton("取消",null).setNeutralButton("恢复默认") { _, _ -> CustomKeyboards.mutate(id) { it.typography=emptyMap() }; CustomKeyboards.flush(); ThemeManager.requestCustomizationRefresh() }.create()
        dialog.setOnDismissListener { importer.unregister() }
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            var valid=true
            sizes.forEach { (role,input) ->
                val value=input.text.toString().toFloatOrNull()
                if(value==null || !value.isFinite() || value !in 0f..200f) { input.error="请输入0–200"; valid=false }
                else draft[role]=(draft[role] ?: FontSpec()).copy(size=value)
            }
            if(valid) { CustomKeyboards.mutate(id) { it.typography=draft.toMap() }; if(CustomKeyboards.flush()) { ThemeManager.requestCustomizationRefresh(); dialog.dismiss() } else android.widget.Toast.makeText(activity,"保存失败",1).show() }
        } }; dialog.show()
    }
}
