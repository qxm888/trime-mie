/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.data.learning

import android.content.Context
import android.content.SharedPreferences

/**
 * 词库自学习（第 1 层：统计学习闭环）的开关与状态。
 *
 * 学习完全在本地进行：上屏日志、词频表、分词语料都保存在应用私有目录，
 * 不联网、不上传；总开关默认关闭。开关状态与学习产物分离——
 * 这里只存偏好，数据文件由 [CommitLearningLog] 与 [LearningAggregator] 管理。
 */
object SmartLearningSettings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = com.osfans.trime.util.storageContext(context)
            .getSharedPreferences("smart_learning", Context.MODE_PRIVATE)
    }

    private fun flag(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    private fun setFlag(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    private fun long(key: String, default: Long = 0L): Long =
        prefs.getLong(key, default)

    private fun setLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    private fun int(key: String, default: Int = 0): Int =
        prefs.getInt(key, default)

    private fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    private fun string(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    private fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** 学习总开关：关闭时不采集、不聚合、不写词库。 */
    var learningEnabled: Boolean
        get() = flag("learning_enabled")
        set(value) = setFlag("learning_enabled", value)

    /** 分词语料积累：把连续上屏按词边界保存，供后续构建整句搭配模型。 */
    var corpusEnabled: Boolean
        get() = flag("corpus_enabled")
        set(value) = setFlag("corpus_enabled", value)

    /** 新词自动入库：高频连续组合写入 Rime 用户词典（同时完成调频）。 */
    var newWordEnabled: Boolean
        get() = flag("new_word_enabled", true)
        set(value) = setFlag("new_word_enabled", value)

    /**
     * 智能整句（octagram 八股文搭配模型，实验性）：给启用方案写
     * contextual_suggestions 与 grammar 配置，长句输入的组句排序更准。
     */
    var smartSentenceEnabled: Boolean
        get() = flag("smart_sentence_enabled")
        set(value) = setFlag("smart_sentence_enabled", value)

    /** 下词联想：根据用户 bigram 习惯，上屏一个词后预测下一个词。 */
    var wordPredictionEnabled: Boolean
        get() = flag("word_prediction_enabled")
        set(value) = setFlag("word_prediction_enabled", value)

    /** 当前使用的语言模型 id（.gram 文件名去后缀），默认打包的小模型。 */
    var grammarLanguage: String
        get() = string("grammar_language", DEFAULT_GRAMMAR_LANGUAGE)
        set(value) = setString("grammar_language", value)

    /** 新词入库的频次阈值（同一词累计观察次数）。 */
    var newWordThreshold: Int
        get() = int("new_word_threshold", 5)
        set(value) = setInt("new_word_threshold", value)

    /** 上次批处理完成时间（epoch ms）。 */
    var lastLearningTime: Long
        get() = long("last_learning_time")
        set(value) = setLong("last_learning_time", value)

    /** 上次批处理导入的用户词典词条数。 */
    var lastImportedCount: Int
        get() = int("last_imported_count")
        set(value) = setInt("last_imported_count", value)

    /** 上次批处理的详细诊断（词典匹配/导入结果），设置页摘要直接展示。 */
    var lastLearningDetail: String
        get() = string("last_learning_detail")
        set(value) = setString("last_learning_detail", value)

    /** 学习词表当前词条总数（由聚合器刷新，用于设置页摘要展示）。 */
    var learnedWordCount: Int
        get() = int("learned_word_count")
        set(value) = setInt("learned_word_count", value)

    /** APK 内置的官方简化字小模型（lotem/rime-octagram-data，hans 分支）。 */
    const val DEFAULT_GRAMMAR_LANGUAGE = "zh-hans-t-essay-bgc"
}
