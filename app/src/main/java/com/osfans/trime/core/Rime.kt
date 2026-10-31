/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.core

import com.osfans.trime.BuildConfig
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.ime.core.InlinePreeditMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.minutes

/**
 * Rime JNI and instance methods
 *
 * @see [librime](https://github.com/rime/librime)
 */
class Rime :
    RimeApi,
    RimeLifecycleOwner {
    private val lifecycleRegistry = RimeLifecycleRegistry()
    override val lifecycle get() = lifecycleRegistry

    override val messageFlow = messageFlow_.asSharedFlow()

    override val isReady: Boolean
        get() = lifecycle.currentState == RimeLifecycle.State.READY

    override var schemaCached = RimeSchema(".default")
        private set

    override var statusCached = StatusProto()
        private set

    override var compositionCached = CompositionProto()
        private set

    override var hasMenu: Boolean = false
        private set

    override var paging: Boolean = false
        private set

    private val dispatcher =
        RimeDispatcher(
            object : RimeDispatcher.RimeController {
                override fun nativeStartup() {
                    // Switching IMEs, process recreation and settings restoration
                    // must only load existing artifacts. Deployment is explicit.
                    startRime(false)
                    lifecycleRegistry.emitEvent(RimeLifecycle.Event.ON_READY)
                }

                override fun nativeFinalize() {
                    exitRime()
                }
            },
        )

    private val lock = Mutex()

    private val inlinePreeditMode by AppPrefs.defaultInstance().general.inlinePreeditMode
    private val showAsciiSwitchTips by AppPrefs.defaultInstance().general.asciiSwitchTips

    private var asciiSwitchTipsJob: Job? = null
    private var isNullInputType = true
    private var lastAsciiTipsText = ""
    private var pagingMode = false
    private data class OrderedCandidate(val candidate: CandidateProto, val source: Int)
    private var candidateProjection: List<OrderedCandidate>? = null
    private var projectionSourceSize = 0
    private var projectionCode = ""
    private var projectionSchema = ""
    private var personalPage = 0
    private var personalPageSize = 5
    private var personalHighlight = 0
    private var displayedCandidates: Candidates? = null
    private var projectedCommit: CommitProto? = null
    private var lastSlowInputLog = 0L
    private var slowInputCount = 0

    private fun updateCandidateProjection(schema: String, code: String) {
        val changed = code != projectionCode || schema != projectionSchema
        if (changed) { personalPage = 0; personalHighlight = 0 }
        projectionCode = code; projectionSchema = schema
        val rule = if (code.isEmpty()) null else CandidatePreferences.get(schema, code)
        if (rule == null) { candidateProjection = null; projectionSourceSize = 0; return }
        if (changed || candidateProjection == null) personalPageSize = (RimeConfig.openSchema(schema).use { it.getInt("menu/page_size") }
            ?: RimeConfig.openConfig("default").use { it.getInt("menu/page_size") } ?: 5).coerceIn(1, 100)
        val source = getRimeCandidates(0, maxOf(rule.searchLimit, rule.order.size).coerceIn(32, 20000))
        projectionSourceSize = source.size
        val existing = source.map { it.text }.toSet()
        val entries = source.mapIndexed { index, candidate -> OrderedCandidate(candidate, index) }.filter { it.candidate.text !in rule.hidden }.toMutableList()
        rule.aliases.filter { it !in existing && it !in rule.hidden }.forEach { entries.add(OrderedCandidate(CandidateProto(it, "用户造词", ""), -1)) }
        val ranks = rule.order.withIndex().associate { it.value to it.index }
        // Linear stable projection instead of sorting the whole candidate range on each key.
        val buckets = entries.groupBy { it.candidate.text }
        val pinned = rule.order.filterNot { it in rule.forgotten }.flatMap { buckets[it].orEmpty() }
        candidateProjection = pinned + entries.filter { it.candidate.text !in ranks && it.candidate.text !in rule.forgotten } +
            entries.filter { it.candidate.text in rule.forgotten }
    }

    private fun projectedCandidates(start: Int, limit: Int): List<OrderedCandidate> {
        val projection = candidateProjection ?: return getRimeCandidates(start, limit).mapIndexed { i, c -> OrderedCandidate(c, start + i) }
        val begin = start.coerceIn(0, projection.size)
        val result = projection.subList(begin, (begin + limit).coerceAtMost(projection.size)).toMutableList()
        if (result.size < limit) {
            val nativeStart = projectionSourceSize + (start - projection.size).coerceAtLeast(0)
            getRimeCandidates(nativeStart, limit - result.size).forEachIndexed { i, c -> result.add(OrderedCandidate(c, nativeStart + i)) }
        }
        return result
    }

    private fun chooseProjected(index: Int): Boolean {
        val entry = projectedCandidates(index, 1).firstOrNull() ?: return false
        if (entry.source >= 0) {
            return selectRimeCandidate(entry.source, true).also { selected ->
                if (selected) CandidatePreferences.selected(projectionSchema, projectionCode, entry.candidate.text)
            }
        }
        CandidatePreferences.selected(projectionSchema, projectionCode, entry.candidate.text)
        clearRimeComposition()
        projectedCommit = CommitProto(entry.candidate.text)
        return true
    }

    init {
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            throw IllegalStateException("Rime has already been created!")
        }
    }

    private suspend inline fun <T> withRimeContext(crossinline block: suspend () -> T): T = withContext(dispatcher) {
        block()
    }

    override suspend fun isEmpty(): Boolean = withRimeContext {
        getCurrentRimeSchema() == ".default" // 無方案
    }

    override suspend fun deploy() = lock.withLock {
        com.osfans.trime.util.DiagnosticLog.event("rime", "manual_deploy", "requested")
        UserDataManager.sync()
        val deployFinished = CompletableDeferred<Boolean>()
        val deployHandler: (RimeMessage<*>) -> Unit = { message ->
            if (message is RimeMessage.DeployMessage && !message.isUserDataSync) {
                when (message.data) {
                    RimeMessage.DeployMessage.State.Start -> Unit

                    RimeMessage.DeployMessage.State.Success -> {
                        deployFinished.complete(true)
                    }

                    RimeMessage.DeployMessage.State.Failure -> {
                        deployFinished.complete(false)
                    }
                }
            }
        }
        registerRimeMessageHandler(deployHandler)
        try {
            withRimeContext {
                exitRime()
                startRime(true)
            }
            val success =
                withContext(Dispatchers.IO) {
                    withTimeout(5.minutes) {
                        deployFinished.await()
                    }
                }
            check(success) { "Rime deploy failed" }
            withRimeContext {
                // The native maintenance has loaded the deployment plugins and
                // finished writing shared artifacts. Compile themes sequentially.
                com.osfans.trime.data.theme.ThemeLoader.deployThemeConfigs()
            }
            withContext(Dispatchers.Main) {
                com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
            }
            com.osfans.trime.util.appContext.getSharedPreferences("settings_restore_result", android.content.Context.MODE_PRIVATE)
                .edit().remove("deploy").commit()
            Unit
        } finally {
            unregisterRimeMessageHandler(deployHandler)
        }
    }

    override suspend fun deployThemeConfig(themeId: String, force: Boolean): Boolean = withRimeContext {
        if (force) com.osfans.trime.data.theme.ThemeLoader.touchThemeSource(themeId)
        deployRimeConfigFile(themeId, "config_version")
    }

    override suspend fun updateConfig() = withRimeContext {
        exitRime()
        startRime(false)
    }

    override suspend fun syncUserData(): Boolean = lock.withLock {
        UserDataManager.sync()
        // RimeSyncUserData schedules maintenance asynchronously and returns once the
        // worker is started. Wait for DeployMessage so callers (e.g. export) only
        // proceed after sync/<installation_id>/ has been written.
        val syncFinished = CompletableDeferred<Boolean>()
        val syncHandler: (RimeMessage<*>) -> Unit = { message ->
            if (message is RimeMessage.DeployMessage && message.isUserDataSync) {
                when (message.data) {
                    RimeMessage.DeployMessage.State.Success -> syncFinished.complete(true)
                    RimeMessage.DeployMessage.State.Failure -> syncFinished.complete(false)
                    else -> {}
                }
            }
        }
        registerRimeMessageHandler(syncHandler)
        val syncOk =
            try {
                val started = withRimeContext {
                    userDataSyncInProgress = true
                    try {
                        syncRimeUserData().also { if (!it) userDataSyncInProgress = false }
                    } catch (error: Throwable) {
                        userDataSyncInProgress = false
                        throw error
                    }
                }
                if (!started) {
                    false
                } else {
                    withContext(Dispatchers.IO) {
                        withTimeout(5.minutes) {
                            syncFinished.await()
                        }
                    }
                }
            } finally {
                unregisterRimeMessageHandler(syncHandler)
            }
        // Mirror the local user data dir back to the external tree, so the
        // userdb dumps written by the maintenance above are pushed out.
        return syncOk && UserDataManager.sync()
    }

    override suspend fun processKey(
        value: Int,
        modifiers: UInt,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value, modifiers.toInt(), isVirtual)
    }

    override suspend fun processKey(
        value: KeyValue,
        modifiers: KeyModifiers,
        isVirtual: Boolean,
    ): Boolean = withRimeContext {
        processKeyInner(value.value, modifiers.toInt(), isVirtual)
    }

    override suspend fun simulateKeySequence(sequence: String): Boolean = withRimeContext {
        Timber.d("simulateKeySequence: $sequence")
        if (simulateRimeKeySequence(sequence)) {
            val commit = getRimeCommit()
            val input = getRimeRawInput()
            if (!commit.text.isNullOrEmpty() || input.isNotEmpty()) {
                emitResponse(commit)
                true
            } else {
                emitResponse(CommitProto(sequence))
                false
            }
        } else {
            false
        }.also { Timber.d("simulateKeySequence ${if (it) "success" else "failed"}") }
    }

    override suspend fun selectCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        val rawInputBefore = getRimeRawInput()
        (if (candidateProjection != null) chooseProjected(idx + if (global) 0 else personalPage) else selectRimeCandidate(idx, global)).also { emitResponse(rawInputBefore = rawInputBefore) }
    }

    override suspend fun deleteCandidate(idx: Int, global: Boolean): Boolean = withRimeContext {
        val source = if (candidateProjection != null) projectedCandidates(idx + if (global) 0 else personalPage, 1).firstOrNull()?.source else idx
        (source != null && source >= 0 && deleteRimeCandidate(source, global || candidateProjection != null)).also { emitResponse() }
    }

    override suspend fun customizeCandidate(idx: Int, text: String, global: Boolean, action: String, value: String): Boolean = withRimeContext {
        val code = getRimeRawInput()
        val schema = getRimeStatus().schemaId
        if (code.isEmpty()) return@withRimeContext false
        val index = idx + if (global || candidateProjection == null) 0 else personalPage
        // Refuse a stale long-press menu after the composition changes.
        val current = if (candidateProjection != null) projectedCandidates(index, 1).firstOrNull() else if (global)
            getRimeCandidates(idx, 1).firstOrNull()?.let { OrderedCandidate(it, idx) }
        else displayedCandidates?.candidates?.getOrNull(idx)?.let { candidate ->
            var found: OrderedCandidate? = null
            var offset = 0
            while (offset < 20000 && found == null) {
                val chunk = getRimeCandidates(offset, 128)
                val localIndex = chunk.indexOfFirst { it.text == candidate.text && it.comment == candidate.comment }
                if (localIndex >= 0) found = OrderedCandidate(candidate, offset + localIndex)
                if (chunk.size < 128) break
                offset += chunk.size
            }
            found
        }
        if (current?.candidate?.text != text) return@withRimeContext false
        val rule = CandidatePreferences.get(schema, code) ?: CandidatePreferences.Rule(searchLimit = 64)
        if (action == "forget") {
            require(current.source >= 0) { "此词是应用内造词，还未写入 Rime 用户词典，不能执行原生忘词" }
            try {
                val forgotten = forgetNativeCandidate(current.source, current.candidate)
                require(forgotten) { "当前方案未处理 Control+Delete，未执行忘词" }
                if (forgotten) CandidatePreferences.save(schema, code, rule.copy(
                    order = rule.order - text, forgotten = rule.forgotten - text, hidden = rule.hidden - text))
                return@withRimeContext forgotten
            } finally { personalPage = 0; personalHighlight = 0; emitResponse() }
        }
        if (action == "reset") {
            CandidatePreferences.save(schema, code, null)
        } else {
            val limit = maxOf(rule.searchLimit, current.source + 32, rule.order.size + 32).coerceIn(64, 20000)
            val source = getRimeCandidates(0, limit)
            val ranks = rule.order.withIndex().associate { it.value to it.index }
            val order = (source.map { it.text } + rule.aliases).distinct().filter { it !in rule.hidden }.sortedBy { ranks[it] ?: Int.MAX_VALUE }.toMutableList()
            val oldIndex = order.indexOf(text)
            if (oldIndex < 0) return@withRimeContext false
            when (action) {
                "delete", "code" -> {
                    if (action == "code") {
                        require(value.isNotBlank() && value.length <= 128 && value.none { it.isWhitespace() }) { "请输入有效编码（不含空白）" }
                        require(value != code) { "新编码与当前编码相同" }
                        val alphabet = schemaCached.alphabet.ifEmpty { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" } + "'"
                        require(value.all { it in alphabet }) { "新编码包含当前方案不支持的字符" }
                    }
                    CandidatePreferences.save(schema, code, rule.copy(hidden = rule.hidden + text, aliases = rule.aliases - text, searchLimit = limit),
                        newCode = value.takeIf { action == "code" }, word = text.takeIf { action == "code" })
                }
                else -> {
                    val target = when (action) {
                        "pin1" -> 0; "pin2" -> 1; "pin3" -> 2
                        "up" -> oldIndex - 1; "down" -> oldIndex + 1
                        else -> return@withRimeContext false
                    }.coerceIn(0, order.lastIndex)
                    order.removeAt(oldIndex); order.add(target, text)
                    CandidatePreferences.save(schema, code, rule.copy(order = order.take(maxOf(target + 1, rule.order.size + if (oldIndex >= rule.order.size) 1 else 0)), forgotten = rule.forgotten - text, searchLimit = limit))
                }
            }
        }
        personalPage = 0; personalHighlight = 0
        emitResponse()
        true
    }

    /** Highlight the native source candidate, then use Trime's original Control+Delete. */
    private fun forgetNativeCandidate(source: Int, expected: CandidateProto): Boolean {
        val raw = getRimeRawInput()
        val caret = getRimeCaretPos()
        fun page(): Candidates.Paged? {
            val response=getRimeResponse(true)
            if(!response.commit.text.isNullOrEmpty()) projectedCommit=CommitProto(projectedCommit?.text.orEmpty()+response.commit.text)
            return response.candidates as? Candidates.Paged
        }
        try {
            setRimeCaretPos(raw.toByteArray(Charsets.UTF_8).size)
            processRimeKey(0xff50, 0) // Home: native selector, no app projection.
            setRimeCaretPos(raw.toByteArray(Charsets.UTF_8).size)
            var menu = page() ?: return false
            require(!menu.hasPrevPage && menu.highlighted == 0) { "当前方案不支持定位候选，未执行忘词" }
            val pageSize = menu.candidates.size.coerceAtLeast(1)
            repeat(source / pageSize) {
                require(changeRimeCandidatePage(false)) { "候选定位失败，未执行忘词" }
            }
            menu = page() ?: return false
            val target = source % pageSize
            val linear = getRimeOption("_linear") || getRimeOption("_horizontal")
            val vertical = getRimeOption("_vertical")
            val forward = if (vertical && !linear) 0xff51 else if (!vertical && linear) 0xff53 else 0xff54
            val backward = if (vertical && !linear) 0xff53 else if (!vertical && linear) 0xff51 else 0xff52
            val delta = target - menu.highlighted
            repeat(kotlin.math.abs(delta)) { processRimeKey(if (delta > 0) forward else backward, 0) }
            menu = page() ?: return false
            val actual = menu.candidates.getOrNull(menu.highlighted)
            require(getRimeRawInput() == raw && menu.highlighted == target && actual?.text == expected.text && actual.comment == expected.comment) {
                "候选已变化或方案不支持定位，未执行忘词"
            }
            return processRimeKey(0xffff, KeyModifier.Control.modifier.toInt())
        } finally {
            if (getRimeRawInput() == raw) setRimeCaretPos(caret)
        }
    }

    override suspend fun changeCandidatePage(backward: Boolean): Boolean = withRimeContext {
        if (candidateProjection != null && pagingMode) {
            val next = (personalPage + if (backward) -personalPageSize else personalPageSize).coerceAtLeast(0)
            if (next == personalPage || projectedCandidates(next, 1).isEmpty()) return@withRimeContext false
            personalPage = next; personalHighlight = 0; emitResponse(); true
        } else changeRimeCandidatePage(backward).also { emitResponse() }
    }

    override suspend fun moveCursorPos(position: Int) = withRimeContext {
        setRimeCaretPos(position)
        emitResponse()
        Unit
    }

    override suspend fun availableSchemata(): Array<SchemaItem> = withRimeContext { getAvailableRimeSchemaList() }

    override suspend fun enabledSchemata(): Array<SchemaItem> = withRimeContext { getSelectedRimeSchemaList() }

    override suspend fun setEnabledSchemata(schemaIds: Array<String>) = withRimeContext { selectRimeSchemas(schemaIds) }

    override suspend fun selectedSchemata(): Array<SchemaItem> = withRimeContext { getRimeSchemaList() }

    override suspend fun selectedSchemaId(): String = withRimeContext { getCurrentRimeSchema() }

    override suspend fun selectSchema(schemaId: String) = withRimeContext { selectRimeSchema(schemaId) }

    override suspend fun currentSchema(): RimeSchema = withRimeContext {
        RimeSchema(getCurrentRimeSchema())
    }

    override suspend fun commitComposition(): Boolean = withRimeContext {
        val rawInputBefore = getRimeRawInput()
        commitRimeComposition().also { if (it) emitResponse(rawInputBefore = rawInputBefore) }
    }

    override suspend fun clearComposition() = withRimeContext {
        clearRimeComposition()
        emitResponse()
        Unit
    }

    override suspend fun getRawInput(): String = withRimeContext {
        getRimeRawInput()
    }

    override suspend fun setRuntimeOption(
        option: String,
        value: Boolean,
    ): Unit = withRimeContext {
        setRimeOption(option, value)
    }

    override suspend fun getRuntimeOption(option: String): Boolean = withRimeContext {
        getRimeOption(option)
    }

    override suspend fun setNullInputType(value: Boolean) = withRimeContext {
        isNullInputType = value
    }

    override suspend fun getCandidates(
        startIndex: Int,
        limit: Int,
    ): Array<CandidateProto> = withRimeContext {
        if (candidateProjection != null) projectedCandidates(startIndex, limit).map { it.candidate }.toTypedArray() else getRimeCandidates(startIndex, limit)
    }

    override suspend fun setCandidatePagingMode(enabled: Boolean) = withRimeContext {
        pagingMode = enabled
        emitResponse()
        Unit
    }

    private fun startRime(fullCheck: Boolean) {
        // exitRime joins any prior native maintenance before we get here.
        userDataSyncInProgress = false
        DataManager.sync()
        UserDataManager.ensureDefaultCustomConfig()
        InputStats.restoreFromRimeIfEmpty()
        val sharedDataDir = DataManager.sharedDataDir.absolutePath
        val userDataDir = UserDataManager.userDataDir.absolutePath
        Timber.d(
            """
            Starting rime with:
            sharedDataDir: $sharedDataDir
            userDataDir: $userDataDir
            fullCheck: $fullCheck
            """.trimIndent(),
        )
        if (fullCheck) {
            startupRime(sharedDataDir, userDataDir, BuildConfig.BUILD_VERSION_NAME, true)
        } else {
            startupRimeWithoutMaintenance(sharedDataDir, userDataDir, BuildConfig.BUILD_VERSION_NAME)
            scheduleStartupMaintenance(sharedDataDir, userDataDir)
        }
        com.osfans.trime.util.DiagnosticLog.event("rime", "startup", "manualDeploy=$fullCheck")
    }

    /**
     * Restores automatic deployment without librime's directory-mtime false
     * positives: only a newer source YAML starts maintenance, and the compile
     * runs on librime's worker thread while input stays usable.
     */
    private fun scheduleStartupMaintenance(
        sharedDataDir: String,
        userDataDir: String,
    ) {
        val needed =
            runCatching {
                RimeStartupMaintenance.needsMaintenance(
                    java.io.File(sharedDataDir),
                    java.io.File(userDataDir),
                )
            }.getOrElse {
                Timber.w(it, "Could not inspect rime sources for automatic maintenance")
                false
            }
        if (!needed) return
        val accepted =
            runCatching { startRimeMaintenance(true) }.getOrElse {
                Timber.w(it, "Could not schedule automatic maintenance")
                false
            }
        com.osfans.trime.util.DiagnosticLog.event("rime", "auto_maintenance", "accepted=$accepted")
        Timber.i("Automatic rime maintenance scheduled: accepted=$accepted")
    }

    private fun processKeyInner(value: Int, modifiers: Int, isVirtual: Boolean): Boolean {
        val status = getRimeStatus()
        lastAsciiTipsText = asciiTipsText(status)
        val rawInputBefore = getRimeRawInput()
        InputStats.onKeyDown(status.schemaId)
        if (candidateProjection != null && modifiers == 0 && rawInputBefore == projectionCode) {
            val offset = if (pagingMode) personalPage else 0
            val digit = if (value in '1'.code..'9'.code && !schemaCached.alphabet.contains(value.toChar())) value - '1'.code else -1
            if (value == 32 || digit >= 0) {
                val selected = chooseProjected(offset + if (digit >= 0) digit else personalHighlight)
                if (selected) { emitResponse(rawInputBefore = rawInputBefore); return true }
                return true
            }
            if (pagingMode && (value == 0xff55 || value == 0xff56)) {
                val next = (personalPage + if (value == 0xff55) -personalPageSize else personalPageSize).coerceAtLeast(0)
                if (projectedCandidates(next, 1).isNotEmpty()) { personalPage = next; personalHighlight = 0 }
                emitResponse(); return true
            }
            if (value == 0xff52 || value == 0xff54) {
                val count = projectedCandidates(offset, if (pagingMode) personalPageSize else 16).size
                personalHighlight = (personalHighlight + if (value == 0xff52) -1 else 1).coerceIn(0, (count - 1).coerceAtLeast(0))
                emitResponse(); return true
            }
        }
        val started = android.os.SystemClock.elapsedRealtime()
        val handled = processRimeKey(value, modifiers)
        val engineDone = android.os.SystemClock.elapsedRealtime()
        val committed = emitResponse(rawInputBefore = rawInputBefore)
        val total = android.os.SystemClock.elapsedRealtime() - started
        if (total >= 48) {
            slowInputCount++
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSlowInputLog >= 5000) {
                com.osfans.trime.util.DiagnosticLog.event("input", "slow",
                    "schema=${status.schemaId} engine=${engineDone - started}ms response=${total - (engineDone - started)}ms projection=${candidateProjection != null} count=$slowInputCount")
                lastSlowInputLog = now; slowInputCount = 0
            }
        }
        if (!handled) {
            handleRimeMessage(
                10, // RimeMessage.MessageType.Key,
                arrayOf(value, modifiers, isVirtual),
            )
        }
        InputStats.onKeyProcessed(getRimeRawInput(), committed)
        return handled
    }

    private fun asciiTipsText(status: StatusProto): String = when {
        status.isAsciiMode -> "En"

        status.schemaName.isNotEmpty() && !status.schemaName.startsWith('.') ->
            status.schemaName.take(2)

        else -> ""
    }

    private fun emitResponse(commit: CommitProto? = null, rawInputBefore: String? = null): Boolean {
        val response = getRimeResponse(pagingMode)
        updateCandidateProjection(response.status.schemaId, getRimeRawInput())
        val commitProto = commit ?: projectedCommit ?: response.commit
        projectedCommit = null
        val commitText = commitProto.text
        handleRimeMessage(4, arrayOf(commitProto))
        handlePreedit(response.composition)
        if (response.composition.length <= 0 && lastAsciiTipsText != asciiTipsText(response.status)) {
            showAsciiSwitchTips(response.status)
        }
        val rendered = if (candidateProjection == null) response.candidates else when (val native = response.candidates) {
            is Candidates.Bulk -> Candidates.Bulk(
                if (native.total < 0) -1 else native.total + candidateProjection!!.size - projectionSourceSize,
                personalHighlight, projectedCandidates(0, 16).map { it.candidate.copy(label = "") }.toTypedArray())
            is Candidates.Paged -> {
                val items = projectedCandidates(personalPage, personalPageSize)
                Candidates.Paged(hasPrevPage = personalPage > 0, hasNextPage = projectedCandidates(personalPage + personalPageSize, 1).isNotEmpty(),
                    isHorizontalLayout = native.isHorizontalLayout, highlighted = personalHighlight.coerceAtMost((items.size - 1).coerceAtLeast(0)),
                    candidates = items.mapIndexed { i, it -> it.candidate.copy(label = (i + 1).toString()) }.toTypedArray())
            }
        }
        displayedCandidates = rendered
        when (val candidates = rendered) {
            is Candidates.Paged -> handleRimeMessage(7, arrayOf(candidates))
            is Candidates.Bulk -> handleRimeMessage(9, arrayOf(candidates))
        }
        handleRimeMessage(8, arrayOf(response.status))
        val isCommit = !commitText.isNullOrEmpty()
        if (isCommit) {
            InputStats.onCommit(
                commitText!!,
                rawInputBefore,
                response.status.schemaId,
                response.status.schemaName,
            )
        }
        return isCommit
    }

    private fun handlePreedit(composition: CompositionProto) {
        val mode = if (isNullInputType) {
            InlinePreeditMode.DISABLE
        } else {
            inlinePreeditMode
        }
        val inlinePreedit = when (mode) {
            InlinePreeditMode.DISABLE -> ""
            InlinePreeditMode.COMPOSING_TEXT -> composition.preedit ?: ""
            InlinePreeditMode.COMMIT_TEXT_PREVIEW -> composition.commitTextPreview ?: ""
        }
        val composition = if (mode == InlinePreeditMode.COMPOSING_TEXT) {
            CompositionProto()
        } else {
            composition
        }
        handleRimeMessage(5, arrayOf(inlinePreedit))
        handleRimeMessage(6, arrayOf(composition))
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                statusCached = getRimeStatus()
                schemaCached = RimeSchema(it.data.id)
            }

            is RimeMessage.OptionMessage -> {
                // Option change won't trigger response update
                val status = getRimeStatus()
                statusCached = status
                updateSchemaCached(status)
                if (it.data.option == "ascii_mode") {
                    showAsciiSwitchTips(status)
                }
            }

            is RimeMessage.DeployMessage -> {
                if (!it.isUserDataSync && it.data == RimeMessage.DeployMessage.State.Start) {
                    OpenCCDictManager.buildOpenCCDict()
                }
            }

            is RimeMessage.CompositionMessage -> {
                val composition = it.data
                compositionCached = composition
            }

            is RimeMessage.PagedCandidatesMessage -> {
                val paged = it.data
                paging = paged.hasPrevPage
                hasMenu = paged.candidates.isNotEmpty()
            }

            is RimeMessage.BulkCandidatesMessage -> {
                hasMenu = it.data.candidates.isNotEmpty()
            }

            is RimeMessage.StatusMessage -> {
                statusCached = it.data
                updateSchemaCached(it.data)
            }

            else -> {}
        }
    }

    private fun updateSchemaCached(status: StatusProto) {
        val (schemaId, schemaName) = status
        // Engine response update won't send SchemaMessage, but usually update RimeStatus
        if (schemaId != schemaCached.schemaId) {
            schemaCached = RimeSchema(schemaId)
            // notify downstream consumers that schema has changed
            messageFlow_.tryEmit(
                RimeMessage.SchemaMessage(
                    SchemaItem(schemaId, schemaName),
                ),
            )
        }
    }

    private fun showAsciiSwitchTips(status: StatusProto) {
        if (!showAsciiSwitchTips) return
        val tipsText = asciiTipsText(status)
        if (tipsText.isEmpty()) return

        lastAsciiTipsText = tipsText

        val tips = CompositionProto(tipsText)
        messageFlow_.tryEmit(RimeMessage.CompositionMessage(tips))
        compositionCached = tips
        asciiSwitchTipsJob?.cancel()
        asciiSwitchTipsJob = lifecycleScope.launch {
            delay(1000L)
            val ctx = getRimeContext()
            handleRimeMessage(6, arrayOf(ctx.composition))
        }
    }

    /**
     * Start the native Rime dispatcher when storage is ready and the lifecycle is stopped.
     *
     * @return true when startup was accepted; false when storage is unavailable or the
     * lifecycle was not stopped.
     */
    fun startup(): Boolean {
        if (!UserDataManager.isStorageAvailable()) {
            Timber.w("Skip starting rime: storage not available!")
            return false
        }
        if (lifecycle.currentState != RimeLifecycle.State.STOPPED) {
            Timber.w("Skip starting rime: not at stopped state!")
            return false
        }
        registerRimeMessageHandler(::handleRimeMessage)
        lifecycleRegistry.emitEvent(RimeLifecycle.Event.ON_START)
        dispatcher.start()
        return true
    }

    fun finalize() {
        if (lifecycle.currentState != RimeLifecycle.State.READY) {
            Timber.w("Skip stopping rime: not at ready state!")
            return
        }
        lifecycleRegistry.emitEvent(RimeLifecycle.Event.ON_STOP)
        Timber.i("Rime finalize()")
        dispatcher.stop().let {
            if (it.isNotEmpty()) {
                Timber.w("${it.size} job(s) didn't get a chance to run!")
            }
        }
        lifecycleRegistry.emitEvent(RimeLifecycle.Event.ON_STOPPED)
        unregisterRimeMessageHandler(::handleRimeMessage)
    }

    companion object {
        @Volatile private var userDataSyncInProgress = false
        private val messageFlow_ =
            MutableSharedFlow<RimeMessage<*>>(
                extraBufferCapacity = 15,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )

        private val rimeMessageHandlers = CopyOnWriteArrayList<(RimeMessage<*>) -> Unit>()

        init {
            System.loadLibrary("rime_jni")
            System.loadLibrary("trime_startup")
        }

        // init
        @JvmStatic
        external fun startupRime(
            sharedDir: String,
            userDir: String,
            versionName: String,
            fullCheck: Boolean,
        )

        @JvmStatic
        private external fun startupRimeWithoutMaintenance(
            sharedDir: String,
            userDir: String,
            versionName: String,
        )

        @JvmStatic
        private external fun startRimeMaintenance(fullCheck: Boolean): Boolean

        @JvmStatic
        external fun exitRime()

        @JvmStatic
        external fun deployRimeSchemaFile(schemaFile: String): Boolean

        @JvmStatic
        external fun deployRimeConfigFile(
            fileName: String,
            versionKey: String,
        ): Boolean

        @JvmStatic
        external fun syncRimeUserData(): Boolean

        // input
        @JvmStatic
        external fun processRimeKey(
            keycode: Int,
            mask: Int,
        ): Boolean

        @JvmStatic
        external fun commitRimeComposition(): Boolean

        @JvmStatic
        external fun clearRimeComposition()

        // output
        @JvmStatic
        external fun getRimeCommit(): CommitProto

        @JvmStatic
        external fun getRimeContext(): ContextProto

        @JvmStatic
        external fun getRimeStatus(): StatusProto

        // runtime options
        @JvmStatic
        external fun setRimeOption(
            option: String,
            value: Boolean,
        )

        @JvmStatic
        external fun getRimeOption(option: String): Boolean

        @JvmStatic
        external fun getRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getCurrentRimeSchema(): String

        @JvmStatic
        external fun selectRimeSchema(schemaId: String): Boolean

        // testing
        @JvmStatic
        external fun simulateRimeKeySequence(keySequence: String): Boolean

        @JvmStatic
        external fun getRimeRawInput(): String

        @JvmStatic
        external fun getRimeCaretPos(): Int

        @JvmStatic
        external fun setRimeCaretPos(caretPos: Int)

        @JvmStatic
        external fun selectRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun deleteRimeCandidate(index: Int, global: Boolean): Boolean

        @JvmStatic
        external fun changeRimeCandidatePage(backward: Boolean): Boolean

        @JvmStatic
        external fun getAvailableRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun getSelectedRimeSchemaList(): Array<SchemaItem>

        @JvmStatic
        external fun selectRimeSchemas(schemaIds: Array<String>): Boolean

        @JvmStatic
        external fun getRimeCandidates(
            startIndex: Int,
            limit: Int,
        ): Array<CandidateProto>

        @JvmStatic
        external fun getRimeResponse(pagingMode: Boolean): RimeResponse

        @JvmStatic
        fun handleRimeMessage(
            type: Int,
            params: Array<Any>,
        ) {
            val nativeMessage = RimeMessage.nativeCreate(type, params)
            val message = if (nativeMessage is RimeMessage.DeployMessage && userDataSyncInProgress) {
                nativeMessage.copy(isUserDataSync = true).also {
                    if (it.data != RimeMessage.DeployMessage.State.Start) userDataSyncInProgress = false
                }
            } else nativeMessage
            Timber.d("Handling $message")
            rimeMessageHandlers.forEach { it.invoke(message) }
            messageFlow_.tryEmit(message)
        }

        private fun registerRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            if (rimeMessageHandlers.contains(handler)) return
            rimeMessageHandlers.add(handler)
        }

        private fun unregisterRimeMessageHandler(handler: (RimeMessage<*>) -> Unit) {
            rimeMessageHandlers.remove(handler)
        }
    }
}
