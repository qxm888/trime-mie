/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 本地离线引擎（sherpa-onnx 反射调用）。底座移植自说点啥/BiBi-Keyboard
 * SherpaReflectionSupport + BaseSherpaOfflineRecognizerManager 与
 * SenseVoice/Qwen3/FunASR Nano/Parakeet/FireRed 各引擎的 config 构造（Apache-2.0）。
 * 模型文件全部平铺在 LocalAsrModels 的安装目录（安装时已做 SHA-256 校验）。
 */
package com.osfans.trime.ime.voice.builtin

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

internal class ReflectiveStream(val instance: Any) {
    private val withRate = try { instance.javaClass.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
    private val withoutRate = try { instance.javaClass.getMethod("acceptWaveform", FloatArray::class.java) } catch (_: Throwable) { null }
    private val release = try { instance.javaClass.getMethod("release") } catch (_: Throwable) { null }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        val withRateMethod = withRate
        if (withRateMethod != null) {
            try {
                withRateMethod.invoke(instance, samples, sampleRate)
                return
            } catch (t: Throwable) {
                val fallback = withoutRate ?: throw t
                fallback.invoke(instance, samples)
                return
            }
        }
        withoutRate?.invoke(instance, samples) ?: throw NoSuchMethodException("acceptWaveform")
    }

    fun release() {
        release?.let { runCatching { it.invoke(instance) } }
    }
}

internal class ReflectiveRecognizer(private val instance: Any, private val cls: Class<*>) {
    private val createStream = cls.getMethod("createStream")
    private val releaseMethod = try { cls.getMethod("release") } catch (_: Throwable) { null }
    private val streamClass = createStream.returnType
    private val decode = cls.getMethod("decode", streamClass)
    private val getResult = cls.getMethod("getResult", streamClass)

    fun createStream(): ReflectiveStream = ReflectiveStream(createStream.invoke(instance))

    fun decode(stream: ReflectiveStream): String? {
        decode.invoke(instance, stream.instance)
        val result = getResult.invoke(instance, stream.instance)
        return try {
            result?.javaClass?.getDeclaredField("text")?.apply { isAccessible = true }?.get(result) as? String
        } catch (_: Throwable) {
            result?.toString()
        }
    }

    fun release() {
        releaseMethod?.let { runCatching { it.invoke(instance) } }
    }
}

internal fun sherpaTrySetField(target: Any, name: String, value: Any?): Boolean = try {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    field.set(target, value)
    true
} catch (_: Throwable) {
    try {
        val setterName = "set" + name.replaceFirstChar { it.uppercase() }
        val setter = target.javaClass.methods.firstOrNull { it.name == setterName && it.parameterTypes.size == 1 } ?: return false
        setter.invoke(target, value)
        true
    } catch (t: Throwable) {
        Timber.w(t, "sherpa: cannot set field %s", name)
        false
    }
}

/**
 * 离线识别器缓存管理：config 相等即复用（sherpa config 是 data class，equals 可靠），
 * 互斥加载，keep-alive 自动卸载。
 */
