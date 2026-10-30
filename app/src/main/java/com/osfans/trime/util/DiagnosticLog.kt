/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util

import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import timber.log.Timber

/** Bounded lifecycle journal. Callers supply metrics, never audio, text or credentials. */
object DiagnosticLog {
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "mie-diagnostics").apply { isDaemon = true } }
    private val lock = Any()
    fun event(area: String, stage: String, metrics: String = "") {
        val line = "${java.time.Instant.now()} uptime=${SystemClock.elapsedRealtime()} $area/$stage $metrics"
        Timber.tag("MieDiagnostics").i(line)
        writer.execute {
            runCatching { synchronized(lock) {
                val file = File(appContext.filesDir, "diagnostics.log")
                if (file.length() > 192 * 1024) {
                    val previous = File(file.parentFile, "diagnostics.previous.log")
                    previous.delete(); file.renameTo(previous)
                }
                file.appendText(line + "\n")
            } }
        }
    }
    fun snapshot(): String = synchronized(lock) {
        val contexts=mutableListOf(appContext)
        if(android.os.Build.VERSION.SDK_INT>=24) contexts+=appContext.createDeviceProtectedStorageContext()
        contexts.distinctBy { it.filesDir.absolutePath }.joinToString("\n") { context ->
            "storage=${if(BootStorage.deviceProtected(context)) "device" else "credential"}\n"+
                listOf("diagnostics.previous.log","diagnostics.log").joinToString("\n") { name ->
                    runCatching { File(context.filesDir,name).takeIf { it.isFile }?.inputStream()?.use { it.readBoundedText(256*1024) }.orEmpty() }.getOrDefault("")
                }
        }
    }
}
