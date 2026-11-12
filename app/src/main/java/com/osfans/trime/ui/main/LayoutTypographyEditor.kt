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
import com.osfans.trime.data.keymap.LayoutTypography
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.data.theme.ThemeManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LayoutTypographyEditor {
    fun show(activity: FragmentActivity, id: String) {
        val keyboard=CustomKeyboards.get(id) ?: return
        val draft=keyboard.typography.toMutableMap()
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(32,8,32,16) }
        val importer=activity.activityResultRegistry.register("font-import-${java.util.UUID.randomUUID()}",androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            if(uri!=null) {
                activity.lifecycleScope.launch {
                val file=java.io.File(FontOverrides.folder(),"font-${java.util.UUID.randomUUID()}.ttf")
                val result=withContext(Dispatchers.IO) { runCatching {
                    activity.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取字体" }
                        file.outputStream().use { output ->
                            val buffer=ByteArray(8192); var total=0
                            while(true) { val count=input.read(buffer); if(count<0) break; total+=count; require(total<=128*1024*1024) { "字体不能超过128MB" }; output.write(buffer,0,count) }
                        }
                    }
                    FontOverrides.fromSelection(file.absolutePath) ?: error("字体不可用")
                } }
                result.onSuccess { android.widget.Toast.makeText(activity,"已导入，请在对应文字的字体列表中选择",1).show() }
                    .onFailure { file.delete(); android.widget.Toast.makeText(activity,it.message ?: "字体导入失败",1).show() }
                }
            }
        }
        body.addView(MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text="导入字体文件…"; setOnClickListener { importer.launch("*/*") } })
        body.addView(android.widget.TextView(activity).apply {
            text="全局默认跟随系统当前字体，单项设置优先。次级键盘可继承主键盘全局字体；字号仍按各项设置。"
            setPadding(0,8,0,16)
        })
        val drawers=SettingsDrawers(body)
        val buttons=mutableMapOf<String,MaterialButton>()
        fun inheritedGlobal() = keyboard.secondaryOf.takeIf { it.isNotBlank() }?.let(CustomKeyboards::globalFont).orEmpty()
        fun roleLabel(role: String): String {
            val font=draft[role]?.font.orEmpty()
            return "字体 · " + when {
                font.isNotBlank() -> FontOverrides.name(font)
                draft[LayoutTypography.GLOBAL]?.font?.isNotBlank() == true -> "跟随当前键盘全局字体"
                inheritedGlobal().isNotBlank() -> "继承主键盘全局字体"
                else -> "系统当前字体"
            }
        }
        val global=drawers.section("当前键盘全局字体")
        global.addView(MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            fun summary(value: String) = "字体 · " + if(value.isNotBlank()) FontOverrides.name(value)
                else if(inheritedGlobal().isNotBlank()) "继承主键盘全局字体" else "系统当前字体"
            text=summary(draft[LayoutTypography.GLOBAL]?.font.orEmpty()); isAllCaps=false
            setOnClickListener {
                FontChainPicker.show(activity,"当前键盘全局字体",draft[LayoutTypography.GLOBAL]?.font.orEmpty(),
                    defaultLabel="次级键盘继承主键盘；未设置时跟随系统当前字体") { value ->
                    draft[LayoutTypography.GLOBAL]=FontSpec(font=value)
                    text=summary(value)
                    buttons.forEach { (role,button) -> button.text=roleLabel(role) }
                }
            }
        })
        val sizes=mutableMapOf<String,TextInputEditText>()
        val colors=mutableMapOf<String,String>()
        FontOverrides.roles.forEach { (role,label) ->
            val original=draft[role] ?: FontSpec()
            val content=drawers.section(label)
            // 「高亮候选文字」只开放颜色：字形与字号跟随「候选文字」，单独设不会生效。
            if (role != "candidateHl") {
                val button=MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text=roleLabel(role); isAllCaps=false
                    setOnClickListener {
                        FontChainPicker.show(activity, label, (draft[role] ?: FontSpec()).font,
                            defaultLabel="跟随当前键盘全局字体 / 主键盘全局字体") { value ->
                            draft[role]=(draft[role] ?: FontSpec()).copy(font=value)
                            text=roleLabel(role)
                        }
                    }
                }
                buttons[role]=button
                content.addView(button)
                val size=TextInputEditText(activity).apply { setSingleLine(); setText(original.size.toString()); inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
                content.addView(TextInputLayout(activity).apply { hint="字号 sp（0 跟随默认，最大200）"; addView(size) }); sizes[role]=size
            }
            colors[role]=original.color
            val colorButton=MaterialButton(activity,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                fun colorLabel() = "颜色 · " + (colors[role]?.takeIf { it.isNotBlank() } ?: "跟随主题配色")
                text=colorLabel(); isAllCaps=false
                setOnClickListener {
                    AppearancePicker.show(activity, "$label 颜色", colors[role].orEmpty(), false) { value ->
                        colors[role]=value
                        text=colorLabel()
                    }
                }
            }
            content.addView(colorButton)
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
            colors.forEach { (role, hex) -> draft[role]=(draft[role] ?: FontSpec()).copy(color=hex) }
            if(valid) { CustomKeyboards.mutate(id) { it.typography=draft.toMap() }; if(CustomKeyboards.flush()) { ThemeManager.requestCustomizationRefresh(); dialog.dismiss() } else android.widget.Toast.makeText(activity,"保存失败",1).show() }
        } }; dialog.show()
    }
}
