/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.composition.PreeditUi
import com.osfans.trime.ime.composition.PreeditFontFallback
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** Real-device regression. The host backs up/restores the independent IME and assets. */
class Mie3116Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun member(value: Any, name: String): Any? {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing member $name")
    }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(200) { var ok = false; main { ok = condition() }; if (ok) return; SystemClock.sleep(70) }
        error(label)
    }
    private fun pass(label: String) = sendStatus(0, Bundle().apply { putString("case", "PASS: $label") })
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun tap(view: View) {
        val at = IntArray(2); var x = 0f; var y = 0f
        main { view.getLocationOnScreen(at); x = at[0] + view.width / 2f; y = at[1] + view.height / 2f; check(view.isShown) }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).also { sendPointerSync(it); it.recycle() }
        }
        SystemClock.sleep(180)
    }
    private fun screenshot(name: String) {
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(targetContext.cacheDir, "mie3116-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    override fun onStart() = runBlocking {
        waitForIdleSync()
        val result = Bundle(); var activity: Activity? = null; var dialog: AlertDialog? = null; var step = "startup"
        try {
            CustomKeyboards.awaitBuiltinLayouts()
            val p = AppPrefs.defaultInstance()
            main {
                p.keyboard.floatingKeyboard.setValue(false); p.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                p.keyboard.oneHandMode.setValue(0); p.keyboard.hideInputBar.setValue(false)
                p.general.inputStatsEnabled.setValue(false); com.osfans.trime.data.stats.InputStats.setEnabled(false)
            }
            val builtins = KeyboardLayoutCatalog.roots(targetContext, KeyboardLayoutCatalog.BUILTIN)
            check(builtins.size == 9) { "Expected nine builtins: ${builtins.map { it.id }}" }
            val fixture = CustomKeyboards.create("V16 真机测试")
            CustomKeyboards.mutate(fixture) {
                it.rows.clear(); it.rows += CustomKeyboards.CustomRow(mutableListOf(
                    CustomKeyboards.CustomKey("A", 50f, mutableMapOf("click" to "a")),
                    CustomKeyboards.CustomKey("空格", 50f, mutableMapOf("click" to "space"))))
            }
            check(KeyboardLayoutCatalog.roots(targetContext, KeyboardLayoutCatalog.CUSTOM).any { it.id == fixture })
            check(KeyboardLayoutCatalog.roots(targetContext, KeyboardLayoutCatalog.BUILTIN).none { it.id == fixture })
            for (entry in builtins) {
                val kb = CustomKeyboards.get(entry.id + "_9")!!
                check(kb.rows.last().keys.last().width == 15f) { "Unequal confirm key: ${entry.id}" }
                check(kb.toolbar.any { it.action == "__mie_theme_picker" })
                check(kb.toolbar.filter { it.action == "__mie_floating_keyboard" }.all { it.icon == "icons/mie2_tb_float.png" })
            }
            pass("all 9 builtin families discovered; user layout isolated; 9-key confirm width and dynamic skin action migrated")
            // One operation copies root + child, with reordered keys and mapped navigation.
            val a = CustomKeyboards.create("V16 来源"); val b = CustomKeyboards.create("V16 目标")
            val ac = CustomKeyboards.createSecondary("数字", a); val bc = CustomKeyboards.createSecondary("数字", b)
            fun key(label: String, click: String, long: String = "") = CustomKeyboards.CustomKey(label, 50f, mutableMapOf("click" to click, "longClick" to long))
            main {
                CustomKeyboards.mutate(a) { it.rows.clear(); it.rows += CustomKeyboards.CustomRow(mutableListOf(key("C", "c", "paste"), key("数字", "Keyboard_$ac"))) }
                CustomKeyboards.mutate(b) { it.rows.clear(); it.rows += CustomKeyboards.CustomRow(mutableListOf(key("数字", "Keyboard_$bc"), key("C", "c", "copy"))) }
                CustomKeyboards.mutate(ac) { it.rows.clear(); it.rows += CustomKeyboards.CustomRow(mutableListOf(key("1", "1", "copy"), key("返回", "Keyboard_$a"))) }
                CustomKeyboards.mutate(bc) { it.rows.clear(); it.rows += CustomKeyboards.CustomRow(mutableListOf(key("返回", "Keyboard_default"), key("1", "1", "cut"))) }
            }
            val plan = KeyboardFamilyBindingCopy.plan(ac, bc)
            check(plan.updates.size == 2 && plan.keyCount == 4)
            check(KeyboardFamilyBindingCopy.apply(plan) == 4)
            check(CustomKeyboards.get(b)!!.rows[0].keys[1].bindings["longClick"] == "paste")
            check(CustomKeyboards.get(b)!!.rows[0].keys[0].bindings["click"] == "Keyboard_$bc")
            check(CustomKeyboards.get(bc)!!.rows[0].keys[0].bindings["click"] == "Keyboard_default")
            check(CustomKeyboards.get(bc)!!.rows[0].keys[1].bindings["longClick"] == "copy")
            check(runCatching { KeyboardFamilyBindingCopy.apply(plan) }.isFailure)
            pass("whole-family copy from child selects both pages; reordered C/1 gestures copied; navigation remapped; default return retained; stale plan rejected")
            val icons = File(CustomKeyAssets.root(targetContext), "icons")
            val sentinel = File(icons, "mie3116-user-file.keep").apply { writeText("keep") }
            File(icons, "tb_theme.png").writeText("old stock asset fixture")
            BuiltinToolbarAssets.install(targetContext)
            check(!File(icons, "tb_theme.png").exists()); check(sentinel.readText() == "keep")
            check(icons.listFiles()!!.count { it.name.startsWith("mie2_tb_") && it.extension == "png" } == 11)
            check(CustomKeyAssets.resolve(targetContext, "icons/tb_theme.png") == File(icons, "mie2_tb_theme.png").path)
            check(CustomKeyAssets.resolve(targetContext, File(icons, "tb_theme.png").path) == File(icons, "mie2_tb_theme.png").path)
            sentinel.delete()
            pass("11 new toolbar PNGs installed; old owned icon deleted; user file preserved; relative and absolute legacy aliases resolve")
            val themeId = ThemeManager.prefs.selectedTheme.getValue()
            ThemeManager.selectTheme(themeId, layoutMode = CustomKeyboards.MODE_CUSTOM, customLayoutId = "custom_blue_9")
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("IME unavailable") { KeyboardWindow.instance != null }
            val ime = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            val input = member(ime, "inputView") as InputView
            await("IME not visible") { input.isShown && ime.currentInputConnection != null }
            SystemClock.sleep(450)
            screenshot("blue-nine-toolbar")
            suspend fun picker(): AlertDialog {
                var ready: AlertDialog? = null
                main { ime.lifecycleScope.launch { ready = ThemePickerDialog.build(ime.lifecycleScope, input.context).also { ime.showDialog(it) } } }
                await("picker did not open") { ready?.isShowing == true }
                await("initial picker layout not ready") {
                    val grid = views(ready!!.window!!.decorView).filterIsInstance<GridView>().single()
                    grid.isEnabled && grid.adapter != null && grid.childCount > 0
                }
                waitForIdleSync(); SystemClock.sleep(250)
                return ready!!
            }
            // Exercise the actual toolbar gesture/command path, which must not
            // fall through to android.intent.action.THEME.
            val skin = views(input).filterIsInstance<com.osfans.trime.ime.bar.ui.ToolButton>().first {
                (member(it, "config") as? com.osfans.trime.data.theme.model.ToolBar.Button)?.action == "__mie_theme_picker"
            }
            tap(skin)
            await("skin toolbar did not open internal picker") { (member(ime, "showingDialog") as? AlertDialog)?.isShowing == true }
            var current = member(ime, "showingDialog") as AlertDialog; dialog = current
            pass("actual toolbar skin icon opens internal picker without Android THEME intent")
            var root = current.window!!.decorView
            val radios = views(root).filterIsInstance<RadioGroup>().single()
            val grid = views(root).filterIsInstance<GridView>().single()
            check(radios.childCount == 3)
            val oldMode = CustomKeyboards.mode; val oldId = CustomKeyboards.activeId
            for (index in listOf(0, 1, 2, 0, 2, 1, 2)) {
                tap(radios.getChildAt(index))
                await("category list not loaded $index") { grid.isEnabled && grid.adapter != null }
                check(grid.numColumns == if (index == 0) 1 else 2)
                check(CustomKeyboards.mode == oldMode && CustomKeyboards.activeId == oldId) { "Category toggle changed runtime" }
            }
            check(grid.adapter.count == 9)
            screenshot("builtin-picker")
            main { current.dismiss() }
            pass("real touch cycles all 3 categories; custom/builtin 2 columns; category filtering leaves current keyboard untouched")
            // Repeat actual picker selections across engines to exercise teardown/rebind.
            val themeNames = ThemeManager.getAllThemes().map { it.configId }
            check(themeNames.isNotEmpty())
            repeat(3) { round ->
                for (index in listOf(0, 1, 2)) {
                    waitForIdleSync(); SystemClock.sleep(450)
                    step = "round=$round category=$index"
                    sendStatus(0, Bundle().apply { putString("step", step) })
                    current = picker(); dialog = current; root = current.window!!.decorView
                    val r = views(root).filterIsInstance<RadioGroup>().single()
                    val g = views(root).filterIsInstance<GridView>().single()
                    tap(r.getChildAt(index)); await("selection category not ready $step") { g.isEnabled && g.adapter != null && g.childCount > 0 }
                    val target = when (index) {
                        0 -> themeNames.getOrElse(1) { themeNames.first() }
                        1 -> fixture
                        else -> "custom_blue"
                    }
                    val position = when (index) {
                        0 -> themeNames.indexOf(target)
                        else -> KeyboardLayoutCatalog.roots(targetContext, if (index == 1) KeyboardLayoutCatalog.CUSTOM else KeyboardLayoutCatalog.BUILTIN).indexOfFirst { it.id == target }
                    }
                    check(position >= 0) { "Missing target $target category=$index" }
                    main { g.smoothScrollToPosition(position) }
                    await("target tile not laid out category=$index target=$target position=$position count=${g.adapter.count}") { position in g.firstVisiblePosition..g.lastVisiblePosition }
                    SystemClock.sleep(120)
                    tap(g.getChildAt(position - g.firstVisiblePosition))
                    await("picker switch failed round=$round category=$index") { !current.isShowing }
                    check(CustomKeyboards.mode == if (index == 0) CustomKeyboards.MODE_THEME else CustomKeyboards.MODE_CUSTOM)
                    if (index != 0) check(CustomKeyboards.activeId == target)
                    await("IME lost after picker switch") { (member(ime, "inputView") as InputView).isShown }
                }
            }
            pass("9 actual picker selections across legacy theme/user/builtin engines; no crash or cross-category active layout")
            ThemeManager.selectTheme(themeId, layoutMode = CustomKeyboards.MODE_CUSTOM, customLayoutId = "custom_blue")
            await("custom scope not ready") { ColorManager.currentScope()?.theme?.isCustom == true }
            main {
                val scope = ColorManager.currentScope()!!
                val ui = PreeditUi(ime, scope)
                val text = "kz kz💕 👩🏽‍💻 🇨🇳 1️⃣"
                ui.preedit.typeface = Typeface.MONOSPACE
                ui.update(CompositionProto(text.length, 6, 6, 7, text))
                check(ui.preedit.text.toString() == text)
                val fallback = PreeditFontFallback.apply(text, Typeface.MONOSPACE) { emptyList() } as Spanned
                val heart = text.indexOf("💕")
                val heartSpan = fallback.getSpans(heart, heart + 2, MetricAffectingSpan::class.java).single()
                check(fallback.getSpanStart(heartSpan) == heart && fallback.getSpanEnd(heartSpan) == heart + 2)
                val paint = TextPaint().apply { typeface = Typeface.MONOSPACE; textSize = 32f }; heartSpan.updateDrawState(paint)
                check(paint.hasGlyph("💕")) { "Chosen fallback lacks heart at 32px" }
                sendStatus(0, Bundle().apply { putString("glyph", "heart supported; systemFace=${paint.typeface == Typeface.DEFAULT}; probeSize=${paint.textSize}") })
                val candidate = CandidateItemUi(ime, scope)
                candidate.update(CandidateProto("测试", CandidateProto.CREATED_WORD_COMMENT, "1"), false)
                val label = member(candidate, "text") as TextView
                check(label.text.toString() == "测试 造")
                check((label.text as Spanned).getSpans(0, label.text.length, RelativeSizeSpan::class.java).single().sizeChange == .6f)
                check((member(candidate, "comment") as TextView).visibility == View.GONE)
                // Display production widgets, using the real Android renderer for the screenshot.
                val panel = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL; setPadding(30, 140, 30, 30)
                    setBackgroundColor(android.graphics.Color.rgb(245, 245, 245))
                    addView(TextView(activity).apply { this.text = "V3.1.16 编码区 / 造词标注真机渲染"; textSize = 18f })
                    addView(ui.root); addView(candidate.root)
                }
                activity!!.setContentView(panel)
            }
            SystemClock.sleep(400); screenshot("emoji-created-badge")
            pass("production custom preedit preserves emoji/ZWJ/flags/keycap; heart uses supported fallback glyph across complete surrogate; created word renders small 造 suffix")
            main { activity!!.finish() }
            activity = startActivitySync(Intent(targetContext, com.osfans.trime.ui.main.InputStatsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val stats = activity as com.osfans.trime.ui.main.InputStatsActivity
            await("heatmap not laid out") { stats.findViewById<View>(com.osfans.trime.R.id.stats_heat_grid).width > 0 }
            val heat = stats.findViewById<View>(com.osfans.trime.R.id.stats_heat_grid)
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { com.osfans.trime.ui.main.StatsImageShare.render(stats, heat) }
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(report.path, options)
            check(options.outWidth > 0 && options.outHeight > 0)
            val scale = minOf(1f, 1080f / heat.width)
            check(options.outHeight == kotlin.math.ceil(heat.height * scale.toDouble()).toInt())
            val rendered = android.graphics.BitmapFactory.decodeFile(report.path)
            val background = com.google.android.material.color.MaterialColors.getColor(stats, android.R.attr.colorBackground, android.graphics.Color.WHITE)
            check(rendered.getPixel(0, 0) == background) { "Heatmap export background differs from page" }
            rendered.recycle()
            report.copyTo(File(targetContext.cacheDir, "mie3116-heatmap-only.png"), overwrite = true)
            main {
                stats.javaClass.getDeclaredMethod("showShareDialog").apply { isAccessible = true }.invoke(stats)
            }
            pass("heatmap-only renderer exports exact heat grid dimensions without statistics report/title; PNG decoded successfully")
            result.putString("result", "PASS")
        } catch (e: Throwable) {
            screenshot("failure")
            val g = dialog?.window?.decorView?.let { views(it).filterIsInstance<GridView>().firstOrNull() }
            result.putString("result", "FAIL: ${e.message}; step=$step grid=${g?.adapter?.count}/${g?.childCount} enabled=${g?.isEnabled} size=${g?.width}x${g?.height}")
            result.putString("trace", e.stackTraceToString())
        }
        finally { main { dialog?.dismiss(); activity?.finish() }; CustomKeyboards.flush() }
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
