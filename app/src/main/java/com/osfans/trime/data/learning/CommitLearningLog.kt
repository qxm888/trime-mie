/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.data.learning

import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * 学习用上屏日志：把符合条件的 commit 追加到私有 JSONL 文件。
 *
 * 只收「词级」样本——一次 commit 恰好消耗全部编码且为 2~4 字纯中文。
 * 这个约束同时挡掉了粘贴、剪贴板确认、整句连打和引导符号（"/"）等污染源：
 * 它们要么不经过引擎 commit，要么编码无法与词条对齐。
 *
 * 文件位于应用私有目录（filesDir/learning），单文件超过 [MAX_LOG_BYTES]
 * 时滚动保留一份历史；密码框与禁用个性化学习的字段一律不采集。
 */
object CommitLearningLog :
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {
    /** 一条词级上屏样本：t=时间戳、w=词条、c=编码、s=方案 id。包内共享给聚合器。 */
    @Serializable
    data class Entry(
        val t: Long,
        val w: String,
        val c: String,
        val s: String,
    )

    private val json = Json
    private val lock = Any()

    @Volatile
    private var editorAllowed = false

    /** 最近一次真实键入上屏的墙钟时间（含未达采集条件的），供后台任务判断空闲。 */
    @Volatile
    var lastCommitAtMs: Long = 0L
        private set

    private fun learningDir(): File {
        val dir = File(com.osfans.trime.util.appContext.filesDir, "learning")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun logFile(): File = File(learningDir(), "learning_log.jsonl")

    /**
     * 与 [com.osfans.trime.data.stats.InputStats.setWordCloudEditor] 同源：
     * 输入框切换时更新「该字段是否允许个性化学习」的判定。
     */
    fun setEditor(info: EditorInfo?) {
        editorAllowed = info != null &&
            com.osfans.trime.data.stats.WordCloudVocabulary.allows(info.inputType, info.imeOptions)
    }

    /**
     * 在引擎 commit 路径上调用（[com.osfans.trime.core.Rime.emitResponse]）。
     * [rawInputBefore] 是本次 commit 前的组合编码，[afterInput] 是 commit 后
     * 剩余编码；两者之差就是词条实际消耗的编码段，用它做词条↔编码对齐。
     * 异步落盘，不阻塞打字。
     */
    fun record(
        text: String,
        rawInputBefore: String?,
        afterInput: String,
        schemaId: String,
    ) {
        lastCommitAtMs = System.currentTimeMillis()
        if (!SmartLearningSettings.learningEnabled) return
        if (!editorAllowed) return
        if (!eligible(text, rawInputBefore, afterInput, schemaId)) return
        val raw = rawInputBefore ?: return
        val consumedCode = raw.take((raw.length - afterInput.length).coerceAtLeast(0))
        val entry = Entry(System.currentTimeMillis(), text, consumedCode, schemaId)
        launch {
            try {
                synchronized(lock) { append(entry) }
            } catch (e: Exception) {
                Timber.w(e, "CommitLearningLog append failed")
            }
        }
    }

    /** 词条观察计数与最近编码的快照读出，供聚合器与设置页摘要使用。 */
    fun file(): File = logFile()

    fun sizeBytes(): Long = logFile().takeIf { it.isFile }?.length() ?: 0L

    /** 用户在近一分钟内还在打字时，后台任务应推迟 deploy。 */
    fun isRecentlyCommitted(): Boolean =
        System.currentTimeMillis() - lastCommitAtMs < RECENT_COMMIT_WINDOW_MS

    fun clear() {
        synchronized(lock) {
            logFile().delete()
            File(learningDir(), "learning_log.jsonl.1").delete()
        }
    }

    private fun append(entry: Entry) {
        val file = logFile()
        if (file.length() > MAX_LOG_BYTES) {
            val previous = File(learningDir(), "learning_log.jsonl.1")
            previous.delete()
            file.renameTo(previous)
        }
        file.appendText(json.encodeToString(entry) + "\n", Charsets.UTF_8)
    }

    private fun eligible(
        text: String,
        rawInputBefore: String?,
        afterInput: String,
        schemaId: String,
    ): Boolean {
        if (schemaId.isEmpty()) return false
        val raw = rawInputBefore?.takeIf { it.isNotEmpty() } ?: return false
        if (raw.startsWith("/")) return false
        val chars = text.codePointCount(0, text.length)
        if (chars < MIN_CHARS || chars > MAX_CHARS) return false
        if (!isAllChinese(text)) return false
        // 本次 commit 消耗的编码段 = 编码总长 − 剩余段；逐词上屏的中间
        // commit 也带剩余编码，按消耗段对齐即可，无需等组合清空。
        val consumed = raw.length - afterInput.length
        if (consumed <= 0 || consumed > raw.length) return false
        val consumedCode = raw.take(consumed)
        // 编码必须全为小写字母（全拼类方案）；注音等其它编码形式后续版本再支持。
        if (!consumedCode.all { it in 'a'..'z' }) return false
        // 每字 2~6 码的全拼合理区间，过短多为简拼样本，对齐不可靠。
        val perChar = consumedCode.length.toFloat() / chars
        if (perChar < 2f || perChar > 6f) return false
        return true
    }

    private fun isAllChinese(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            if (!isChineseCodePoint(c)) return false
            i += Character.charCount(c)
        }
        return text.isNotEmpty()
    }

    private fun isChineseCodePoint(c: Int): Boolean = c in 0x4E00..0x9FFF ||
        c in 0x3400..0x4DBF ||
        c in 0x20000..0x2A6DF ||
        c in 0x2A700..0x2CEAF ||
        c in 0x2CEB0..0x2EBEF ||
        c in 0x30000..0x323AF ||
        c in 0xF900..0xFADF

    private const val MIN_CHARS = 2
    private const val MAX_CHARS = 4
    private const val MAX_LOG_BYTES = 2 * 1024 * 1024
    private const val RECENT_COMMIT_WINDOW_MS = 60_000L
}
