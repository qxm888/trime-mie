/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 火山引擎 WebSocket 流式 ASR（移植自说点啥/BiBi-Keyboard VolcStreamAsrEngine，
 * Apache-2.0）：bigmodel_async 二进制协议；同文侧改为外部推 PCM（feed），
 * 启动后先发 full client request，再按 gzip 压缩帧发送音频。
 */
package com.osfans.trime.ime.voice.builtin

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import timber.log.Timber

/** 火山引擎鉴权头组装（移植自 VolcAuthHeaders）。 */
data class VolcAuthValues(
    val useNewAuth: Boolean,
    val apiKey: String,
    val appKey: String,
    val accessKey: String,
) {
    fun credentialHeaders(): List<Pair<String, String>> = if (useNewAuth) {
        listOf("X-Api-Key" to apiKey)
    } else {
        listOf(
            "X-Api-App-Key" to appKey,
            "X-Api-Access-Key" to accessKey,
        )
    }
}

object VolcAsrModelCatalog {
    fun resourceIdOf(modelId: String): String = when (modelId) {
        "doubao_streaming_v2" -> "volc.seedasr.sauc.duration"
        "doubao_streaming_v1" -> "volc.bigasr.sauc.duration"
        else -> "volc.seedasr.sauc.duration"
    }
}

fun volcHeaders(
    auth: VolcAuthValues,
    resourceId: String,
    connectId: String,
): okhttp3.Headers {
    val builder = okhttp3.Headers.Builder()
    auth.credentialHeaders().forEach { (name, value) -> builder.add(name, value) }
    builder.add("X-Api-Resource-Id", resourceId)
    builder.add("X-Api-Connect-Id", connectId)
    return builder.build()
}

