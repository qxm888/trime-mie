/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.*
import android.view.FrameMetrics
import android.view.View
import android.view.ViewTreeObserver
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.TestInputPanel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** Self-hosted, development-only. Writes synthetic timings, never editor contents. */
@android.annotation.TargetApi(24)
class PerformanceRunner : Instrumentation() {
    private var label = "unlabelled"
    private var sink = 0
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        if (Build.VERSION.SDK_INT < 24) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("error", "Performance measurement requires Android 7 or newer") })
            return
        }
        label = arguments?.getString("label")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,40}")) } ?: "unlabelled"
        start()
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }
    private fun field(value: Any, name: String): Any {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value)!! }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        return value.javaClass.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value)!!
    }
    private fun summary(samples: List<Long>): JSONObject {
        require(samples.isNotEmpty())
        val sorted = samples.sorted()
        fun percentile(q: Double) = sorted[(ceil(q * sorted.size).toInt() - 1).coerceAtLeast(0)] / 1e6
        return JSONObject().put("n", samples.size).put("median_ms", percentile(.5)).put("p95_ms", percentile(.95))
            .put("raw_ns", JSONArray(samples))
    }
    override fun onStart() {
        val result = Bundle()
        val report = JSONObject().put("label", label).put("version", BuildConfig.VERSION_NAME)
            .put("debuggable", true).put("device", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
            .put("clock", "SystemClock.elapsedRealtimeNanos")
        val out = File(targetContext.cacheDir, "performance-$label.json")
        val frames = Collections.synchronizedList(mutableListOf<JSONObject>())
        var service: TrimeInputMethodService? = null
        var callback: android.view.Window.OnFrameMetricsAvailableListener? = null
        val metricsThread = HandlerThread("benchmark-frames")
        try {
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main { (field(activity, "testInputPanel") as TestInputPanel).show(activity.window) }
            repeat(100) {
                if (KeyboardWindow.instance == null) SystemClock.sleep(100)
            }
            val window = checkNotNull(KeyboardWindow.instance) { "IME unavailable: select this development input method" }
            service = field(window, "service") as TrimeInputMethodService
            val imeWindow = checkNotNull(service.window?.window)
            SystemClock.sleep(1200)
            val actualTheme = field(window, "theme") as Theme
            val actualKeyboard = KeyboardWindow.currentKeyboard
            report.put("active_key_count", actualKeyboard.keys.size).put("custom_mode", actualTheme.isCustom)
                .put("width_px", targetContext.resources.displayMetrics.widthPixels)
            // Fixed, isolated fixture: no layout/config writes or user key bindings.
            val config = TextKeyboard(width = 10f, keyboardHeight = 240, keys = List(40) {
                TextKeyboard.TextKey(width = 10f, click = KeyActionToken.Plain(('a' + it % 26).toString()))
            })
            val theme = Theme("benchmark-fixture-v1", style = GeneralStyle(keyboardHeight = 240, keyWidth = 10f, keyHeight = 60))
            val width = targetContext.resources.displayMetrics.widthPixels
            for ((name, w, h) in listOf(Triple("model_docked", width, 0), Triple("model_floating", width * 3 / 5, 600))) {
                val samples = mutableListOf<Long>()
                repeat(100) { index ->
                    main {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val keyboard = Keyboard(targetContext, theme, w, config, "benchmark_fixture", h)
                        val duration = SystemClock.elapsedRealtimeNanos() - start
                        sink += keyboard.keys.size + keyboard.height
                        if (index >= 20) samples += duration
                    }
                }
                report.put(name, summary(samples))
            }
            if (Build.VERSION.SDK_INT >= 24) {
                metricsThread.start()
                callback = android.view.Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
                    if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                        val entry = JSONObject().put("total_ns", metrics.getMetric(FrameMetrics.TOTAL_DURATION)).put("dropped_reports", dropped)
                        if (Build.VERSION.SDK_INT >= 31) entry.put("deadline_ns", metrics.getMetric(FrameMetrics.DEADLINE))
                        frames += entry
                    }
                }
                main { imeWindow.addOnFrameMetricsAvailableListener(callback!!, Handler(metricsThread.looper)) }
            }
            val rebuild = mutableListOf<Long>()
            val toDraw = mutableListOf<Long>()
            repeat(50) { index ->
                val latch = CountDownLatch(1)
                main {
                    val root = field(window, "keyboardView") as View
                    val start = SystemClock.elapsedRealtimeNanos()
                    var seen = false
                    val listener = object : ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            if (seen) return
                            seen = true
                            if (index >= 10) toDraw += SystemClock.elapsedRealtimeNanos() - start
                            root.post { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnDrawListener(this) }
                            latch.countDown()
                        }
                    }
                    root.viewTreeObserver.addOnDrawListener(listener)
                    window.refreshKeyboards(true)
                    if (index >= 10) rebuild += SystemClock.elapsedRealtimeNanos() - start
                    root.invalidate()
                }
                check(latch.await(5, TimeUnit.SECONDS)) { "No keyboard draw: $index" }
                SystemClock.sleep(35)
            }
            SystemClock.sleep(300)
            report.put("full_rebuild", summary(rebuild)).put("refresh_to_ondraw", summary(toDraw))
            if (callback != null) main { imeWindow.removeOnFrameMetricsAvailableListener(callback!!) }
            callback = null
            val frameCopy = synchronized(frames) { frames.toList() }
            report.put("frames", JSONArray(frameCopy)).put("frame_count", frameCopy.size)
            report.put("over_deadline", frameCopy.count { it.has("deadline_ns") && it.getLong("deadline_ns") > 0 && it.getLong("total_ns") > it.getLong("deadline_ns") })
            report.put("memory_java_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                .put("native_heap_bytes", Debug.getNativeHeapAllocatedSize()).put("status", "PASS")
            result.putString("result", "PASS")
            out.writeText(report.toString(2))
            result.putString("report", out.name)
            finish(Activity.RESULT_OK, result)
        } catch (t: Throwable) {
            report.put("status", "FAIL").put("error", t.stackTraceToString())
            out.writeText(report.toString(2))
            result.putString("result", "FAIL")
            result.putString("error", t.toString())
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            callback?.let { listener -> runCatching { main { service?.window?.window?.removeOnFrameMetricsAvailableListener(listener) } } }
            if (metricsThread.isAlive) metricsThread.quitSafely()
        }
    }
}
