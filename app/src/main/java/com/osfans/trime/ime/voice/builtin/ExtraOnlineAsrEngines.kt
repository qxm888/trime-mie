/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 追加在线引擎（各自独立协议，全部移植自说点啥/BiBi-Keyboard 对应引擎，
 * Apache-2.0）：Gemini（generateContent inline audio）、小米 MiMo（chat/completions
 * input_audio）、ElevenLabs（speech-to-text multipart）、Soniox（files→transcriptions
 * 四步异步）、StepAudio（SSE 裸 PCM）。音频统一 16kHz/16bit/mono PCM，按各家要求封装。
 */
package com.osfans.trime.ime.voice.builtin

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

internal fun pcmToWavBytes(pcm: ByteArray, sampleRate: Int = AudioRecorder.SAMPLE_RATE): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val out = ByteArrayOutputStream(44 + pcm.size)
    fun le16(v: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
    fun le32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
    out.write("RIFF".toByteArray()); le32(36 + pcm.size)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray()); le32(16)
    le16(1); le16(channels); le32(sampleRate); le32(byteRate); le16(channels * bitsPerSample / 8); le16(bitsPerSample)
    out.write("data".toByteArray()); le32(pcm.size)
    out.write(pcm)
    return out.toByteArray()
}

/** 各家错误体统一解析：error.message → message → 正文前 200 字。 */
internal fun extractHttpError(body: String): String = try {
    val o = JSONObject(body)
    val err = o.optJSONObject("error")
    err?.optString("message")?.takeIf { it.isNotBlank() }
        ?: o.optString("message").takeIf { it.isNotBlank() }
        ?: body.take(200)
} catch (_: Throwable) {
    body.take(200)
}

/**
 * Google Gemini：POST {base}/models/{model}:generateContent?key=...
 * inline_data（audio/wav base64）+ system_instruction 转写提示词。
 * 响应取 candidates[0].content.parts[] 第一个非空 text。
 */
class GeminiAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val endpointProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
) : AsrEngine {
    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (apiKeyProvider().isBlank()) {
            listener.onError("请在设置 → 语音输入填写 Gemini API Key")
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
            if (pcm.size < 3200) return@launch listener.onFinal("")
            try {
                val wav = pcmToWavBytes(pcm)
                val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
                val body = buildBody(b64)
                val url = buildUrl(endpointProvider(), modelProvider(), apiKeyProvider())
                val http = OkHttpClient.Builder()
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${extractHttpError(text)}")
                    val out = parseText(text)
                    listener.onFinal(out.trim())
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.e(t, "gemini asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        buffer.reset()
    }

    private fun buildUrl(endpoint: String, model: String, apiKey: String): String {
        val (basePart, queryPart) = endpoint.trim().split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        var base = basePart.trim().trimEnd('/')
        if (!Regex("/v\\d+(beta)?(/|$)", RegexOption.IGNORE_CASE).containsMatchIn(base)) base += "/v1beta"
        var url = "$base/models/$model:generateContent"
        if (!queryPart.isNullOrBlank()) url += "?$queryPart"
        url += (if (url.contains("?")) "&" else "?") + "key=" + apiKey.trim()
        return url
    }

    private fun buildBody(base64Audio: String): String {
        val inlineAudio = JSONObject()
            .put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", base64Audio))
        val systemInstruction = JSONObject().put(
            "parts", JSONArray().put(JSONObject().put("text", "请将以下音频逐字转写为文本，不要输出解释或前后缀。")),
        )
        val user = JSONObject().put("role", "user").put("parts", JSONArray().put(inlineAudio))
        return JSONObject()
            .put("system_instruction", systemInstruction)
            .put("contents", JSONArray().put(user))
            .put("generationConfig", JSONObject().put("temperature", 0))
            .toString()
    }

    private fun parseText(body: String): String = try {
        val cands = JSONObject(body).optJSONArray("candidates") ?: return ""
        val content = cands.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text")?.trim().orEmpty()
            if (t.isNotEmpty()) return t
        }
        ""
    } catch (_: Throwable) {
        ""
    }
}

/**
 * 小米 MiMo：POST chat/completions，头 api-key（非 Bearer），
 * messages[0].content = [{type:"input_audio", input_audio:{data:"data:audio/wav;base64,..."}}]，
 * asr_options.language；响应 choices[0].message.content。
 */
class MiMoAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val endpointProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val languageProvider: () -> String,
) : AsrEngine {
    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (apiKeyProvider().isBlank()) {
            listener.onError("请在设置 → 语音输入填写 MiMo API Key")
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
            if (pcm.size < 3200) return@launch listener.onFinal("")
            try {
                val b64 = Base64.encodeToString(pcmToWavBytes(pcm), Base64.NO_WRAP)
                val audioPart = JSONObject()
                    .put("type", "input_audio")
                    .put("input_audio", JSONObject().put("data", "data:audio/wav;base64,$b64"))
                val messages = JSONArray().put(
                    JSONObject().put("role", "user").put("content", JSONArray().put(audioPart)),
                )
                val body = JSONObject()
                    .put("model", modelProvider().ifBlank { "mimo-v2.5-asr" })
                    .put("messages", messages)
                    .put("asr_options", JSONObject().put("language", languageProvider().ifBlank { "auto" }))
                    .toString()
                val http = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
                val req = Request.Builder()
                    .url(endpointProvider())
                    .header("api-key", apiKeyProvider().trim())
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${extractHttpError(text)}")
                    val choices = JSONObject(text).optJSONArray("choices")
                    val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    listener.onFinal(content.trim())
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.e(t, "mimo asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        buffer.reset()
    }
}

/**
 * ElevenLabs：POST /v1/speech-to-text，头 xi-api-key，multipart
 * file/audio.wav + model_id=scribe_v1 + tag_audio_events=false + num_speakers=1。
 * 响应顶层 text（或 transcripts[] 逐项拼接）。
 */
class ElevenLabsAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val languageProvider: () -> String,
) : AsrEngine {
    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (apiKeyProvider().isBlank()) {
            listener.onError("请在设置 → 语音输入填写 ElevenLabs API Key")
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
            if (pcm.size < 3200) return@launch listener.onFinal("")
            try {
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", pcmToWavBytes(pcm).toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model_id", modelProvider().ifBlank { "scribe_v1" })
                    .addFormDataPart("tag_audio_events", "false")
                    .addFormDataPart("num_speakers", "1")
                val lang = languageProvider().trim()
                if (lang.isNotEmpty() && !lang.equals("auto", true)) multipart.addFormDataPart("language_code", lang)
                val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
                val req = Request.Builder()
                    .url("https://api.elevenlabs.io/v1/speech-to-text")
                    .header("xi-api-key", apiKeyProvider().trim())
                    .post(multipart.build())
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${extractHttpError(body)}")
                    val json = JSONObject(body)
                    var out = json.optString("text", "")
                    if (out.isBlank()) {
                        val transcripts = json.optJSONArray("transcripts")
                        if (transcripts != null) {
                            val sb = StringBuilder()
                            for (i in 0 until transcripts.length()) {
                                sb.append(transcripts.optJSONObject(i)?.optString("text").orEmpty())
                                if (i < transcripts.length() - 1) sb.append('\n')
                            }
                            out = sb.toString()
                        }
                    }
                    listener.onFinal(out.trim())
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.e(t, "elevenlabs asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        buffer.reset()
    }
}

/**
 * Soniox：上传文件 → 创建转写任务 → 轮询状态 → 拉取 tokens 拼接。
 * 全程 Bearer 鉴权；模型 stt-async-v5。
 */
class SonioxAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val apiKeyProvider: () -> String,
    private val languageProvider: () -> String,
) : AsrEngine {
    companion object {
        private const val BASE = "https://api.soniox.com"
        private const val MODEL = "stt-async-v5"
    }

    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (apiKeyProvider().isBlank()) {
            listener.onError("请在设置 → 语音输入填写 Soniox API Key")
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
            if (pcm.size < 3200) return@launch listener.onFinal("")
            try {
                val apiKey = apiKeyProvider().trim()
                val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

                val upload = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", pcmToWavBytes(pcm).toRequestBody("audio/wav".toMediaType()))
                    .build()
                val fileId = JSONObject(
                    http.newCall(
                        Request.Builder().url("$BASE/v1/files")
                            .header("Authorization", "Bearer $apiKey").post(upload).build(),
                    ).execute().use { r ->
                        val b = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}：${extractHttpError(b)}")
                        b
                    },
                ).getString("id")

                val taskBody = JSONObject().apply {
                    put("file_id", fileId)
                    put("model", MODEL)
                    put("enable_language_identification", true)
                    val lang = languageProvider().trim()
                    if (lang.isNotEmpty() && !lang.equals("auto", true)) {
                        put("language_hints", JSONArray().put(lang))
                    }
                }.toString()
                val taskId = JSONObject(
                    http.newCall(
                        Request.Builder().url("$BASE/v1/transcriptions")
                            .header("Authorization", "Bearer $apiKey")
                            .post(taskBody.toRequestBody("application/json".toMediaType())).build(),
                    ).execute().use { r ->
                        val b = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}：${extractHttpError(b)}")
                        b
                    },
                ).getString("id")

                while (true) {
                    kotlinx.coroutines.delay(1000)
                    val statusBody = http.newCall(
                        Request.Builder().url("$BASE/v1/transcriptions/$taskId")
                            .header("Authorization", "Bearer $apiKey").get().build(),
                    ).execute().use { r ->
                        val b = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}：${extractHttpError(b)}")
                        b
                    }
                    val status = JSONObject(statusBody).optString("status").lowercase()
                    when {
                        status == "completed" -> break
                        status == "error" -> throw IllegalStateException(
                            JSONObject(statusBody).optString("error_message").ifBlank { "转写失败" },
                        )
                    }
                }

                val transcript = http.newCall(
                    Request.Builder().url("$BASE/v1/transcriptions/$taskId/transcript")
                        .header("Authorization", "Bearer $apiKey").get().build(),
                ).execute().use { r ->
                    val b = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}：${extractHttpError(b)}")
                    b
                }
                val tokens = JSONObject(transcript).optJSONArray("tokens")
                val sb = StringBuilder()
                if (tokens != null) {
                    for (i in 0 until tokens.length()) sb.append(tokens.optJSONObject(i)?.optString("text").orEmpty())
                }
                listener.onFinal(sb.toString().trim())
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.e(t, "soniox asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        buffer.reset()
    }
}

