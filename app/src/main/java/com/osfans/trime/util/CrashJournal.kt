/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util

import android.content.Context
import android.os.Build
import com.osfans.trime.BuildConfig
import java.io.File

/** Synchronous, bounded crash record: survives process death and the first unlock. */
object CrashJournal {
    private const val LIMIT=64*1024
    private fun directory(context:Context)=if(Build.VERSION.SDK_INT>=24) context.createDeviceProtectedStorageContext().filesDir else context.filesDir
    @Synchronized fun record(context:Context,thread:Thread,error:Throwable,stage:String="uncaught") {
        val root=directory(context)
        check(root.mkdirs() || root.isDirectory)
        val file=File(root,"mie-last-crash.txt")
        val previous=File(root,"mie-previous-crash.txt")
        if(file.isFile) { previous.delete();check(file.renameTo(previous)) }
        val info="time=${java.time.Instant.now()} version=${BuildConfig.VERSION_NAME} api=${Build.VERSION.SDK_INT} pid=${android.os.Process.myPid()} stage=$stage locked=${BootStorage.locked(context)} deviceProtected=${BootStorage.deviceProtected(context)} thread=${thread.name.take(100)}\n"
        file.writeAtomicText((info+error.stackTraceToString()).take(LIMIT))
    }
    @Synchronized fun snapshot(context:Context):String = listOf("mie-previous-crash.txt","mie-last-crash.txt").joinToString("\n") { name ->
        runCatching { File(directory(context),name).takeIf { it.isFile }?.inputStream()?.use { it.readBoundedText(LIMIT*4) }.orEmpty() }.getOrDefault("")
    }
}
