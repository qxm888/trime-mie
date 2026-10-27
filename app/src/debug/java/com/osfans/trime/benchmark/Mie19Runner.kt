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
class Mie19Runner : Instrumentation() {
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
        val oldAppearance = p.keyboard.surfaceAppearance.getValue()
        val oldHideBar = p.keyboard.hideInputBar.getValue()
        val oldPopup = p.candidates.mode.getValue(); val oldBall = p.candidates.floatingBall.getValue(); val oldOpacity = p.candidates.ballOpacity.getValue()
        val file = File(targetContext.filesDir, "candidate_preferences.json"); val oldPreferences = file.takeIf { it.exists() }?.readBytes()
        val fixture = java.nio.file.Files.createTempDirectory(targetContext.getExternalFilesDir(null)!!.toPath(), "mie19-fixture-").toFile()
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
            val appearance = com.osfans.trime.ime.appearance.SurfaceAppearance
            val video = File(targetContext.filesDir, "mie19-fixture.mp4")
            check(video.isFile)
            val keyStyle = com.osfans.trime.ime.appearance.SurfaceStyle(background = "#66FFFFFF", borderWidth = 1f, borderColor = "#66808080", radius = 10f, blur = true)
            main {
                p.keyboard.hideInputBar.setValue(false); p.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.DISABLED)
                p.keyboard.videoBackground.setValue(video.path); p.keyboard.unifiedBackground.setValue(true)
                appearance.save(com.osfans.trime.ime.appearance.SurfaceAppearanceConfig(keys = keyStyle, toolbar = keyStyle.copy(background = "#66FF0000"), linkToolbar = true, barBlur = true))
            }
            await("Video not playing") { runCatching { (member(member(input(), "unifiedVideo")!!, "player") as MediaPlayer).isPlaying }.getOrDefault(false) }
            fun blur() = input().keyboardView.getTag(com.osfans.trime.R.id.keyboard_backdrop_blur)!!
            await("No blurred background") { member(blur(), "front") != null }
            main {
                check(appearance.current().effectiveToolbar == appearance.current().keys)
                val buttons = all(input().keyboardView).filterIsInstance<com.osfans.trime.ime.bar.ui.ToolButton>()
                check(buttons.isNotEmpty() && buttons.all { it.background is com.osfans.trime.ime.appearance.SurfaceDrawable })
                check((member(member(input(), "inputBar")!!, "view") as View).background is com.osfans.trime.ime.appearance.SurfaceDrawable)
            }
            fun signature(bitmap: android.graphics.Bitmap): Long {
                var value = 17L
                for (y in 0 until bitmap.height step 5) for (x in 0 until bitmap.width step 5) value = value * 31 + bitmap.getPixel(x, y)
                return value
            }
            var first = 0L
            main { first = signature(member(blur(), "front") as android.graphics.Bitmap) }
            SystemClock.sleep(700)
            main { check(first != signature(member(blur(), "front") as android.graphics.Bitmap)) { "Video blur is frozen" } }
            main {
                val colors = all(input().keyboardView).filterIsInstance<com.osfans.trime.ime.keyboard.KeyView>().take(10).map { key ->
                    val bitmap = android.graphics.Bitmap.createBitmap(key.width, key.height, android.graphics.Bitmap.Config.ARGB_8888)
                    check(com.osfans.trime.ime.appearance.BackdropBlur.draw(key, android.graphics.Canvas(bitmap), android.graphics.RectF(0f, 0f, key.width.toFloat(), key.height.toFloat())))
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    bitmap.recycle(); color
                }.distinct()
                check(colors.size >= 4) { "Translated keys sampled the same background position" }
            }
            shot("mie19-glass.png")
            pass("linked key/icon appearance, toolbar border/background and changing video backdrop blur")
            main {
                val independent = appearance.current().copy(linkToolbar = false)
                appearance.save(independent)
                check(appearance.current().effectiveToolbar.background == "#66FF0000")
            }; pause()
            main { KeyboardWindow.instance!!.switchKeyboard("number") }; pause()
            await("Blur not recreated after layout switch") { member(blur(), "front") != null }
            pass("independent toolbar settings preserved; layout switch retains blur rendering")
            main { appearance.save(appearance.current().copy(keys = keyStyle.copy(blur = false), toolbar = keyStyle.copy(blur = false), barBlur = false)) }; pause()
            main { check(input().keyboardView.getTag(com.osfans.trime.R.id.keyboard_backdrop_blur) == null) }
            pass("all blur disabled leaves no sampling controller")
            lateinit var dialog: androidx.appcompat.app.AlertDialog
            main { dialog = com.osfans.trime.ui.main.SurfaceAppearanceEditor.show(activity) }; pause()
            shot("mie19-settings.png")
            main { all(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString().startsWith("▸ 状态栏图标") }.performClick() }; pause()
            shot("mie19-settings-expanded.png")
            main { dialog.dismiss() }; pause()
            result.putString("result", "PASS")
        } catch (e: Throwable) { result.putString("result", "FAIL: ${android.util.Log.getStackTraceString(e)}") }
        finally {
            runCatching {
                RimeDaemon.changeStorage {
                    if (oldPreferences == null) { file.delete(); File(file.path + ".bak").delete() } else file.writeBytes(oldPreferences)
                    CandidatePreferences.reload()
                    p.profile.dataStorageMode.setValue(oldMode); p.profile.directExternalPath.setValue(oldPath); p.profile.directExternalReady.setValue(oldReady)
                }
                main { CustomKeyboards.setMode(oldKeyboardMode); ThemeManager.prefs.selectedTheme.setValue(oldTheme); p.keyboard.videoBackground.setValue(oldVideo); p.keyboard.unifiedBackground.setValue(oldUnified); p.keyboard.keyBackground.setValue(oldBackground); p.keyboard.surfaceAppearance.setValue(oldAppearance); p.candidates.floatingBall.setValue(oldBall); p.candidates.ballOpacity.setValue(oldOpacity); p.candidates.mode.setValue(oldPopup); p.keyboard.hideInputBar.setValue(oldHideBar) }
                runBlocking { ThemeManager.refreshActiveTheme() }
                fixture.deleteRecursively()
            }.onFailure { result.putString("restore", android.util.Log.getStackTraceString(it)) }
        }
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