/**
 * StepAudio：SSE 端点，body {"audio":{"data":base64 裸 PCM,
 * "input":{"transcription":{model,language,enable_itn},"format":{pcm_s16le,16k,16bit,mono}}}}。
 * 逐行解析 event/data：优先累加 transcript.text.delta，done 兜底。
 */
class StepAudioAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val endpointProvider: () -> String,
    private val apiKeyProvider: () -> String,
    private val modelProvider: () -> String,
    private val languageProvider: () -> String,
) : AsrEngine {
    private val running = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        if (apiKeyProvider().isBlank()) {
            listener.onError("请在设置 → 语音输入填写 StepAudio（阶跃）API Key")
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
            if (pcm.size < 3200) return@launch listener.onFinal("")
            try {
                val transcription = JSONObject().apply {
                    put("model", modelProvider().ifBlank { "stepaudio-2.5-asr" })
                    val lang = languageProvider().trim()
                    if (lang.isNotEmpty()) put("language", lang)
                    put("enable_itn", true)
                }
                val format = JSONObject().apply {
                    put("type", "pcm"); put("codec", "pcm_s16le")
                    put("rate", AudioRecorder.SAMPLE_RATE); put("bits", 16); put("channel", 1)
                }
                val body = JSONObject().put(
                    "audio",
                    JSONObject()
                        .put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
                        .put("input", JSONObject().put("transcription", transcription).put("format", format)),
                ).toString()
                val http = OkHttpClient.Builder().callTimeout(180, TimeUnit.SECONDS).build()
                val req = Request.Builder()
                    .url(endpointProvider())
                    .header("Authorization", "Bearer ${apiKeyProvider().trim()}")
                    .header("Accept", "text/event-stream")
                    .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val sse = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${extractHttpError(sse)}")
                    listener.onFinal(parseSse(sse))
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Timber.e(t, "stepaudio asr failed")
                listener.onError("语音识别失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun cancel() {
        running.set(false)
        buffer.reset()
    }

    private fun parseSse(body: String): String {
        val out = StringBuilder()
        var currentEvent = ""
        val dataLines = mutableListOf<String>()
        fun flush() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n").trim()
            if (data.isNotEmpty() && data != "[DONE]") appendData(out, currentEvent, data)
            currentEvent = ""
            dataLines.clear()
        }
        body.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isEmpty() -> flush()
                line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
            }
        }
        flush()
        return out.toString().trim()
    }

    private fun appendData(out: StringBuilder, event: String, data: String) {
        try {
            val obj = JSONObject(data)
            val type = obj.optString("type", event)
            fun extractText(name: String): String {
                val v = obj.opt(name) ?: return ""
                return when (v) {
                    is String -> v
                    is JSONObject -> v.optString("text").ifBlank { v.optString("content") }
                    else -> v.toString()
                }
            }
            val delta = extractText("delta")
            val text = extractText("text")
            when {
                event == "transcript.text.delta" || type == "transcript.text.delta" -> out.append(delta.ifBlank { text })
                event == "transcript.text.done" || type == "transcript.text.done" ->
                    if (out.isBlank() && text.isNotBlank()) out.append(text)
                delta.isNotBlank() -> out.append(delta)
                out.isBlank() && text.isNotBlank() -> out.append(text)
            }
        } catch (_: Throwable) {
            if (event == "transcript.text.delta") out.append(data)
        }
    }
}
