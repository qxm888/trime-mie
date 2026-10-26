/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.stats

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Native input statistics engine, a Kotlin port of the well-known
 * `input_statistics.lua` script (github.com/happyDom, 咩版优化).
 *
 * **Per-scheme bookkeeping**: every rime schema gets its own [Stats] bucket
 * (the schema id comes from the rime status on each commit / key event), so
 * switching schemes never mixes their numbers.
 *
 * It tracks typing speed, keystrokes, per-key heat (键盘热力), code length
 * distribution per day / week / month / year, plus a lifetime career level.
 * All data stays on the device in `filesDir/input_stats.json`.
 */
object InputStats {
    @Serializable
    data class HeatKey(val label: String, val x: Float = 0f, val width: Float = 1f, val height: Float = 60f)

    private const val TAG = "InputStats"
    private const val SPEED_LOG_VERSION = 3

    /** A word typed with a pause longer than this finishes a speed session. */
    private const val SESSION_GAP_MS = 5000L

    private const val HEAT_KEYS_LIMIT = 128

    /** 窗口时长（原脚本 10 秒），极速 = 任意累计 ≥ 此值的段窗口最大速率。 */
    private const val SPEED_WINDOW_S = 10.0

    /** 每周期保留的段样本上限（防无限增长，足够十年使用）。 */
    private const val SEGMENTS_LIMIT = 10000

    /** Debounce for disk writes; one write per typing burst is enough. */
    private const val SAVE_DEBOUNCE_MS = 2000L

    @Serializable
    data class Fastest(
        var spd: Double = 0.0,
        var keySpd: Double = 0.0,
        var avgCodeLen: Double = 0.0,
    )

    @Serializable
    data class PeriodStats(
        var count: Int = 0,
        var length: Int = 0,
        var keyTouchCnt: Long = 0,
        var codeLenNoSpace: Long = 0,
        var fastest: Fastest = Fastest(),
        /** Start timestamp (epoch ms) of the period this bucket currently covers. */
        var ts: Long = 0,
        /** Word-length histogram: utf-8 char count -> occurrences. */
        var lengths: MutableMap<String, Int> = mutableMapOf(),
        /** Code-length histogram: keys used per commit -> occurrences. */
        var codeLengths: MutableMap<String, Int> = mutableMapOf(),
        /** Per-key press counts (键盘热力): key label -> presses. */
        var keyCounts: MutableMap<String, Long> = mutableMapOf(),
        /** 段样本（对应原脚本 avgGaps/avgCnts/avgCodeLen）：段时长(秒)、段字数、段码长。 */
        var avgGaps: MutableList<Double> = mutableListOf(),
        var avgCnts: MutableList<Int> = mutableListOf(),
        var avgCodeLens: MutableList<Int> = mutableListOf(),
    ) {
        /** 均速 = Σ段字数 / Σ段时长 × 60（与原脚本 statisticsCal 一致）。 */
        val avgSpd: Double
            get() {
                val gaps = avgGaps.sum()
                if (gaps < 1.0) return 0.0
                return avgCnts.sumOf { it.toLong() } / gaps * 60.0
            }

        val avgSpeedCodeLen: Double
            get() = avgCnts.sumOf { it.toLong() }.let { chars ->
                if (chars > 0) avgCodeLens.sumOf { it.toLong() }.toDouble() / chars else 0.0
            }
    }

    @Serializable
    data class Stats(
        var daily: PeriodStats = PeriodStats(),
        var weekly: PeriodStats = PeriodStats(),
        var monthly: PeriodStats = PeriodStats(),
        var yearly: PeriodStats = PeriodStats(),
        var careerTotal: Long = 0,
        var logVer: Int = SPEED_LOG_VERSION,
    )

    @Serializable
    private data class Store(
        var version: Long = 2,
        /** The scheme the user typed in last; the UI defaults to it. */
        var activeId: String = "",
        var activeName: String = "",
        /** schema id -> stats bucket */
        var schemas: MutableMap<String, Stats> = mutableMapOf(),
        /** schema id -> display name (from rime status) */
        var schemaNames: MutableMap<String, String> = mutableMapOf(),
        /** 最近一次构建的键盘布局（行 → 键标签），热力图按它渲染 */
        var layoutRows: List<List<String>> = emptyList(),
        var layoutGeometry: List<List<HeatKey>> = emptyList(),
    )

