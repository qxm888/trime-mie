/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.backup

import android.content.Context
import androidx.preference.PreferenceManager
import com.osfans.trime.BuildConfig
import com.osfans.trime.core.data.*
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.ime.voice.builtin.LocalAsrModels
import com.osfans.trime.util.readBoundedText
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable settings and resources. A restore is staged and applied before managers initialize. */
object FullSettingsBackup {
    private val prefNames=listOf("__default__","font_overrides","voice_settings","builtin_asr","named_functions","clipboard_sync")
    private val stateNames=setOf("custom_keyboards.json","toolbar_overrides.json","key_binding_overrides.json","candidate_preferences.json","input_stats.json")
    private val assetNames=setOf("custom_fonts","custom_kb_bg","baidu_skins")
    private val skippedKeys=setOf("profile_data_storage_mode","profile_direct_external_path","profile_direct_external_ready","profile_external_rime_tree_uri","profile_external_rime_display_name","pid","last_crash_time","last_background_sync_time","last_background_sync_status")
    private const val LIMIT=16L*1024*1024*1024
    private const val FORMAT="trime-mie-settings"
    private fun prefs(context: Context,name: String)=if(name=="__default__") PreferenceManager.getDefaultSharedPreferences(context) else context.getSharedPreferences(name,Context.MODE_PRIVATE)
    private fun stage(context: Context)=File(context.noBackupFilesDir,"mie-restore-pending")
    private fun encode(value: Any?): JSONObject = when(value) {
        is Boolean -> JSONObject().put("type","boolean").put("value",value)
        is Int -> JSONObject().put("type","int").put("value",value)
        is Long -> JSONObject().put("type","long").put("value",value)
        is Float -> JSONObject().put("type","float").put("value",value.toDouble())
        is Set<*> -> JSONObject().put("type","set").put("value",JSONArray(value.toList()))
        else -> JSONObject().put("type","string").put("value",value as? String ?: "")
    }
    private fun preferenceSnapshot(context: Context,secrets: Boolean): JSONObject {
        val result=JSONObject()
        prefNames.forEach { name ->
            val entries=JSONObject()
            prefs(context,name).all.forEach { (key,value) ->
                val sensitive=name=="builtin_asr" && (key.contains("api_key") || key.contains("access_key") || key.contains("app_key") || key.contains("secret")) || name=="clipboard_sync" && key=="password"
                if(key !in skippedKeys && (secrets || !sensitive)) entries.put(key,encode(value))
            }
            result.put(name,entries)
        }
        return result
    }
    private fun safe(root: File,name: String): File {
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name && name.split('/').none { it==".." || it=="." || it.isEmpty() }) { "备份包含非法路径" }
        return File(root,name).canonicalFile.also { require(it.path.startsWith(root.canonicalPath+File.separator)) { "备份路径越界" } }
    }
    private fun roots(context: Context): Map<String,File> {
        val p=PreferenceManager.getDefaultSharedPreferences(context)
        val direct=p.getString("profile_data_storage_mode","DIRECT_EXTERNAL")=="DIRECT_EXTERNAL"
        val rime=if(direct) DirectExternalStorage.validate(p.getString("profile_direct_external_path",DirectExternalStorage.defaultPath)!!) else File(context.getExternalFilesDir(null) ?: context.filesDir,"rime")
        val assets=if(direct) File(rime,"mie_resources/${context.packageName}") else context.filesDir
        return linkedMapOf("state" to context.filesDir,"assets" to assets,"privateAssets" to context.filesDir,"rime" to rime,"models" to File(MieUserDirectory.directory(context),"语音模型"))
    }
    @Synchronized fun create(context: Context,models: Boolean,secrets: Boolean): File {
        check(CustomKeyboards.flush() && KeyBindingOverrides.flush() && ToolbarOverrides.flush()) { "键盘设置未能保存" }
        InputStats.flush()
        if(models) LocalAsrModels.migrateLegacy(context)
        val targets=roots(context)
        val folder=File(MieUserDirectory.directory(context),"备份").apply { check(mkdirs() || isDirectory) }
        val stamp=java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(java.util.Date())
        val output=File(folder,"trime-mie-${BuildConfig.VERSION_NAME.lowercase()}-$stamp.zip")
        val temp=File(folder,output.name+".partial")
        val inventory=JSONArray(); var total=0L; val names=mutableSetOf<String>()
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                fun put(name: String,input: InputStream) {
                    require(names.add(name) && names.size<=30000) { "备份文件过多或重名" }
                    val sha=MessageDigest.getInstance("SHA-256"); var size=0L
                    zip.putNextEntry(ZipEntry(name)); input.use { stream ->
                        val buf=ByteArray(65536)
                        while(true) { val n=stream.read(buf); if(n<0) break; total+=n; size+=n; require(total<=LIMIT) { "备份超过16GB" }; zip.write(buf,0,n); sha.update(buf,0,n) }
                    }; zip.closeEntry()
                    inventory.put(JSONObject().put("name",name).put("size",size).put("sha256",sha.digest().joinToString("") { "%02x".format(it) }))
                }
                put("preferences.json",preferenceSnapshot(context,secrets).toString().byteInputStream())
                put("state/candidate_preferences.json",JSONObject(CandidatePreferences.exportJson()).getJSONObject("rules").toString().byteInputStream())
                stateNames.filterNot { it=="candidate_preferences.json" }.forEach { name -> File(context.filesDir,name).takeIf { it.isFile }?.let { put("state/$name",it.inputStream()) } }
                fun tree(root: File,prefix: String,include: (File)->Boolean={ true }) {
                    if(!root.isDirectory) return
                    root.walkTopDown().onEnter { it.canonicalFile==File(root.canonicalFile,it.relativeTo(root).path) && include(it) }.forEach { file ->
                        if(file.isFile && include(file)) {
                            require(file.canonicalPath.startsWith(root.canonicalPath+File.separator)) { "资源路径越界" }
                            put(prefix+"/"+file.relativeTo(root).invariantSeparatorsPath,file.inputStream())
                        }
                    }
                }
                assetNames.forEach { name -> tree(File(targets.getValue("assets"),name),"assets/$name") }
                if(targets.getValue("assets").canonicalFile!=context.filesDir.canonicalFile)
                    assetNames.forEach { name -> tree(File(context.filesDir,name),"privateAssets/$name") }
                // Native is stopped while its source configuration and user dictionaries are copied.
                com.osfans.trime.daemon.RimeDaemon.changeStorage {
                    tree(targets.getValue("rime"),"rime") { file ->
                        val top=file.relativeTo(targets.getValue("rime")).invariantSeparatorsPath.substringBefore('/')
                        top !in setOf("mie","mie_resources","build",".git","installation.yaml")
                    }
                }
                if(models) tree(targets.getValue("models"),"models") { !it.name.startsWith('.') }
                val manifest=JSONObject().put("format",FORMAT).put("version",1).put("appVersion",BuildConfig.VERSION_NAME)
                    .put("models",models).put("secrets",secrets).put("files",inventory)
                    .put("roots",JSONObject(targets.mapValues { it.value.absolutePath }))
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            }
            check(temp.renameTo(output)) { "无法保存备份" }
            return output
        } finally { temp.delete() }
    }
    @Synchronized fun prepare(context: Context,input: InputStream): Int {
        val work=File(context.noBackupFilesDir,"mie-restore-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val archive=File(work,"input.zip")
        try {
            input.use { stream -> archive.outputStream().use { out ->
                val buf=ByteArray(65536); var total=0L
                while(true) { val n=stream.read(buf); if(n<0) break; total+=n; require(total<=LIMIT) { "备份超过16GB" }; out.write(buf,0,n) }
            } }
            var count=0
            ZipFile(archive).use { zip ->
                require(zip.size()<=30001) { "备份文件过多" }
                val manifest=JSONObject(zip.getInputStream(zip.getEntry("manifest.json") ?: error("缺少备份清单")).use { it.readBoundedText(16*1024*1024) })
                require(manifest.getString("format")==FORMAT && manifest.getInt("version")==1) { "不是支持的咩设置备份" }
                val entries=manifest.getJSONArray("files"); var total=0L; val names=mutableSetOf<String>()
                require(entries.length() in 1..30000)
                val data=File(work,"data").apply { mkdirs() }
                for(i in 0 until entries.length()) {
                    val item=entries.getJSONObject(i); val name=item.getString("name"); val size=item.getLong("size")
                    require(size in 0..LIMIT && names.add(name)); val target=safe(data,name)
                    require(name=="preferences.json" || name.substringBefore('/') in setOf("state","assets","privateAssets","rime","models"))
                    if(name=="preferences.json") require(size<=16*1024*1024)
                    if(name.startsWith("state/")) require(name.removePrefix("state/") in stateNames)
                    if(name.startsWith("state/")) require(size<=32*1024*1024)
                    if(name.startsWith("assets/")) require(name.removePrefix("assets/").substringBefore('/') in assetNames)
                    if(name.startsWith("privateAssets/")) require(name.removePrefix("privateAssets/").substringBefore('/') in assetNames)
                    if(name.startsWith("rime/")) require(name.removePrefix("rime/").substringBefore('/') !in setOf("mie","mie_resources","build",".git","installation.yaml"))
                    target.parentFile!!.mkdirs(); val sha=MessageDigest.getInstance("SHA-256"); var actual=0L
                    zip.getInputStream(zip.getEntry(name) ?: error("备份缺少文件")).use { stream -> target.outputStream().use { out ->
                        val buf=ByteArray(65536)
                        while(true) { val n=stream.read(buf); if(n<0) break; actual+=n; total+=n; require(actual<=size && total<=LIMIT) { "备份解压大小异常" }; out.write(buf,0,n); sha.update(buf,0,n) }
                    } }
                    require(actual==size && sha.digest().joinToString("") { "%02x".format(it) }==item.getString("sha256")) { "备份文件校验失败" }
                    count++
                }
                validatePreferences(JSONObject(File(data,"preferences.json").readText()))
                stateNames.forEach { name -> File(data,"state/$name").takeIf { it.isFile }?.let { JSONObject(it.readText()) } }
                File(data,"manifest.json").writeText(manifest.toString())
                val pending=stage(context)
                discardPrepared(context)
                check(!pending.exists() || pending.deleteRecursively()) { "无法清理之前的恢复任务" }
                check(data.renameTo(pending)) { "无法准备恢复" }
                prepareFiles(context,pending)
                File(pending,"ready").writeText("1")
            }
            return count
        } finally { work.deleteRecursively() }
    }
    private fun validatePreferences(all: JSONObject) {
        all.keys().forEach { name ->
            require(name in prefNames)
            val group=all.getJSONObject(name)
            require(group.length()<=10000)
            group.keys().forEach { key ->
                val entry=group.getJSONObject(key)
                when(entry.getString("type")) {
                    "boolean" -> entry.getBoolean("value")
                    "int" -> require(entry.getLong("value") in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                    "long" -> entry.getLong("value")
                    "float" -> require(entry.getDouble("value").toFloat().isFinite())
                    "string" -> require(entry.getString("value").length<=1024*1024)
                    "set" -> { val list=entry.getJSONArray("value"); require(list.length()<=10000); for(i in 0 until list.length()) list.getString(i) }
                    else -> error("未知设置类型")
                }
            }
        }
    }
    private fun writePreferences(context: Context,all: JSONObject,restoring: Boolean=true,rebase: (String)->String) {
        validatePreferences(all)
        all.keys().forEach { name ->
            val current=prefs(context,name)
            val editor=current.edit().clear()
            val entries=all.getJSONObject(name)
            val effective=JSONObject(entries.toString())
            current.all.forEach { (key,value) ->
                val secret=name=="builtin_asr" && (key.contains("api_key") || key.contains("access_key") || key.contains("app_key") || key.contains("secret")) || name=="clipboard_sync" && key=="password"
                if(key in skippedKeys || secret && !entries.has(key)) effective.put(key,encode(value))
            }
            effective.keys().forEach { key ->
                val e=effective.getJSONObject(key)
                when(e.getString("type")) {
                    "boolean" -> editor.putBoolean(key,if(restoring && name=="clipboard_sync" && key=="enabled") false else e.getBoolean("value"))
                    "int" -> editor.putInt(key,e.getInt("value"))
                    "long" -> editor.putLong(key,e.getLong("value"))
                    "float" -> editor.putFloat(key,e.getDouble("value").toFloat())
                    "string" -> editor.putString(key,rebase(e.getString("value")))
                    "set" -> { val list=e.getJSONArray("value"); editor.putStringSet(key,(0 until list.length()).map { rebase(list.getString(it)) }.toSet()) }
                }
            }
            check(editor.commit()) { "设置写入失败" }
        }
    }
    private fun prepareFiles(context: Context,pending: File) {
        val targets=roots(context)
        val manifest=JSONObject(File(pending,"manifest.json").readText())
        val old=manifest.getJSONObject("roots")
        targets.keys.forEach { require(old.getString(it).startsWith('/') && old.getString(it).length in 2..1024) }
        val files=manifest.getJSONArray("files"); val plan=JSONArray()
        val mappings=linkedMapOf<String,String>()
        listOf("state","rime","models").forEach { mappings[old.getString(it)+"/"]=targets.getValue(it).absolutePath+"/" }
        assetNames.forEach { folder -> mappings[old.getString("assets")+"/$folder/"]=targets.getValue("assets").absolutePath+"/$folder/" }
        if((0 until files.length()).any { files.getJSONObject(it).getString("name").startsWith("privateAssets/") })
            assetNames.forEach { folder -> mappings[old.getString("privateAssets")+"/$folder/"]=targets.getValue("privateAssets").absolutePath+"/$folder/" }
        // Android JSONObject escapes slashes, including JSON strings inside preferences.
        // Replace each original match once so moving into a child folder cannot rebase twice.
        val encoded=linkedMapOf<String,String>()
        mappings.forEach { (a,b) -> for(level in 0..8) {
            val slash="\\".repeat(level)+"/"
            encoded[a.replace("/",slash)]=b.replace("/",slash)
        } }
        val pattern=Regex(encoded.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })
        fun rebase(raw: String): String = pattern.replace(raw) { encoded.getValue(it.value) }
        try {
            for(i in 0 until files.length()) {
                val name=files.getJSONObject(i).getString("name"); if(name=="preferences.json") continue
                val target=safe(targets.getValue(name.substringBefore('/')),name.substringAfter('/'))
                target.parentFile!!.mkdirs()
                val prepared=File(target.parentFile,".${target.name}.mie-pending-${java.util.UUID.randomUUID()}")
                val entry=JSONObject().put("target",target.path).put("prepared",prepared.path)
                plan.put(entry)
                File(pending,"plan.json").writeAtomicText(plan.toString())
                val source=safe(pending,name)
                val rewrite=when(name.substringBefore('/')) {
                    "state" -> target.name in setOf("custom_keyboards.json","toolbar_overrides.json","key_binding_overrides.json")
                    "rime" -> target.extension in setOf("yaml","yml") && !target.name.endsWith(".dict.yaml")
                    else -> false
                }
                if(rewrite && source.length()<=32*1024*1024) prepared.writeText(rebase(source.readText()))
                else source.copyTo(prepared,true)
                entry.put("size",prepared.length())
            }
            File(pending,"prepared-preferences.json").writeText(rebase(File(pending,"preferences.json").readText()))
            File(pending,"plan.json").writeAtomicText(plan.toString())
        } catch(error: Exception) {
            for(i in 0 until plan.length()) File(plan.getJSONObject(i).getString("prepared")).delete()
            throw error
        }
    }
    fun discardPrepared(context: Context) {
        val pending=stage(context)
        File(pending,"plan.json").takeIf { it.isFile }?.let { file ->
            val plan=JSONArray(file.readAtomicText() ?: "[]")
            for(i in 0 until plan.length()) File(plan.getJSONObject(i).getString("prepared")).delete()
        }
    }
    /** Startup only switches prepared files by rename; large model copies happened on IO. */
    fun applyPending(context: Context) {
        val pending=stage(context)
        val rollback=File(context.noBackupFilesDir,"mie-restore-rollback").apply { mkdirs() }
        val journal=File(rollback,"journal.json")
        val prefBackup=File(rollback,"preferences.json")
        if(File(rollback,"committed").exists()) {
            context.getSharedPreferences("settings_restore_result",Context.MODE_PRIVATE).edit()
                .putBoolean("deploy",true).putString("message","整包设置已恢复；输入引擎首次启动将完整部署。").commit()
            if(journal.isFile) {
                val list=JSONArray(journal.readText())
                for(i in 0 until list.length()) File(list.getJSONObject(i).getString("saved")).delete()
            }
            pending.deleteRecursively(); rollback.deleteRecursively(); return
        }
        if(!File(pending,"ready").isFile) return
        val result=context.getSharedPreferences("settings_restore_result",Context.MODE_PRIVATE)
        fun undo(list: JSONArray) {
            for(i in list.length()-1 downTo 0) {
                val e=list.getJSONObject(i); val target=File(e.getString("target")); val prepared=File(e.getString("prepared")); val saved=File(e.getString("saved"))
                if(saved.exists()) {
                    if(target.exists() && !prepared.exists()) check(target.renameTo(prepared))
                    check(saved.renameTo(target))
                } else if(!e.getBoolean("exists") && target.exists() && !prepared.exists()) check(target.renameTo(prepared))
            }
        }
        if(journal.isFile) {
            undo(JSONArray(journal.readText()))
            if(prefBackup.isFile) writePreferences(context,JSONObject(prefBackup.readText()),false) { it }
            rollback.deleteRecursively(); rollback.mkdirs()
        }
        val before=preferenceSnapshot(context,true)
        prefBackup.writeText(before.toString())
        val recorded=JSONArray()
        try {
            val list=JSONArray(File(pending,"plan.json").readAtomicText() ?: error("缺少恢复计划"))
            for(i in 0 until list.length()) {
                val entry=list.getJSONObject(i)
                val target=File(entry.getString("target")); val prepared=File(entry.getString("prepared"))
                require(prepared.isFile && prepared.length()==entry.getLong("size")) { "已准备的恢复文件丢失或改变，请重新导入" }
                val saved=File(target.parentFile,".${target.name}.mie-previous-${java.util.UUID.randomUUID()}")
                recorded.put(JSONObject().put("target",target.path).put("prepared",prepared.path).put("exists",target.exists()).put("saved",saved.path))
                val tempJournal=File(rollback,"journal.tmp"); tempJournal.writeText(recorded.toString()); check(tempJournal.renameTo(journal))
                if(target.exists()) check(target.renameTo(saved)) { "无法保留原资源" }
                check(prepared.renameTo(target)) { "无法启用恢复资源" }
            }
            writePreferences(context,JSONObject(File(pending,"prepared-preferences.json").readText())) { it }
            File(rollback,"committed").writeText("1")
            result.edit().putBoolean("deploy",true).putString("message","整包设置已恢复；下次启动输入引擎将完整部署。剪贴板自动同步保持关闭，请按需重新开启。").commit()
            for(i in 0 until recorded.length()) File(recorded.getJSONObject(i).getString("saved")).delete()
            pending.deleteRecursively(); rollback.deleteRecursively()
        } catch(error: Exception) {
            runCatching { undo(recorded) }
            runCatching { writePreferences(context,before,false) { it } }
            result.edit().putString("message","恢复未完成，原数据已回退：${error.message}。备份仍保留，可授权目录后重试。").commit()
            com.osfans.trime.util.DiagnosticLog.event("backup","restore_failed","type=${error.javaClass.simpleName}")
        }
    }
}
