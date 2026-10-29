/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.preference.PreferenceManager
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.backup.FullSettingsBackup
import com.osfans.trime.data.sync.ClipboardTransfer
import com.osfans.trime.data.theme.Theme
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Isolated fixtures: no real credentials, clipboard content, model, or default IME changes. */
class Mie30Runner : Instrumentation() {
    private var captureUi=false
    override fun onCreate(args: Bundle?) { captureUi=args?.getString("ui")=="true"; super.onCreate(args); start() }
    private fun pass(name: String) = sendStatus(0,Bundle().apply { putString("case","PASS: $name") })
    override fun onStart() {
        val result=Bundle()
        val temp=File(targetContext.cacheDir,"mie30-${System.nanoTime()}").apply { mkdirs() }
        try {
            protocol(temp)
            portableRestore(temp)
            fonts()
            if(captureUi) screens()
            result.putString("result","PASS")
        } catch(error: Throwable) {
            result.putString("result","FAIL: ${error.javaClass.simpleName}: ${error.message}")
            result.putString("trace",error.stackTraceToString().take(5000))
        } finally { temp.deleteRecursively() }
        finish(if(result.getString("result")=="PASS") android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,result)
    }
    private fun screens() {
        val folder=File(targetContext.filesDir,"mie30-ui").apply { mkdirs() }
        // Mark the setup guide as seen for this process; do not change the default IME.
        if (com.osfans.trime.ui.setup.SetupActivity.shouldShowUp()) {
            val setup=startActivitySync(android.content.Intent(targetContext,com.osfans.trime.ui.setup.SetupActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { setup.finish() }; waitForIdleSync()
        }
        listOf("MainActivity", "FontSettingsActivity", "ClipboardSyncActivity", "FullBackupActivity",
            "VoiceSettingsActivity", "CustomKeyboardActivity", "ToolbarEditActivity").forEach { name ->
            val activity=startActivitySync(android.content.Intent().setClassName(targetContext,"com.osfans.trime.ui.main.$name").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync { (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(activity.window.decorView.windowToken,0) }
            waitForIdleSync(); android.os.SystemClock.sleep(1200)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            fun toolbar(view: android.view.View): androidx.appcompat.widget.Toolbar? {
                if (view is androidx.appcompat.widget.Toolbar) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) toolbar(view.getChildAt(i))?.let { return it }
                return null
            }
            runOnMainSync {
                val action=checkNotNull(toolbar(activity.window.decorView)?.menu?.findItem(com.osfans.trime.R.id.settings_test_input_entry))
                check(action.isVisible)
            }
            // Opening the transient tester must not resize or add a permanent entry to the page.
            runOnMainSync {
                toolbar(activity.window.decorView)!!.menu.performIdentifierAction(com.osfans.trime.R.id.settings_test_input_entry,0)
            }
            waitForIdleSync(); android.os.SystemClock.sleep(1200)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(folder,"$name-test-input.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
            }
            pass("top test-input action and transient panel: $name")
            runOnMainSync { activity.finish() }; waitForIdleSync()
        }
        pass("seven settings pages have the top test-input action")
    }
    private fun protocol(temp: File) {
        val socket=ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val files=ConcurrentHashMap<String,ByteArray>()
        val thread=Thread {
            while(!socket.isClosed) runCatching { socket.accept().use { connection ->
                connection.soTimeout=10000
                val input=connection.getInputStream().buffered()
                fun line(): String {
                    val bytes=java.io.ByteArrayOutputStream()
                    while(true) { val c=input.read(); if(c<0 || c==10) break; if(c!=13) bytes.write(c) }
                    return bytes.toString("UTF-8")
                }
                val first=line().split(' '); if(first.size<2) return@use
                var length=0
                while(true) { val header=line(); if(header.isEmpty()) break; if(header.startsWith("Content-Length:",true)) length=header.substringAfter(':').trim().toInt() }
                check(length in 0..(33*1024*1024))
                val body=ByteArray(length); var read=0
                while(read<length) { val n=input.read(body,read,length-read); check(n>0); read+=n }
                val path=first[1]; val method=first[0]
                val status=when(method) { "PUT"->{ files[path]=body; 201 }; "MKCOL"->201; else->if(files.containsKey(path)) 200 else 404 }
                val data=if(method=="GET") files[path] ?: ByteArray(0) else ByteArray(0)
                val output=connection.getOutputStream()
                output.write("HTTP/1.1 $status OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray()); output.write(data); output.flush()
            } }
        }.apply { isDaemon=true; start() }
        val client=OkHttpClient.Builder().callTimeout(10,TimeUnit.SECONDS).build()
        val transport=ClipboardTransfer(targetContext,client) { path ->
            val url=okhttp3.HttpUrl.Builder().scheme("http").host("127.0.0.1").port(socket.localPort).apply { path.forEach { addPathSegment(it) } }.build()
            Request.Builder().url(url)
        }
        try {
            listOf("V3.0 测试🙂", "测试🙂".repeat(4000)).forEach { text ->
                transport.upload(ClipboardTransfer.Local(text=text))
                val profile=JSONObject(files.getValue("/SyncClipboard.json").toString(Charsets.UTF_8))
                check(transport.receive(profile).local.text==text)
            }
            pass("text and large-text attachment protocol round trip")
            for(name in listOf("测试文件.txt","test-image.png")) {
                val file=File(temp,name)
                if(name.endsWith("png")) {
                    val bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888)
                    file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
                } else file.writeText("fixture-only 中文🙂")
                val uri=FileProvider.getUriForFile(targetContext,"${targetContext.packageName}.fileprovider",file)
                transport.upload(ClipboardTransfer.Local(uri=uri))
                val profile=JSONObject(files.getValue("/SyncClipboard.json").toString(Charsets.UTF_8))
                check(profile.getString("type")==if(name.endsWith("png")) "Image" else "File")
                val remote=transport.receive(profile)
                try { check(remote.file!!.readBytes().contentEquals(file.readBytes())) } finally { remote.file?.parentFile?.deleteRecursively() }
                val corrupt=JSONObject(profile.toString()).put("transferDataHash","00".repeat(32))
                check(runCatching { transport.receive(corrupt) }.isFailure)
                val traversal=JSONObject(profile.toString()).put("dataName","../unsafe")
                check(runCatching { transport.receive(traversal) }.isFailure)
            }
            pass("image/file bytes, Unicode filenames, corruption and traversal rejection")
        } finally { socket.close(); thread.join(2000); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    }
    private fun portableRestore(temp: File) {
        val tag="fixture-${System.nanoTime()}"
        val public=File(android.os.Environment.getExternalStorageDirectory(),"rime/mie/$tag").apply { mkdirs() }
        val names=mutableSetOf<String>()
        var fileRoot=File(temp,"files")
        val fake=object: ContextWrapper(targetContext) {
            override fun getFilesDir()=fileRoot.apply { mkdirs() }
            override fun getNoBackupFilesDir()=File(temp,"no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences {
                val renamed="mie30-$tag-$name"; names.add(renamed)
                return targetContext.getSharedPreferences(renamed,mode)
            }
        }
        try {
            val prefs=PreferenceManager.getDefaultSharedPreferences(fake)
            prefs.edit().putString("profile_data_storage_mode","DIRECT_EXTERNAL").putString("profile_direct_external_path",public.path)
                .putBoolean("fixture_boolean",true).putInt("fixture_int",42).putStringSet("fixture_set",setOf("甲","乙")).commit()
            val font=fake.getSharedPreferences("font_overrides",Context.MODE_PRIVATE)
            font.edit().putFloat("size.key",23f).putString("key",File(fake.filesDir,"custom_fonts/a.ttf").path).commit()
            val state=File(fake.filesDir,"custom_keyboards.json").apply { writeText("{\"probe\":\"${fake.filesDir}/custom_fonts/a.ttf\"}") }
            val literalPath="${fake.filesDir}/keep-as-text"
            File(fake.filesDir,"input_stats.json").writeText(JSONObject().put("literal",literalPath).toString())
            File(public,"fixture.dict.yaml").writeText("literal-path\t${public.path}/keep-as-word\n")
            File(fake.filesDir,"custom_fonts").mkdirs(); File(fake.filesDir,"custom_fonts/a.ttf").writeText("font-fixture")
            File(public,"test.schema.yaml").writeText("schema_id: mie30_fixture\n")
            val backup=FullSettingsBackup.create(fake,false,false)
            state.writeText("{\"probe\":\"changed\"}"); font.edit().putFloat("size.key",9f).commit()
            check(FullSettingsBackup.prepare(fake,backup.inputStream())>0)
            check(state.readText().contains("changed"))
            FullSettingsBackup.applyPending(fake)
            check(!state.readText().contains("changed") && font.getFloat("size.key",0f)==23f)
            check(prefs.getInt("fixture_int",0)==42 && prefs.getStringSet("fixture_set",emptySet())==setOf("甲","乙"))
            pass("isolated full backup/restore: typed prefs, keyboard JSON, assets, path preservation")
            fileRoot=File(temp,"new-device-files")
            val otherRime=File(public,"new-device-rime").apply { mkdirs() }
            prefs.edit().putString("profile_direct_external_path",otherRime.path).commit()
            FullSettingsBackup.prepare(fake,backup.inputStream()); FullSettingsBackup.applyPending(fake)
            check(JSONObject(File(fake.filesDir,"custom_keyboards.json").readText()).getString("probe").startsWith(fake.filesDir.path))
            check(font.getString("key","")!!.startsWith(fake.filesDir.path))
            check(File(fake.filesDir,"custom_fonts/a.ttf").readText()=="font-fixture")
            check(File(otherRime,"test.schema.yaml").isFile)
            check(JSONObject(File(fake.filesDir,"input_stats.json").readText()).getString("literal")==literalPath)
            check(File(otherRime,"fixture.dict.yaml").readText().contains(public.path+"/keep-as-word"))
            pass("new-device file roots and font paths relocate while current storage grant survives")
            // Simulate death after a single rename. Startup must roll back and finish the plan.
            FullSettingsBackup.prepare(fake,backup.inputStream())
            val pending=File(fake.noBackupFilesDir,"mie-restore-pending")
            val plan=JSONArray(File(pending,"plan.json").readText()); val first=plan.getJSONObject(0)
            val target=File(first.getString("target")); val prepared=File(first.getString("prepared")); val saved=File(target.parentFile,".mie30-old")
            val oldPrefs=File(fake.noBackupFilesDir,"mie-restore-rollback").apply { mkdirs() }
            val journal=JSONArray().put(JSONObject().put("target",target.path).put("prepared",prepared.path).put("exists",target.exists()).put("saved",saved.path))
            File(oldPrefs,"journal.json").writeText(journal.toString())
            check(target.renameTo(saved)); check(prepared.renameTo(target))
            FullSettingsBackup.applyPending(fake)
            check(!pending.exists() && !saved.exists())
            pass("restore interrupted between renames recovers and completes")
            val committed=File(fake.noBackupFilesDir,"mie-restore-rollback").apply { mkdirs() }
            File(committed,"committed").writeText("1")
            val restore=fake.getSharedPreferences("settings_restore_result",Context.MODE_PRIVATE)
            restore.edit().putBoolean("deploy",false).commit()
            FullSettingsBackup.applyPending(fake)
            check(restore.getBoolean("deploy",false) && !committed.exists())
            pass("committed restore interrupted before cleanup still requests full deployment")
        } finally {
            FullSettingsBackup.discardPrepared(fake)
            public.deleteRecursively()
            if(android.os.Build.VERSION.SDK_INT>=24) names.forEach { targetContext.deleteSharedPreferences(it) }
        }
    }
    private fun fonts() {
        val source=File("/system/fonts").listFiles().orEmpty().firstOrNull { it.isFile && it.extension in setOf("ttf","otf","ttc") } ?: error("No system font")
        val directory=File(DataManager.userDataDir,"fonts").apply { mkdirs() }
        val file=File(directory,"mie30-font-${System.nanoTime()}.${source.extension}")
        try {
            source.copyTo(file); java.io.RandomAccessFile(file,"rw").use { if(it.length()<17*1024*1024) it.setLength(17L*1024*1024) }
            val fonts=Theme("Mie30 fixture").fonts
            val method=fonts.javaClass.getDeclaredMethod("getTypeface",List::class.java).apply { isAccessible=true }
            val first=method.invoke(fonts,listOf(file.name))
            check(first!==android.graphics.Typeface.DEFAULT) { "Font fixture fell back to system default" }
            val began=android.os.SystemClock.elapsedRealtime()
            repeat(2000) { check(method.invoke(fonts,listOf(file.name))===first) }
            pass("17MB font retained across 2000 resolves (${android.os.SystemClock.elapsedRealtime()-began}ms)")
        } finally { file.delete() }
    }
}