    // ---- career tables (ported from the Lua script) ----
    private val careerLevels = arrayOf(
        longArrayOf(1, 0), longArrayOf(2, 100), longArrayOf(3, 300), longArrayOf(4, 600),
        longArrayOf(5, 1000), longArrayOf(6, 2000), longArrayOf(7, 4000), longArrayOf(8, 7000),
        longArrayOf(9, 12000), longArrayOf(10, 20000), longArrayOf(11, 35000), longArrayOf(12, 55000),
        longArrayOf(13, 80000), longArrayOf(14, 120000), longArrayOf(15, 180000), longArrayOf(16, 280000),
        longArrayOf(17, 420000), longArrayOf(18, 620000), longArrayOf(19, 900000), longArrayOf(20, 1300000),
    )
    private val careerTitles = arrayOf(
        "🌱 初识键盘", "🍃 摸键学徒", "🌿 码字新人", "🌾 指尖练习生", "🌼 输入学徒",
        "🌸 码流行者", "🌺 飞键使", "🌻 连击者", "🌷 码阵构筑师", "🌹 熟练输入师",
        "⚔️ 码道武者", "🗡️ 飞码剑士", "🔥 码力掌控者", "🌪️ 疾风键客", "✨ 码道宗师",
        "🌌 星码使", "🌠 破空键圣", "👑 万码之王", "🪽 羽化码仙", "🐉 键道真君",
    )
    private val careerRealms = arrayOf(
        10000000L to "码仙境", 5000000L to "码圣境", 1000000L to "通玄境",
        200000L to "御码境", 50000L to "飞键境", 10000L to "熟键境", 0L to "凡键境",
    )

    data class CareerLevel(
        val title: String,
        val lv: Int,
        val expCur: Long,
        val expMax: Long,
    )

    fun careerLevel(total: Long): CareerLevel {
        val n = careerLevels.size
        for (i in n - 1 downTo 0) {
            if (total >= careerLevels[i][1]) {
                val expStart = careerLevels[i][1]
                val expEnd = if (i < n - 1) careerLevels[i + 1][1] else careerLevels[n - 1][1] + 500000
                return CareerLevel(careerTitles[i], careerLevels[i][0].toInt(), total - expStart, expEnd - expStart)
            }
        }
        return CareerLevel(careerTitles[0], 1, total, careerLevels[1][1])
    }

    fun realm(total: Long): String = careerRealms.firstOrNull { total >= it.first }?.second ?: "凡键境"

    fun attrSpeed(avgSpd: Double): String = when {
        avgSpd >= 80 -> "S+"
        avgSpd >= 60 -> "S"
        avgSpd >= 45 -> "A+"
        avgSpd >= 35 -> "A"
        avgSpd >= 25 -> "B+"
        avgSpd >= 18 -> "B"
        else -> "C"
    }

    fun attrAccuracy(errRatio: Double): String = when {
        errRatio <= 1 -> "S+"
        errRatio <= 3 -> "S"
        errRatio <= 6 -> "A+"
        errRatio <= 10 -> "A"
        errRatio <= 15 -> "B+"
        errRatio <= 22 -> "B"
        else -> "C"
    }

    /** Today's mood line, ported from the Lua `get_input_state`. */
    fun todayState(
        avgSpd: Double,
        errRatio: Double,
        longWordRatio: Double,
        fastestSpd: Double,
    ): String = when {
        avgSpd >= 80 && errRatio <= 5 -> "👑 封神了，请收下我的膝盖"
        fastestSpd >= 100 -> "🚀 速度突破天际"
        avgSpd >= 60 && errRatio <= 5 -> "✨ 键气逼人"
        avgSpd >= 60 -> "🔥 指尖冒烟中"
        longWordRatio >= 40 && avgSpd >= 35 -> "📚 打词达人"
        errRatio <= 3 && avgSpd >= 25 -> "🎯 稳如老码农"
        avgSpd >= 40 && errRatio <= 10 -> "🌸 状态不错哦"
        errRatio >= 20 -> "🫠 手今天不太听话"
        errRatio >= 12 -> "🌀 手有点滑捏"
        avgSpd >= 30 -> "💫 稳步前进中"
        avgSpd >= 18 -> "🌱 慢慢热身中"
        else -> "🐢 今天刚起步~"
    }