class VolcStreamAsrEngine(
    private val scope: CoroutineScope,
    private val listener: AsrListener,
    private val authProvider: () -> VolcAuthValues,
    private val modelProvider: () -> String,
    private val languageProvider: () -> String,
    private val ddcProvider: () -> Boolean,
) : AsrEngine {
    companion object {
        private const val WS_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

        private const val PROTOCOL_VERSION = 0x1
        private const val HEADER_SIZE_UNITS = 0x1

        private const val MSG_TYPE_FULL_CLIENT_REQ = 0x1
        private const val MSG_TYPE_AUDIO_ONLY_CLIENT_REQ = 0x2
        private const val MSG_TYPE_FULL_SERVER_RESP = 0x9
        private const val MSG_TYPE_ERROR_SERVER = 0xF

        private const val SERIALIZE_NONE = 0x0
        private const val SERIALIZE_JSON = 0x1

        private const val COMPRESS_GZIP = 0x1

        private const val FLAG_AUDIO_LAST = 0x2
        private const val FLAG_SERVER_FINAL_MASK = 0x3
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private val awaitingFinal = AtomicBoolean(false)
    private val audioLastSent = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private var closeJob: Job? = null
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.get()) return
        running.set(true)
        audioLastSent.set(false)
        synchronized(prebufferLock) { prebuffer.clear() }
        openWebSocket()
    }

    override fun feed(pcm: ByteArray) {
        if (!running.get()) return
        if (!wsReady.get()) {
            synchronized(prebufferLock) {
                prebuffer.addLast(pcm.copyOf())
                // 预缓冲上限约 2s，避免握手慢时内存堆积
                while (prebuffer.size > 10) prebuffer.removeFirst()
            }
        } else {
            var flushed: Array<ByteArray>? = null
            synchronized(prebufferLock) {
                if (prebuffer.isNotEmpty()) {
                    flushed = prebuffer.toTypedArray()
                    prebuffer.clear()
                }
            }
            flushed?.forEach { b -> runCatching { sendAudioFrame(b, last = false) } }
            runCatching { sendAudioFrame(pcm, last = false) }
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        awaitingFinal.set(true)
        closeJob?.cancel()
        closeJob = scope.launch(Dispatchers.IO) {
            // WS 未就绪时短暂等待握手，避免清空预缓冲导致空音频
            var waited = 0
            while (!wsReady.get() && waited < 500) {
                delay(50); waited += 50
            }
            if (wsReady.get()) {
                var flushed: Array<ByteArray>? = null
                synchronized(prebufferLock) {
                    if (prebuffer.isNotEmpty()) {
                        flushed = prebuffer.toTypedArray()
                        prebuffer.clear()
                    }
                }
                flushed?.forEach { b -> runCatching { sendAudioFrame(b, last = false) } }
                if (!audioLastSent.get()) {
                    runCatching {
                        sendAudioFrame(byteArrayOf(), last = true)
                        audioLastSent.set(true)
                    }
                }
            }
            // 严格等待服务端最终结果，兜底 10s 超时
            var left = 10_000L
            while (awaitingFinal.get() && left > 0) {
                delay(50); left -= 50
            }
            runCatching { ws?.close(1000, "stop") }
            ws = null
            wsReady.set(false)
        }
    }

    override fun cancel() {
        running.set(false)
        awaitingFinal.set(false)
        closeJob?.cancel()
        synchronized(prebufferLock) { prebuffer.clear() }
        val socket = ws
        ws = null
        wsReady.set(false)
        scope.launch(Dispatchers.IO) {
            runCatching { socket?.close(1000, "cancel") }
        }
    }

    private fun openWebSocket() {
        val auth = authProvider()
        if (if (auth.useNewAuth) auth.apiKey.isBlank() else auth.appKey.isBlank() || auth.accessKey.isBlank()) {
            listener.onError("请在设置 → 语音输入填写火山引擎的 API Key")
            running.set(false)
            return
        }
        val resourceId = VolcAsrModelCatalog.resourceIdOf(modelProvider())
        val req = Request.Builder()
            .url(WS_ENDPOINT)
            .headers(volcHeaders(auth, resourceId, UUID.randomUUID().toString()))
            .build()

        ws = http.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    try {
                        val payload = gzip(buildFullClientRequestJson().toByteArray(Charsets.UTF_8))
                        val frame = buildClientFrame(MSG_TYPE_FULL_CLIENT_REQ, 0, SERIALIZE_JSON, COMPRESS_GZIP, payload)
                        webSocket.send(ByteString.of(*frame))
                        wsReady.set(true)
                        // 握手期间已 stop()：立即冲刷预缓冲并发送最后标记
                        if (!running.get() && awaitingFinal.get()) flushAndSendLast()
                    } catch (t: Throwable) {
                        Timber.e(t, "volc asr: failed to send full client request")
                        listener.onError("无法连接火山引擎：${t.message ?: ""}")
                        stop()
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    try {
                        handleServerMessage(bytes)
                    } catch (t: Throwable) {
                        Timber.e(t, "volc asr: failed to handle server message")
                        listener.onError("识别失败：${t.message ?: ""}")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try { webSocket.close(code, reason) } catch (_: Throwable) {}
                    wsReady.set(false)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "volc asr: websocket failed")
                    wsReady.set(false)
                    if (running.get() || awaitingFinal.get()) {
                        listener.onError("火山引擎连接失败：${t.message ?: response?.message ?: ""}")
                    }
                    running.set(false)
                    awaitingFinal.set(false)
                    closeJob?.cancel()
                    ws = null
                }
            },
        )
    }

    private fun flushAndSendLast() {
        if (!wsReady.get()) return
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        flushed?.forEach { b -> runCatching { sendAudioFrame(b, last = false) } }
        if (!audioLastSent.get()) {
            runCatching {
                sendAudioFrame(byteArrayOf(), last = true)
                audioLastSent.set(true)
            }
        }
    }

    private fun sendAudioFrame(pcm: ByteArray, last: Boolean) {
        val webSocket = ws ?: return
        if (!wsReady.get()) return
        val payload = gzip(pcm)
        val frame = buildClientFrame(
            MSG_TYPE_AUDIO_ONLY_CLIENT_REQ,
            if (last) FLAG_AUDIO_LAST else 0,
            SERIALIZE_NONE,
            COMPRESS_GZIP,
            payload,
        )
        webSocket.send(ByteString.of(*frame))
    }

    private fun buildFullClientRequestJson(): String {
        val auth = authProvider()
        val user = JSONObject().apply { put("uid", auth.appKey.ifBlank { "trime" }) }
        val audio = JSONObject().apply {
            put("format", "pcm")
            put("rate", 16000)
            put("bits", 16)
            put("channel", 1)
            val lang = languageProvider()
            if (lang.isNotBlank()) put("language", lang)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", ddcProvider())
        }
        return JSONObject()
            .put("user", user)
            .put("audio", audio)
            .put("request", request)
            .toString()
    }

    private fun handleServerMessage(bytes: ByteString) {
        val arr = bytes.toByteArray()
        if (arr.size < 8) return
        val b0 = arr[0].toInt() and 0xFF
        val b1 = arr[1].toInt() and 0xFF
        val b2 = arr[2].toInt() and 0xFF
        val headerSizeBytes = (b0 and 0x0F) * 4
        val msgType = (b1 ushr 4) and 0x0F
        val flags = b1 and 0x0F
        val serialization = (b2 ushr 4) and 0x0F
        val compression = b2 and 0x0F
        var offset = headerSizeBytes
        if (msgType == MSG_TYPE_FULL_SERVER_RESP) {
            if (arr.size < offset + 8) return
            offset += 4 // sequence
            val payloadSize = readUInt32BE(arr, offset)
            offset += 4
            if (arr.size < offset + payloadSize) return
            var payload = arr.copyOfRange(offset, offset + payloadSize)
            if (compression == COMPRESS_GZIP) payload = gunzip(payload)
            if (serialization == SERIALIZE_JSON) {
                val text = parseTextFromJson(String(payload, Charsets.UTF_8))
                val isFinal = (flags and FLAG_SERVER_FINAL_MASK) == FLAG_SERVER_FINAL_MASK
                if (!running.get() && !isFinal) return
                if (isFinal) {
                    listener.onFinal(text)
                    running.set(false)
                    awaitingFinal.set(false)
                    scope.launch(Dispatchers.IO) {
                        runCatching { ws?.close(1000, "final") }
                        ws = null
                        wsReady.set(false)
                    }
                } else if (text.isNotBlank()) {
                    listener.onPartial(text)
                }
            }
        } else if (msgType == MSG_TYPE_ERROR_SERVER) {
            if (arr.size < offset + 8) return
            val code = readUInt32BE(arr, offset)
            val size = readUInt32BE(arr, offset + 4)
            val start = offset + 8
            val end = (start + size).coerceAtMost(arr.size)
            val msg = runCatching { String(arr.copyOfRange(start, end), Charsets.UTF_8) }.getOrDefault("")
            val lowerMsg = msg.lowercase()
            if (code == 45000000 && ("decode ws request failed" in lowerMsg || "unable to decode" in lowerMsg)) return
            awaitingFinal.set(false)
            running.set(false)
            ws = null
            wsReady.set(false)
            listener.onError("火山引擎错误 $code：$msg")
        }
    }

    private fun parseTextFromJson(json: String): String = runCatching {
        val o = JSONObject(json)
        if (o.has("result")) o.getJSONObject("result").optString("text", "") else ""
    }.getOrDefault("")

    private fun buildClientFrame(messageType: Int, flags: Int, serialization: Int, compression: Int, payload: ByteArray): ByteArray {
        val header = ByteArray(4)
        header[0] = (((PROTOCOL_VERSION and 0x0F) shl 4) or (HEADER_SIZE_UNITS and 0x0F)).toByte()
        header[1] = (((messageType and 0x0F) shl 4) or (flags and 0x0F)).toByte()
        header[2] = (((serialization and 0x0F) shl 4) or (compression and 0x0F)).toByte()
        header[3] = 0
        val size = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.size).array()
        return header + size + payload
    }

    private fun readUInt32BE(arr: ByteArray, offset: Int): Int = ((arr[offset].toInt() and 0xFF) shl 24) or
        ((arr[offset + 1].toInt() and 0xFF) shl 16) or
        ((arr[offset + 2].toInt() and 0xFF) shl 8) or
        (arr[offset + 3].toInt() and 0xFF)

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray = try {
        GZIPInputStream(data.inputStream()).readBytes()
    } catch (_: Throwable) {
        data
    }
}
