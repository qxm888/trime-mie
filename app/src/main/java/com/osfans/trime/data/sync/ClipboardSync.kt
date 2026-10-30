/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.sync

import android.content.ClipboardManager
import android.content.Context
import com.osfans.trime.util.DiagnosticLog
import com.osfans.trime.util.readBoundedText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Independently implemented SyncClipboard v3.1.1+ WebDAV-compatible client. */
object ClipboardSync : ClipboardManager.OnPrimaryClipChangedListener {
    data class Config(val enabled: Boolean = false, val url: String = "", val user: String = "", val password: String = "", val allowHttp: Boolean = false)
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences("clipboard_sync", Context.MODE_PRIVATE)
    private val clipboard get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val status = MutableStateFlow("同步未开启")
    val state = status.asStateFlow()
    private var loop: Job? = null
    private var uploadJob: Job? = null
    @Volatile private var serviceAlive = false
    @Volatile private var windowVisible = false
    @Volatile private var generation = 0L
    @Volatile private var revision = 0L
    @Volatile private var pending: ClipboardTransfer.Local? = null
    @Volatile private var received: String? = null
    @Volatile private var blockRemote = false
    private var seen = ""
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .dns(LocalNetworkAccess.dns { context }).connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()

    fun init(ctx: Context) { context = com.osfans.trime.util.storageContext(ctx) }
    fun config() = Config(prefs.getBoolean("enabled",false), prefs.getString("url","").orEmpty(), prefs.getString("user","").orEmpty(), prefs.getString("password","").orEmpty(), prefs.getBoolean("allow_http",false))
    fun validate(c: Config) {
        val url = c.url.trim().toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "服务器地址不能包含账号、查询参数或片段" }
        require(url.isHttps || c.allowHttp) { "请使用 HTTPS；局域网 HTTP 需主动开启允许明文传输" }
    }
    fun save(c: Config) {
        val normalized = c.copy(url=c.url.trim())
        if(normalized.url.isNotBlank() || normalized.enabled) validate(normalized)
        val changed = config()!=normalized
        check(prefs.edit().putBoolean("enabled",normalized.enabled).putString("url",normalized.url).putString("user",normalized.user)
            .putString("password",normalized.password).putBoolean("allow_http",normalized.allowHttp).commit())
        if(changed) prefs.edit().remove("seen").apply()
        // Explicit Save is also a retry: reset backoff even if no field changed.
        // Preserve an unsent clipboard item when retrying the same endpoint.
        restart(resetContent=changed)
    }
    fun retry() = restart(resetContent=false)
    fun start() {
        if(android.os.Build.VERSION.SDK_INT>=24 && !(context.getSystemService(Context.USER_SERVICE) as android.os.UserManager).isUserUnlocked) return
        serviceAlive = true; restart()
    }
    fun stop() {
        serviceAlive = false; generation++; loop?.cancel(); uploadJob?.cancel()
        client.dispatcher.cancelAll()
        clipboard.removePrimaryClipChangedListener(this); pending = null
    }
    private fun restart(resetContent: Boolean = true) {
        generation++; loop?.cancel(); uploadJob?.cancel()
        if (resetContent) { pending = null; received = null }
        client.dispatcher.cancelAll()
        clipboard.removePrimaryClipChangedListener(this)
        val c=config(); seen=prefs.getString("seen","").orEmpty()
        blockRemote=runCatching { clipboard.hasPrimaryClip() && localContent()==null }.getOrDefault(false)
        status.value=if(c.enabled) "等待输入法服务连接" else "同步未开启"
        if(!serviceAlive || !c.enabled) return
        clipboard.addPrimaryClipChangedListener(this)
        // 服务器轮询只在键盘可见时运行；隐藏时本地上传仍走事件驱动。
        if(windowVisible) startPolling()
    }
    /** 键盘窗口显隐时由输入法服务调用，避免后台 24 小时轮询服务器。 */
    fun setWindowVisible(visible: Boolean) {
        if(windowVisible == visible) return
        windowVisible = visible
        if(visible) startPolling() else { loop?.cancel(); loop = null }
    }
    private fun startPolling() {
        val c=config()
        if(!serviceAlive || !c.enabled || loop?.isActive == true) return
        val epoch=generation
        loop=scope.launch {
            var failures=0
            while(isActive && epoch==generation) {
                runCatching { mutex.withLock { sync(c,epoch) } }.onSuccess { failures=0 }.onFailure {
                    if(it is CancellationException) throw it
                    failures++
                    reportError(it,epoch)
                }
                delay(if(failures==0) 5000L else (5000L * failures).coerceAtMost(60000))
            }
        }
    }
    private fun localContent() = ClipboardTransfer.read(context)
    override fun onPrimaryClipChanged() {
        revision++
        val text=runCatching { localContent() }.onFailure {
            DiagnosticLog.event("clipboard_sync","clipboard_unavailable","type=${it.javaClass.simpleName}")
        }.getOrNull() ?: run { pending=null; received=null; blockRemote=true; return }
        blockRemote=false
        if(text.key==received) return
        received=null
        pending=text
        uploadJob?.cancel()
        val epoch=generation; val c=config()
        uploadJob=scope.launch {
            delay(300)
            if(c.enabled && epoch==generation) runCatching { mutex.withLock { sync(c,epoch) } }.onFailure {
                if(it is CancellationException) throw it
                reportError(it,epoch)
            }
        }
    }
    private fun request(c: Config,vararg path: String): Request.Builder {
        validate(c)
        LocalNetworkAccess.checkHost(context,c.url.trim().toHttpUrl().host)
        val url=(c.url.trimEnd('/')+"/").toHttpUrl().newBuilder().apply {
            (path.toList().ifEmpty { listOf("SyncClipboard.json") }).forEach { addPathSegment(it) }
        }.build()
        return Request.Builder().url(url).header("Authorization",Credentials.basic(c.user,c.password,Charsets.UTF_8))
    }
    private fun checkStatus(code: Int) { check(code in 200..299) { when(code) { 401,403 -> "服务器认证失败，请检查账号密码"; else -> "服务器返回 HTTP $code" } } }
    private fun checkEpoch(epoch: Long) {
        if(epoch!=generation) throw CancellationException("同步配置或服务状态已改变")
    }
    private fun transfer(c: Config,epoch: Long)=ClipboardTransfer(context,client) { path ->
        checkEpoch(epoch);request(c,*path.toTypedArray())
    }
    private fun send(c: Config,content: ClipboardTransfer.Local,epoch: Long): String = transfer(c,epoch).upload(content)
    private suspend fun sync(c: Config,epoch: Long,sendPending: Boolean=true,manualRevision: Long?=null) {
        checkEpoch(epoch)
        val local=withContext(Dispatchers.Main) {
            checkEpoch(epoch)
            if(manualRevision==null && blockRemote && pending==null) {
                status.value="本地内容不参与自动同步，可手动接收远端"
                null
            } else revision to pending
        } ?: return
        if(sendPending) local.second?.let { text ->
            val digest=send(c,text,epoch)
            withContext(Dispatchers.Main) {
                checkEpoch(epoch)
                if(local.first==revision && pending==text) pending=null
                seen=digest;prefs.edit().putString("seen",seen).apply()
            }
        }
        currentCoroutineContext().ensureActive();checkEpoch(epoch)
        val stamp=manualRevision ?: withContext(Dispatchers.Main) { revision }
        val raw=client.newCall(request(c).get().build()).execute().use {
            checkEpoch(epoch)
            if(it.code==404) {
                withContext(Dispatchers.Main) { checkEpoch(epoch);status.value="连接正常，远端暂无剪贴板" }
                return
            }
            checkStatus(it.code)
            it.body?.byteStream()?.use { input -> input.readBoundedText(2*1024*1024) } ?: error("服务器返回空内容")
        }
        currentCoroutineContext().ensureActive();checkEpoch(epoch)
        val obj=JSONObject(raw)
        require(obj.has("type") && obj.has("text") && obj.has("hasData")) { "协议不兼容，请使用 SyncClipboard v3.1.1 或以上版本" }
        val digest=ClipboardTransfer.signature(obj)
        if(manualRevision!=null || digest!=seen) {
            val remote=transfer(c,epoch).receive(obj)
            var applied=false
            try {
                withContext(Dispatchers.Main) {
                    checkEpoch(epoch)
                    if(stamp!=revision || manualRevision==null && (pending!=null || blockRemote)) {
                        status.value="已保留更新的本地剪贴板，跳过此次接收"
                    } else {
                        val before=received
                        received=remote.local.key
                        try {
                            if(localContent()?.key!=remote.local.key) clipboard.setPrimaryClip(remote.clip(context))
                        } catch(error:Exception) { received=before;throw error }
                        applied=true
                        if(manualRevision!=null) { pending=null;blockRemote=false }
                        seen=digest;prefs.edit().putString("seen",seen).apply()
                        status.value="同步正常 · 文本 / 图片 / 文件"
                    }
                }
            } finally {
                // Cancellation between receiving an attachment and applying the clipboard
                // must also remove the newly created temporary download directory.
                if(!applied) remote.file?.parentFile?.deleteRecursively()
            }
        } else withContext(Dispatchers.Main) { checkEpoch(epoch);status.value="同步正常 · 文本 / 图片 / 文件" }
    }
    private data class Command(val config:Config,val epoch:Long,val revision:Long)
    private suspend fun command()=withContext(Dispatchers.Main) { Command(config(),generation,revision) }
    private suspend fun upload(content:ClipboardTransfer.Local,command:Command):String = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive();checkEpoch(command.epoch)
            val digest=send(command.config,content,command.epoch)
            withContext(Dispatchers.Main) {
                checkEpoch(command.epoch)
                seen=digest;prefs.edit().putString("seen",seen).apply()
                if(revision==command.revision) pending=null
            }
            "已上传"
        }
    }
    suspend fun uploadNow(): String {
        val (snapshot,text)=withContext(Dispatchers.Main) {
            Command(config(),generation,revision) to (localContent() ?: error("剪贴板为空、敏感内容或不支持的多文件内容"))
        }
        return upload(text,snapshot)
    }
    suspend fun uploadFile(uri: android.net.Uri): String = upload(ClipboardTransfer.Local(uri=uri),command()).let { "附件已上传" }
    suspend fun downloadNow(): String {
        val snapshot=command()
        return withContext(Dispatchers.IO) { mutex.withLock {
            currentCoroutineContext().ensureActive();checkEpoch(snapshot.epoch)
            // Pending local content and the seen marker are changed only after a successful
            // explicit receive. A timeout/404/cancel leaves the unsent clipboard available.
            sync(snapshot.config,snapshot.epoch,false,snapshot.revision)
            withContext(Dispatchers.Main) { checkEpoch(snapshot.epoch);status.value }
        } }
    }
    private suspend fun reportError(error: Throwable,epoch:Long) = withContext(Dispatchers.Main) {
        if(epoch!=generation) return@withContext
        status.value=if(error is LocalNetworkAccess.PermissionRequired) error.message.orEmpty() else if(error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty() else "同步连接失败，请检查网络、地址与证书"
        DiagnosticLog.event("clipboard_sync","error","type=${error.javaClass.simpleName} localPermission=${LocalNetworkAccess.granted(context)}")
    }
}
