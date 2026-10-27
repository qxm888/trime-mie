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
class Mie17Runner : Instrumentation() {
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
        val fixture = java.nio.file.Files.createTempDirectory(targetContext.getExternalFilesDir(null)!!.toPath(), "mie17-fixture-").toFile()
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
            val session = RimeDaemon.createSession("mie17-check")
            try {
                runBlocking { session.runOnReady {
                    deploy(); check(selectSchema("mie_probe")); setRuntimeOption("ascii_mode", false)
                    clearComposition(); setCandidatePagingMode(false); simulateKeySequence("a")
                    val original = getCandidates(0, 3).map { it.text }
                    check(original.size == 3)
                    val word = original[2]
                    check(customizeCandidate(2, word, true, "pin1"))
                    check(getCandidates(0, 3).first().text == word)
                    check(selectCandidate(0, true))
                } }; pause()
                main { check(editor.text.toString().endsWith("丙")) { "Tapped candidate committed wrong source" } }
                runBlocking { session.runOnReady { simulateKeySequence("a"); processKey(32) } }; pause()
                main { check(editor.text.toString().endsWith("丙丙")) { "Space committed wrong source" } }
                runBlocking { session.runOnReady {
                    simulateKeySequence("a"); check(customizeCandidate(0, "丙", true, "pin2")); processKey('2'.code)
                } }; pause()
                main { check(editor.text.toString().endsWith("丙丙丙")) { "Number key committed wrong source" } }
                pass("candidate pinning, click, space and numeric selection preserve native source mapping")
                runBlocking { session.runOnReady {
                    setCandidatePagingMode(true); simulateKeySequence("a")
                    check(changeCandidatePage(false)); check(selectCandidate(0, false))
                } }; pause()
                main { check(editor.text.toString().endsWith("乙")) { "Paged selection committed wrong source" } }
                runBlocking { session.runOnReady {
                    setCandidatePagingMode(false); simulateKeySequence("a")
                    check(customizeCandidate(1, "丙", true, "code", "b"))
                    check(getCandidates(0, 4).none { it.text == "丙" })
                    clearComposition(); simulateKeySequence("b"); check(getCandidates(0, 3).first().text == "丙")
                    check(selectCandidate(0, true))
                } }; pause()
                main { check(editor.text.toString().endsWith("乙丙")) { "Custom code committed wrong text: ${editor.text}" } }
                runBlocking { session.runOnReady {
                    clearComposition(); simulateKeySequence("b"); check(customizeCandidate(0, "丙", true, "delete"))
                    check(getCandidates(0, 3).none { it.text == "丙" }); clearComposition()
                } }; pause()
                pass("paged selection, custom encoding and candidate deletion use isolated fixture data")
            } finally { RimeDaemon.destroySession("mie17-check") }
            main {
                com.osfans.trime.ui.main.KeyAppearanceEditor.show(activity, "Appearance fixture", com.osfans.trime.data.keymap.KeyAppearance.Style()) { }
            }; pause()
            main {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                val headers = all(root).filterIsInstance<TextView>().filter { it.text.startsWith("▸ ") }
                check(headers.size == 6)
                check(all(root).filterIsInstance<EditText>().none { it.isShown })
                headers.first().performClick()
                check(all(root).filterIsInstance<EditText>().count { it.isShown } == 4)
                root.findViewById<View>(android.R.id.button2).performClick()
            }
            pass("appearance drawers start collapsed; expanding one shows only its fields")
            main {
                com.osfans.trime.ui.main.KeyboardOptionsEditor.show(activity, com.osfans.trime.data.keymap.KeyboardOptions()) { }
            }; pause()
            main {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                val headers = all(root).filterIsInstance<TextView>().filter { it.text.startsWith("▸ ") }
                check(headers.size == 3 && all(root).filterIsInstance<EditText>().none { it.isShown })
                headers[0].performClick()
                check(all(root).filterIsInstance<EditText>().count { it.isShown } == 4)
                headers[2].performClick()
                check(all(root).filterIsInstance<EditText>().count { it.isShown } == 2)
                root.findViewById<View>(android.R.id.button2).performClick()
            }
            pass("layout drawers start collapsed; opening another closes the previous group")
            var chosen = "unset"
            main { com.osfans.trime.ui.main.AppearancePicker.show(activity, "按键背景", "#FFFFFF", true) { chosen = it } }; pause()
            shot("mie17-palette.png")
            main {
                val root = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.hasWindowFocus() }
                check(all(root).count { it.contentDescription?.startsWith("选择颜色 #") == true } == 12)
                check(all(root).filterIsInstance<TextView>().any { it.text == "选择图片…" })
                all(root).filterIsInstance<TextView>().first { it.text == "透明" }.performClick()
                root.findViewById<View>(android.R.id.button1).performClick()
                check(chosen == "#00000000")
            }
            com.osfans.trime.data.stats.InputStats.activeSchemaId = "mie_probe"
            com.osfans.trime.data.stats.InputStats.onKeyHeat("MIE17_HEAT")
            com.osfans.trime.data.stats.InputStats.read("mie_probe") { stats ->
                val counts = listOf(stats.daily, stats.weekly, stats.monthly, stats.yearly).map { it.keyCounts["MIE17_HEAT"] }
                check(counts.all { it != null && it > 0 } && counts.distinct().size == 1)
            }
            pass("preset palette, transparent selection, image entry and all four heat periods")
            main { p.keyboard.keyBackground.setValue("#FFA74942") }
            await("Whole key override missing") {
                val key = KeyboardWindow.currentKeyboard.keys.first()
                (key.getBackgroundDrawable() as? android.graphics.drawable.GradientDrawable)?.color?.defaultColor == android.graphics.Color.parseColor("#FFA74942")
            }
            main { p.keyboard.keyBackground.setValue(""); p.candidates.floatingBall.setValue(true); p.candidates.ballOpacity.setValue(65); p.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.ALWAYS_SHOW) }
            fun candidateView() = member(member(KeyboardWindow.instance!!, "service")!!, "candidatesView") as com.osfans.trime.ime.composition.CandidatesView
            await("Idle ball missing") { runCatching { (member(candidateView(), "ball") as View).let { it.isShown && it.width >= 40 } }.getOrDefault(false) }
            pause(); shot("mie17-ball.png")
            main { check(kotlin.math.abs(candidateView().alpha - .65f) < .01f); (member(candidateView(), "ball") as View).performClick() }; pause()
            main { check((member(candidateView(), "tools") as View).isShown); check((member(candidateView(), "tools") as View).width > 0); (member(candidateView(), "ball") as View).performLongClick() }; pause()
            main { check(member(member(input(), "windowManager")!!, "currentWindow") is com.osfans.trime.ime.switches.SwitchOptionWindow) }
            val floatSession = RimeDaemon.createSession("mie17-floating-check")
            try {
                runBlocking { floatSession.runOnReady { setCandidatePagingMode(true); clearComposition(); simulateKeySequence("a") } }; pause()
                await("Typing did not expand candidate window") { (member(candidateView(), "header") as View).isShown && candidateView().height > 44 }
                await("Candidate items were not rendered") { all(candidateView()).filterIsInstance<TextView>().any { it.text.toString().contains("甲") && it.isShown && it.height > 0 } }
                shot("mie17-floating-candidates.png")
                main { (member(candidateView(), "header") as View).performClick(); check((member(candidateView(), "ball") as View).isShown) }
                runBlocking { floatSession.runOnReady { clearComposition() } }
            } finally { RimeDaemon.destroySession("mie17-floating-check") }
            main { p.keyboard.hideInputBar.setValue(true) }; pause()
            main { check((member(member(input(), "inputBar")!!, "view") as View).visibility == View.GONE); p.keyboard.hideInputBar.setValue(false); p.candidates.mode.setValue(oldPopup) }; pause()
            pass("whole-key background, idle ball, temporary toolbar, long-press menu, composition expansion and hidden status bar")
            val video = File(targetContext.filesDir, "mie17-fixture.mp4")
            check(video.isFile)
            main { p.keyboard.unifiedBackground.setValue(false); p.keyboard.videoBackground.setValue(video.path); ThemeManager.requestCustomizationRefresh() }
            await("Video did not start") { runCatching { (member(member(KeyboardWindow.instance!!, "videoBackground")!!, "player") as MediaPlayer).isPlaying }.getOrDefault(false) }
            lateinit var player: MediaPlayer
            main { player = member(member(KeyboardWindow.instance!!, "videoBackground")!!, "player") as MediaPlayer }
            await("Video clock did not advance") { player.currentPosition > 400 }
            main { KeyboardWindow.instance!!.switchKeyboard("number") }; pause()
            main { check(member(member(KeyboardWindow.instance!!, "videoBackground")!!, "player") === player) }
            pass("layout switch reuses the same video decoder")
            main { p.keyboard.unifiedBackground.setValue(true) }
            await("Unified video missing") { runCatching { (member(member(input(), "unifiedVideo")!!, "player") as MediaPlayer).isPlaying }.getOrDefault(false) }
            pause()
            main {
                val view = member(input(), "unifiedVideo") as View
                check(view.parent === input().keyboardView && view.height == input().keyboardView.height && view.top == 0)
                check((member(member(input(), "inputBar")!!, "view") as View).background == null)
                check(member(KeyboardWindow.instance!!, "videoBackground") == null)
                player = member(view, "player") as MediaPlayer
                KeyboardWindow.instance!!.switchKeyboard("default")
            }; pause()
            main { check(member(member(input(), "unifiedVideo")!!, "player") === player); input().setUnifiedVideoActive(false); check(!player.isPlaying); input().setUnifiedVideoActive(true); check(player.isPlaying) }
            pass("unified background covers toolbar and keyboard; switches retain one decoder; hide/resume works")
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