internal class SherpaOfflineManager {
    companion object {
        @Volatile private var instance: SherpaOfflineManager? = null
        fun getInstance(): SherpaOfflineManager = instance ?: synchronized(this) {
            instance ?: SherpaOfflineManager().also { instance = it }
        }

        fun isOnnxAvailable(): Boolean = try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer"); true
        } catch (t: Throwable) {
            Timber.d(t, "sherpa-onnx not available")
            false
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()

    @Volatile private var cachedRecognizer: ReflectiveRecognizer? = null
    @Volatile private var cachedConfig: Any? = null
    @Volatile private var unloadJob: Job? = null

    val isPrepared: Boolean get() = cachedRecognizer != null

    fun unload() {
        val snapshot = cachedRecognizer ?: return
        scope.launch {
            mutex.withLock {
                if (cachedRecognizer !== snapshot) return@withLock
                cachedRecognizer = null
                cachedConfig = null
            }
            snapshot.release()
            Timber.d("sherpa offline recognizer unloaded")
        }
    }

    private fun createRecognizer(config: Any): Any {
        val clsRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        val clsConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
        // 绝对路径加载必须 assetManager=null（sherpa-onnx issue 2562）
        val ctor = try {
            clsRecognizer.getDeclaredConstructor(clsConfig)
        } catch (_: Throwable) {
            clsRecognizer.getDeclaredConstructor(android.content.res.AssetManager::class.java, clsConfig)
        }
        return if (ctor.parameterTypes.size == 2) ctor.newInstance(null, config) else ctor.newInstance(config)
    }

    suspend fun decodeOffline(
        config: Any,
        samples: FloatArray,
        sampleRate: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null,
    ): String? = mutex.withLock {
        try {
            val cached = cachedRecognizer
            val recognizer = if (cached != null && cachedConfig == config) {
                cached
            } else {
                onLoadStart?.invoke()
                val nativeRecognizer = createRecognizer(config)
                val raw = ReflectiveRecognizer(nativeRecognizer, nativeRecognizer.javaClass)
                val old = cachedRecognizer
                cachedRecognizer = raw
                cachedConfig = config
                onLoadDone?.invoke()
                old?.takeIf { it !== raw }?.release()
                raw
            }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                val text = recognizer.decode(stream)
                unloadJob?.cancel()
                if (!alwaysKeep) {
                    if (keepAliveMs <= 0L) {
                        unload()
                    } else {
                        unloadJob = scope.launch {
                            delay(keepAliveMs)
                            unload()
                        }
                    }
                }
                text
            } finally {
                stream.release()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Timber.e(t, "sherpa offline decode failed")
            null
        }
    }
}

/**
 * 五个离线家族共用的按住说话引擎：feed 累积 PCM，stop 后整体推理一次。
 * 家族差异只在 config 构建（见 [buildRecognizerConfig]）。
 */
class OfflineSherpaAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val familyKey: String,
    private val onModelLoadStart: () -> Unit = {},
) : AsrEngine {
    private val running = AtomicBoolean(false)
    private val buffer = java.io.ByteArrayOutputStream()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        val ctx = AsrContextHolder.get()
        val variant = ctx?.let { LocalAsrModels.installedVariant(it, familyKey) }
        if (ctx == null || variant == null) {
            listener.onError("尚未安装本地模型，请在设置 → 语音输入下载或导入")
            return
        }
        if (!SherpaOfflineManager.isOnnxAvailable()) {
            listener.onError("本地识别库不可用")
            return
        }
        running.set(true)
        buffer.reset()
    }

    override fun feed(pcm: ByteArray) {
        if (!running.get()) return
        buffer.write(pcm)
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        val pcm = buffer.toByteArray()
        buffer.reset()
        scope.launch(Dispatchers.IO) {
            if (pcm.size < 3200) {
                listener.onFinal("")
                return@launch
            }
            try {
                val ctx = AsrContextHolder.get()!!
                val variant = LocalAsrModels.installedVariant(ctx, familyKey)
                    ?: return@launch listener.onError("本地模型不存在，请重新下载")
                val paths = LocalAsrModels.modelPaths(ctx, familyKey, variant)
                    ?: return@launch listener.onError("本地模型文件缺失，请重新下载")
                val config = buildRecognizerConfig(paths)
                val text = SherpaOfflineManager.getInstance().decodeOffline(
                    config = config,
                    samples = pcmToFloatArray(pcm),
                    sampleRate = AudioRecorder.SAMPLE_RATE,
                    keepAliveMs = run {
                        val minutes = AsrSettings.localKeepAliveMinutes(familyKey)
                        if (minutes <= 0) 0L else minutes.toLong() * 60_000L
                    },
                    alwaysKeep = AsrSettings.localKeepAliveMinutes(familyKey) < 0,
                    onLoadStart = onModelLoadStart,
                )
                val cleaned = text?.trim()?.let(::sanitizeResult).orEmpty()
                if (cleaned.isBlank()) listener.onFinal("") else listener.onFinal(cleaned)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Timber.e(t, "offline asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        buffer.reset()
    }

    private fun featConfig(): Any {
        val feat = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig").getDeclaredConstructor().newInstance()
        sherpaTrySetField(feat, "sampleRate", AudioRecorder.SAMPLE_RATE)
        sherpaTrySetField(feat, "featureDim", 80)
        if (familyKey == "parakeet" || familyKey == "firered_asr") sherpaTrySetField(feat, "dither", 0.0f)
        return feat
    }

    private fun baseModelConfig(tokens: String, numThreads: Int): Any {
        val modelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig").getDeclaredConstructor().newInstance()
        sherpaTrySetField(modelConfig, "tokens", tokens)
        sherpaTrySetField(modelConfig, "numThreads", AsrSettings.localThreads(familyKey).coerceIn(1, 8))
        sherpaTrySetField(modelConfig, "provider", "cpu")
        sherpaTrySetField(modelConfig, "debug", false)
        return modelConfig
    }

    private fun finalizeRecognizerConfig(modelConfig: Any, withFeat: Boolean): Any {
        val recConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig").getDeclaredConstructor().newInstance()
        if (!sherpaTrySetField(recConfig, "modelConfig", modelConfig)) sherpaTrySetField(recConfig, "model_config", modelConfig)
        if (withFeat) {
            val feat = featConfig()
            if (!sherpaTrySetField(recConfig, "featConfig", feat)) sherpaTrySetField(recConfig, "feat_config", feat)
            sherpaTrySetField(recConfig, "decodingMethod", "greedy_search")
            sherpaTrySetField(recConfig, "maxActivePaths", 4)
        }
        return recConfig
    }

    private fun buildRecognizerConfig(paths: Map<String, String>): Any = when (familyKey) {
        "sensevoice" -> {
            val sv = Class.forName("com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig").getDeclaredConstructor().newInstance()
            val modelFile = paths["model.int8.onnx"] ?: paths["model.onnx"].orEmpty()
            sherpaTrySetField(sv, "model", modelFile)
            sherpaTrySetField(sv, "language", AsrSettings.localLanguage("sensevoice").ifBlank { "auto" })
            if (!sherpaTrySetField(sv, "useInverseTextNormalization", AsrSettings.localItn("sensevoice"))) {
                sherpaTrySetField(sv, "useItn", AsrSettings.localItn("sensevoice"))
            }
            val modelConfig = baseModelConfig(paths.getValue("tokens.txt"), AsrSettings.localThreads("sensevoice"))
            if (!sherpaTrySetField(modelConfig, "senseVoice", sv)) sherpaTrySetField(modelConfig, "sense_voice", sv)
            finalizeRecognizerConfig(modelConfig, withFeat = false)
        }

        "qwen3_asr" -> {
            val q = Class.forName("com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig").getDeclaredConstructor().newInstance()
            // tokenizer 参数是目录：vocab/merges/tokenizer_config 与模型平铺在同一目录
            val tokenizerDir = File(paths.getValue("vocab.json")).parentFile?.absolutePath.orEmpty()
            sherpaTrySetField(q, "convFrontend", paths.getValue("conv_frontend.onnx"))
            sherpaTrySetField(q, "encoder", paths.getValue("encoder.int8.onnx"))
            sherpaTrySetField(q, "decoder", paths.getValue("decoder.int8.onnx"))
            sherpaTrySetField(q, "tokenizer", tokenizerDir)
            sherpaTrySetField(q, "maxTotalLen", 512)
            sherpaTrySetField(q, "maxNewTokens", 512)
            val modelConfig = baseModelConfig("", AsrSettings.localThreads("qwen3_asr"))
            if (!sherpaTrySetField(modelConfig, "qwen3Asr", q)) sherpaTrySetField(modelConfig, "qwen3_asr", q)
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        "funasr_nano" -> {
            val fn = Class.forName("com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig").getDeclaredConstructor().newInstance()
            val tokenizerDir = File(paths.getValue("tokenizer.json")).parentFile?.absolutePath.orEmpty()
            sherpaTrySetField(fn, "encoderAdaptor", paths.getValue("encoder_adaptor.int8.onnx"))
            sherpaTrySetField(fn, "llm", paths.getValue("llm.int8.onnx"))
            sherpaTrySetField(fn, "embedding", paths.getValue("embedding.int8.onnx"))
            sherpaTrySetField(fn, "tokenizer", tokenizerDir)
            sherpaTrySetField(fn, "userPrompt", "语音转写：")
            sherpaTrySetField(fn, "language", AsrSettings.localLanguage("funasr_nano"))
            sherpaTrySetField(fn, "itn", AsrSettings.localItn("funasr_nano"))
            val modelConfig = baseModelConfig("", AsrSettings.localThreads("funasr_nano"))
            if (!sherpaTrySetField(modelConfig, "funasrNano", fn)) sherpaTrySetField(modelConfig, "funasr_nano", fn)
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        "parakeet" -> {
            val t = Class.forName("com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig").getDeclaredConstructor().newInstance()
            sherpaTrySetField(t, "encoder", paths.getValue("encoder.int8.onnx"))
            sherpaTrySetField(t, "decoder", paths.getValue("decoder.int8.onnx"))
            sherpaTrySetField(t, "joiner", paths.getValue("joiner.int8.onnx"))
            val modelConfig = baseModelConfig(paths.getValue("tokens.txt"), AsrSettings.localThreads("parakeet"))
            sherpaTrySetField(modelConfig, "modelType", "nemo_transducer")
            if (!sherpaTrySetField(modelConfig, "transducer", t)) sherpaTrySetField(modelConfig, "transducerModel", t)
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        "firered_asr" -> {
            val ctc = Class.forName("com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig").getDeclaredConstructor().newInstance()
            sherpaTrySetField(ctc, "model", paths.getValue("model.int8.onnx"))
            val modelConfig = baseModelConfig(paths.getValue("tokens.txt"), AsrSettings.localThreads("firered_asr"))
            if (!sherpaTrySetField(modelConfig, "fireRedAsrCtc", ctc)) sherpaTrySetField(modelConfig, "fire_red_asr_ctc", ctc)
            finalizeRecognizerConfig(modelConfig, withFeat = true)
        }

        else -> throw IllegalStateException("未知本地家族 $familyKey")
    }

    private fun sanitizeResult(text: String): String = when (familyKey) {
        "qwen3_asr" -> text
            .replace("<|im_start|>", "").replace("<|im_end|>", "")
            .replace("<asr_text>", "").replace("</asr_text>", "")
            .trim()
        "firered_asr" -> text.replace("<sil>", "").replace(Regex(" {2,}"), " ").trim()
        else -> text.trim()
    }

    private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = bb.short.toFloat() / 32768f
        return out
    }
}
