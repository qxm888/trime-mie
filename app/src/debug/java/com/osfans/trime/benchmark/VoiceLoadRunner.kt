/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.osfans.trime.ime.voice.builtin.*
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses generated audio, no microphone or network. */
class VoiceLoadRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        startActivitySync(android.content.Intent().setClassName(targetContext, "com.osfans.trime.ui.main.MainActivity").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val result = Bundle()
        val done = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val began = android.os.SystemClock.elapsedRealtime()
        var error: String? = null
        val listener = object : AsrListener {
            override fun onFinal(text: String) { result.putInt("recognized_characters", text.length); done.countDown() }
            override fun onError(message: String) { error = message; done.countDown() }
        }
        var engine: OfflineSherpaAsrEngine? = null
        try {
            check(LocalAsrModels.installedVariant(targetContext, "qwen3_asr") != null) { "No installed qwen3 model" }
            engine = OfflineSherpaAsrEngine(scope, listener, "qwen3_asr",
                onModelLoadStart = { sendStatus(0, Bundle().apply { putString("stage", "model_load_start") }) },
                onModelLoadDone = { sendStatus(0, Bundle().apply { putString("stage", "model_load_done") }) })
            engine.start()
            // One second of quiet synthetic waveform; never captures the user's microphone.
            val pcm = java.nio.ByteBuffer.allocate(32000).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(16000) { pcm.putShort((kotlin.math.sin(it * 2 * Math.PI * 220 / 16000) * 32).toInt().toShort()) }
            engine.feed(pcm.array()); engine.stop()
            check(done.await(90, TimeUnit.SECONDS)) { "Native model load/decode timed out" }
            check(error == null) { error.orEmpty() }
            result.putString("result", "PASS")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.javaClass.simpleName}: ${t.message}") }
        finally { engine?.cancel(); scope.cancel(); SherpaOfflineManager.getInstance().unload() }
        result.putLong("elapsed_ms", android.os.SystemClock.elapsedRealtime() - began)
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
