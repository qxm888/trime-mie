/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import com.osfans.trime.ime.ai.*
import kotlinx.coroutines.*

/** Explicitly downloads one public Mozilla model into the disposable test application only. */
class AiOfflineRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    override fun onStart() = runBlocking {
        waitForIdleSync()
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".debug.sentences"))
            if (!OfflineTranslationService.supported()) error("Unsupported test runtime")
            AiTextSettings.init(targetContext)
            val route = OfflineTranslationCatalog.route(OfflineTranslationCatalog.models(targetContext), "zh-Hans", "en")
            check(route.size == 1)
            val began = SystemClock.elapsedRealtime()
            var last = 0L
            withTimeout(240_000) {
                OfflineTranslationService.download(targetContext, route) { _, _, progress ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - last >= 5000) {
                        last = now
                        sendStatus(0, Bundle().apply { putString("download", "${progress.downloadedBytes}/${progress.totalBytes}") })
                    }
                }
            }
            result.putLong("download_ms", SystemClock.elapsedRealtime() - began)
            val pssBefore = android.os.Debug.getPss()
            val started = SystemClock.elapsedRealtime()
            val translated = withTimeout(60_000) { OfflineTranslationService.translate(targetContext, "你好，很高兴认识你。", "zh-Hans", "en") }
            check(translated.any { it in 'a'..'z' } && translated != "你好，很高兴认识你。")
            result.putString("translation", translated)
            result.putLong("inference_ms", SystemClock.elapsedRealtime() - started)
            result.putLong("pss_before_kb", pssBefore)
            result.putLong("pss_after_unload_kb", android.os.Debug.getPss())
            check(OfflineTranslationService.installed(targetContext).any { it.source == "zh-Hans" && it.target == "en" })
            OfflineTranslationService.delete(targetContext, route.single())
            check(OfflineTranslationService.installed(targetContext).isEmpty())
            result.putString("result", "PASS_DOWNLOAD_LOCAL_INFERENCE_UNLOAD_DELETE")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}") }
        finish(if (result.getString("result")?.startsWith("PASS") == true) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
