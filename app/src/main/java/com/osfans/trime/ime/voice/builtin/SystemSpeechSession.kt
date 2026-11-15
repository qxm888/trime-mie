/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice.builtin

/** All operations and callbacks run on the main thread, as required by SpeechRecognizer. */
internal interface SystemSpeechBackend {
    fun start(listener: AsrListener)
    fun stop()
    fun cancel()
    fun destroy()
}

/** A single gesture owns one recognizer. Closing it silences all late platform callbacks. */
internal class SystemSpeechSession(
    private val listener: AsrListener,
    private val createBackend: () -> SystemSpeechBackend,
) : AsrEngine {
    private var backend: SystemSpeechBackend? = null
    private var started = false
    private var closed = false
    private var stopping = false
    override val isRunning get() = started && !closed
    override val capturesAudio get() = true

    override fun start() {
        if (started || closed) return
        started = true
        try {
            val instance = createBackend()
            backend = instance
            instance.start(object : AsrListener {
                override fun onReady() { if (isRunning && !stopping) listener.onReady() }
                override fun onLevel(level: Float) {
                    if (isRunning && !stopping) listener.onLevel(level.coerceIn(0f, 1f))
                }
                override fun onCaptureEnded() {
                    if (isRunning && !stopping) {
                        stopping = true
                        listener.onCaptureEnded()
                    }
                }
                override fun onPartial(text: String) {
                    if (isRunning && text.isNotBlank()) listener.onPartial(text)
                }
                override fun onFinal(text: String) {
                    if (!isRunning) return
                    close(cancel = false)
                    listener.onFinal(text)
                }
                override fun onError(message: String) = fail(message)
            })
        } catch (_: Exception) {
            fail("无法启动系统端侧识别，请检查系统支持、麦克风权限和语言模型；可改用 SenseVoice 等本地模型")
        }
    }

    // The system service captures audio directly. Feeding PCM would open the microphone twice.
    override fun feed(pcm: ByteArray) = Unit

    override fun stop() {
        if (!isRunning || stopping) return
        stopping = true
        try { backend?.stop() } catch (_: Exception) { fail("系统端侧识别未能结束，请重新录音") }
    }

    override fun cancel() = close(cancel = true)

    private fun fail(message: String) {
        if (!isRunning) return
        close(cancel = true)
        listener.onError(message)
    }

    private fun close(cancel: Boolean) {
        if (closed) return
        closed = true
        val instance = backend
        backend = null
        if (cancel) runCatching { instance?.cancel() }
        runCatching { instance?.destroy() }
    }
}
