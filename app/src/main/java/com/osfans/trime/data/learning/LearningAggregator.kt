/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.data.learning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 词库自学习的批处理聚合器：消费 [CommitLearningLog] 的日志，产出三样东西——
 *
 * 1. 词频表（learning_freq.json）：词 → 累计观察次数 + 编码众数；
 * 2. Rime 用户词典导入：达到阈值的词按「词条\t编码\t频次」经
 *    [com.osfans.trime.data.userdict.UserDictManager.importUserDict] 写入
 *    userdb——已有词条只会调到更高频（导入合并语义为取 max，幂等），
 *    词典里没有的词就此成为新词；
 * 3. 分词语料（learning_corpus.txt）：按会话把连续上屏的词以空格连接，
 *    保留词边界，作为后续构建整句搭配模型的原料。
 *
 * 全部数据保存在应用私有目录 filesDir/learning，一次 [runOnce] 是原子轮次：
 * 聚合成功才删除已消费的日志，失败则下次重跑同样内容。
 */
object LearningAggregator {
    @Serializable
    private data class WordStat(
        val n: Int = 0,
        val s: String = "",
        val codes: Map<String, Int> = emptyMap(),
    )

    data class Result(
        val entries: Int,
        val corpusLines: Int,
        val imported: Int,
        val newWords: Int,
        val detail: String = "",
    )

    private val json = Json

