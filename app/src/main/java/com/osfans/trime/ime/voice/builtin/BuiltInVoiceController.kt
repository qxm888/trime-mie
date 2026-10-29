/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice.builtin

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.voice.VoiceInput
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope

/** One held gesture owns its recorder, engine and editor. Old callbacks cannot affect a new one. */
class BuiltInVoiceController(private val service: TrimeInputMethodService) : VoiceInput {
    init {
        com.osfans.trime.ime.voice.VoiceSettings.init(service.applicationContext)
        AsrSettings.init(service.applicationContext)
        AsrContextHolder.init(service.applicationContext)
    }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Session(val input: InputConnection, val packageName: String) {
        var engine: AsrEngine? = null
        var recorder: AudioRecorder? = null
        var held = true
        var recorderStarted = false
        var modelLoading = false
        var level = 0f
        var finished = false
        var watchdog: Job? = null
        val id = android.os.SystemClock.elapsedRealtime()
        var bytes = 0L
        var preparation: Job? = null
        var preview = false
        var previewText = ""
        var replacedSelection = ""
        var partialLogged = false
        val streaming = com.osfans.trime.ime.voice.VoiceSettings.streaming
    }
    private var current: Session? = null
    val isActive get() = current != null
    val ownsComposition get() = current?.preview == true
    private fun sameEditor(session: Session) = service.currentInputConnection === session.input &&
        service.currentInputEditorInfo?.packageName == session.packageName

    private fun publish(session: Session) {
        if (current !== session || session.finished) return
        val phase = when {
            session.held && session.recorderStarted -> com.osfans.trime.ime.voice.VoiceRecordingState.Phase.RECORDING
            session.modelLoading -> com.osfans.trime.ime.voice.VoiceRecordingState.Phase.LOADING_MODEL
            session.held -> com.osfans.trime.ime.voice.VoiceRecordingState.Phase.CONNECTING
            else -> com.osfans.trime.ime.voice.VoiceRecordingState.Phase.PROCESSING
        }
        service.updateVoicePhase(phase,session.level)
    }

    private fun clearPreview(session: Session) {
        if (session.preview && sameEditor(session)) {
            session.input.setComposingText(session.replacedSelection, 1)
            session.input.finishComposingText()
        }
        session.preview = false
    }

    fun selectionChanged(start: Int, end: Int, composingStart: Int, composingEnd: Int) {
        val session = current ?: return
        if (!session.preview || session.finished) return
        if (start != end || composingStart < 0 || start !in composingStart..composingEnd) {
            session.input.finishComposingText()
            session.preview = false
            finish(session, "idle")
        }
    }
    var lastStatus = "idle"
        private set
    override fun enabled() = com.osfans.trime.ime.voice.VoiceSettings.enabled
    private fun notice(text: String) { main.post { Toast.makeText(service, text, Toast.LENGTH_LONG).show() } }

