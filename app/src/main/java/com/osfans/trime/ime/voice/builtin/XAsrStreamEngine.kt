/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * X-ASR 本地流式引擎（OnlineRecognizer 反射 + 单写者会话循环，移植自说点啥/
 * BiBi-Keyboard XAsrStreamAsrEngine + XAsrStreamSession，Apache-2.0）。
 * 200ms 帧 acceptWaveform → isReady 时 decode（活跃上限 8 次）→ 节流 partial；
 * 松手后解完剩余帧、补 2×480ms 零填充 right-context、inputFinished 冲刷出最终文本。
 * 同文侧最终结果一次性上屏（partial 不进编辑框，与会话语义一致）。
 */
package com.osfans.trime.ime.voice.builtin

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import timber.log.Timber

internal class XAsrReflectiveRecognizer(private val instance: Any, private val cls: Class<*>) {
    private val createStream = cls.getMethod("createStream", String::class.java)

    fun createStream(): Any = createStream.invoke(instance, "")

    fun isReady(stream: Any): Boolean = try {
        cls.getMethod("isReady", stream.javaClass).invoke(instance, stream) as Boolean
    } catch (t: Throwable) {
        false
    }

    fun decode(stream: Any) {
        cls.getMethod("decode", stream.javaClass).invoke(instance, stream)
    }

    fun getResultText(stream: Any): String? = try {
        val result = cls.getMethod("getResult", stream.javaClass).invoke(instance, stream)
        result?.javaClass?.getMethod("getText")?.invoke(result) as? String
    } catch (t: Throwable) {
        null
    }

    fun acceptWaveform(stream: Any, samples: FloatArray) {
        stream.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
            .invoke(stream, samples, AudioRecorder.SAMPLE_RATE)
    }

    fun inputFinished(stream: Any) {
        stream.javaClass.getMethod("inputFinished").invoke(stream)
    }

    fun releaseStream(stream: Any) {
        try { stream.javaClass.getMethod("release").invoke(stream) } catch (_: Throwable) {}
    }

    fun release() {
        try { cls.getMethod("release").invoke(instance) } catch (_: Throwable) {}
    }
}

class XAsrStreamEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val onModelLoadStart: () -> Unit = {},
    private val onModelLoadDone: () -> Unit = {},
) : AsrEngine {
    companion object {
        private val activeSession = java.util.concurrent.Semaphore(1)
        private const val LIVE_DECODE_MAX_LOOPS = 8
        private const val FINALIZE_DECODE_MAX_LOOPS = 512
        private const val MODEL_CHUNK_MS = 480
        private const val TAIL_PAD_CHUNKS = 2
        private const val PARTIAL_INTERVAL_MS = 200L
    }

    private val running = AtomicBoolean(false)
    private val events = Channel<Event>(640)
    private var loopJob: Job? = null

    private sealed class Event {
        class Pcm(val bytes: ByteArray) : Event()
        object Finish : Event()
        object Cancel : Event()
    }

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        val ctx = AsrContextHolder.get()
        val variant = ctx?.let { LocalAsrModels.installedVariant(it, "x_asr") }
        if (ctx == null || variant == null) {
            listener.onError("尚未安装 X-ASR 模型，请在设置 → 语音输入下载或导入")
            return
        }
        running.set(true)
        loopJob = scope.launch(Dispatchers.IO) {
            if (!activeSession.tryAcquire()) { running.set(false); listener.onError("上一次本地流式识别仍在处理中，请稍后重试"); return@launch }
            try {
                runSession(ctx, variant)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Timber.e(t, "x-asr session failed")
                listener.onError("本地流式识别失败：${t.message ?: t.javaClass.simpleName}")
            } finally { running.set(false); activeSession.release() }
        }
    }

    override fun feed(pcm: ByteArray) {
        if (!running.get()) return
        if (events.trySend(Event.Pcm(pcm)).isFailure && running.get()) { listener.onError("本地模型处理过慢，请选择较小模型"); cancel() }
    }

    override fun stop() {
        if (!running.get()) return
        events.trySend(Event.Finish)
    }

    override fun cancel() {
        running.set(false)
        loopJob?.cancel()
        events.close()
    }

    private suspend fun runSession(context: android.content.Context, variant: String) {
        val paths = LocalAsrModels.modelPaths(context, "x_asr", variant)
        if (paths == null) {
            listener.onError("X-ASR 模型文件缺失，请重新下载")
            return
        }
        val recognizer = prepareRecognizer(paths) ?: run {
            listener.onError("X-ASR 模型加载失败")
            return
        }
        try {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val stream = recognizer.createStream()
        try {
        var lastPartialAt = 0L
        var lastPartialText = ""

        fun decodeWhileReady(maxLoops: Int): Int {
            var loops = 0
            while (recognizer.isReady(stream) && loops < maxLoops) {
                recognizer.decode(stream)
                loops++
            }
            return loops
        }

        for (event in events) {
            when (event) {
                is Event.Pcm -> {
                    val floats = pcmToFloatArray(event.bytes)
                    if (floats.isEmpty()) continue
                    recognizer.acceptWaveform(stream, floats)
                    decodeWhileReady(LIVE_DECODE_MAX_LOOPS)
                    if (!running.get()) break
                    val partial = recognizer.getResultText(stream).orEmpty()
                    val now = System.currentTimeMillis()
                    if (partial.isNotBlank() && now - lastPartialAt >= PARTIAL_INTERVAL_MS && partial != lastPartialText) {
                        listener.onPartial(formatCjkSpacing(partial.trim()))
                        lastPartialAt = now
                        lastPartialText = partial
                    }
                }

                is Event.Finish -> {
                    // 解完剩余帧 + 补零填充 right-context + inputFinished 冲刷最终结果
                    decodeWhileReady(FINALIZE_DECODE_MAX_LOOPS)
                    val chunkSamples = AudioRecorder.SAMPLE_RATE * MODEL_CHUNK_MS / 1000
                    repeat(TAIL_PAD_CHUNKS) {
                        recognizer.acceptWaveform(stream, FloatArray(chunkSamples))
                        decodeWhileReady(FINALIZE_DECODE_MAX_LOOPS)
                    }
                    recognizer.inputFinished(stream)
                    decodeWhileReady(FINALIZE_DECODE_MAX_LOOPS)
                    val text = formatCjkSpacing(recognizer.getResultText(stream).orEmpty().trim())
                    running.set(false)
                    listener.onFinal(text)
                    return
                }

                is Event.Cancel -> {
                    running.set(false)
                    return
                }
            }
        }
        } finally { recognizer.releaseStream(stream) }
        } finally { running.set(false); recognizer.release(); events.close() }
    }

    private fun prepareRecognizer(paths: Map<String, String>): XAsrReflectiveRecognizer? = runCatching {
        onModelLoadStart()
        val began = android.os.SystemClock.elapsedRealtime()
        com.osfans.trime.util.DiagnosticLog.event("voice", "stream_model_load_start", "family=x_asr bytes=${paths.values.sumOf { java.io.File(it).length() }}")
        val transducer = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
            .getDeclaredConstructor().newInstance()
        // 1.13.3 起 data class 增加 qnnConfig 且无 @JvmOverloads：优先无参构造 + 反射写字段
        sherpaTrySetField(transducer, "encoder", paths.getValue("encoder-480ms.onnx"))
        sherpaTrySetField(transducer, "decoder", paths.getValue("decoder-480ms.onnx"))
        sherpaTrySetField(transducer, "joiner", paths.getValue("joiner-480ms.onnx"))

        val modelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
            .getDeclaredConstructor().newInstance()
        sherpaTrySetField(modelConfig, "tokens", paths.getValue("tokens.txt"))
        sherpaTrySetField(modelConfig, "numThreads", AsrSettings.localThreads("x_asr").coerceIn(1, 8))
        sherpaTrySetField(modelConfig, "provider", "cpu")
        sherpaTrySetField(modelConfig, "modelType", "zipformer2")
        sherpaTrySetField(modelConfig, "debug", false)
        sherpaTrySetField(modelConfig, "transducer", transducer)

        val feat = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            .getDeclaredConstructor().newInstance()
        sherpaTrySetField(feat, "sampleRate", AudioRecorder.SAMPLE_RATE)
        sherpaTrySetField(feat, "featureDim", 80)

        val recConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
            .getDeclaredConstructor().newInstance()
        sherpaTrySetField(recConfig, "modelConfig", modelConfig)
        sherpaTrySetField(recConfig, "featConfig", feat)
        sherpaTrySetField(recConfig, "decodingMethod", "greedy_search")
        sherpaTrySetField(recConfig, "enableEndpoint", false)
        sherpaTrySetField(recConfig, "maxActivePaths", 4)

        val clsRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
        val clsConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
        val ctor = clsRecognizer.getDeclaredConstructor(
            android.content.res.AssetManager::class.java, clsConfig,
        )
        XAsrReflectiveRecognizer(ctor.newInstance(null, recConfig), clsRecognizer).also {
            onModelLoadDone()
            com.osfans.trime.util.DiagnosticLog.event("voice", "stream_model_load_done", "ms=${android.os.SystemClock.elapsedRealtime() - began}")
        }
    }.onFailure { Timber.e(it, "x-asr recognizer init failed"); com.osfans.trime.util.DiagnosticLog.event("voice", "stream_model_load_failed", "type=${(it.cause ?: it).javaClass.simpleName}") }.getOrNull()

    /** CJK 相邻字符间的空白折叠（移植 formatXAsrText 的 normalize 部分）。 */
    private fun formatCjkSpacing(text: String): String {
        if (text.isBlank()) return text
        val sb = StringBuilder(text.length)
        var lastWhitespace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                lastWhitespace = true
                continue
            }
            if (lastWhitespace && sb.isNotEmpty()) {
                val prev = sb.last()
                val nextNoLeadingSpace = ch in ",.!?;:%)]}"
                if (!(isCjkOrCjkPunct(prev) && isCjkOrCjkPunct(ch)) && !nextNoLeadingSpace) sb.append(' ')
            }
            lastWhitespace = false
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun isCjkOrCjkPunct(c: Char): Boolean = c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF' ||
        c in '\uF900'..'\uFAFF' || c in "，。！？；：、（）《》〈〉【】「」『』“”‘’"

    private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = bb.short.toFloat() / 32768f
        return out
    }
}
