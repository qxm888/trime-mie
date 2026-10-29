/* SPDX-License-Identifier: GPL-3.0-or-later
 * Model catalog adapted from BryceWG/BiBi-Keyboard PR #134 (Apache-2.0).
 * https://github.com/BryceWG/BiBi-Keyboard/pull/134
 */
package com.osfans.trime.ime.voice.builtin

object TencentModels {
    data class Model(val id: String, val label: String)
    data class Category(val id: String, val label: String, val streaming: Boolean, val models: List<Model>)
    val categories = listOf(
        Category("large_stream_2_0", "大模型 2.0 · 流式", true, listOf(
            Model("16k_zh_en_2.0", "中英语言"),
            Model("Hy-ASR-3.0-preview", "Hy-ASR-3.0-preview"),
        )),
        Category("large_stream_1_0", "大模型 1.0 · 流式", true, listOf(
            Model("16k_zh_en", "中英语言"),
            Model("16k_multi_lang", "多语言"),
            Model("16k_en_large", "英语通用"),
        )),
        Category("standard_stream", "标准版 · 流式", true, listOf(
            Model("16k_zh", "中文通用"),
            Model("16k_zh-TW", "中文繁体"),
            Model("16k_zh_edu", "中文教育"),
            Model("16k_zh_medical", "中文医疗"),
            Model("16k_zh_court", "中文法庭"),
            Model("16k_yue", "粤语"),
            Model("16k_en", "英语通用"),
            Model("16k_en_game", "英语游戏"),
            Model("16k_en_edu", "英语教育"),
            Model("16k_ko", "韩语"),
            Model("16k_ja", "日语"),
            Model("16k_th", "泰语"),
            Model("16k_id", "印尼语"),
            Model("16k_vi", "越南语"),
            Model("16k_ms", "马来语"),
            Model("16k_fil", "菲律宾语"),
            Model("16k_pt", "葡萄牙语"),
            Model("16k_tr", "土耳其语"),
            Model("16k_ar", "阿拉伯语"),
            Model("16k_es", "西班牙语"),
            Model("16k_hi", "印地语"),
            Model("16k_fr", "法语"),
            Model("16k_de", "德语"),
        )),
        Category("large_file", "大模型 · 极速文件", false, listOf(
            Model("16k_zh_en", "中英语言"),
            Model("16k_multi_lang", "多语言"),
        )),
        Category("standard_file", "标准版 · 极速文件", false, listOf(
            Model("16k_zh", "中文通用"),
            Model("16k_zh-PY", "中英粤混合"),
            Model("16k_yue", "粤语"),
            Model("16k_en", "英语通用"),
            Model("16k_ja", "日语"),
            Model("16k_ko", "韩语"),
            Model("16k_vi", "越南语"),
            Model("16k_ms", "马来语"),
            Model("16k_id", "印尼语"),
            Model("16k_fil", "菲律宾语"),
            Model("16k_th", "泰语"),
            Model("16k_pt", "葡萄牙语"),
            Model("16k_tr", "土耳其语"),
            Model("16k_ar", "阿拉伯语"),
            Model("16k_es", "西班牙语"),
            Model("16k_hi", "印地语"),
            Model("16k_fr", "法语"),
            Model("16k_de", "德语"),
        )),
    )
    fun category(id: String) = categories.firstOrNull { it.id == id } ?: categories.first { it.id == "standard_stream" }
    fun model(category: Category, id: String) = category.models.firstOrNull { it.id == id } ?: category.models.first()
}
