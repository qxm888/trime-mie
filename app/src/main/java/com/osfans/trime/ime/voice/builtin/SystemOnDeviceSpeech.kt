/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice.builtin

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Public Android APIs only. Never substitute createSpeechRecognizer or an OEM private binder. */
object SystemOnDeviceSpeech {
    fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 &&
        runCatching { Api31.available(context) }.getOrDefault(false)

    fun availabilityMessage(context: Context): String = when {
        Build.VERSION.SDK_INT < 31 -> "系统端侧识别需要 Android 12 或更高版本"
        !available(context) -> "当前系统未向第三方开放标准端侧语音识别；安装系统端侧大模型不代表开放了语音接口。可选择 SenseVoice 等本地模型。"
        else -> "系统已提供端侧识别入口，能否识别还取决于所选语言模型和系统服务状态"
    }

    @RequiresApi(31)
    internal fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.ifBlank { "zh-CN" })
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** Does not capture audio or download anything. Destroy even when support callbacks never arrive. */
    suspend fun inspect(context: Context, language: String): String = withContext(Dispatchers.Main.immediate) {
        if (!available(context)) return@withContext availabilityMessage(context)
        if (Build.VERSION.SDK_INT < 33) return@withContext "系统提供端侧识别入口，但此 Android 版本不支持查询语言模型状态；请长按空格验证识别效果。"
        Api33.inspect(context, language)
    }

    internal fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "系统端侧识别没有麦克风权限，请在语音设置中授权"
        SpeechRecognizer.ERROR_AUDIO -> "系统端侧识别无法录音，请检查麦克风是否被占用"
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未识别到语音，请重新录音"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "系统端侧识别正忙，请稍后重试"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "系统端侧识别不支持所选语言，请更换语言或本地模型"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "所选语言的端侧语音模型尚不可用，请在系统中安装对应语音模型"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统端侧服务返回网络错误，本次识别已停止；未切换在线识别"
        else -> "系统端侧识别失败（$code），请检查系统服务或更换本地模型"
    }

    @RequiresApi(31)
    internal object Api31 {
        fun available(context: Context) = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        fun create(context: Context): SpeechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    }

    @RequiresApi(33)
    private object Api33 {
        suspend fun inspect(context: Context, language: String): String {
            val recognizer = try { Api31.create(context) } catch (_: Exception) {
                return "系统声明提供端侧识别，但无法创建服务；请检查系统组件和权限"
            }
            return try {
                withTimeoutOrNull(5000) {
                    suspendCancellableCoroutine { continuation ->
                        val executor = Executor { Handler(Looper.getMainLooper()).post(it) }
                        recognizer.checkRecognitionSupport(intent(language), executor, object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (!continuation.isActive) return
                                val installed = support.installedOnDeviceLanguages.joinToString().ifBlank { "无" }
                                val pending = support.pendingOnDeviceLanguages.joinToString().ifBlank { "无" }
                                val supported = support.supportedOnDeviceLanguages.joinToString().ifBlank { "无" }
                                continuation.resume("已安装的端侧语音语言：$installed\n待安装：$pending\n系统支持：$supported\n当前请求：${language.ifBlank { "zh-CN" }}；实际识别以系统返回为准。")
                            }
                            override fun onError(error: Int) {
                                if (continuation.isActive) continuation.resume("系统端侧服务未能返回语言支持信息（$error）；请在系统语音设置中检查。")
                            }
                        })
                    }
                } ?: "系统端侧服务检查超时；未开始录音，也未切换在线识别"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                "系统端侧服务检查失败；请检查系统组件和权限"
            } finally { runCatching { recognizer.destroy() } }
        }
    }
}

/** The system owns microphone capture. A separate recognizer is created for each gesture. */
internal class AndroidOnDeviceSpeechBackend(
    private val context: Context,
    private val language: String,
) : SystemSpeechBackend {
    private var recognizer: SpeechRecognizer? = null

    override fun start(listener: AsrListener) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < 31 || !SystemOnDeviceSpeech.available(context)) {
            throw UnsupportedOperationException("System on-device recognition is unavailable")
        }
        val instance = SystemOnDeviceSpeech.Api31.create(context)
        recognizer = instance
        instance.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = listener.onCaptureEnded()
            override fun onError(error: Int) = listener.onError(SystemOnDeviceSpeech.errorMessage(error))
            override fun onResults(results: Bundle?) = listener.onFinal(bestText(results))
            override fun onPartialResults(partialResults: Bundle?) = listener.onPartial(bestText(partialResults))
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        instance.startListening(SystemOnDeviceSpeech.intent(language))
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.cancel() }
    override fun destroy() {
        val instance = recognizer
        recognizer = null
        instance?.destroy()
    }
    private fun bestText(bundle: Bundle?): String = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }.orEmpty()
}
