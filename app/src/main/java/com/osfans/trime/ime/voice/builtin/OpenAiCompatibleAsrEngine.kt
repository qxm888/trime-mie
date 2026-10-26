/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * OpenAI 兼容文件转写引擎（移植自说点啥/BiBi-Keyboard OpenAiFileAsrEngine 的
 * multipart 模式，Apache-2.0）。POST {endpoint}（multipart: file/model/language），
 * 响应 {text}。适配 OpenAI、SiliconFlow、OpenRouter 及一切 /v1/audio/transcriptions
 * 兼容的自建服务。
 */
package com.osfans.trime.ime.voice.builtin

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

class OpenAiCompatibleAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val endpointProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val languageProvider: () -> String,
    private val bodyStyle: OpenAiBodyStyle = OpenAiBodyStyle.MULTIPART,
    private val extraMultipartFields: List<Pair<String, String>> = emptyList(),
) : AsrEngine {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (apiKeyProvider().isBlank() || endpointProvider().isBlank()) {
            listener.onError("请在设置 → 语音输入填写 OpenAI 兼容服务的地址与 API Key")
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
            if (pcm.size < 3200) { // 少于 100ms 视为没说话
                listener.onFinal("")
                return@launch
            }
            try {
                val text = if (bodyStyle == OpenAiBodyStyle.OPENROUTER_JSON) {
                    transcribeJson(pcm)
                } else {
                    transcribe(pcm)
                }
                listener.onFinal(text.trim())
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.e(t, "openai-compatible asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        buffer.reset()
    }

    private fun transcribe(pcm: ByteArray): String {
        val endpoint = endpointProvider().trim().removeSuffix("/")
        val model = modelProvider().trim()
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "speech.wav", wavBytes(pcm).toRequestBody("audio/wav".toMediaType()))
        val language = languageProvider().trim()
        if (language.isNotBlank() && !language.equals("auto", true)) {
            bodyBuilder.addFormDataPart("language", language)
        }
        extraMultipartFields.forEach { (name, value) -> bodyBuilder.addFormDataPart(name, value) }
        val req = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${apiKeyProvider().trim()}")
            .post(bodyBuilder.build())
            .build()
        http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}：${bodyStr.take(200)}")
            }
            val json = JSONObject(bodyStr)
            // OpenAI 兼容：{text}；个别服务包一层 {data:{text}}
            val text = json.optString("text", "")
            if (text.isNotEmpty()) return text
            return json.optJSONObject("data")?.optString("text", "").orEmpty()
        }
    }

    /** OpenRouter 形态：JSON {"model","input_audio":{"data":裸base64,"format":"wav"}}。 */
    private fun transcribeJson(pcm: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(pcmToWavBytes(pcm), android.util.Base64.NO_WRAP)
        val body = JSONObject()
            .put("model", modelProvider().trim())
            .put(
                "input_audio",
                JSONObject().put("data", b64).put("format", "wav"),
            )
            .toString()
        val key = apiKeyProvider().trim().removePrefix("Bearer ").trim()
        val req = Request.Builder()
            .url(endpointProvider().trim().removeSuffix("/"))
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}：${bodyStr.take(200)}")
            }
            val json = JSONObject(bodyStr)
            return json.optString("text", "").ifBlank { json.optString("output_text", "") }
        }
    }

    /** PCM16LE(16k/mono) → WAV 容器。 */
    private fun wavBytes(pcm: ByteArray): ByteArray = pcmToWavBytes(pcm)
}
