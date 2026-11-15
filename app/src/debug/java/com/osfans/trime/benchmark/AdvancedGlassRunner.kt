/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import com.osfans.trime.ime.appearance.*

/** Synthetic local backgrounds only; exercises the asynchronous worker and change-driven scheduling. */
class AdvancedGlassRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun frame(blur: BackdropBlur, name: String): Bitmap? = blur.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(blur) as? Bitmap
    private fun await(label: String, ready: () -> Boolean) {
        repeat(120) { if (ui(ready)) return; SystemClock.sleep(30) }
        error(label)
    }
    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(targetContext.packageName.endsWith(".debug.sentences"))
            activity = startActivitySync(Intent(targetContext, Mie302FixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            lateinit var root: FrameLayout
            lateinit var blur: BackdropBlur
            var captures = 0
            var color = Color.RED
            val config = SurfaceAppearanceConfig(keys = SurfaceStyle(blur = true), keyBlur = 65,
                blurFrameRate = 5, backdrop = PanelBackdrop(enabled = true, systemBlur = false))
            ui {
                root = FrameLayout(activity)
                blur = BackdropBlur(root, { config }, active = { true }) { canvas ->
                    captures++
                    val paint = Paint().apply { this.color = color }
                    canvas.drawRect(0f, 0f, root.width / 2f, root.height.toFloat(), paint)
                    paint.color = Color.BLUE
                    canvas.drawRect(root.width / 2f, 0f, root.width.toFloat(), root.height.toFloat(), paint)
                }
                activity.setContentView(root)
            }
            await("Independent key blur did not publish: worker may have attempted to mutate an immutable bitmap") {
                frame(blur, "front") != null && frame(blur, "frontKey") != null
            }
            val first = ui { checkNotNull(frame(blur, "frontKey")) }
            check(!first.isMutable)
            val oldPixel = first.getPixel(0, 0)
            ui { color = Color.GREEN; repeat(100) { BackdropBlur.invalidateBackground(root) } }
            await("Dynamic local background did not update") { frame(blur, "frontKey") !== first }
            ui {
                check(first.getPixel(0, 0) == oldPixel)
                check(checkNotNull(frame(blur, "frontKey")).getPixel(0, 0) == Color.GREEN)
            }
            result.putString("local_gaussian", "PANEL_AND_INDEPENDENT_KEY_FRAMES_WITHOUT_SYSTEM_BLUR")
            SystemClock.sleep(350)
            val idle = ui { captures }
            SystemClock.sleep(450)
            check(ui { captures } == idle) { "Static background kept capturing" }
            ui { root.visibility = View.INVISIBLE; repeat(100) { BackdropBlur.invalidateBackground(root) } }
            SystemClock.sleep(300)
            check(ui { captures } == idle)
            ui { root.visibility = View.VISIBLE }
            await("Pending hidden work did not resume") { captures > idle }
            SystemClock.sleep(300)
            val before = ui { captures }
            repeat(12) { ui { BackdropBlur.invalidateBackground(root) }; SystemClock.sleep(15) }
            SystemClock.sleep(300)
            val updates = ui { captures - before }
            check(updates in 1..3) { "5fps cap exceeded: $updates captures for a short burst" }
            result.putInt("burst_captures_at_5fps", updates)
            result.putString("scheduling", "STATIC_IDLE_HIDDEN_PAUSE_RESUME_AND_BURST_COALESCING_PASS")
            val timings = ArrayList<Long>()
            val width = 160; val height = 100
            val pixels = IntArray(width * height); val work = IntArray(pixels.size)
            repeat(40) { iteration ->
                pixels.indices.forEach { pixels[it] = if (it % width < width / 2) Color.RED else Color.BLUE }
                val started = SystemClock.elapsedRealtimeNanos()
                GaussianBackdropFilter.apply(pixels, work, width, height, 24)
                if (iteration >= 10) timings += SystemClock.elapsedRealtimeNanos() - started
            }
            timings.sort()
            result.putLong("filter_160x100_median_us", timings[timings.size / 2] / 1000)
            result.putLong("filter_160x100_p95_us", timings[(timings.size * 0.95).toInt()] / 1000)
            result.putString("result", "PASS_DYNAMIC_LOCAL_GAUSSIAN_AND_SCHEDULING")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}") }
        finally { ui { activity?.finish() } }
        finish(if (result.getString("result")?.startsWith("PASS") == true) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
