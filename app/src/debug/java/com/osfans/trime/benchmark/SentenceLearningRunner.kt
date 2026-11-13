/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.sentences.SentenceAccumulator
import com.osfans.trime.data.sentences.SentenceRepository
import com.osfans.trime.data.sentences.SentenceRules
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.sentences.SentenceSuggestionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only for a freshly installed, disposable debug application; no real editor/user dictionary. */
class SentenceLearningRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun member(value: Any, name: String): Any {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value)!! }
            catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
                .apply { isAccessible = true }.invoke(value)!! }
            catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing $name")
    }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(200) { if (ui(condition)) return; SystemClock.sleep(40) }
        error(label)
    }
    private fun status(value: String) = sendStatus(0, Bundle().apply { putString("case", value) })
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    override fun onStart() = runBlocking {
        var activity: SentenceLearningActivity? = null
        var fixture: SyntheticInputSchema? = null
        val output = Bundle(); val report = JSONObject()
        try {
            // Instrumentation starts before Application.onCreate completes.
            waitForIdleSync()
            val prefs = AppPrefs.defaultInstance()
            ui {
                prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
                prefs.keyboard.floatingKeyboard.setValue(false)
                prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0)
                prefs.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.ALWAYS_SHOW)
                prefs.candidates.position.setValue(com.osfans.trime.ime.composition.PopupPosition.BOTTOM_RIGHT)
                prefs.general.inputStatsEnabled.setValue(false)
                prefs.general.sentenceLearningEnabled.setValue(true)
            }
            val own = startActivitySync(Intent(targetContext, SentenceLearningActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SentenceLearningActivity
            activity = own
            await("Keyboard unavailable") { KeyboardWindow.instance != null }
            val ime = member(checkNotNull(KeyboardWindow.instance), "service") as TrimeInputMethodService
            await("Editor unavailable") { ime.currentInputConnection != null && ime.isInputViewShown }
            val session = member(ime, "rime") as RimeSession
            fixture = SyntheticInputSchema.create(session)
            val controller = (member(ime, "sentenceSuggestions") as Lazy<*>).value as SentenceSuggestionController
            val repository = SentenceRepository.get(targetContext)
            repository.clear()
            fun popup(): PopupWindow? = runCatching { member(controller, "popup") as PopupWindow }.getOrNull()
            suspend fun clearEditor() {
                ime.postRimeJob { clearComposition() }.join()
                ui { controller.preferenceChanged(); own.input.setText("") }
                waitForIdleSync(); SystemClock.sleep(100)
            }
            fun commit(text: String) { ui { ime.commitText(text, fromEngine = true) }; waitForIdleSync() }
            val sentence = "感谢gpt帮我做项目"
            repeat(3) { count ->
                clearEditor()
                commit("感谢")
                for (key in listOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_T)) {
                    ui { ime.sendDownUpKeyEvent(key, 0) }; waitForIdleSync()
                }
                commit("帮我"); commit("做项目"); commit("。")
                repository.awaitIdle()
                val suggestions = repository.search("感谢gpt")
                check(suggestions.isNotEmpty() == (count == 2)) { "Promotion threshold wrong at ${count + 1}" }
            }
            check(repository.learned().single().frequency == 3L)
            status("PASS_THRESHOLD mixed Chinese/Latin fragments learned on third complete occurrence")
            for (inline in listOf(InlinePreeditMode.DISABLE, InlinePreeditMode.COMPOSING_TEXT, InlinePreeditMode.COMMIT_TEXT_PREVIEW)) {
                clearEditor()
                ui { prefs.general.inlinePreeditMode.setValue(inline) }
                commit("感谢")
                for (key in "gpt") session.runOnReady { processKey(key.code) }
                waitForIdleSync()
                await("Suggestion popup missing for $inline") { popup()?.isShowing == true }
                waitForIdleSync(); SystemClock.sleep(200)
                val before = ui { InputMethodService.Insets().also(ime::onComputeInsets).contentTopInsets }
                // Compare the same Rime/editor state with and without the popup.
                // Completing text also removes candidates/preedit, which may independently
                // change the existing keyboard's candidate-bar layout.
                ui { popup()!!.dismiss() }
                waitForIdleSync(); SystemClock.sleep(100)
                val hidden = ui { InputMethodService.Insets().also(ime::onComputeInsets).contentTopInsets }
                check(before == hidden) { "Popup changed application insets: $before -> $hidden" }
                ui { controller.javaClass.getDeclaredMethod("position").apply { isAccessible = true }.invoke(controller) }
                await("Popup did not reopen") { popup()?.isShowing == true }
                waitForIdleSync(); SystemClock.sleep(100)
                ui {
                    val content = popup()!!.contentView
                    val density = own.resources.displayMetrics.density
                    check(content.height <= 44 * density) { "Suggestion too tall: ${content.height / density}dp" }
                    check(content.width <= 220 * density + 1) { "Suggestion too wide" }
                    val floating = member(ime, "candidatesView") as View
                    val candidateBounds = android.graphics.Rect()
                    val suggestionBounds = android.graphics.Rect()
                    val location = IntArray(2)
                    content.getLocationOnScreen(location)
                    suggestionBounds.set(location[0], location[1], location[0] + content.width, location[1] + content.height)
                    floating.getLocationOnScreen(location)
                    candidateBounds.set(location[0], location[1], location[0] + floating.width, location[1] + floating.height)
                    if (floating.isShown && !candidateBounds.isEmpty) {
                        check(!android.graphics.Rect.intersects(candidateBounds, suggestionBounds)) {
                            "Suggestion covers candidate window: $candidateBounds / $suggestionBounds"
                        }
                    }
                    report.put("suggestionHeightDp", content.height / density)
                    report.put("suggestionWidthDp", content.width / density)
                }
                status("POPUP_READY inline=$inline compact; existing candidate window unobscured")
                SystemClock.sleep(1000)
                val tap = ui {
                    val row = views(popup()!!.contentView).filterIsInstance<TextView>()
                        .first { it.contentDescription == "常用句：$sentence" }
                    val location = IntArray(2); row.getLocationOnScreen(location)
                    floatArrayOf(location[0] + row.width / 2f, location[1] + row.height / 2f)
                }
                val down = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tap[0], tap[1], 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                await("Completion duplicated or lost text for $inline") { own.input.text.toString() == sentence }
                repository.awaitIdle()
                check(repository.learned().single().frequency == 3L) { "Completion trained itself" }
                status("PASS_COMPLETION inline=$inline exactly one full sentence; no self-learning; fixed app inset")
            }
            clearEditor()
            // OEM secure keyboards may replace/destroy this IME for an actual password
            // editor. Exercise our guard with synthetic metadata without changing that
            // system protection or switching away from the disposable test editor.
            val privacyBaseline = repository.counts()
            report.put("beforePrivacyPending", privacyBaseline.first).put("beforePrivacyLearned", privacyBaseline.second)
            ui {
                controller.start(EditorInfo().apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                })
                repeat(3) { controller.committed("密码框里这句不能学习。", true) }
            }
            repository.awaitIdle()
            check(repository.counts() == privacyBaseline && popup() == null) { "Password metadata guard wrote data" }
            status("PASS_PASSWORD synthetic EditorInfo guard; OEM secure keyboard unchanged")
            ui {
                controller.start(EditorInfo().apply {
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                })
                repeat(3) { controller.committed("禁止个性化学习不能记录。", true) }
            }
            repository.awaitIdle(); check(repository.counts() == privacyBaseline) { "No-learning metadata guard wrote data" }
            status("PASS_NO_PERSONALIZED_LEARNING synthetic EditorInfo flag respected")
            report.put("privacyGuardTest", "synthetic EditorInfo in the real controller; OEM secure keyboard unchanged")
            ui { controller.start(ime.currentInputEditorInfo) }
            clearEditor(); commit("感谢gpt")
            await("Popup missing before delete") { popup()?.isShowing == true }
            ui { views(popup()!!.contentView).first { it.contentDescription == "常用句：$sentence" }.performLongClick() }
            repository.awaitIdle()
            await("Deletion did not complete") { runBlocking { repository.search("感谢gpt").isEmpty() } }
            status("PASS_DELETE long press removes the learned sentence")
            clearEditor()
            repository.clear()
            ui { prefs.general.sentenceLearningEnabled.setValue(false) }
            repeat(3) { commit("关闭开关以后不要记录。"); ui { own.input.setText("") }; waitForIdleSync() }
            repository.awaitIdle(); check(repository.counts() == (0 to 0))
            status("PASS_DISABLED switch stops learning and querying")
            // Populate a separate test database with maximum-sized synthetic vocabularies.
            withContext(Dispatchers.IO) {
                val db = (member(repository, "database") as SQLiteOpenHelper).writableDatabase
                db.beginTransaction()
                try {
                    db.delete("sentences", null, null)
                    val insert = db.compileStatement("INSERT INTO sentences(text,lookup,t1,t2,t3,frequency,learned,updated) VALUES(?,?,?,?,?,?,?,?)")
                    val now = System.currentTimeMillis()
                    for ((kind, count) in listOf(0 to 5001, 1 to 2001)) repeat(count) { index ->
                        val text = (if (kind == 1) "性能测量句子" else "待学习测量句子") + index.toString().padStart(4, '0') + "甲".repeat(200)
                        insert.clearBindings(); insert.bindString(1, text); insert.bindString(2, SentenceRules.lookup(text))
                        insert.bindLong(3, now - 2); insert.bindLong(4, if (kind == 1) now - 1 else 0)
                        insert.bindLong(5, if (kind == 1) now else 0); insert.bindLong(6, 3); insert.bindLong(7, kind.toLong()); insert.bindLong(8, now)
                        insert.executeInsert()
                    }
                    insert.close(); db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            repository.record("这是容量边界检查句子")
            check(repository.counts() == (5000 to 2000))
            report.put("pendingRows", 5000).put("learnedRows", 2000)
            repeat(20) { repository.search("性能") }
            val lookupTimes = mutableListOf<Double>()
            repeat(300) {
                val start = SystemClock.elapsedRealtimeNanos()
                check(repository.search("性能").size == 3)
                lookupTimes += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
            }
            fun percentile(values: List<Double>, quantile: Double): Double = values.sorted()[(values.lastIndex * quantile).toInt()]
            report.put("lookupP50Ms", percentile(lookupTimes, .50)).put("lookupP95Ms", percentile(lookupTimes, .95)).put("lookupMaxMs", lookupTimes.max())
            withContext(Dispatchers.IO) {
                val db = (member(repository, "database") as SQLiteOpenHelper).readableDatabase
                db.rawQuery("EXPLAIN QUERY PLAN SELECT text FROM sentences WHERE learned=1 AND lookup COLLATE BINARY>=? AND lookup COLLATE BINARY<? ORDER BY frequency DESC,updated DESC LIMIT 4", arrayOf("性能", checkNotNull(SentenceRules.prefixEnd("性能")))).use {
                    val plan = buildList { while (it.moveToNext()) add(it.getString(3)) }.joinToString("; ")
                    report.put("queryPlan", plan)
                    report.put("prefixRangeIndex", plan.contains("sentence_prefix") && plan.contains("lookup>"))
                }
            }
            val baseline = mutableListOf<Double>(); val enabled = mutableListOf<Double>()
            val frameGaps = mutableMapOf<Boolean, MutableList<Double>>(false to mutableListOf(), true to mutableListOf())
            for (on in listOf(false, true, false, true)) {
                status("BENCHMARKING learning=$on")
                clearEditor()
                ui { prefs.general.sentenceLearningEnabled.setValue(on); prefs.general.inlinePreeditMode.setValue(InlinePreeditMode.DISABLE) }
                var lastFrame = 0L
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(time: Long) {
                        if (lastFrame > 0) frameGaps.getValue(on) += (time - lastFrame) / 1_000_000.0
                        lastFrame = time; Choreographer.getInstance().postFrameCallback(this)
                    }
                }
                ui { Choreographer.getInstance().postFrameCallback(callback) }
                try {
                    val keys = "abcde ".repeat(24)
                    for ((index, key) in keys.withIndex()) {
                        val start = SystemClock.elapsedRealtimeNanos()
                        ime.postRimeJob { processKey(key.code) }.join()
                        waitForIdleSync()
                        val duration = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        if (index >= 12) (if (on) enabled else baseline) += duration
                    }
                    SystemClock.sleep(200)
                } finally { ui { Choreographer.getInstance().removeFrameCallback(callback) } }
            }
            report.put("keySamplesPerState", baseline.size)
            report.put("disabledKeyP50Ms", percentile(baseline, .5)).put("disabledKeyP95Ms", percentile(baseline, .95))
            report.put("enabledKeyP50Ms", percentile(enabled, .5)).put("enabledKeyP95Ms", percentile(enabled, .95))
            report.put("disabledFrameGapP95Ms", percentile(frameGaps.getValue(false), .95))
            report.put("enabledFrameGapP95Ms", percentile(frameGaps.getValue(true), .95))
            report.put("droppedLearningEvents", repository.dropped.get())
            val callbackCosts = ui {
                val accumulator = SentenceAccumulator { }
                buildList { repeat(10000) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    accumulator.append(if (it % 16 == 15) "。" else "我")
                    add((SystemClock.elapsedRealtimeNanos() - start) / 1000.0)
                } }
            }
            report.put("accumulatorP95Us", percentile(callbackCosts, .95))
            status("PASS_CAPACITY 5000 pending + 2000 learned; prefix index used; benchmark complete")
            check(report.getBoolean("prefixRangeIndex")) { "Prefix range index missing: ${report.getString("queryPlan")}" }
            output.putString("result", "PASS")
            output.putString("measurements", report.toString())
        } catch (error: Throwable) {
            output.putString("result", "FAIL: ${error.message}")
            output.putString("trace", error.stackTraceToString())
            output.putString("partialMeasurements", report.toString())
        } finally {
            runCatching { fixture?.restore() }.onFailure {
                output.putString("cleanupWarning", it.message)
            }
            activity?.let { ui { it.finish() } }
        }
        finish(if (output.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }
}

class SentenceLearningActivity : Activity() {
    lateinit var input: EditText
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        input = EditText(this).apply { hint = "常用句测试，只使用合成句子"; setSingleLine(false) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@SentenceLearningActivity).apply {
                text = "常用句功能与性能实测\n仅使用测试生成的文字"; textSize = 20f
                setTextColor(Color.DKGRAY); setPadding(32, 160, 32, 32)
            })
            addView(input, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()).bottom
            input.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = bottom }
            insets
        }
        setContentView(root); input.requestFocus()
        input.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }, 300)
    }
}
