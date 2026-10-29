/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.ThemeKeyboardImporter
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.resolveKeyboardPreset
import kotlinx.coroutines.*

/** One import action, with the selected theme and current/default layout preselected. */
class ThemeKeyboardImportDialog(private val activity:AppCompatActivity,
    private val completed:(ThemeKeyboardImporter.Result)->Unit) {
    private var themes=emptyList<ThemeItem>()
    private var theme:Theme?=null
    private var keyboard=""
    private var job:Job?=null
    private var busy=false
    private fun dp(n:Int)=(n*activity.resources.displayMetrics.density).toInt()
    fun show() {
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(12)) }
        val source=MaterialButton(activity, null,com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val layout=MaterialButton(activity, null,com.google.android.material.R.attr.materialButtonOutlinedStyle)
        val name=TextInputEditText(activity).apply { setSingleLine();filters=arrayOf(android.text.InputFilter.LengthFilter(80)) }
        val field=TextInputLayout(activity).apply { hint="新键盘名称";addView(name) }
        val progress=LinearProgressIndicator(activity).apply { isIndeterminate=true }
        val note=TextView(activity).apply { text="复制布局、按键绑定、背景、字体及工具栏；按键跳转需要的数字／符号布局会一起生成。导入后各项设置独立保存。";setPadding(0,dp(12),0,dp(12)) }
        val error=TextView(activity).apply { setTextColor(androidx.core.content.ContextCompat.getColor(activity,com.google.android.material.R.color.design_default_color_error)) }
        body.addView(source);body.addView(layout);body.addView(field);body.addView(note);body.addView(progress);body.addView(error)
        val dialog=MaterialAlertDialogBuilder(activity).setTitle("从主题键盘导入")
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("取消",null).setPositiveButton("导入为新键盘",null).create()
        fun setBusy(value:Boolean) {
            busy=value;source.isEnabled=!value;layout.isEnabled=!value && theme!=null;name.isEnabled=!value
            progress.visibility=if(value) View.VISIBLE else View.GONE
            dialog.setCancelable(!value)
            dialog.getButton(-1)?.isEnabled=!value && theme!=null
        }
        fun chooseLayout(id:String) {
            keyboard=id
            val config=runCatching { resolveKeyboardPreset(theme!!.presetKeyboards,id) }.getOrNull()
            layout.text="布局 · ${config?.name?.ifBlank { id } ?: id}（$id）"
            name.setText("${theme!!.name} · ${config?.name?.ifBlank { id } ?: id}".take(80))
        }
        fun load(id:String) {
            job?.cancel();theme=null;error.text="";source.text="正在读取主题…";layout.text="正在读取布局…";setBusy(true)
            job=activity.lifecycleScope.launch {
                try {
                    val (items,result)=withContext(Dispatchers.IO) {
                        ThemeManager.getAllThemes().distinctBy { it.configId }.sortedBy { it.name } to ThemeLoader.loadTheme(id)
                    }
                    themes=items
                    val loaded=when(result) {
                        is ThemeLoader.ThemeLoadResult.Success -> result.theme
                        is ThemeLoader.ThemeLoadResult.Failure -> throw IllegalArgumentException("无法读取主题 $id，请先部署或选择其他主题。${result.error.message.orEmpty().take(120)}")
                    }
                    require(loaded.presetKeyboards.isNotEmpty()) { "该主题没有键盘布局" }
                    theme=loaded;source.text="主题 · ${loaded.name}（$id）"
                    chooseLayout(KeyboardWindow.lastAttachedId.takeIf { it in loaded.presetKeyboards }
                        ?: "default".takeIf { it in loaded.presetKeyboards } ?: loaded.presetKeyboards.keys.first())
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(failure:Exception) { source.text="选择主题";layout.text="无可用布局";error.text=failure.message ?: "主题读取失败" }
                finally { setBusy(false) }
            }
        }
        source.setOnClickListener {
            if(themes.isEmpty()) { load(ThemeManager.prefs.selectedTheme.getValue());return@setOnClickListener }
            MaterialAlertDialogBuilder(activity).setTitle("选择主题")
                .setItems(themes.map { "${it.name}（${it.configId}）" }.toTypedArray()) { _,index -> load(themes[index].configId) }
                .setNegativeButton("取消",null).show()
        }
        layout.setOnClickListener {
            val current=theme ?: return@setOnClickListener
            val ids=current.presetKeyboards.keys.toList()
            MaterialAlertDialogBuilder(activity).setTitle("选择键盘布局")
                .setSingleChoiceItems(ids.map { id ->
                    val title=runCatching { resolveKeyboardPreset(current.presetKeyboards,id)?.name }.getOrNull().orEmpty()
                    if(title.isBlank() || title==id) id else "$title（$id）"
                }.toTypedArray(),ids.indexOf(keyboard)) { picker,index -> chooseLayout(ids[index]);picker.dismiss() }
                .setNegativeButton("取消",null).show()
        }
        val observer=object:DefaultLifecycleObserver { override fun onDestroy(owner:LifecycleOwner) { job?.cancel();dialog.dismiss() } }
        dialog.setOnDismissListener { job?.cancel();activity.lifecycle.removeObserver(observer) }
        activity.lifecycle.addObserver(observer)
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                if(busy) return@setOnClickListener
                val current=theme ?: return@setOnClickListener
                val title=name.text.toString().trim()
                if(title.isEmpty()) { name.error="请输入键盘名称";return@setOnClickListener }
                // Capture theme-mode settings without activating the theme or changing layout mode.
                val appearance=SurfaceAppearance.current(false)
                val unified=SurfaceAppearance.unifiedBackground(false)
                setBusy(true);error.text=""
                job=activity.lifecycleScope.launch {
                    try {
                        val result=withContext(Dispatchers.IO) { ThemeKeyboardImporter(activity.applicationContext).import(current,keyboard,title,appearance,unified) }
                        job=null;dialog.dismiss();completed(result)
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(failure:Exception) { error.text=failure.message ?: "导入失败，请检查素材和存储空间";setBusy(false) }
                }
            }
            load(ThemeManager.prefs.selectedTheme.getValue())
        }
        dialog.show()
    }
}
