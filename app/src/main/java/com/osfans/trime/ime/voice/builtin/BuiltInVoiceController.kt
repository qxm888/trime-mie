/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice.builtin

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.widget.Toast
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.voice.VoiceInput
import kotlinx.coroutines.*

/** One held gesture owns its recorder, engine and editor. Old callbacks cannot affect a new one. */
class BuiltInVoiceController(private val service: TrimeInputMethodService) : VoiceInput {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private class Session(val input: InputConnection, val packageName: String) {
        var engine: AsrEngine? = null
        var recorder: AudioRecorder? = null
        var held = true
        var finished = false
        var watchdog: Job? = null
    }
    private var current: Session? = null
    var lastStatus = "idle"
        private set
    override fun enabled() = com.osfans.trime.ime.voice.VoiceSettings.enabled
    private fun notice(text: String) { main.post { Toast.makeText(service, text, Toast.LENGTH_LONG).show() } }

    override fun start(mock: Boolean) {
        cancel()
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
        if (!mock) service.postRimeJob { commitComposition() }
        val listener = object : AsrListener {
            override fun onFinal(text: String) { main.post {
                if (current !== session || session.finished) return@post
                session.finished = true
                if (service.currentInputConnection === session.input &&
                    service.currentInputEditorInfo?.packageName == session.packageName) {
                    if (text.isNotBlank()) service.commitText(text) else notice("未识别到内容")
                }
                finish(session, "idle")
            } }
            override fun onError(message: String) { main.post {
                if (current !== session || session.finished) return@post
                session.finished = true
                notice(message)
                finish(session, "error")
            } }
        }
        val engine = if (mock) MockEngine(scope, listener) else AsrEngineFactory.create(scope, listener) { notice("正在加载本地模型…") }
        if (engine == null) { finish(session, "error"); notice("识别引擎不可用，请重新选择"); return }
        session.engine = engine
        try {
            engine.start()
            if (!mock) {
                session.recorder = AudioRecorder(service, scope, { listener.onError(it) }) { pcm -> engine.feed(pcm) }
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
        lastStatus = "processing"
        scope.launch {
            session.recorder?.stopAndJoin()
            withContext(Dispatchers.Main) {
                if (current === session && !session.finished) session.engine?.stop()
            }
        }
    }
    private fun finish(session: Session, status: String) {
        session.held = false
        session.watchdog?.cancel()
        session.recorder?.stop()
        session.engine?.cancel()
        if (current === session) { current = null; lastStatus = status }
    }
    override fun cancel(): Job? {
        current?.let { finish(it, "idle") }
        lastStatus = "idle"
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
