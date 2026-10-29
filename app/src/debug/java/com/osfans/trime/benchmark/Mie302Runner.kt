/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.compact.CompactCandidateViewAdapter
import com.osfans.trime.ime.core.AutoScaleTextView
import org.json.JSONObject
import java.io.File

class Mie302FixtureActivity : Activity()

/** Isolated rendering checks. Does not select an IME, edit layouts, or send input. */
class Mie302Runner : Instrumentation() {
    private val report = JSONObject()
    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { block() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
    }
    private fun pass(name: String) = sendStatus(0, Bundle().apply { putString("case", "PASS: $name") })
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        try {
            main { backgrounds(); shaders(); candidates() }
            activity = startActivitySync(Intent(targetContext, Mie302FixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            blur(activity)
            result.putString("result", "PASS")
        } catch (e: Throwable) {
            result.putString("result", "FAIL: ${e.message}")
            result.putString("trace", e.stackTraceToString())
        } finally { activity?.let { main { it.finish() } } }
        report.put("status", result.getString("result"))
        File(targetContext.filesDir, "mie302-performance.json").writeText(report.toString(2))
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
    private fun median(block: () -> Unit): Double {
        repeat(5) { block() }
        val samples = List(21) { val start = SystemClock.elapsedRealtimeNanos(); block(); SystemClock.elapsedRealtimeNanos() - start }
        return samples.sorted()[samples.size / 2] / 1e6
    }
    private fun backgrounds() {
        var invalidations = 0
        val bg = GradientDrawable().apply { setColor(Color.WHITE) }
        bg.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) { invalidations++ }
            override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {}
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }
        val style = KeyBackgroundStyle()
        repeat(2000) { bg.cornerRadius = 14f; bg.setStroke(1, Color.GRAY) }
        val old = invalidations
        style.apply(bg, 14f, 1, Color.GRAY)
        invalidations = 0
        repeat(2000) { style.apply(bg, 14f, 1, Color.GRAY) }
        check(old >= 2000 && invalidations == 0)
        style.apply(bg, 18f, 2, Color.RED)
        check(invalidations > 0)
        val oldMs = median { repeat(2000) { bg.cornerRadius = 14f; bg.setStroke(1, Color.GRAY) } }
        style.apply(bg, 14f, 1, Color.GRAY)
        val newMs = median { repeat(2000) { style.apply(bg, 14f, 1, Color.GRAY) } }
        report.put("background_2000", JSONObject().put("old_invalidations", old).put("cached_invalidations", 0).put("old_median_ms", oldMs).put("cached_median_ms", newMs))
        pass("unchanged rounded backgrounds do not invalidate; geometry/color changes still apply")
    }
    private fun shaders() {
        val area = RectF(3f, 3f, 96f, 60f)
        val effects = KeyEffects(gradient=KeyGradient(enabled=true), borderGradient=KeyBorderGradient(enabled=true), gloss=true, edge3d=true, ripple=true)
        val cache = KeyEffectShaders(1f)
        cache.update(area, effects)
        val first = cache.gradient
        val border = cache.border
        val oldMs = median { repeat(2000) { KeyEffectPainter.gradientShader(area, effects.gradient) } }
        val newMs = median { repeat(2000) { cache.update(area, effects); check(cache.gradient === first && cache.border === border) } }
        area.right += 10f; cache.update(area, effects)
        check(cache.gradient !== first)
        val oldGradient = KeyEffectPainter.gradientShader(area, effects.gradient)
        fun render(shader: Shader?): IntArray {
            val b = Bitmap.createBitmap(120, 70, Bitmap.Config.ARGB_8888)
            Canvas(b).drawRect(area, Paint().apply { this.shader = shader })
            return IntArray(120 * 70).also { b.getPixels(it, 0, 120, 0, 0, 120, 70); b.recycle() }
        }
        check(render(oldGradient).contentEquals(render(cache.gradient)))
        cache.update(area, effects.copy(gradient=effects.gradient.copy(startColor="#FF00FF00")))
        check(cache.gradient !== first)
        report.put("gradient_2000", JSONObject().put("old_median_ms", oldMs).put("cached_median_ms", newMs))
        pass("gradient/outline shaders reuse identity; resizing and appearance changes invalidate; rendered gradient pixels match")
    }
    private fun candidates() {
        val scheme = ColorKey.entries.associate { it.name.lowercase() to "#FF805060" }
        val scope = ThemeScope(Theme("performance-fixture")).apply { updateScheme(scheme) }
        val ui = CandidateItemUi(targetContext, scope)
        val item = CandidateProto("性能检查", "fixture", "")
        ui.update(item, false)
        val content = (ui.root as android.view.ViewGroup).getChildAt(0)
        val background = content.background
        repeat(1000) { ui.update(item, false); check(content.background === background) }
        ui.update(item, true); check(content.background !== background)
        val highlighted = content.background
        scope.updateScheme(scheme + ("hilited_candidate_back_color" to "#FF00AA00"))
        ui.update(item, true); check(content.background !== highlighted)
        val font = AutoScaleTextView(targetContext)
        font.text = "相同文字"
        font.updateTextStyle(16f, Typeface.DEFAULT)
        repeat(1000) { check(!font.updateTextStyle(16f, Typeface.DEFAULT)) }
        check(font.updateTextStyle(24f, Typeface.MONOSPACE))
        var notices = 0
        val adapter = CompactCandidateViewAdapter(scope)
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { notices++ }
            override fun onItemRangeChanged(start: Int, count: Int) { notices++ }
            override fun onItemRangeInserted(start: Int, count: Int) { notices++ }
        })
        val data = arrayOf(item, item.copy(text="第二项"))
        adapter.updateCandidates(data, 2, 0)
        notices = 0
        repeat(1000) { adapter.updateCandidates(data, 2, 0) }
        check(notices == 0)
        adapter.updateCandidates(data, 2, 1); check(notices == 2)
        notices = 0
        adapter.updateLayoutParams(60, 1f); adapter.updateCandidates(data, 2, 1)
        check(notices > 0)
        notices = 0
        adapter.updateCandidates(data, 20, 1); check(notices > 0)
        report.put("identical_candidate_updates", 1000).put("identical_candidate_notifications", 0)
        pass("candidate ripple/font reuse; identical lists skip notifications; highlight/layout changes still rebind")
    }
    private fun blur(activity: Activity) {
        var captures = 0
        lateinit var root: FrameLayout
        main {
            root = FrameLayout(activity)
            BackdropBlur(root, { SurfaceAppearanceConfig(keys=SurfaceStyle(blur=true)) }) { canvas -> captures++; canvas.drawColor(Color.GRAY) }
            activity.setContentView(root)
        }
        waitForIdleSync(); SystemClock.sleep(600)
        var initial = 0
        main { initial = captures; check(initial > 0) }
        SystemClock.sleep(700)
        main { check(captures == initial); BackdropBlur.invalidateBackground(root) }
        SystemClock.sleep(350)
        main { check(captures == initial + 1) }
        main { repeat(100) { BackdropBlur.invalidateBackground(root) } }
        SystemClock.sleep(350)
        main { check(captures == initial + 2) }
        main { activity.setContentView(FrameLayout(activity)) }
        waitForIdleSync(); SystemClock.sleep(150)
        main { activity.setContentView(root) }
        waitForIdleSync(); SystemClock.sleep(350)
        main { check(captures > initial + 2) }
        report.put("static_blur_idle_700ms_captures", 0).put("background_change_captures", 1)
        pass("static blur stops recapturing at idle; a background update requests one new capture")
    }
}