    private fun learningDir(): File {
        val dir = File(com.osfans.trime.util.appContext.filesDir, "learning")
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    private fun freqFile(): File = File(learningDir(), "learning_freq.json")

    private fun corpusFile(): File = File(learningDir(), "learning_corpus.txt")

    private fun bigramFile(): File = File(learningDir(), "learning_bigram.json")

    /** 设置页摘要用：当前词频表的词条总数。 */
    fun wordCount(): Int {
        val table = readFreqTable() ?: return 0
        return table.size
    }

    fun corpusSizeBytes(): Long = corpusFile().takeIf { it.isFile }?.length() ?: 0L

    fun clear() {
        freqFile().delete()
        corpusFile().delete()
        bigramFile().delete()
        synchronized(bigramLock) { bigramCache = emptyMap() }
        SmartLearningSettings.lastLearningTime = 0L
        SmartLearningSettings.lastImportedCount = 0
        SmartLearningSettings.learnedWordCount = 0
    }

    // ------------------------------------------------------------------
    // 学习词管理（P2）
    // ------------------------------------------------------------------

    /** 学习词管理列表：达到阈值的词条（词、编码、累计次数），按次数降序。 */
    data class LearnedWord(val word: String, val code: String, val count: Int)

    fun learnedWords(): List<LearnedWord> {
        val threshold = SmartLearningSettings.newWordThreshold
        return readFreqTable().orEmpty()
            .mapNotNull { (word, stat) ->
                val code = stat.codes.maxByOrNull { it.value }?.key.orEmpty()
                if (stat.n >= threshold && code.isNotEmpty()) LearnedWord(word, code, stat.n) else null
            }
            .sortedByDescending { it.count }
    }

    /**
     * 删除一个已学词条：移出词频表（不再参与后续调频），并按落点清理——
     * 有用户词典的向其导入负频次（librime 把 commits < 0 标记为删除），
     * 走应用内造词的则从候选投影移除。
     */
    suspend fun removeLearnedWord(word: String): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val table = readFreqTable() ?: return@runCatching
            val stat = table.remove(word) ?: return@runCatching
            val code = stat.codes.maxByOrNull { it.value }?.key.orEmpty()
            check(code.isNotEmpty()) { "该词条没有可用编码" }
            val dictName = resolveDictName(stat.s)
            if (dictName.isNotEmpty()) {
                val tsv = "$word\t$code\t-1\n"
                com.osfans.trime.data.userdict.UserDictManager.importUserDict(
                    ByteArrayInputStream(tsv.toByteArray(Charsets.UTF_8)),
                    dictName,
                    "learning_delete.tsv",
                ).getOrThrow()
            }
            // 应用内造词的清理；词典路径的词没有造词记录，此处自然为 no-op。
            runCatching {
                com.osfans.trime.core.data.CandidatePreferences.editCreatedWord(
                    com.osfans.trime.core.data.CandidatePreferences.CreatedWord(stat.s, code, word),
                    null,
                )
            }
            writeFreqTable(table)
            SmartLearningSettings.learnedWordCount = table.values.count { it.n >= SmartLearningSettings.newWordThreshold }
        }
    }

    // ------------------------------------------------------------------
    // 下词预测（P2）：相邻词对 bigram
    // ------------------------------------------------------------------

    private val bigramLock = Any()

    @Volatile
    private var bigramCache: Map<String, List<Pair<String, Int>>> = emptyMap()

    /**
     * 预测下一个词：返回该词之后最常出现的前 [limit] 个后继词。
     * 数据由每轮聚合从「同一会话内相邻上屏的词」统计而来，纯内存查询。
     */
    fun predictNext(word: String, limit: Int = 3): List<String> {
        val cache = bigramCache
        val candidates = cache[word] ?: return emptyList()
        return candidates.take(limit).map { it.first }
    }

    private fun readBigram(): MutableMap<String, MutableMap<String, Int>> {
        val file = bigramFile()
        if (!file.isFile) return mutableMapOf()
        return try {
            val raw = json.decodeFromString<Map<String, Map<String, Int>>>(file.readText(Charsets.UTF_8))
            raw.mapValuesTo(mutableMapOf()) { (_, inner) -> inner.toMutableMap() }
        } catch (e: Exception) {
            Timber.w(e, "Reset corrupted learning bigram table")
            mutableMapOf()
        }
    }

    private fun refreshBigramCache(table: Map<String, MutableMap<String, Int>>) {
        val ranked = table.entries.associate { (first, inner) ->
            first to inner.entries.sortedByDescending { it.value }.take(BIGRAM_SUFFIX_LIMIT).map { it.key to it.value }
        }
        synchronized(bigramLock) { bigramCache = ranked }
    }

    /**
     * 执行一轮聚合。返回 null 表示没有待处理数据。
     * 调用方负责在合适的时机触发 Rime deploy（userdb 导入需要重载才生效）。
     */
    suspend fun runOnce(): Result? = withContext(Dispatchers.IO) {
        val entries = readLogEntries()
        if (entries.isEmpty()) return@withContext null

        val sessions = splitSessions(entries)
        val corpusLines = appendCorpus(sessions)
        val table = mergeIntoFreqTable(entries)
        val bigramWords = updateBigram(sessions)
        val (imported, newWords, detail) = importLearnedWords(table)
        writeFreqTable(table)
        refreshBigramCache(readBigram())

        // 日志已全部安全消费（freq/语料/导入三步都成功），清空日志文件。
        CommitLearningLog.clear()

        SmartLearningSettings.lastLearningTime = System.currentTimeMillis()
        SmartLearningSettings.lastImportedCount = imported
        SmartLearningSettings.lastLearningDetail = detail
        SmartLearningSettings.learnedWordCount = table.values.count { it.n >= SmartLearningSettings.newWordThreshold }
        Timber.i(
            "Learning aggregate: %d entries, %d corpus lines, %d imported (%d new), %d bigram heads; %s",
            entries.size, corpusLines, imported, newWords, bigramWords, detail,
        )
        Result(entries.size, corpusLines, imported, newWords, detail)
    }

    private fun readLogEntries(): List<CommitLearningLog.Entry> {
        val list = mutableListOf<CommitLearningLog.Entry>()
        listOf(logFile(), File(learningDir(), "learning_log.jsonl.1")).forEach { file ->
            if (!file.isFile) return@forEach
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        list.add(json.decodeFromString<CommitLearningLog.Entry>(line))
                    } catch (e: Exception) {
                        Timber.w("Skip malformed learning log line")
                    }
                }
            }
        }
        return list
    }

    private fun logFile(): File = File(learningDir(), "learning_log.jsonl")

    /**
     * 会话切分：间隔超过 [SESSION_GAP_MS] 或切换方案就另起一段。
     * 语料切行与 bigram 统计共用同一会话边界。
     */
    private fun splitSessions(entries: List<CommitLearningLog.Entry>): List<List<CommitLearningLog.Entry>> {
        val sessions = mutableListOf<List<CommitLearningLog.Entry>>()
        var current = mutableListOf<CommitLearningLog.Entry>()
        var lastTime = 0L
        var lastSchema = ""
        entries.forEach { entry ->
            val newSession = lastTime != 0L &&
                (entry.t - lastTime > SESSION_GAP_MS || entry.s != lastSchema)
            if (newSession && current.isNotEmpty()) {
                sessions.add(current); current = mutableListOf()
            }
            current.add(entry)
            lastTime = entry.t
            lastSchema = entry.s
        }
        if (current.isNotEmpty()) sessions.add(current)
        return sessions
    }

    /**
     * 语料切行：每个会话一行，词与词之间以空格连接，
     * 保留用户上屏时的自然分词边界。
     */
    private fun appendCorpus(sessions: List<List<CommitLearningLog.Entry>>): Int {
        if (!SmartLearningSettings.corpusEnabled) return 0
        val lines = sessions
            .filter { it.isNotEmpty() }
            .map { session -> session.joinToString(" ") { it.w } }
        if (lines.isEmpty()) return 0

        val file = corpusFile()
        file.appendText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        if (file.length() > MAX_CORPUS_BYTES) {
            // 超限时保留最近的语料，头部截断。
            val text = file.readText(Charsets.UTF_8)
            val kept = text.substring(text.length - MAX_CORPUS_BYTES).substringAfter('\n', "")
            file.writeText(kept, Charsets.UTF_8)
        }
        return lines.size
    }

    /**
     * bigram 统计：同一会话内相邻上屏的词对（前词 → 后词 → 次数）。
     * 每轮全量合并；前词条目超过上限时按总计数淘汰最冷门的前词。
     */
    private fun updateBigram(sessions: List<List<CommitLearningLog.Entry>>): Int {
        val table = readBigram()
        sessions.forEach { session ->
            var previous: String? = null
            session.forEach { entry ->
                val current = entry.w
                val first = previous
                if (first != null && first != current) {
                    val inner = table.getOrPut(first) { mutableMapOf() }
                    inner[current] = (inner[current] ?: 0) + 1
                }
                previous = current
            }
        }
        if (table.size > BIGRAM_LIMIT) {
            table.entries
                .sortedByDescending { (_, inner) -> inner.values.sum() }
                .take(BIGRAM_LIMIT)
                .associateTo(mutableMapOf()) { it.toPair() }
                .let { table.clear(); table.putAll(it) }
        }
        val size = table.size
        if (size > 0) bigramFile().writeText(json.encodeToString(table), Charsets.UTF_8)
        return size
    }

    private fun readFreqTable(): MutableMap<String, WordStat>? {
        val file = freqFile()
        if (!file.isFile) return null
        return try {
            val raw = json.decodeFromString<Map<String, WordStat>>(file.readText(Charsets.UTF_8))
            raw.toMutableMap()
        } catch (e: Exception) {
            Timber.w(e, "Reset corrupted learning freq table")
            null
        }
    }

    private fun writeFreqTable(table: Map<String, WordStat>) {
        freqFile().writeText(json.encodeToString(table), Charsets.UTF_8)
    }

    private fun mergeIntoFreqTable(entries: List<CommitLearningLog.Entry>): MutableMap<String, WordStat> {
        val table = readFreqTable() ?: mutableMapOf()
        entries.forEach { entry ->
            val old = table[entry.w] ?: WordStat()
            table[entry.w] = old.copy(
                n = old.n + 1,
                s = entry.s,
                codes = old.codes.toMutableMap().apply {
                    this[entry.c] = (this[entry.c] ?: 0) + 1
                },
            )
        }
        if (table.size > FREQ_LIMIT) {
            table.entries.sortedByDescending { it.value.n }.take(FREQ_LIMIT)
                .associateTo(mutableMapOf()) { it.toPair() }
                .let { table.clear(); table.putAll(it) }
        }
        return table
    }

    /**
     * 达到阈值的词按方案分组导入各自的 Rime 用户词典。
     * 编码取观察众数——同一词出现过的不同编码里最高频的那个。
     * 方案没有用户词典（translator 关了 enable_user_dict）时退回应用内造词：
     * 走候选投影置顶，同样达成「自动组词 + 排序」的效果。
     * [detail] 汇总每一步结果，供设置页弹窗直接展示诊断。
     */
    private fun importLearnedWords(table: Map<String, WordStat>): Triple<Int, Int, String> {
        if (!SmartLearningSettings.newWordEnabled) return Triple(0, 0, "新词自动入库未开启")
        val threshold = SmartLearningSettings.newWordThreshold
        val bySchema = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val details = mutableListOf<String>()
        var imported = 0
        var newWords = 0
        var inAppCreated = 0
        table.entries
            .filter { it.value.n >= threshold }
            .forEach { (word, stat) ->
                val code = stat.codes.maxByOrNull { it.value }?.key.orEmpty()
                if (code.isEmpty()) {
                    details.add("「$word」无编码")
                    return@forEach
                }
                val dictName = resolveDictName(stat.s)
                if (dictName.isNotEmpty()) {
                    bySchema.getOrPut(dictName) { mutableListOf() }.add(word to code)
                    return@forEach
                }
                // 应用内造词兜底：写入候选投影（pinned 置顶），不依赖方案 userdb。
                val created = runCatching {
                    com.osfans.trime.core.data.CandidatePreferences.createWord(stat.s, code, word)
                }.onFailure { Timber.w(it, "In-app created word failed for '%s'", word) }
                    .isSuccess
                if (created) {
                    inAppCreated++
                    newWords++
                    details.add("「$word」已按应用内造词置顶（方案 ${stat.s} 无用户词典）")
                } else {
                    details.add("「$word」应用内造词失败（方案 ${stat.s}）")
                }
            }
        bySchema.forEach { (dictName, words) ->
            val tsv = words.joinToString("\n") { (word, code) ->
                val freq = table[word]?.n ?: threshold
                "$word\t$code\t$freq"
            } + "\n"
            val result = com.osfans.trime.data.userdict.UserDictManager.importUserDict(
                ByteArrayInputStream(tsv.toByteArray(Charsets.UTF_8)),
                dictName,
                "learning_import.tsv",
            )
            result.onSuccess { count ->
                imported += count
                newWords += words.size
                details.add("词典 $dictName 导入 $count 条")
                Timber.i("Learning import: %d entries into '%s'", count, dictName)
            }.onFailure {
                details.add("词典 $dictName 导入失败：${it.message.orEmpty().take(60)}")
                Timber.w(it, "Learning import into '%s' failed", dictName)
            }
        }
        if (inAppCreated > 0) Timber.i("Learning fallback: %d in-app created words", inAppCreated)
        if (details.isEmpty()) details.add("无达到阈值的词条")
        return Triple(imported, newWords, details.joinToString("；"))
    }

    /**
     * 由词条观察时的方案 id 推导用户词典名：userdb 名可能是方案 id 本身、
     * 或带「.userdb」「.tabledb」等 db 类型后缀（如 fyunzhju.tabledb）。
     * 找不到时返回空串，由调用方走应用内造词兜底。
     */
    private fun resolveDictName(schemaId: String): String {
        val dicts = runCatching {
            com.osfans.trime.data.userdict.UserDictManager.getUserDictList()
        }.getOrDefault(emptyArray())
        if (dicts.isEmpty()) return ""
        dicts.firstOrNull { it == schemaId }
            ?: dicts.firstOrNull { it.removeSuffix(".userdb").removeSuffix(".tabledb") == schemaId }
            ?: dicts.firstOrNull { it.startsWith("$schemaId.") }
            ?.let { return it }
        return ""
    }

    private const val SESSION_GAP_MS = 5000L
    private const val FREQ_LIMIT = 10000
    private const val MAX_CORPUS_BYTES = 1500 * 1024
    private const val BIGRAM_LIMIT = 5000
    private const val BIGRAM_SUFFIX_LIMIT = 20
}