    override fun start(mock: Boolean) {
        cancel()
        com.osfans.trime.util.DiagnosticLog.event("voice", "request", "enabled=${enabled()} vendor=${AsrSettings.selectedVendor} micPermission=${AudioRecorder.hasPermission(service)}")
        if (!enabled()) { notice("请在设置 → 语音输入开启语音输入"); return }
        if (!mock && AsrSettings.selectedVendor == "none") { notice("请在设置 → 语音输入选择识别引擎"); return }
        if (!mock && !AudioRecorder.hasPermission(service)) { notice("请在设置 → 语音输入授予麦克风权限"); return }
        val def = AsrVendors.byId(AsrSettings.selectedVendor)
        if (!mock && def?.local == true && LocalAsrModels.installedVariant(service, def.id) == null) {
            notice("本地模型未安装，请在设置 → 语音输入下载或导入"); return
        }
        if (!mock && def?.local != true && !AsrSettings.hasCredential()) {
            notice("请在设置 → 语音输入配置所选服务的凭据"); return
        }
        val input = service.currentInputConnection ?: return
        val session = Session(input, service.currentInputEditorInfo?.packageName.orEmpty())
        current = session
        lastStatus = "connecting"
        publish(session)
        com.osfans.trime.util.DiagnosticLog.event("voice", "start", "id=${session.id} vendor=${if (mock) "mock" else AsrSettings.selectedVendor}")
        if (!mock) session.preparation = service.postRimeJob { commitComposition() }
        val listener = object : AsrListener {
            override fun onPartial(text: String) { main.post {
                if (text.isBlank()) return@post
                service.lifecycleScope.launch {
                    session.preparation?.join()
                    if (current !== session || session.finished || !sameEditor(session) || !session.streaming || text == session.previewText) return@launch
                    if (!session.preview) session.replacedSelection = session.input.getSelectedText(0)?.toString().orEmpty()
                    if (session.input.setComposingText(text, 1)) {
                        session.preview = true
                        session.previewText = text
                        if (!session.partialLogged) {
                            session.partialLogged = true
                            com.osfans.trime.util.DiagnosticLog.event("voice", "first_partial", "id=${session.id} chars=${text.length} ms=${android.os.SystemClock.elapsedRealtime()-session.id}")
                        }
                    }
                }
            } }
            override fun onFinal(text: String) { main.post {
                service.lifecycleScope.launch {
                session.preparation?.join()
                if (current !== session || session.finished) return@launch
                session.finished = true
                com.osfans.trime.util.DiagnosticLog.event("voice", "final", "id=${session.id} chars=${text.length} ms=${android.os.SystemClock.elapsedRealtime() - session.id}")
                if (service.currentInputConnection === session.input &&
                    service.currentInputEditorInfo?.packageName == session.packageName) {
                    if (text.isNotBlank()) {
                        session.preview = false
                        service.commitText(text)
                    } else { clearPreview(session); notice("未识别到内容") }
                }
                finish(session, "idle")
                }
            } }
            override fun onError(message: String) { main.post {
                if (current !== session || session.finished) return@post
                session.finished = true
                com.osfans.trime.util.DiagnosticLog.event("voice", "error", "id=${session.id} stage=$lastStatus")
                notice(message)
                finish(session, "error")
            } }
        }
        val engine = if (mock) MockEngine(scope, listener) else AsrEngineFactory.create(scope, listener,
            onModelLoadStart = { main.post { if (current === session && !session.finished) { session.modelLoading = true; lastStatus = "loading_model"; publish(session); notice("正在加载本地模型，首次加载可能较慢…") } } },
            onModelLoadDone = { main.post { if (current === session && !session.finished) { session.modelLoading = false; lastStatus = if(session.held) "recording" else "recognizing"; publish(session) } } },
        )
        if (engine == null) { finish(session, "error"); notice("识别引擎不可用，请重新选择"); return }
        session.engine = engine
        try {
            engine.start()
            if (!mock) {
                session.recorder = AudioRecorder(service, scope, { listener.onError(it) },
                    onStarted = { main.post {
                        if (current === session && session.held && !session.finished) { session.recorderStarted = true; publish(session) }
                    } },
                    onLevel = { level -> main.post {
                        if (current === session && session.held && !session.finished) { session.level = level; publish(session) }
                    } },
                ) { pcm -> session.bytes += pcm.size; engine.feed(pcm) }
                if (session.recorder?.start() != true) { listener.onError("无法开始录音，请检查麦克风权限"); return }
            }
            lastStatus = "recording"
            session.watchdog = scope.launch { delay(120_000); listener.onError("本次语音已超时，请重新录音") }
        } catch (e: Exception) { listener.onError("无法开始识别：${e.message.orEmpty()}") }
    }
    override fun release() {
        val session = current ?: return
        if (!session.held || session.finished) return
        session.held = false
        publish(session)
        lastStatus = "processing"
        com.osfans.trime.util.DiagnosticLog.event("voice", "release", "id=${session.id} bytes=${session.bytes}")
        session.watchdog?.cancel()
        session.watchdog = scope.launch { delay(90_000); listenerTimeout(session) }
        scope.launch {
            session.recorder?.stopAndJoin()
            withContext(Dispatchers.Main) {
                if (current === session && !session.finished) session.engine?.stop()
            }
        }
    }
    private fun listenerTimeout(session: Session) { main.post {
        if (current !== session || session.finished) return@post
        com.osfans.trime.util.DiagnosticLog.event("voice", "timeout", "id=${session.id} stage=$lastStatus bytes=${session.bytes}")
        session.finished = true
        notice("识别超时。请检查模型与可用内存，或更换较小模型；持续无响应时重启输入法。可导出日志反馈。")
        finish(session, "error")
    } }
    private fun finish(session: Session, status: String) {
        clearPreview(session)
        session.finished = true
        com.osfans.trime.util.DiagnosticLog.event("voice", "finish", "id=${session.id} status=$status")
        session.held = false
        session.watchdog?.cancel()
        session.recorder?.stop()
        session.engine?.cancel()
        if (current === session) { current = null; lastStatus = status; service.updateVoiceRecording(false) }
    }
    override fun cancel(): Job? {
        current?.let { finish(it, "idle") }
        lastStatus = "idle"
        service.updateVoiceRecording(false)
        return null
    }
    override fun dispose() { cancel(); scope.cancel() }
    private class MockEngine(private val scope: CoroutineScope, private val listener: AsrListener) : AsrEngine {
        private var running = false
        override val isRunning get() = running
        override fun start() { running = true }
        override fun feed(pcm: ByteArray) {}
        override fun stop() { if (running) { running = false; scope.launch { delay(120); listener.onFinal("内置语音测试") } } }
        override fun cancel() { running = false }
    }
}
