/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ViewAnimator
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.VideoKeyboardBackground
import com.osfans.trime.ime.switches.SwitchOptionAdapter
import com.osfans.trime.ime.switches.SwitchOptionEntry
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindowManager
import org.kodein.di.DI
import java.io.File

/** Debug-package checks; clipboardOnly exercises actual clipboard events and restores the original clip. */
class Mie15Runner : Instrumentation() {
    private var clipboardOnly = false
    private var deployOnly = false
    override fun onCreate(args: Bundle?) { super.onCreate(args); clipboardOnly = args?.getString("clipboardOnly") == "true"; deployOnly = args?.getString("deployOnly") == "true"; start() }
    private fun member(value: Any, name: String): Any {
        var c: Class<*>? = value.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(value)!! } catch (_: NoSuchFieldException) { }
            try { return c.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value)!! } catch (_: NoSuchMethodException) { }
            c = c.superclass
        }
        error("Missing $name")
    }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun await(label: String, action: () -> Boolean) {
        repeat(150) { var yes = false; main { yes = action() }; if (yes) return; SystemClock.sleep(80) }
        error(label)
    }
    private fun pause() { waitForIdleSync(); SystemClock.sleep(350) }
    private fun pass(label: String) = sendStatus(0, Bundle().apply { putString("case", label) })
    override fun onStart() {
        val result = Bundle()
        try {
            val activity = startActivitySync(Intent(targetContext, if (clipboardOnly || deployOnly) com.osfans.trime.ui.main.MainActivity::class.java else CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (deployOnly) {
                val session = com.osfans.trime.daemon.RimeDaemon.createSession("mie15-deploy-check")
                try {
                    kotlinx.coroutines.runBlocking {
                        session.runOnReady {
                            deploy()
                            pass("native deployment reported success")
                            val ids = enabledSchemata().map { it.id }
                            check(ids.isNotEmpty()) { "No enabled schema after deployment" }
                            pass("enabled schemas: ${ids.joinToString()}")
                            val original = selectedSchemaId()
                            val ascii = getRuntimeOption("ascii_mode")
                            try {
                                for (id in ids) {
                                    check(selectSchema(id)) { "Cannot select $id" }
                                    setRuntimeOption("ascii_mode", false)
                                    clearComposition()
                                    check(simulateKeySequence("a")) { "Cannot type with $id" }
                                    check(getCandidates(0, 5).isNotEmpty()) { "No native dictionary candidates for $id" }
                                    clearComposition()
                                    pass("$id: real native candidates available")
                                }
                            } finally {
                                clearComposition()
                                selectSchema(original)
                                setRuntimeOption("ascii_mode", ascii)
                            }
                        }
                    }
                    result.putString("result", "PASS")
                    finish(Activity.RESULT_OK, result)
                    return
                } finally { com.osfans.trime.daemon.RimeDaemon.destroySession("mie15-deploy-check") }
            }
            if (clipboardOnly) {
                main { member(activity, "testInputPanel").javaClass.getDeclaredMethod("show", android.view.Window::class.java).invoke(member(activity, "testInputPanel"), activity.window) }
                checkRealClipboard(activity)
                result.putString("result", "PASS")
                finish(Activity.RESULT_OK, result)
                return
            }
            main { AppPrefs.defaultInstance().keyboard.hideInputBar.setValue(false) }
            await("IME unavailable") { KeyboardWindow.instance != null }
            pause()
            val service = member(KeyboardWindow.instance!!, "service")
            val input = member(service, "inputView") as InputView
            val always = member(member(input, "inputBar"), "alwaysUi") as AlwaysUi
            for (collapsed in listOf(false, true)) {
                main {
                    always.setToolbarCollapsed(collapsed)
                    always.clipboardUi.text.text = "https://example.invalid/clipboard-fixture"
                    always.updateState(AlwaysUi.State.Clipboard)
                }; pause()
                main {
                    check(always.clipboardUi.root.isShown)
                    check(!always.buttonsUi.viewport.isShown)
                    for (name in listOf("leftMostButton", "rightMostButton", "collapseButton")) check(!(member(always, name) as View).isShown) { "$name overlaps clipboard" }
                    check((member(always, "animator") as ViewAnimator).displayedChild == 1)
                    always.updateState(AlwaysUi.State.Toolbar)
                }; pause()
                main { check(always.buttonsUi.viewport.isShown) }
            }
            pass("clipboard replaces all toolbar icons; toolbar restored")
            val menu = SwitchOptionWindow(member(input, "di") as DI)
            main { (member(input, "windowManager") as BoardWindowManager).attachWindow(menu) }
            pause()
            val grid = menu.view
            val adapter = grid.adapter as SwitchOptionAdapter
            // Repeated list size and label changes exercise the same grid recycling as schema changes.
            repeat(8) { round ->
                main { adapter.submitList(List(if (round % 2 == 0) 9 else 19) { SwitchOptionEntry.Static("长标签测试中文 → 西文 $it", android.R.drawable.ic_menu_preferences, SwitchOptionEntry.Static.Type.Keyboard) }) }
                pause()
                main {
                    check(grid.childCount >= 4)
                    check(grid.itemAnimator == null)
                    check(!grid.layoutManager!!.supportsPredictiveItemAnimations())
                    for (i in 0 until grid.childCount) {
                        val child = grid.getChildAt(i)
                        check(child.width in grid.width / 4 - 2..grid.width / 4 + 2) { "Unequal cell width" }
                        val holder = grid.getChildViewHolder(child) as SwitchOptionAdapter.ViewHolder
                        val label = holder.ui.label
                        check(label.width > 0 && label.width <= child.width && label.maxLines == 2) { "Label escapes cell" }
                        if (i < 4) check(child.top == grid.getChildAt(0).top) { "First row misaligned" }
                    }
                }
            }
            pass("menu: 8 list replacements, four equal columns and bounded labels before scrolling")
            main { (member(input, "windowManager") as BoardWindowManager).attachWindow(KeyboardWindow.instance!!) }
            pause()
            val videoFile = File(targetContext.filesDir, "mie15-fixture.mp4")
            check(videoFile.isFile) { "Video fixture missing" }
            val prefs = AppPrefs.defaultInstance()
            main { prefs.keyboard.videoBackground.setValue(""); ThemeManager.requestCustomizationRefresh() }
            pause()
            main { prefs.keyboard.videoBackground.setValue(videoFile.path); ThemeManager.requestCustomizationRefresh() }
            await("Theme did not rebuild video background") { runCatching { member(KeyboardWindow.instance!!, "videoBackground") is VideoKeyboardBackground }.getOrDefault(false) }
            val video = member(KeyboardWindow.instance!!, "videoBackground") as VideoKeyboardBackground
            await("Video not playing") { runCatching { (member(video, "player") as MediaPlayer).isPlaying }.getOrDefault(false) }
            var first = 0
            main { first = (member(video, "player") as MediaPlayer).currentPosition }
            SystemClock.sleep(500)
            main {
                check((member(video, "player") as MediaPlayer).currentPosition != first)
                check(video.isShown && video.width > 0 && video.height > 0)
                video.bitmap!!.also { check(it.width > 0); it.recycle() }
                KeyboardWindow.instance!!.setVideoActive(false)
                check(!(member(video, "player") as MediaPlayer).isPlaying)
                KeyboardWindow.instance!!.setVideoActive(true)
                check((member(video, "player") as MediaPlayer).isPlaying)
            }
            pass("theme video: live decoded frames, advancing playback, pause/resume")
            main { prefs.keyboard.videoBackground.setValue(""); ThemeManager.requestCustomizationRefresh() }
            pause()
            pass("video setting reset")
            main { activity.finish() }
            result.putString("result", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("result", "FAIL: ${android.util.Log.getStackTraceString(e)}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun checkRealClipboard(activity: Activity) {
        val prefs = AppPrefs.defaultInstance()
        val oldListening = prefs.clipboard.clipboardListening.getValue()
        val oldSuggestion = prefs.clipboard.clipboardSuggestion.getValue()
        val oldTimeout = prefs.clipboard.clipboardSuggestionTimeout.getValue()
        val oldLastBean = ClipboardHelper.lastBean
        val clipboard = targetContext.getSystemService(ClipboardManager::class.java)
        var oldClip: ClipData? = null
        main { oldClip = clipboard.primaryClip }
        try {
            main {
                prefs.clipboard.clipboardListening.setValue(true)
                prefs.clipboard.clipboardSuggestion.setValue(true)
                prefs.clipboard.clipboardSuggestionTimeout.setValue(2)
            }
            await("IME unavailable") { KeyboardWindow.instance != null }
            pause()
            fun always(): AlwaysUi = member(member(member(member(KeyboardWindow.instance!!, "service"), "inputView"), "inputBar"), "alwaysUi") as AlwaysUi
            await("Keyboard keys unavailable") { runCatching { KeyboardWindow.currentKeyboard.keys.isNotEmpty() && (member(KeyboardWindow.instance!!, "currentKeyboardView") as View).isShown }.getOrDefault(false) }
            pass("theme=${ThemeManager.activeTheme.name} keyboard=${KeyboardWindow.lastAttachedId} keys=${KeyboardWindow.currentKeyboard.keys.size}")
            main {
                val keys = member(KeyboardWindow.instance!!, "currentKeyboardView") as android.view.ViewGroup
                pass("keyboard view=${keys.isShown}/${keys.width}x${keys.height} children=${keys.childCount} first=${keys.getChildAt(0)?.let { "${it.width}x${it.height}@${it.x},${it.y}" }}")
            }
            repeat(12) { round ->
                val text = "https://example.invalid/mie2-copy-$round-${SystemClock.uptimeMillis()}"
                main { clipboard.setPrimaryClip(ClipData.newPlainText("copy regression", text)) }
                await("System clipboard did not reach suggestion $round") { always().currentState == AlwaysUi.State.Clipboard && always().clipboardUi.text.text.toString() == text.take(42) }
                main {
                    val ui = always()
                    // A keyboard callback may rebuild its buttons while a clipboard suggestion is visible.
                    ui.applyToolbar(ToolbarOverrides.forKeyboard(ThemeManager.activeTheme, KeyboardWindow.lastAttachedId))
                }
                pause()
                main {
                    val ui = always()
                    check(ui.clipboardUi.root.isShown)
                    check(!ui.buttonsUi.viewport.isShown) { "Toolbar overlaps actual copied text at $round" }
                    for (name in listOf("leftMostButton", "rightMostButton", "collapseButton")) check(!(member(ui, name) as View).isShown) { "$name overlaps actual copied text at $round" }
                }
                if (round == 0) {
                    uiAutomation.takeScreenshot().also { bitmap ->
                        File(targetContext.filesDir, "mie2-clipboard-check.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                if (round % 2 == 0) {
                    main { always().clipboardUi.dismiss.performClick() }
                }
                await("Toolbar did not restore $round") { always().currentState == AlwaysUi.State.Toolbar && always().buttonsUi.viewport.isShown }
            }
            pass("mie2: 12 actual system copies, toolbar rebuild while suggestion visible, dismiss/timeout restore")
        } finally {
            kotlinx.coroutines.runBlocking {
                ClipboardHelper.allBeansSnapshot().filter { it.text?.startsWith("https://example.invalid/mie2-copy-") == true }
                    .forEach { ClipboardHelper.delete(it.id) }
            }
            main {
                prefs.clipboard.clipboardListening.setValue(false)
                if (oldClip != null) clipboard.setPrimaryClip(oldClip!!) else clipboard.clearPrimaryClip()
                prefs.clipboard.clipboardSuggestion.setValue(oldSuggestion)
                prefs.clipboard.clipboardSuggestionTimeout.setValue(oldTimeout)
                prefs.clipboard.clipboardListening.setValue(oldListening)
                ClipboardHelper.lastBean = oldLastBean
                activity.finish()
            }
        }
    }
}
