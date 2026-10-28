/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.core.data.MissingFileCopier
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ui.main.SurfaceAppearanceEditor
import com.osfans.trime.ime.core.FloatingKeyboardContainer
import com.osfans.trime.ime.keyboard.GestureFrame
import java.io.File

/** App-owned data is restored in finally. Does not select a schema or change the default IME. */
class Mie212Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { action() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }
    private fun pass(text: String) = sendStatus(0, Bundle().apply { putString("case", "PASS: $text") })
    private fun shot(name: String) {
        uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.filesDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    override fun onStart() {
        val result = Bundle()
        val activity = startActivitySync(Intent(targetContext, com.osfans.trime.ui.setup.SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = AppPrefs.defaultInstance().keyboard
        val oldSurfaces = prefs.surfaceAppearance.getValue() to prefs.customSurfaceAppearance.getValue()
        val oldBackgrounds = prefs.keyBackground.getValue() to prefs.customKeyBackground.getValue()
        val oldUnified = prefs.unifiedBackground.getValue() to prefs.customUnifiedBackground.getValue()
        val oldCandidate = CandidatePreferences.exportJson()
        val oldStats = InputStats.exportJson()
        var layout = ""
        val temp = File(targetContext.cacheDir, "mie212-${System.nanoTime()}").apply { mkdirs() }
        try {
            SurfaceAppearance.save(SurfaceAppearanceConfig(keyboardCorners = listOf(10f, 20f, 30f, 40f)), false)
            SurfaceAppearance.save(SurfaceAppearanceConfig(keyboardCorners = listOf(5f, 6f, 7f, 8f)), true)
            check(SurfaceAppearance.current(false).keyboardCorners == listOf(10f, 20f, 30f, 40f))
            check(SurfaceAppearance.current(true).keyboardCorners == listOf(5f, 6f, 7f, 8f))
            main {
                val view = FloatingKeyboardContainer(activity, false).apply { setBackgroundColor(Color.RED); setCornerRadii(listOf(24f, 0f, 0f, 0f)); layout(0, 0, 100, 100) }
                val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                check(Color.alpha(bitmap.getPixel(0, 0)) == 0 && bitmap.getPixel(99, 0) == Color.RED && bitmap.getPixel(0, 99) == Color.RED)
                bitmap.recycle()
            }
            pass("independent corner values and real asymmetric clipping")
            val source = File(temp, "source.yaml").apply { writeText("base") }
            val dest = File(temp, "rime/default.yaml")
            MissingFileCopier.copyFileIfMissing(source, dest); check(dest.readText() == "base")
            dest.writeText("user customized"); MissingFileCopier.copyFileIfMissing(source, dest); check(dest.readText() == "user customized")
            for (name in listOf("default.yaml", "key_bindings.yaml", "punctuation.yaml", "symbols.yaml")) {
                check(targetContext.assets.open("shared/$name").use { it.readBytes().isNotEmpty() })
            }
            pass("four Prelude assets packaged; seed preserves existing user YAML")
            val entry = ToolbarOverrides.Entry("__mie_one_hand", icon = "/tmp/icon.png", iconSize = 30)
            val bar = ToolbarOverrides.applyForCustomKeyboard(StandaloneKeyboardTheme.base().toolBar, listOf(entry))
            check(bar.buttons.single().foreground.iconSize == 30)
            layout = CustomKeyboards.create("Mie212 temporary")
            CustomKeyboards.mutate(layout) { it.toolbar = mutableListOf(entry) }
            val imported = CustomKeyboardYaml.decode(CustomKeyboards.exportYaml(listOf(layout))).getValue(layout)
            check(imported.toolbar.single().iconSize == 30)
            pass("image size survives toolbar application and layout YAML round trip")
            CandidatePreferences.save("mie212", "ni", CandidatePreferences.Rule(order = listOf("拟", "你"), forgotten = setOf("你"), hidden = setOf("泥")))
            check("你" in CandidatePreferences.get("mie212", "ni")!!.forgotten)
            CandidatePreferences.selected("mie212", "ni", "你")
            val promoted = CandidatePreferences.get("mie212", "ni")!!
            check(promoted.forgotten.isEmpty() && promoted.order.first() == "你" && "泥" in promoted.hidden)
            val backup = CandidatePreferences.exportJson()
            CandidatePreferences.importJson("""{"version":1,"rules":{"[\"mie212_legacy\",\"a\"]":{}}}""")
            check(CandidatePreferences.get("mie212_legacy", "a")!!.searchLimit == 512)
            CandidatePreferences.importJson(backup)
            CandidatePreferences.save("mie212", "ni", null); CandidatePreferences.importJson(backup)
            check(CandidatePreferences.get("mie212", "ni") == promoted)
            val dictionary = CandidatePreferences.dictionaryDrafts().filterKeys { it.startsWith("mie_order_mie212_") }.values.single()
            check(dictionary.indexOf("你\tni\t") < dictionary.indexOf("拟\tni\t") && !dictionary.contains("泥\tni\t"))
            pass("forgotten word promotes on selection; JSON and dictionary weights preserve adjusted order")
            val started = SystemClock.elapsedRealtime()
            repeat(2000) { i ->
                InputStats.onKeyDown("mie212", 1000L + i * 60)
                InputStats.onKeyProcessed("ni", false)
                InputStats.onKeyHeat("n")
                InputStats.onCommit("你", "ni", "mie212", "Mie212", 1010L + i * 60)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            val stats = InputStats.exportJson()
            InputStats.importJson(stats)
            check(InputStats.read("mie212") { it.careerTotal } == 2000L)
            check(InputStats.read("mie212") { it.weekly.keyCounts["n"] } == 2000L)
            val invalid = stats.replace("\"version\":2", "\"version\":99")
            check(runCatching { InputStats.importJson(invalid) }.isFailure)
            check(InputStats.read("mie212") { it.careerTotal } == 2000L)
            InputStats.flush()
            check(InputStats.rimeFile().readText().contains("mie212"))
            pass("statistics portable mirror, import/reject and heat map; 2000 synthetic input cycles in ${elapsed}ms (not an end-to-end typing benchmark)")
            main {
                val key = GestureFrame(activity)
                var clicks = 0
                key.onClick = { clicks++ }
                repeat(100) { i ->
                    val time = SystemClock.uptimeMillis()
                    MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10f, 10f, 0).also { key.onTouchEvent(it); it.recycle() }
                    MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, 10f, 10f, 0).also { key.onTouchEvent(it); it.recycle() }
                }
                check(clicks == 100)
            }
            pass("100 rapid single-action key taps retain all 100 callbacks")
            val screen = startActivitySync(Intent(targetContext, com.osfans.trime.ui.main.PersonalDataActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(5500); shot("mie212-backup.png")
            main {
                fun open(view: android.view.View): Boolean {
                    if (view is android.widget.TextView && view.text.toString() == "▸ 输入统计") { view.performClick(); return true }
                    if (view is android.view.ViewGroup) for (i in 0 until view.childCount) if (open(view.getChildAt(i))) return true
                    return false
                }
                check(open(screen.window.decorView))
            }
            SystemClock.sleep(300); shot("mie212-backup-expanded.png")
            main { screen.finish(); SurfaceAppearanceEditor.show(activity, true) }
            SystemClock.sleep(400); shot("mie212-appearance.png")
            result.putString("result", "PASS")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}"); shot("mie212-failure.png") }
        finally {
            if (layout.isNotEmpty()) { CustomKeyboards.delete(layout); CustomKeyboards.flush() }
            CandidatePreferences.importJson(oldCandidate)
            InputStats.importJson(oldStats)
            prefs.surfaceAppearance.setValue(oldSurfaces.first); prefs.customSurfaceAppearance.setValue(oldSurfaces.second)
            prefs.keyBackground.setValue(oldBackgrounds.first); prefs.customKeyBackground.setValue(oldBackgrounds.second)
            prefs.unifiedBackground.setValue(oldUnified.first); prefs.customUnifiedBackground.setValue(oldUnified.second)
            temp.deleteRecursively()
            main { activity.finish() }
        }
        finish(if (result.getString("result") == "PASS") -1 else 0, result)
    }
}
