/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import java.io.File
import kotlinx.coroutines.runBlocking

/** Uses an isolated three-word dictionary. Restores location, theme and candidate preferences. */
class Mie18Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun member(value: Any, name: String): Any? {
        var c: Class<*>? = value.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) { }
            try { return c.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) { }
            c = c.superclass
        }; error(name)
    }
    private fun main(action: () -> Unit) { var error: Throwable? = null; runOnMainSync { try { action() } catch (e: Throwable) { error = e } }; error?.let { throw it } }
    private fun await(label: String, check: () -> Boolean) { repeat(200) { var ok = false; main { ok = check() }; if (ok) return; SystemClock.sleep(60) }; error(label) }
    private fun pause() { main { }; SystemClock.sleep(300) }
    private fun shot(name: String) {
        uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    private fun pass(text: String) = sendStatus(0, Bundle().apply { putString("case", text) })
    private fun input() = member(member(KeyboardWindow.instance!!, "service")!!, "inputView") as InputView
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    override fun onStart() {
        val result = Bundle()
        val activity = startActivitySync(Intent().setClassName(targetContext, "com.osfans.trime.ui.main.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val p = AppPrefs.defaultInstance()
        val oldPath = p.profile.directExternalPath.getValue(); val oldMode = p.profile.dataStorageMode.getValue(); val oldReady = p.profile.directExternalReady.getValue()
        val oldTheme = ThemeManager.prefs.selectedTheme.getValue(); val oldKeyboardMode = CustomKeyboards.mode
        val oldVideo = p.keyboard.videoBackground.getValue(); val oldUnified = p.keyboard.unifiedBackground.getValue()
        val oldBackground = p.keyboard.keyBackground.getValue()
        val oldHideBar = p.keyboard.hideInputBar.getValue()
        val oldPopup = p.candidates.mode.getValue(); val oldBall = p.candidates.floatingBall.getValue(); val oldOpacity = p.candidates.ballOpacity.getValue()
        val file = File(targetContext.filesDir, "candidate_preferences.json"); val oldPreferences = file.takeIf { it.exists() }?.readBytes()
        val fixture = java.nio.file.Files.createTempDirectory(targetContext.getExternalFilesDir(null)!!.toPath(), "mie18-fixture-").toFile()
        try {
            fun write(name: String, text: String) = File(fixture, name).writeText(text.trimIndent())
            write("default.yaml", """
                config_version: '1'
                schema_list: [{schema: mie_probe}]
                menu: {page_size: 2}
            """)
            write("default.custom.yaml", "patch: {}")
            write("mie_probe.schema.yaml", """
                schema: {schema_id: mie_probe, name: Mie probe, version: '1'}
                switches: [{name: ascii_mode, reset: 0, states: [中文, 西文]}]
                engine:
                  processors: [speller, selector, navigator, express_editor]
                  segmentors: [abc_segmentor, fallback_segmentor]
                  translators: [table_translator]
                speller: {alphabet: abcdefghijklmnopqrstuvwxyz}
                translator: {dictionary: mie_probe, enable_user_dict: false, enable_sentence: false}
                menu: {page_size: 2}
            """)
            write("mie_probe.dict.yaml", "---\nname: mie_probe\nversion: '1'\nsort: original\n...\n甲\ta\t100\n乙\ta\t90\n丙\ta\t80\n丁\tb\t100\n")
            RimeDaemon.changeStorage {
                p.profile.dataStorageMode.setValue(DataStorageMode.DIRECT_EXTERNAL)
                p.profile.directExternalPath.setValue(fixture.path); p.profile.directExternalReady.setValue(true)
            }
            main { CustomKeyboards.setMode(CustomKeyboards.MODE_THEME); ThemeManager.prefs.selectedTheme.setValue("tongwenfeng.trime") }
            runBlocking { ThemeManager.refreshActiveTheme() }
            main {
                val panel = member(activity, "testInputPanel")!!
                panel.javaClass.getDeclaredMethod("show", android.view.Window::class.java).invoke(panel, activity.window)
            }
            await("Fixture keyboard missing") { KeyboardWindow.instance != null && input().isShown }
            val editor = member(member(activity, "testInputPanel")!!, "input") as EditText
            val service = member(KeyboardWindow.instance!!, "service") as com.osfans.trime.ime.core.TrimeInputMethodService
            fun candidateView() = member(service, "candidatesView") as ViewGroup
            fun bar() = member(member(input(), "inputBar")!!, "view") as android.widget.ViewAnimator
            val session = RimeDaemon.createSession("mie18-check")
            try {
                runBlocking { session.runOnReady {
                    deploy(); check(selectSchema("mie_probe")); setRuntimeOption("ascii_mode", false)
                    setCandidatePagingMode(true); clearComposition()
                } }
                main { p.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.ALWAYS_SHOW); p.candidates.floatingBall.setValue(true); p.keyboard.hideInputBar.setValue(false) }; pause()
                main { check((member(candidateView(), "ball") as View).visibility == View.GONE) { "Ball shown with status bar" } }
                main { p.keyboard.hideInputBar.setValue(true) }; pause()
                main { check((member(candidateView(), "ball") as View).isShown) { "Ball missing with hidden status bar" } }
                main { p.keyboard.hideInputBar.setValue(false) }; pause()
                main { check((member(candidateView(), "ball") as View).visibility == View.GONE) { "Ball remains after status bar restored" } }
                pass("ball requires hidden status bar and updates immediately in both directions")
                runBlocking { session.runOnReady { setCandidatePagingMode(true); simulateKeySequence("a") } }; pause()
                main {
                    val ui = member(candidateView(), "candidatesUi")!!
                    val pager = member(ui, "paginationUi")!!
                    val prev = member(pager, "prevIcon") as View
                    val next = member(pager, "nextIcon") as View
                    check(prev.left < next.left && prev.right < next.left) { "Page controls overlap" }
                    check(prev.width >= (32 * prev.resources.displayMetrics.density).toInt())
                    check(prev.height <= (24 * prev.resources.displayMetrics.density).toInt())
                    val footer = member(pager, "root") as View
                    check(candidateView().height - footer.bottom <= (prev.resources.displayMetrics.density + 1).toInt()) { "Blank space below footer" }
                    check(!prev.isEnabled && next.isEnabled)
                    check(all(candidateView()).filterIsInstance<TextView>().none { it.text.toString().contains("候选窗") })
                    next.performClick()
                }; pause()
                main {
                    val pager = member(member(candidateView(), "candidatesUi")!!, "paginationUi")!!
                    check((member(pager, "prevIcon") as View).isEnabled)
                    check(!(member(pager, "nextIcon") as View).isEnabled)
                }
                shot("mie18-paging.png")
                pass("title removed; opposite-edge compact paging controls change pages")
                runBlocking { session.runOnReady { clearComposition() } }; pause()
                val recorder = com.osfans.trime.ime.voice.builtin.AudioRecorder
                check(recorder.pcmLevel(ByteArray(6400)) == 0f)
                val pcm = ByteArray(6400) { if (it % 2 == 0) 0 else 32 }
                check(recorder.pcmLevel(pcm) in 0.7f..1f)
                main { service.updateVoiceRecording(true, recorder.pcmLevel(pcm)) }; pause()
                main { check(bar().displayedChild == 3 && bar().isShown); check((member(member(input(), "inputBar")!!, "alwaysUi")!!.let { member(it, "root") } as View).visibility != View.VISIBLE) }
                shot("mie18-recording.png")
                main { service.updateVoiceRecording(false) }; pause()
                main { check(bar().displayedChild != 3) }
                main { p.keyboard.hideInputBar.setValue(true) }; pause()
                main { check(bar().visibility == View.GONE); service.updateVoiceRecording(true, 0.8f) }; pause()
                main { check(bar().isShown && bar().displayedChild == 3); service.voiceInput.cancel() }; pause()
                main { check(bar().visibility == View.GONE); check(!service.voiceRecordingState.value.recording) }
                pass("PCM level mapping; whole-bar waveform; stop/cancel restores visible or hidden status bar (synthetic amplitude)")
            } finally { main { service.updateVoiceRecording(false) }; RimeDaemon.destroySession("mie18-check") }
            result.putString("result", "PASS")
        } catch (e: Throwable) { result.putString("result", "FAIL: ${android.util.Log.getStackTraceString(e)}") }
        finally {
            runCatching {
                RimeDaemon.changeStorage {
                    if (oldPreferences == null) { file.delete(); File(file.path + ".bak").delete() } else file.writeBytes(oldPreferences)
                    CandidatePreferences.reload()
                    p.profile.dataStorageMode.setValue(oldMode); p.profile.directExternalPath.setValue(oldPath); p.profile.directExternalReady.setValue(oldReady)
                }
                main { CustomKeyboards.setMode(oldKeyboardMode); ThemeManager.prefs.selectedTheme.setValue(oldTheme); p.keyboard.videoBackground.setValue(oldVideo); p.keyboard.unifiedBackground.setValue(oldUnified); p.keyboard.keyBackground.setValue(oldBackground); p.candidates.floatingBall.setValue(oldBall); p.candidates.ballOpacity.setValue(oldOpacity); p.candidates.mode.setValue(oldPopup); p.keyboard.hideInputBar.setValue(oldHideBar) }
                runBlocking { ThemeManager.refreshActiveTheme() }
                fixture.deleteRecursively()
            }.onFailure { result.putString("restore", android.util.Log.getStackTraceString(it)) }
        }
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
