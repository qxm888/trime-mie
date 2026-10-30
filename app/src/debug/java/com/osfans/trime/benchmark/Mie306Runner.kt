/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.osfans.trime.data.sync.ClipboardSync
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.ime.voice.VoiceSettings
import com.osfans.trime.ime.voice.builtin.AsrSettings
import com.osfans.trime.ime.voice.builtin.AsrContextHolder
import com.osfans.trime.util.*
import com.osfans.trime.worker.BackgroundSyncWork
import java.io.File

/** Isolated DE context with a deliberately inaccessible CE applicationContext. No reboot/IME switch. */
class Mie306Runner:Instrumentation() {
    override fun onCreate(args:Bundle?) { super.onCreate(args);start() }
    private fun main(block:()->Unit) {
        var error:Throwable?=null
        runOnMainSync { try { block() } catch(e:Throwable) { error=e } }
        error?.let { throw it }
    }
    private fun pass(name:String)=sendStatus(0,Bundle().apply { putString("case","PASS: $name") })
    override fun onStart() {
        val result=Bundle()
        val prefix="mie306_${java.util.UUID.randomUUID()}_"
        val protected=targetContext.createDeviceProtectedStorageContext()
        val folder=File(protected.cacheDir,prefix).apply { mkdirs() }
        val prefNames=mutableSetOf<String>()
        val objects=listOf(FontOverrides to listOf("context","snapshot"),VoiceSettings to listOf("context"),
            AsrSettings to listOf("context"),AsrContextHolder to listOf("appContext"),ClipboardSync to listOf("context"))
        val originals=mutableListOf<Triple<Any,java.lang.reflect.Field,Any?>>()
        val denied=object:ContextWrapper(targetContext) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences=error("CE storage accessed during boot fixture")
        }
        val device=object:ContextWrapper(protected) {
            override fun isDeviceProtectedStorage()=true
            override fun getApplicationContext():Context=denied
            override fun createDeviceProtectedStorageContext():Context=this
            override fun getFilesDir():File=folder
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
                prefNames+=prefix+name
                return protected.getSharedPreferences(prefix+name,mode)
            }
        }
        try {
            main { check(com.osfans.trime.data.prefs.AppPrefs.defaultInstance()!=null) }
            val field=ClipboardSync.javaClass.getDeclaredField("serviceAlive").apply { isAccessible=true }
            check(!field.getBoolean(ClipboardSync)) { "fixture requires inactive test IME service" }
            check(storageContext(device)===device && BootStorage.deviceProtected(storageContext(device)))
            check(storageContext(targetContext)===targetContext.applicationContext)
            pass("explicit DE context survives applicationContext normalization; normal unlocked context stays CE")
            main {
                objects.forEach { (target,names) -> names.forEach { name ->
                    val f=target.javaClass.getDeclaredField(name).apply { isAccessible=true }
                    originals+=Triple(target,f,f.get(target))
                } }
                FontOverrides.init(device);check(FontOverrides.selection("key").isEmpty())
                VoiceSettings.init(device);check(VoiceSettings.enabled && VoiceSettings.streaming)
                AsrSettings.init(device);check(AsrSettings.version==0L)
                AsrContextHolder.init(device);check(AsrContextHolder.get()===device)
                ClipboardSync.init(device);check(!ClipboardSync.config().enabled)
            }
            pass("font, voice, ASR/model context and sync settings use isolated DE preferences, never locked CE")
            check(!BootStorage.canScheduleWork(device))
            BackgroundSyncWork.start(device);BackgroundSyncWork.forceStart(device)
            check(BootStorage.canScheduleWork(targetContext))
            pass("both background scheduling entries skip device-protected contexts without initializing WorkManager there")
            CrashJournal.record(device,Thread.currentThread(),IllegalStateException("fixture-first"),"fixture")
            CrashJournal.record(device,Thread.currentThread(),RuntimeException("fixture-second",IllegalArgumentException("fixture-cause")),"fixture")
            val second=CrashJournal.snapshot(device)
            check(second.contains("fixture-first") && second.contains("fixture-second") && second.contains("fixture-cause"))
            CrashJournal.record(device,Thread.currentThread(),RuntimeException("fixture-large-"+"x".repeat(100000)),"fixture")
            check(File(folder,"mie-last-crash.txt").readText().length==64*1024)
            val latest=CrashJournal.snapshot(device)
            check(latest.contains("fixture-second") && !latest.contains("fixture-first") && latest.contains("fixture-large"))
            check(folder.listFiles().orEmpty().count { it.name.endsWith("crash.txt") }==2)
            pass("crash/cause survives a later snapshot in DE; two-record rotation and 64Ki-character bound enforced")
            result.putString("result","PASS")
        } catch(error:Throwable) { result.putString("result","FAIL: ${error.message}");result.putString("trace",error.stackTraceToString()) }
        finally {
            main { originals.forEach { (target,field,value) -> field.set(target,value) } }
            prefNames.forEach { protected.deleteSharedPreferences(it) };folder.deleteRecursively()
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
