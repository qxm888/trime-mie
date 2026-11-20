/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.ime.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class AiTextSettingsActivity : PersonalizationActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiTextSettings.init(applicationContext)
        page("AI 服务 · 润色 / 翻译 / 主题助手"); render()
    }
    private fun toggle(title: String, key: String) {
        body.addView(MaterialSwitch(this).apply {
            text = title; isChecked = AiTextSettings.flag(key)
            setOnCheckedChangeListener { _, value -> AiTextSettings.setFlag(key, value) }
        })
    }
    private fun edit(title: String, value: String, secret: Boolean = false, save: (String) -> Unit) {
        button(title + if (secret) if (value.isBlank()) "：未设置" else "：已设置" else "：$value") {
            val input = EditText(this).apply {
                inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setText(value); setSelectAllOnFocus(true); maxLines = if (secret) 2 else 8
            }
            MaterialAlertDialogBuilder(this).setTitle(title).setView(input)
                .setPositiveButton("保存") { _, _ -> save(input.text.toString().trim()); render() }
                .setNegativeButton("取消", null).show()
        }
    }
    private fun choose(title: String, choices: List<Pair<String, String>>, current: String, save: (String) -> Unit) {
        button("$title：${choices.firstOrNull { it.first == current }?.second ?: current}") {
            MaterialAlertDialogBuilder(this).setTitle(title)
                .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), choices.indexOfFirst { it.first == current }) { dialog, index ->
                    save(choices[index].first); dialog.dismiss(); render()
                }.setNegativeButton("取消", null).show()
        }
    }
    private fun render() {
        body.removeAllViews()
        note("键盘功能菜单 → AI 润色 / 翻译：先选中文字，处理后预览，确认才替换。也可在按键或工具栏编辑中添加这些功能。")
        note("AI 主题助手（键盘编辑 → AI 主题助手 / 主题设置 → AI 主题助手）共用这里的服务商、模型与 API Key。主题助手只发送当前外观参数与你的需求，不会上传按键内容。")
        toggle("语音识别后自动润色", "voice_polish")
        toggle("语音识别后自动翻译", "voice_translate")
        note("两项均开启时先润色再翻译。默认关闭。开启后，本次语音识别文字会发送到所选服务；手动处理只发送你选中的文字。不要在密码框使用。")
        choose("AI 服务商", TextProviders.all.map { it.id to it.label }, AiTextSettings.providerId) { AiTextSettings.providerId = it }
        val provider = AiTextSettings.providerId
        edit("API Key", AiTextSettings.key(), secret = true) { AiTextSettings.set("$provider.api_key", it) }
        edit("兼容接口基地址 / chat/completions 地址", AiTextSettings.endpoint()) { AiTextSettings.set("$provider.endpoint", it) }
        if (TextProviders.byId(provider).models.isNotEmpty()) choose("预设模型", TextProviders.byId(provider).models.map { it to it }, AiTextSettings.model()) { AiTextSettings.set("$provider.model", it) }
        edit("模型名（可自定义）", AiTextSettings.model()) { AiTextSettings.set("$provider.model", it) }
        choose("润色方式", TextPrompts.presets.toList(), AiTextSettings.string("prompt", "clean")) { AiTextSettings.set("prompt", it) }
        if (AiTextSettings.string("prompt", "clean") == "custom") {
            edit("自定义提示词", AiTextSettings.string("custom_prompt")) { AiTextSettings.set("custom_prompt", it) }
        }
        choose("翻译服务", TranslationLanguages.vendors(), AiTextSettings.translationVendor()) {
            AiTextSettings.set("translation_vendor", it)
            if (it == "foxlet" && AiTextSettings.source() == "auto") AiTextSettings.set("source", "zh-Hans")
            if (it != "foxlet") {
                if (AiTextSettings.source() !in TranslationLanguages.codes) AiTextSettings.set("source", "auto")
                if (AiTextSettings.target() !in TranslationLanguages.codes) AiTextSettings.set("target", "en")
            }
        }
        val offline = AiTextSettings.translationVendor() == "foxlet"
        val models = if (offline) OfflineTranslationCatalog.models(this) else emptyList()
        fun languageName(tag: String) = java.util.Locale.forLanguageTag(tag).getDisplayName(java.util.Locale.SIMPLIFIED_CHINESE)
        val sources = if (offline) models.map { it.source }.distinct().sorted().map { it to languageName(it) } else TranslationLanguages.all.toList()
        val targets = if (offline) models.map { it.target }.distinct().sorted()
            .filter { OfflineTranslationCatalog.route(models, AiTextSettings.source(), it).isNotEmpty() }.map { it to languageName(it) }
            else TranslationLanguages.all.filterKeys { it != "auto" }.toList()
        choose("原文语言", sources, AiTextSettings.source()) { AiTextSettings.set("source", it) }
        choose("目标语言", targets, AiTextSettings.target()) { AiTextSettings.set("target", it) }
        if (offline) button("管理离线翻译语言包") { startActivity(android.content.Intent(this, TranslationPacksActivity::class.java)) }
        note("在线翻译接口可能限流或变更，失败可切换服务。AI 模型使用自己的 Key，可能产生服务商费用。所有接口必须使用 HTTPS。")
        button("测试 AI 润色") { test(false) }
        button("测试翻译") { test(true) }
    }
    private fun test(translate: Boolean) {
        val input = EditText(this).apply { setText("你好，很高兴认识你。"); maxLines = 5 }
        MaterialAlertDialogBuilder(this).setTitle("输入测试文字").setView(input)
            .setMessage("仅把下面的测试文字发送给所选服务。")
            .setNegativeButton("取消", null).setPositiveButton("发送测试") { _, _ ->
                val progress = MaterialAlertDialogBuilder(this).setMessage("处理中…").setNegativeButton("取消", null).create()
                val text = input.text.toString()
                val config = AiTextSettings.snapshot()
                val job = lifecycleScope.launch {
                    try {
                        val result = if (translate) {
                            TextTranslations.translate(config, text)
                        } else AiTextService.process(config.model, config.prompt, text)
                        progress.dismiss()
                        MaterialAlertDialogBuilder(this@AiTextSettingsActivity).setTitle("测试结果").setMessage(result).setPositiveButton("关闭", null).show()
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) {
                        progress.dismiss(); Toast.makeText(this@AiTextSettingsActivity, "测试失败：${e.message.orEmpty().take(100)}", Toast.LENGTH_LONG).show()
                    }
                }
                progress.setOnCancelListener { job.cancel() }
                progress.setOnDismissListener { job.cancel() }
                progress.show()
            }.show()
    }
}
