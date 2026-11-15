/* SPDX-License-Identifier: GPL-3.0-or-later
 * Provider choices adapted from BiBi-Keyboard (Apache-2.0).
 */
package com.osfans.trime.ime.ai

enum class TranslationVendor(val id: String, val label: String) {
    Microsoft("microsoft", "微软"), Tencent("tencent", "腾讯交互翻译"),
    Volcengine("volcengine", "火山翻译"), Bilibili("bilibili", "哔哩哔哩 Index"), Google("google", "Google"),
}
object TranslationLanguages {
    val all = linkedMapOf("auto" to "自动识别", "zh-Hans" to "简体中文", "zh-Hant" to "繁体中文",
        "en" to "英语", "ja" to "日语", "ko" to "韩语", "fr" to "法语", "de" to "德语",
        "es" to "西班牙语", "ru" to "俄语", "pt" to "葡萄牙语", "it" to "意大利语", "ar" to "阿拉伯语")
    val codes get() = all.keys
    fun name(code: String) = all[code] ?: error("不支持的语言")
    fun vendors() = TranslationVendor.entries.map { it.id to it.label } + listOf("ai" to "AI 翻译（使用润色服务）", "foxlet" to "Foxlet 离线翻译")
}
