/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.ai

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.AiTextSettingsActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Processes only an explicit selection. Never reads the clipboard or surrounding chat. */
class AiTextController(private val service: TrimeInputMethodService) {
    private var job: Job? = null
    private var dialog: Dialog? = null
    private var generation = 0L
    fun cancel() {
        generation++
        job?.cancel(); job = null
        val old = dialog; dialog = null; old?.dismiss()
    }
    fun show(translate: Boolean) {
        cancel()
        AiTextSettings.init(service.applicationContext)
        val input = service.currentInputConnection ?: return
        val editor = service.currentInputEditorInfo ?: return
        if (isSensitiveInput(editor.inputType)) { toast("密码框不支持 AI 处理"); return }
        val original = input.getSelectedText(0)?.toString().orEmpty()
        if (original.isBlank() || original.length > TextProtocol.MAX_INPUT) {
            service.showDialog(AlertDialog.Builder(service).setTitle(if (translate) "翻译" else "AI 润色")
                .setMessage("请先在输入框选中要处理的文字（最多 12000 字），再打开此功能。")
                .setPositiveButton("知道了", null).setNeutralButton("服务设置") { _, _ -> settings() }.create())
            return
        }
        val token = generation
        val revision = service.textSelectionRevision
        val packageName = editor.packageName
        val config = AiTextSettings.snapshot()
        fun valid() = token == generation && service.currentInputConnection === input &&
            service.currentInputEditorInfo?.packageName == packageName && service.textSelectionRevision == revision &&
            input.getSelectedText(0)?.toString() == original
        val progress = AlertDialog.Builder(service).setTitle(if (translate) "正在翻译" else "正在润色")
            .setMessage("仅处理选中文字，可随时取消。")
            .setNegativeButton("取消") { _, _ -> cancel() }.create()
        dialog = progress
        service.showDialog(progress, onDismiss = { if (dialog === progress) cancel() })
        job = service.lifecycleScope.launch {
            try {
                val result = if (translate) {
                    TextTranslations.translate(config, original)
                } else AiTextService.process(config.model, config.prompt, original)
                if (!valid()) { cancel(); toast("输入位置已变化，请重新选择文字"); return@launch }
                dialog = null; progress.dismiss()
                val view = TextView(service).apply {
                    text = result; textSize = 18f; setTextIsSelectable(true)
                    val pad = (20 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
                }
                val preview = AlertDialog.Builder(service).setTitle(if (translate) "翻译结果" else "润色结果")
                    .setView(ScrollView(service).apply { addView(view) })
                    .setNegativeButton("取消", null).setPositiveButton("替换选中文字") { _, _ ->
                        if (valid()) service.commitText(result) else toast("输入位置已变化，未替换文字")
                    }.create()
                dialog = preview
                service.showDialog(preview, onDismiss = { if (dialog === preview) cancel() })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (token != generation) return@launch
                cancel(); toast("处理失败，原文保留。${e.message.orEmpty().take(80)}")
            }
        }
    }
    private fun toast(text: String) = Toast.makeText(service, text, Toast.LENGTH_LONG).show()
    private fun settings() = service.startActivity(Intent(service, AiTextSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    companion object {
        fun isSensitiveInput(type: Int): Boolean {
            val cls = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            return cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ||
                cls == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        }
    }
}
