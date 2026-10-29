/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.osfans.trime.core.data.MieUserDirectory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** SyncClipboard text/image/single-file wire format, implemented from its public protocol. */
internal class ClipboardTransfer(private val context: Context,private val client: OkHttpClient,private val request: (List<String>)->Request.Builder) {
    data class Local(val text: String?=null,val uri: Uri?=null) {
        val key get()=if(uri!=null) "uri:$uri" else "text:$text"
    }
    data class Remote(val local: Local,val file: File?=null) {
        fun clip(context: Context): ClipData = local.uri?.let { ClipData.newUri(context.contentResolver,"同步附件",it) }
            ?: ClipData.newPlainText("同步剪贴板",local.text.orEmpty())
    }
    companion object {
        // Leave room for UTF-16 and ClipDescription within Android's Binder transaction.
        const val MAX_TEXT=256*1024
        const val MAX_FILE=32L*1024*1024
        private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
        fun signature(obj: JSONObject)=obj.getString("type")+"|"+obj.optString("hash").takeUnless { it=="null" }.orEmpty()
            .uppercase(java.util.Locale.ROOT).ifEmpty { sha(obj.toString().toByteArray()) }
        fun read(context: Context): Local? {
            val clip=(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return null
            if(Build.VERSION.SDK_INT>=24 && clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE",false)==true) return null
            if(clip.itemCount!=1) return null
            val item=clip.getItemAt(0)
            item.uri?.let { uri ->
                if(uri.scheme!="content") return null
                // Prevent a foreign clipboard item from making us upload private app files.
                if(uri.authority.orEmpty().startsWith(context.packageName) &&
                    !(uri.authority=="${context.packageName}.fileprovider" &&
                        (uri.path.orEmpty().startsWith("/files_path/clipboard_sync_downloads/") || uri.path.orEmpty().contains("/mie/剪贴板附件/")))) return null
                return Local(uri=uri)
            }
            return item.text?.toString()?.takeIf { it.isNotBlank() && it.length<=MAX_TEXT }?.let { Local(text=it) }
        }
    }
    private fun name(raw: String): String {
        require(raw.isNotBlank() && raw.length<=240 && raw!="." && raw!=".." && raw.none { it=='/' || it=='\\' || it.code<32 }) { "附件文件名无效" }
        return raw
    }
    private fun check(code: Int) { kotlin.check(code in 200..299) { when(code) { 401,403->"同步认证失败"; 404,409->"附件目录不存在，请检查 WebDAV 的 file 子目录"; else->"附件传输 HTTP $code" } } }
    fun upload(local: Local): String {
        val folder=File(context.cacheDir,"clipboard_sync_upload/${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val obj=JSONObject(); var data: File?=null; var transfer=""
            if(local.uri!=null) {
                val uri=local.uri
                val display=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "clipboard-file"
                val file=File(folder,name(display)); data=file
                val digest=MessageDigest.getInstance("SHA-256"); var size=0L
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
                    val buf=ByteArray(65536)
                    while(true) { val n=input.read(buf); if(n<0) break; size+=n; require(size<=MAX_FILE) { "附件超过32MB" }; out.write(buf,0,n); digest.update(buf,0,n) }
                } } ?: error("附件读取失败，剪贴板授权可能已失效")
                transfer=digest.digest().joinToString("") { "%02X".format(it) }
                obj.put("type",if(context.contentResolver.getType(uri).orEmpty().startsWith("image/")) "Image" else "File")
                    .put("text",file.name).put("dataName",file.name).put("size",size).put("hasData",true)
                    .put("hash",sha((file.name+"|"+transfer).toByteArray(Charsets.UTF_8)))
            } else {
                val text=local.text ?: error("没有可同步内容"); val bytes=text.toByteArray(Charsets.UTF_8)
                require(bytes.size<=MAX_TEXT) { "文本超过256KB，请保存为文件后同步" }; val hash=sha(bytes)
                obj.put("type","Text").put("hash",hash).put("size",text.length)
                if(text.length>10240) {
                    data=File(folder,"Text_$hash.txt").apply { writeBytes(bytes) }; transfer=hash
                    obj.put("hasData",true).put("text",text.take(10240)).put("dataName",data.name)
                } else obj.put("hasData",false).put("text",text)
            }
            data?.let { file ->
                // Existing WebDAV folders return 405; the PUT is authoritative.
                client.newCall(request(listOf("file","")).method("MKCOL",null).build()).execute().close()
                client.newCall(request(listOf("file",file.name)).put(file.asRequestBody("application/octet-stream".toMediaType())).build()).execute().use { check(it.code) }
                obj.put("transferDataHash",transfer)
            }
            client.newCall(request(listOf("SyncClipboard.json")).put(obj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()).execute().use { check(it.code) }
            return signature(obj)
        } finally { folder.deleteRecursively() }
    }
    fun receive(obj: JSONObject): Remote {
        val type=obj.getString("type"); require(type in setOf("Text","Image","File")) { "多文件分组请先在发送端打包为ZIP" }
        val expected=obj.optString("hash").takeUnless { it=="null" }.orEmpty(); var text=obj.getString("text"); var data: File?=null
        try {
            if(obj.getBoolean("hasData")) {
                val filename=name(obj.getString("dataName"))
                val root=runCatching { File(MieUserDirectory.directory(context),"剪贴板附件") }.getOrElse { File(context.filesDir,"clipboard_sync_downloads") }
                val folder=File(root,java.util.UUID.randomUUID().toString()).apply { kotlin.check(mkdirs()) }
                val file=File(folder,filename); data=file
                val digest=MessageDigest.getInstance("SHA-256"); var size=0L
                val limit=if(type=="Text") MAX_TEXT.toLong() else MAX_FILE
                client.newCall(request(listOf("file",filename)).get().build()).execute().use { response ->
                    check(response.code); require((response.body?.contentLength() ?: -1)<=limit) { "附件超过大小限制" }
                    response.body?.byteStream()?.use { input -> file.outputStream().use { out ->
                        val buf=ByteArray(65536)
                        while(true) { val n=input.read(buf); if(n<0) break; size+=n; require(size<=limit) { "附件超过大小限制" }; out.write(buf,0,n); digest.update(buf,0,n) }
                    } } ?: error("远端附件没有内容")
                }
                val actual=digest.digest().joinToString("") { "%02X".format(it) }
                val transfer=obj.optString("transferDataHash").takeUnless { it=="null" }.orEmpty()
                require(transfer.isEmpty() || transfer.equals(actual,true)) { "附件传输校验失败" }
                if(type!="Text") {
                    require(expected.isEmpty() || expected.equals(sha((filename+"|"+actual).toByteArray(Charsets.UTF_8)),true)) { "附件内容校验失败" }
                    return Remote(Local(uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)),file)
                }
                text=file.readText(Charsets.UTF_8); folder.deleteRecursively(); data=null
            } else require(type=="Text") { "附件清单不完整" }
            val bytes=text.toByteArray(Charsets.UTF_8)
            require(bytes.size<=MAX_TEXT && (expected.isEmpty() || expected.equals(sha(bytes),true))) { "文本校验失败或超过256KB，请使用文件传输" }
            return Remote(Local(text=text))
        } catch(error: Throwable) { data?.parentFile?.deleteRecursively(); throw error }
    }
}
