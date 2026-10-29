/* SPDX-License-Identifier: GPL-3.0-or-later
 * Tencent protocol and catalog adapted from BryceWG/BiBi-Keyboard, Apache-2.0.
 * PR #134/#135; recording and session ownership use Trime Mie's AsrEngine interface.
 */
package com.osfans.trime.ime.voice.builtin

import com.osfans.trime.util.DiagnosticLog
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** 16kHz PCM, bounded queues and a single terminal callback, including stop-before-handshake. */
class TencentAsrEngine(private val scope: CoroutineScope,private val listener: AsrListener) : AsrEngine {
    private val terminal=AtomicBoolean(false)
    private val cancelled=AtomicBoolean(false)
    private val active=AtomicBoolean(false)
    private val accepting=AtomicBoolean(false)
    private val queued=AtomicInteger(0)
    private val pcm=Channel<ByteArray>(256)
    private val file=ByteArrayOutputStream()
    private val ready=CompletableDeferred<Unit>()
    private val final=CompletableDeferred<String>()
    private var socket: WebSocket?=null
    private var call: Call?=null
    private var job: Job?=null
    private lateinit var credentials: TencentProtocol.Credentials
    private var streaming=true
    private var model="16k_zh"
    private val http=sharedHttp
    companion object {
        private val sharedHttp=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
    override val isRunning get()=active.get() && !terminal.get()

    override fun start() {
        if (!active.compareAndSet(false,true)) return
        credentials=TencentProtocol.Credentials(AsrSettings.tencentField("app_id").trim(),AsrSettings.tencentField("secret_id").trim(),AsrSettings.tencentField("secret_key").trim())
        if (!credentials.valid()) { fail("请填写腾讯云 AppID、SecretID 和 SecretKey"); return }
        val category=TencentModels.category(AsrSettings.tencentField("category"))
        streaming=category.streaming; model=TencentModels.model(category,AsrSettings.model("tencent")).id
        accepting.set(true)
        DiagnosticLog.event("voice","tencent_start","mode=${if(streaming) "stream" else "flash"} model=$model")
        if (streaming) job=scope.launch(Dispatchers.IO) { stream() }
    }
    override fun feed(pcm: ByteArray) {
        if (!accepting.get() || terminal.get()) return
        if (streaming) {
            val count=queued.addAndGet(pcm.size)
            if (count>320000 || !this.pcm.trySend(pcm.copyOf()).isSuccess) {
                queued.addAndGet(-pcm.size); fail("腾讯语音连接过慢，请重试")
            }
        } else synchronized(file) {
            if (file.size()+pcm.size>32*1024*1024) fail("录音超过32MB，请分段输入") else file.write(pcm)
        }
    }
    override fun stop() {
        if (!accepting.getAndSet(false) || terminal.get()) return
        if (streaming) pcm.close()
        else {
            val bytes=synchronized(file) { file.toByteArray().also { file.reset() } }
            job=scope.launch(Dispatchers.IO) { flash(bytes) }
        }
    }
    override fun cancel() {
        cancelled.set(true); terminal.set(true); active.set(false); accepting.set(false)
        pcm.cancel(); ready.cancel(); final.cancel(); socket?.cancel(); call?.cancel(); job?.cancel()
        synchronized(file) { file.reset() }
    }
    private fun complete(text: String) {
        if (cancelled.get() || !terminal.compareAndSet(false,true)) return
        active.set(false); accepting.set(false); pcm.cancel(); socket?.close(1000,"done")
        DiagnosticLog.event("voice","tencent_final","chars=${text.length}")
        listener.onFinal(text)
    }
    private fun fail(message: String,code: Int=0) {
        if (cancelled.get() || !terminal.compareAndSet(false,true)) return
        active.set(false); accepting.set(false); pcm.cancel(); socket?.cancel(); call?.cancel()
        ready.cancel(); final.cancel(); job?.cancel()
        DiagnosticLog.event("voice","tencent_error","code=$code mode=${if(streaming) "stream" else "flash"}")
        var safe=message.take(300)
        if (::credentials.isInitialized) listOf(credentials.secretId,credentials.secretKey).filter { it.isNotEmpty() }.forEach { safe=safe.replace(it,"***") }
        listener.onError(safe)
    }
    private suspend fun stream() {
        try {
            val transcript=TencentProtocol.Transcript()
            val url=TencentProtocol.streamUrl(credentials,model,System.currentTimeMillis()/1000,UUID.randomUUID().toString(),java.security.SecureRandom().nextInt(Int.MAX_VALUE))
            socket=http.newWebSocket(Request.Builder().url(url).build(),object: WebSocketListener() {
                override fun onMessage(webSocket: WebSocket,text: String) {
                    if (terminal.get()) return
                    runCatching {
                        require(text.length<=1024*1024)
                        val message=JSONObject(text)
                        val code=message.optInt("code",0)
                        val shape=when { message.has("sentences")->"sentences"; message.has("result")->"result"; else->"handshake" }
                        DiagnosticLog.event("voice","tencent_frame","shape=$shape code=$code final=${message.optInt("final",0)}")
                        if (code!=0) { fail("腾讯语音：${message.optString("message","识别失败")}",code); return }
                        ready.complete(Unit)
                        val result=transcript.accept(message)
                        if (message.optInt("final")==1) { final.complete(result); complete(result) }
                        else if (result.isNotBlank()) listener.onPartial(result)
                    }.onFailure { fail("腾讯语音响应格式异常") }
                }
                override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) {
                    if (!final.isCompleted) fail("腾讯语音连接失败，请检查网络和凭据",response?.code ?: 0)
                }
                override fun onClosed(webSocket: WebSocket,code: Int,reason: String) {
                    if (!terminal.get() && !final.isCompleted) fail("腾讯语音连接提前关闭，请重试",code)
                }
            })
            withTimeout(15000) { ready.await() }
            val packet=ByteArrayOutputStream(6400)
            var nextSend=android.os.SystemClock.elapsedRealtime()
            suspend fun send(bytes: ByteArray) {
                val now=android.os.SystemClock.elapsedRealtime()
                if (nextSend>now) delay(nextSend-now)
                check(socket?.send(bytes.toByteString())==true)
                nextSend=android.os.SystemClock.elapsedRealtime()+bytes.size*1000L/32000L
            }
            for (frame in pcm) {
                queued.addAndGet(-frame.size)
                var offset=0
                while(offset<frame.size) {
                    val count=minOf(6400-packet.size(),frame.size-offset)
                    packet.write(frame,offset,count); offset+=count
                    if (packet.size()==6400) { send(packet.toByteArray()); packet.reset() }
                }
            }
            if (packet.size()>0) send(packet.toByteArray())
            check(socket?.send("{\"type\":\"end\"}")==true)
            complete(withTimeout(20000) { final.await() })
        } catch (error: CancellationException) {
            if (!terminal.get()) fail(if(error is TimeoutCancellationException) "腾讯语音等待超时，请重试" else "腾讯语音会话已中断")
        } catch (error: Exception) { if (!terminal.get()) fail("腾讯语音发送失败，请检查网络") }
        finally { socket?.close(1000,"done") }
    }
    private suspend fun flash(pcm: ByteArray) {
        if (pcm.size<3200) { complete(""); return }
        try {
            currentCoroutineContext().ensureActive()
            if (cancelled.get() || terminal.get()) return
            val (url,authorization)=TencentProtocol.flashRequest(credentials,model,System.currentTimeMillis()/1000)
            val request=Request.Builder().url(url).header("Authorization",authorization)
                .post(pcmToWavBytes(pcm).toRequestBody("application/octet-stream".toMediaType())).build()
            val client=http.newBuilder().readTimeout(120,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS).build()
            val current=client.newCall(request); call=current
            if (cancelled.get() || terminal.get() || !currentCoroutineContext().isActive) {
                current.cancel()
                return
            }
            current.execute().use { response ->
                if (cancelled.get() || terminal.get()) return
                if (!response.isSuccessful) { fail("腾讯文件识别请求失败（HTTP ${response.code}）",response.code); return }
                val body=response.body ?: error("empty")
                require(body.contentLength()<=4*1024*1024)
                val source=body.source(); source.request(4L*1024*1024+1)
                require(source.buffer.size<=4L*1024*1024)
                val text=source.buffer.readUtf8()
                val result=JSONObject(text)
                val code=result.optInt("code",0)
                val apiError=result.optJSONObject("Response")?.optJSONObject("Error")
                if (code!=0 || apiError!=null) { fail("腾讯文件识别：${apiError?.optString("Message") ?: result.optString("message","识别失败")}",code); return }
                val list=result.optJSONArray("flash_result")
                val output=(0 until (list?.length() ?: 0)).mapNotNull { list?.optJSONObject(it)?.optString("text") }.joinToString("").trim()
                complete(output)
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (!terminal.get()) fail("腾讯文件识别失败，请检查网络或凭据") }
    }
}
