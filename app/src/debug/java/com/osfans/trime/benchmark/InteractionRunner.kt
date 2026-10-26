/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.popup.LabeledCandidateItemUi
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.VideoKeyboardBackground
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.TestInputPanel

class InteractionRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun member(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(o) } catch (_: NoSuchFieldException) { }
            try { return c.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(o) } catch (_: NoSuchMethodException) { }
            c = c.superclass
        }
        error("Missing field $name")
    }
    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var video: VideoKeyboardBackground? = null
        sendStatus(0, Bundle().apply { putString("stage", "starting activity") })
        val bootstrap = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = AppPrefs.defaultInstance().keyboard
        val hidden = prefs.hideInputBar.getValue()
        val originalVideo = prefs.videoBackground.getValue()
        val originalMode = com.osfans.trime.data.keymap.CustomKeyboards.mode
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
            failure?.let { throw it }
        }
        fun await(label: String, test: () -> Boolean) {
            repeat(150) { var ok = false; main { ok = test() }; if (ok) return; SystemClock.sleep(80) }
            error(label)
        }
        try {
            activity = bootstrap
            main { (member(activity!!, "testInputPanel") as TestInputPanel).show(activity!!.window) }
            await("keyboard unavailable") { KeyboardWindow.instance != null }
            sendStatus(0, Bundle().apply { putString("stage", "keyboard ready") })
            val service = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            fun input() = member(service, "inputView") as InputView
            main {
                val ui = LabeledCandidateItemUi(activity!!, input().scope)
                ui.root.layoutParams = FrameLayout.LayoutParams(-2, -2)
                val item = CandidateProto(text = "候选", comment = "这是很长的注释。".repeat(100), label = "1")
                ui.update(item, false, 260, 2)
                ui.root.measure(View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                check(ui.root.maxLines == 2 && ui.root.measuredWidth <= 260)
                ui.update(item, false, 260, 1)
                check(ui.root.maxLines == 1 && ui.lastBindTruncated)
                ui.setExpanded(true)
                check(ui.root.text.toString().contains(item.comment))
                val key = com.osfans.trime.ime.keyboard.Key(KeyboardWindow.currentKeyboard, input().theme,
                    com.osfans.trime.data.theme.model.TextKeyboard.TextKey(label = " ，", click = com.osfans.trime.data.theme.model.KeyActionToken.Plain(","), composing = com.osfans.trime.data.theme.model.KeyActionToken.Plain(";")))
                check(key.getAction(com.osfans.trime.ime.keyboard.KeyBehavior.COMPOSING)?.code == android.view.KeyEvent.KEYCODE_SEMICOLON)
                check(key.heatLabel() == "，")
                val space = com.osfans.trime.ime.keyboard.Key(KeyboardWindow.currentKeyboard, input().theme,
                    com.osfans.trime.data.theme.model.TextKeyboard.TextKey(label = " ", click = com.osfans.trime.data.theme.model.KeyActionToken.Plain("space")))
                check(space.heatLabel() == "空格")
                check(ui.update(item.copy(text = "长候选".repeat(60)), false, 260, 1) < 1f) { "Single-line head did not shrink" }
                check(ui.update(item, false, 260, 1, autoShrink = false) == 1f)
                ui.update(item, false, 260, 0)
                check(ui.root.maxLines == Int.MAX_VALUE)
                val savedShift = KeyboardWindow.currentKeyboard.mShiftKey
                val shift = com.osfans.trime.ime.keyboard.Key(KeyboardWindow.currentKeyboard, input().theme,
                    com.osfans.trime.data.theme.model.TextKeyboard.TextKey(click = com.osfans.trime.data.theme.model.KeyActionToken.Plain("Shift_L"), keyBackColor = "dxb", hilitedKeyBackColor = "dxbh"))
                shift.setOn(true)
                val expected = com.osfans.trime.data.theme.ColorManager.getDrawable("on_key_back_color")
                val actual = shift.getBackgroundDrawable()
                check(actual != null && expected != null)
                if (expected is android.graphics.drawable.BitmapDrawable) {
                    check(actual is android.graphics.drawable.BitmapDrawable && actual.bitmap === expected.bitmap) { "Locked Shift ignored YAML on_key_back_color image" }
                }
                KeyboardWindow.currentKeyboard.mShiftKey = savedShift
                result.putString("shift_skin", "locked Shift uses YAML on-state drawable, including bitmap binding")
                result.putString("heat", "composing semicolon binding maps to physical comma; unicode blank space maps to 空格")
                result.putString("candidate", "two-row cap; single-row truncation; full annotation recovered")
                prefs.hideInputBar.setValue(true)
            }
            await("input bar was not hidden") { val bar = member(input(), "inputBar")!!; (bar.javaClass.getMethod("getView").invoke(bar) as View).visibility == View.GONE }
            main {
                val manager = member(service, "inputDeviceManager")!!
                check(manager.javaClass.getMethod("getUseCandidatesView").invoke(manager) == true)
                result.putString("hidden_bar", "idle bar hidden; popup candidates enabled")
                com.osfans.trime.data.keymap.CustomKeyboards.setMode("theme")
                prefs.videoBackground.setValue(java.io.File(targetContext.cacheDir, "video-test.mp4").absolutePath)
                com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
            }
            sendStatus(0, Bundle().apply { putString("stage", "candidate and hidden-bar checks complete; video") })
            await("video not attached to keyboard") {
                video = member(KeyboardWindow.instance!!, "videoBackground") as? VideoKeyboardBackground
                video != null
            }
            await("video not prepared") { member(video!!, "prepared") == true }
            await("video not playing") { (member(video!!, "player") as android.media.MediaPlayer).isPlaying }
            main { service.onFinishInputView(false); check(!(member(video!!, "player") as android.media.MediaPlayer).isPlaying) }
            main { service.onStartInputView(service.currentInputEditorInfo, true) }
            await("video did not resume") { (member(video!!, "player") as android.media.MediaPlayer).isPlaying }
            main { prefs.videoBackground.setValue(originalVideo); com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh() }
            await("old video decoder was not released") { member(video!!, "player") == null }
            result.putString("video", "actual IME background: prepare/play; onFinish pauses; onStart resumes; rebuilding releases")
            var report: java.io.File? = null
            var renderFailure: Throwable? = null
            main {
                val content = View(activity!!).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xffff0080.toInt(), 0xff00aaff.toInt()))
                    layout(0, 0, 720, 6200)
                }
                (activity as androidx.fragment.app.FragmentActivity).lifecycleScope.launch {
                    try { report = com.osfans.trime.ui.main.StatsImageShare.render(activity as androidx.fragment.app.FragmentActivity, content) }
                    catch (failure: Throwable) { renderFailure = failure }
                }
            }
            await("long image render failed") { renderFailure?.let { throw it }; report != null }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(report!!.absolutePath, bounds)
            check(bounds.outWidth == 720 && bounds.outHeight == 6200) { "Long image was cropped or split" }
            report!!.delete()
            val categories = com.osfans.trime.data.db.CategoryHelper
            val originalOrder = categories.all().map { it.id }
            kotlinx.coroutines.runBlocking {
                val a = checkNotNull(categories.create("Test-A-${java.util.UUID.randomUUID()}"))
                val b = checkNotNull(categories.create("Test-B-${java.util.UUID.randomUUID()}"))
                try {
                    categories.reorder(listOf(b, a) + originalOrder)
                    check(categories.all().take(2).map { it.id } == listOf(b, a))
                    check(categories.all().map { it.order } == categories.all().indices.toList())
                } finally { categories.delete(a); categories.delete(b); categories.reorder(originalOrder) }
            }
            result.putString("category_order", "reorder persists complete unique order; fixtures removed")
            result.putString("long_image", "single PNG 720x6200 retains full report height")
            result.putString("result", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("result", "FAIL: " + android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            main {
                video?.release(); prefs.hideInputBar.setValue(hidden); prefs.videoBackground.setValue(originalVideo)
                com.osfans.trime.data.keymap.CustomKeyboards.setMode(originalMode)
                com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
                activity?.finish()
            }
        }
    }
}
