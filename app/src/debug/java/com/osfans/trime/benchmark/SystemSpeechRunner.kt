/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import com.osfans.trime.ime.voice.builtin.*
import com.osfans.trime.ui.main.VoiceSettingsActivity
import kotlinx.coroutines.*

/** Public capability probe only: no microphone, model downloads, private binder or IME switching. */
class SystemSpeechRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    override fun onStart() = runBlocking {
        val result = Bundle()
        var activity: Activity? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            check(targetContext.packageName.endsWith(".debug.sentences"))
            ui { AsrSettings.init(targetContext); AsrContextHolder.init(targetContext) }
            val available = ui { SystemOnDeviceSpeech.available(targetContext) }
            result.putBoolean("on_device_available", available)
            val report = SystemOnDeviceSpeech.inspect(targetContext, "zh-CN")
            result.putString("capability_report", report)
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val intent = SystemOnDeviceSpeech.intent("zh-CN")
                check(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
                check(intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) == "zh-CN")
            }
            if (!available && android.os.Build.VERSION.SDK_INT >= 31) {
                val rejected = ui {
                    try { SpeechRecognizer.createOnDeviceSpeechRecognizer(targetContext).destroy(); false }
                    catch (_: UnsupportedOperationException) { true }
                }
                check(rejected) { "Expected public API to reject missing on-device service" }
                check(report.contains("未向第三方开放"))
                result.putString("public_api_constructor", "UNSUPPORTED_OPERATION_CONFIRMED")
                val errors = mutableListOf<String>()
                val listener = object : AsrListener {
                    override fun onFinal(text: String) { error("Unexpected transcription") }
                    override fun onError(message: String) { errors += message }
                }
                ui {
                    AsrSettings.setVendor("system_on_device")
                    check(AsrSettings.hasCredential())
                    val engine = checkNotNull(AsrEngineFactory.create(scope, listener))
                    check(engine.capturesAudio)
                    engine.start(); engine.stop(); engine.cancel()
                    check(!engine.isRunning)
                }
                check(errors.size == 1)
                result.putString("unavailable_engine", "FAILS_ONCE_WITHOUT_RECORDING_OR_ONLINE_FALLBACK")
            }
            ui { AsrSettings.setVendor("system_on_device") }
            activity = startActivitySync(Intent(targetContext, VoiceSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val texts = ui {
                fun collect(view: View): List<String> = when (view) {
                    is TextView -> listOf(view.text.toString())
                    is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                    else -> emptyList()
                }
                collect(activity.window.decorView)
            }
            check(texts.any { it.contains("重新检测端侧语音能力") })
            check(texts.none { it.startsWith("API Key：") })
            result.putString("settings", "SYSTEM_PROVIDER_RENDERED_WITHOUT_API_KEY_OR_MODEL_DOWNLOAD")
            result.putString("result", "PASS_PUBLIC_API_CAPABILITY_AND_UNAVAILABLE_PATH")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}") }
        finally { ui { activity?.finish() }; scope.cancel() }
        finish(if (result.getString("result")?.startsWith("PASS") == true) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