    // ---- engine state ----
    private val lock = Any()
    private var store = Store()
    private var appContext: Context? = null

    /** Live speed session. Its schema ownership is independent of the dashboard selection. */
    private var sessionSchemaId = ""
    private var sessionStart = 0L
    private var lastClick = 0L
    private var lastCommit = 0L
    private var sessionChars = 0
    private var sessionCodeLen = 0

    /** Keystrokes counted but not yet merged into a period (merged on commit). */
    private var pendingKeys = 0L

    /** Raw composition at the previous key event, used to detect new keystrokes. */
    private var lastRawInput = ""

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    @Volatile
    private var dirty = false
    private var revision = 0L
    private val saveLock = Any()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            runCatching {
                val file = storeFile()
                file.readAtomicText()?.let { contents ->
                    store = json.decodeFromString(Store.serializer(), contents)
                    if (store.schemas.values.any { it.logVer < SPEED_LOG_VERSION }) {
                        // Old durations cannot be reconstructed. Preserve the original data before
                        // replacing only the invalid speed samples, never character/heat totals.
                        val backup = File(file.parentFile, "input_stats-speed-v2-backup.json")
                        if (!backup.exists()) file.copyTo(backup)
                        store.schemas.values.filter { it.logVer < SPEED_LOG_VERSION }.forEach { stats ->
                            for (period in arrayOf(stats.daily, stats.weekly, stats.monthly, stats.yearly)) {
                                period.avgGaps.clear(); period.avgCnts.clear(); period.avgCodeLens.clear()
                                period.fastest = Fastest()
                            }
                            stats.logVer = SPEED_LOG_VERSION
                        }
                        scheduleSaveLocked()
                    }
                }
            }.onFailure { Timber.w(it, "$TAG: failed to load stats, starting fresh") }
            store.schemas.values.forEach { normalizePeriods(it) }
        }
    }

    fun shutdown() {
        flush()
        ioScope.cancel()
    }

    /** Copy collections directly; serialization never runs while input events wait on the lock. */
    private fun PeriodStats.snapshot(): PeriodStats = copy(fastest = fastest.copy(),
        lengths = lengths.toMutableMap(), codeLengths = codeLengths.toMutableMap(), keyCounts = keyCounts.toMutableMap(),
        avgGaps = avgGaps.toMutableList(), avgCnts = avgCnts.toMutableList(), avgCodeLens = avgCodeLens.toMutableList())

    internal fun Stats.snapshot(): Stats = copy(daily = daily.snapshot(), weekly = weekly.snapshot(),
        monthly = monthly.snapshot(), yearly = yearly.snapshot())

    fun saveSoon() { ioScope.launch { flush() } }

    /** One writer preserves ordering. Only the short snapshot capture holds the input lock. */
    fun flush() = synchronized(saveLock) {
        val checkpoint = synchronized(lock) {
            if (!dirty || appContext == null) return@synchronized null
            val copy = store.copy(schemas = store.schemas.mapValues { it.value.snapshot() }.toMutableMap(),
                schemaNames = store.schemaNames.toMutableMap(),
                layoutRows = store.layoutRows.map { it.toList() }, layoutGeometry = store.layoutGeometry.map { it.toList() })
            if (sessionChars > 0) copy.schemas[sessionSchemaId]?.let { appendSessionSample(it) }
            revision to copy
        } ?: return@synchronized
        runCatching {
            storeFile().writeAtomicText(json.encodeToString(checkpoint.second))
            synchronized(lock) { if (revision == checkpoint.first) dirty = false }
        }.onFailure { Timber.w(it, "$TAG: failed to save stats") }
    }

    private fun storeFile(): File = File(appContext!!.filesDir, "input_stats.json")

    fun <T> read(schemaId: String, block: (Stats) -> T): T {
        val snapshot = synchronized(lock) {
            val stats = bucket(schemaId)
            normalizePeriods(stats)
            stats.snapshot().also { if (schemaId == sessionSchemaId) appendSessionSample(it) }
        }
        return block(snapshot)
    }

    /** 键盘热力图的布局（最近一次在输入法里构建的键盘）。 */
    fun lastLayout(): List<List<String>> = synchronized(lock) { store.layoutRows }

    /** 由 Keyboard 构建完成时调用：记录当前键盘的行列结构。 */
    fun recordLayout(rows: List<List<String>>) {
        if (rows.isEmpty() || rows == store.layoutRows) return
        synchronized(lock) {
            if (rows == store.layoutRows) return
            store.layoutRows = rows
            dirty = true; revision++
            scheduleSaveLocked()
        }
    }

    fun lastLayoutGeometry(): List<List<HeatKey>> = synchronized(lock) {
        store.layoutGeometry.ifEmpty {
            store.layoutRows.map { row -> row.mapIndexed { index, label -> HeatKey(label, index.toFloat(), 1f) } }
        }
    }

    fun recordLayoutGeometry(rows: List<List<HeatKey>>) {
        if (rows.isEmpty()) return
        synchronized(lock) {
            if (store.layoutGeometry == rows) return
            store.layoutGeometry = rows
            store.layoutRows = rows.map { row -> row.map { it.label } }
            dirty = true; revision++
            scheduleSaveLocked()
        }
    }

    /** All schemes that have data, most-recently-used first. */
    fun schemaList(): List<Pair<String, String>> =
        synchronized(lock) {
            val ids = LinkedHashSet<String>()
            if (store.activeId.isNotEmpty()) ids.add(store.activeId)
            ids.addAll(store.schemaNames.keys)
            ids.addAll(store.schemas.keys)
            ids.filter { it != "_" && store.schemas[it] != null }.map { it to (store.schemaNames[it] ?: it) }
        }

    /** The scheme last typed in (also updated by the UI when browsing). */
    var activeSchemaId: String
        get() = synchronized(lock) { store.activeId }
        set(value) {
            synchronized(lock) {
                if (store.activeId == value) return
                store.activeId = value
                dirty = true; revision++
                scheduleSaveLocked()
            }
        }

    // ----------------------------------------------------------------
    // Hooks called from the rime session
    // ----------------------------------------------------------------

    private fun bucket(schemaId: String): Stats =
        store.schemas.getOrPut(schemaId.ifEmpty { "_" }) { Stats() }

    /**
     * Called before a key event is handed to librime. Monotonic time prevents
     * clock adjustments from changing measured typing durations.
     */
    fun onKeyDown(schemaId: String, nowMs: Long = SystemClock.elapsedRealtime()) {
        synchronized(lock) {
            if (appContext == null || schemaId.isEmpty()) return
            switchSchema(schemaId)
            normalizePeriods(bucket(schemaId))
            // Settle the old characters against their OWN elapsed time before starting again.
            // A pause while a composition is unfinished belongs to that composition's time.
            if (sessionStart != 0L && (nowMs < lastClick ||
                    nowMs - lastClick > SESSION_GAP_MS && lastRawInput.isEmpty())) {
                flushSessionLocked()
            }
            if (sessionStart == 0L) sessionStart = nowMs
            lastClick = nowMs
        }
    }

    /**
     * Called after the key was processed. Counts a keystroke when the raw
     * composition grew, mirroring the Lua heuristic (including its
     * first-key-after-escape special cases). [committed] means librime
     * committed on this very key, which resets the composition.
     */
    fun onKeyProcessed(
        after: String,
        committed: Boolean,
    ) {
        synchronized(lock) {
            if (appContext == null) return
            val effectiveAfter = if (committed) "" else after
            val before = lastRawInput
            if (effectiveAfter.isNotEmpty() && effectiveAfter[0] != '/') {
                if (before != effectiveAfter) {
                    val beforeLen = before.length
                    val afterLen = effectiveAfter.length
                    when {
                        afterLen > beforeLen -> pendingKeys++
                        afterLen == 1 -> when {
                            beforeLen == 1 -> pendingKeys++
                            beforeLen > 2 -> pendingKeys++
                            beforeLen == 2 && effectiveAfter[0] != before[0] -> pendingKeys++
                        }
                    }
                }
                lastRawInput = effectiveAfter
            } else {
                lastRawInput = ""
            }
        }
    }

    /** Per-key heat (键盘热力): called on every soft-key press. */
    fun onKeyHeat(keyLabel: String) {
        if (keyLabel.isEmpty()) return
        synchronized(lock) {
            if (appContext == null) return
            val stats = bucket(store.activeId)
            normalizePeriods(stats)
            val counts = stats.daily.keyCounts
            counts[keyLabel] = (counts[keyLabel] ?: 0) + 1
            if (counts.size > HEAT_KEYS_LIMIT) {
                counts.entries.sortedBy { it.value }.take(counts.size - HEAT_KEYS_LIMIT)
                    .forEach { counts.remove(it.key) }
            }
            dirty = true; revision++
        }
    }

    /** Called when librime commits text. [rawInputBefore] is the composition that produced it. */
    fun onCommit(
        text: String,
        rawInputBefore: String?,
        schemaId: String,
        schemaName: String,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ) {
        if (text.isEmpty() || !isAllChinese(text)) return
        if (schemaId.isEmpty()) return
        if (rawInputBefore != null && rawInputBefore.startsWith("/")) return
        val rawLen = rawInputBefore?.length ?: 0
        val chars = text.codePointCount(0, text.length)
        synchronized(lock) {
            if (appContext == null) return
            switchSchema(schemaId)
            if (schemaName.isNotEmpty()) store.schemaNames[schemaId] = schemaName
            val stats = bucket(schemaId)
            normalizePeriods(stats)

            // one extra key (space / enter / tap) triggers the commit
            val codeLen = rawLen + 1
            // A commit with no observed start has no measurable typing duration.
            if (sessionStart != 0L && nowMs >= sessionStart) {
                lastCommit = nowMs
                sessionChars += chars
                sessionCodeLen += codeLen
            }

            for (p in arrayOf(stats.daily, stats.weekly, stats.monthly, stats.yearly)) {
                p.keyTouchCnt += pendingKeys
                p.codeLenNoSpace += rawLen.toLong()
                p.count++
                p.length += chars
                p.lengths[chars.toString()] = (p.lengths[chars.toString()] ?: 0) + 1
                p.codeLengths[codeLen.toString()] = (p.codeLengths[codeLen.toString()] ?: 0) + 1
            }
            pendingKeys = 0
            stats.careerTotal += chars.toLong()
            // the composition is consumed by this commit, like the Lua commit_notifier
            lastRawInput = ""

            scheduleSaveLocked()
        }
    }

    // ----------------------------------------------------------------
    // Maintenance operations for the UI
    // ----------------------------------------------------------------

    fun clearPeriodStats(schemaId: String) {
        synchronized(lock) {
            if (appContext != null && sessionSchemaId == schemaId) flushSessionLocked()
            val stats = bucket(schemaId)
            stats.daily = PeriodStats()
            stats.weekly = PeriodStats()
            stats.monthly = PeriodStats()
            stats.yearly = PeriodStats()
            normalizePeriods(stats)
            scheduleSaveLocked()
        }
    }

    fun clearFastest(schemaId: String) {
        synchronized(lock) {
            if (appContext != null && sessionSchemaId == schemaId) flushSessionLocked()
            val stats = bucket(schemaId)
            for (p in arrayOf(stats.daily, stats.weekly, stats.monthly, stats.yearly)) {
                p.fastest = Fastest()
            }
            scheduleSaveLocked()
        }
    }

    /** Close the current typing session (service destroy) and persist. */
    fun endSession() {
        synchronized(lock) {
            if (appContext == null) return
            flushSessionLocked()
            pendingKeys = 0
            lastRawInput = ""
        }
        saveSoon()
    }

    // ----------------------------------------------------------------
    // internals
    // ----------------------------------------------------------------

    private fun switchSchema(schemaId: String) {
        val id = schemaId.ifEmpty { "_" }
        // Browsing a different scheme in the dashboard changes activeId, not typing ownership.
        if (sessionSchemaId != id) {
            flushSessionLocked()
            sessionSchemaId = id
            lastRawInput = ""
            pendingKeys = 0
        }
        if (store.activeId != id) { store.activeId = id; dirty = true; revision++ }
    }

    /**
     * Roll expired periods over: if the stored timestamp does not match the
     * start of the current day / week (Monday) / month / year, the bucket is
     * reset. This is the native port of the Lua "跨天不更新" fix
     * (`normalizePeriods`): it runs on load, on every read and before every
     * commit, so statistics always roll over at midnight even when the app
     * stayed alive across days.
     */
    private fun normalizePeriods(stats: Stats, nowMs: Long = System.currentTimeMillis()) {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun startOf(date: java.time.LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

        val dayTs = startOf(today)
        val weekTs = startOf(today.with(DayOfWeek.MONDAY))
        val monthTs = startOf(today.withDayOfMonth(1))
        val yearTs = startOf(today.withDayOfYear(1))

        if (stats.daily.ts != dayTs) {
            // Attribute completed text to the previous day before rolling the periods over.
            if (store.schemas[sessionSchemaId] === stats) {
                val unfinishedStart = if (lastRawInput.isNotEmpty()) {
                    if (lastCommit >= sessionStart) lastCommit else sessionStart
                } else 0L
                val previousClick = lastClick
                flushSessionLocked()
                // An unfinished word still keeps its full composing time after midnight.
                if (unfinishedStart > 0L) { sessionStart = unfinishedStart; lastClick = previousClick }
            }
            stats.daily = PeriodStats(ts = dayTs)
        }
        if (stats.weekly.ts != weekTs) {
            stats.weekly = PeriodStats(ts = weekTs)
        }
        if (stats.monthly.ts != monthTs) {
            stats.monthly = PeriodStats(ts = monthTs)
        }
        if (stats.yearly.ts != yearTs) {
            stats.yearly = PeriodStats(ts = yearTs)
        }
    }

    /** Include a completed segment once. The same helper also works on a UI-only copy. */
    private fun appendSessionSample(stats: Stats): Boolean {
        val durS = (lastCommit - sessionStart) / 1000.0
        if (sessionStart == 0L || durS < 1.0 || sessionChars <= 0) return false
        for (period in arrayOf(stats.daily, stats.weekly, stats.monthly, stats.yearly)) {
            period.avgGaps.add(durS)
            period.avgCnts.add(sessionChars)
            period.avgCodeLens.add(sessionCodeLen)
            if (period.avgGaps.size > SEGMENTS_LIMIT) {
                period.avgGaps.removeAt(0)
                period.avgCnts.removeAt(0)
                period.avgCodeLens.removeAt(0)
            }
        }
        updateFastestLocked(stats)
        return true
    }

    /** Finish a segment and clear BOTH its counts and clock. */
    private fun flushSessionLocked() {
        if (sessionSchemaId.isNotEmpty() && appendSessionSample(bucket(sessionSchemaId))) scheduleSaveLocked()
        sessionStart = 0L
        lastClick = 0L
        lastCommit = 0L
        sessionChars = 0
        sessionCodeLen = 0
    }

    /**
     * 极速（原脚本逻辑）：从最近的段往回累计时长，凑满 10 秒的窗口取
     * 字数/时长的最大值。不足10秒不显示极速，不用均速冒充极速。
     */
    private fun updateFastestLocked(stats: Stats) {
        // All old endpoints have already been evaluated. Only the newly appended endpoint
        // can improve the historical maximum; each segment is >= 1s, so this is bounded.
        val gaps = stats.daily.avgGaps
        var timeSum = 0.0
        var charsSum = 0
        var codeLenSum = 0
        for (i in gaps.lastIndex downTo 0) {
            timeSum += gaps[i]
            charsSum += stats.daily.avgCnts[i]
            codeLenSum += stats.daily.avgCodeLens[i]
            if (timeSum >= SPEED_WINDOW_S) break
        }
        if (timeSum < SPEED_WINDOW_S || charsSum <= 0) return
        val speed = charsSum / timeSum * 60.0
        for (period in arrayOf(stats.daily, stats.weekly, stats.monthly, stats.yearly)) {
            if (speed > period.fastest.spd) period.fastest = Fastest(speed, codeLenSum / timeSum, codeLenSum.toDouble() / charsSum)
        }
    }

    private fun scheduleSaveLocked() {
        dirty = true; revision++
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            flush()
        }
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
}
