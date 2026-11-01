/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.*
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.MaybeStringList
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.FunctionMenuEditor
import kotlinx.coroutines.runBlocking
import java.io.File

/** New-feature checks in an independent IME; host restores private state and original IME. */
class Mie3115Runner : Instrumentation() {
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
        error("No member $name")
    }
    private fun service() = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
    private fun input() = member(service(), "inputView") as InputView
    private fun pass(value: String) = sendStatus(0, Bundle().apply { putString("case", "PASS: $value") })
    private fun pause() { waitForIdleSync(); SystemClock.sleep(220) }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(160) { var yes = false; main { yes = condition() }; if (yes) return; SystemClock.sleep(60) }
        error(label)
    }
    private fun navigate(id: String) {
        main { KeyboardWindow.instance!!.switchKeyboardFromAction(id) }
        await("navigation failed: $id") { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId) == id }
        pause()
    }
    private fun returnKey() {
        main {
            val key = KeyboardWindow.currentKeyboard.keys.first { it.getLabel() == "返回" }
            val action = key.getAction(KeyBehavior.CLICK)!!
            KeyboardWindow.instance!!.switchKeyboardFromAction(action.select)
        }
        pause()
    }
    override fun onStart() = runBlocking {
        waitForIdleSync()
        val result = Bundle(); val p = AppPrefs.defaultInstance()
        val originalLayouts = CustomKeyboards.exportJson()
        val originalId = CustomKeyboards.activeId; val originalMode = CustomKeyboards.mode
        val originalTheme = ThemeManager.prefs.selectedTheme.getValue()
        val pref = p.keyboard.floatingKeyboard.sharedPreferences; val saved = pref.all.toMap()
        val savedFonts = listOf("key", "symbol").associateWith(FontOverrides::selection)
        val touchedMenus = mutableMapOf<String, Set<String>>()
        val unique = "mie3115_${java.util.UUID.randomUUID()}"
        var activity: Activity? = null; var fixtureFont: File? = null
        val modeKeys = listOf(p.keyboard.floatingKeyboard, p.keyboard.floatingKeyboardAutoLandscape, p.keyboard.oneHandMode, p.keyboard.hideInputBar, p.general.inputStatsEnabled)
        val oldStats = com.osfans.trime.data.stats.InputStats.isEnabled()
        try {
            await("new builtin migration unfinished") { CustomKeyboards.get("custom_frost_themes")?.rows?.flatMap { it.keys }?.count { it.bindings["click"].orEmpty().startsWith("Keyboard_custom_") } == 9 }
            main {
                p.keyboard.floatingKeyboard.setValue(false); p.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                p.keyboard.oneHandMode.setValue(0); p.keyboard.hideInputBar.setValue(false)
                p.general.inputStatsEnabled.setValue(false); com.osfans.trime.data.stats.InputStats.setEnabled(false)
                val a = CustomKeyboards.create("$unique A"); val b = CustomKeyboards.create("$unique B")
                fun key(label: String, width: Float, extra: Pair<String, String>) = CustomKeyboards.CustomKey(label, width, mutableMapOf("click" to label.lowercase(), extra))
                CustomKeyboards.mutate(a) { it.rows.clear(); it.rows += CustomKeyboards.CustomRow(mutableListOf(key("C", 20f, "longClick" to "paste"), key("D", 80f, "swipeUp" to "copy"))) }
                CustomKeyboards.mutate(b) {
                    it.rows.clear(); it.rows += CustomKeyboards.CustomRow(mutableListOf(key("D", 60f, "longClick" to "cut"), key("C", 40f, "longClick" to "copy")))
                    it.background = CustomBackground(CustomBackground.Type.COLOR, "#FF112233")
                    it.typography = mapOf(LayoutTypography.GLOBAL to FontSpec("system:serif"))
                }
                val before = CustomKeyboards.get(b)!!
                val plan = KeyboardBindingCopy.plan(a, b); check(plan.matches.size == 2)
                check(KeyboardBindingCopy.apply(plan) == 2)
                val after = CustomKeyboards.get(b)!!
                check(after.rows[0].keys[1].bindings["longClick"] == "paste")
                check(after.rows[0].keys[0].bindings["swipeUp"] == "copy")
                check(after.rows[0].keys.map { it.width } == before.rows[0].keys.map { it.width })
                check(after.background == before.background && after.typography == before.typography)
                val stale = KeyboardBindingCopy.plan(a, b)
                CustomKeyboards.mutate(a) { it.rows[0].keys[0].bindings["longClick"] = "cut" }
                check(runCatching { KeyboardBindingCopy.apply(stale) }.isFailure)
                CustomKeyboards.activeId = "custom_pink"
            }
            pass("binding copy matches C/D across reordered keys; copies gestures; preserves widths/background/fonts; stale plans rejected")
            ThemeManager.selectTheme(originalTheme, layoutMode = CustomKeyboards.MODE_CUSTOM)
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("IME did not attach") { KeyboardWindow.instance != null && input().isShown }
            pause()
            for (variant in listOf("custom_pink", "custom_pink_9", "custom_pink_14", "custom_pink_18", "custom_pink_7_1", "custom_pink_7_2")) {
                navigate(variant)
                for (page in listOf("custom_pink_num", "custom_pink_layouts", "custom_pink_themes")) {
                    navigate(page); returnKey()
                    await("return lost original $variant from $page") { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId) == variant }
                }
                navigate("custom_pink_en")
                main { KeyboardWindow.instance!!.switchKeyboardFromAction("custom_pink") }
                await("English return lost $variant") { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId) == variant }
            }
            navigate("custom_pink_18"); navigate("custom_pink_layouts")
            main { KeyboardWindow.instance!!.switchKeyboardFromAction("custom_pink") }
            await("explicit 26-key selection was redirected") { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId) == "custom_pink" }
            pass("26/9/14/18/two seven-column layouts return from number/layout/theme pages; English return retained; explicit 26 choice works")
            val families = listOf("mint", "pink", "blue", "orange", "purple", "porcelain", "obsidian", "champagne", "frost")
            main { families.forEach { color ->
                val page = CustomKeyboards.get("custom_${color}_themes")!!
                val targets = page.rows.flatMap { it.keys }.mapNotNull { it.bindings["click"] }.filter { it.startsWith("Keyboard_custom_") }.toSet()
                check(targets == families.map { "Keyboard_custom_$it" }.toSet()) { "Missing chooser targets in $color: $targets" }
            } }
            navigate("custom_pink_themes"); pause()
            main {
                val board = (member(input(), "windowManager") as BoardWindowManager).view
                check(KeyboardWindow.currentKeyboard.keys.all { it.y + it.height <= board.height + 1 }) {
                    "Theme chooser row is clipped: model=${KeyboardWindow.currentKeyboard.height} visible=${board.height}"
                }
            }
            uiAutomation.takeScreenshot()?.let { bitmap -> File(targetContext.cacheDir, "mie3115-nine-themes.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            pass("all nine stock theme choosers offer all nine families")
            // Verify the default and declared font policies with a real available font in rime/fonts.
            val systemFont = File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile }
                ?: File("/system/fonts").listFiles()!!.first { it.extension == "ttf" }
            fixtureFont = File(com.osfans.trime.core.data.DataManager.userDataDir, "fonts/$unique.ttf").apply { parentFile!!.mkdirs(); check(!exists()); systemFont.copyTo(this) }
            DirectoryFontFallback.refresh()
            main { savedFonts.keys.forEach { FontOverrides.set(it, "", refresh = false) } }
            main {
                val theme = Theme("$unique empty", fontConfigId = unique)
                check(theme.fonts.key == Typeface.DEFAULT && theme.fonts.symbol == Typeface.DEFAULT) { "Directory font replaced the theme's unconfigured system font" }
                val reference = Paint().apply { typeface = Typeface.DEFAULT; textSize = 28f }.fontMetrics
                val actual = Paint().apply { typeface = theme.fonts.symbol; textSize = 28f }.fontMetrics
                check(reference.top == actual.top && reference.ascent == actual.ascent && reference.bottom == actual.bottom) { "Default symbol font metrics changed from original system baseline" }
                val explicit = Theme("$unique explicit", style = GeneralStyle(keyFont = MaybeStringList.Scalar(fixtureFont!!.name), symbolFont = MaybeStringList.Scalar(fixtureFont!!.name)), fontConfigId = unique)
                check(explicit.fonts.key != Typeface.DEFAULT && explicit.fonts.symbol != Typeface.DEFAULT)
            }
            pass("rime/fonts never takes over an unconfigured theme font; symbol metrics match original system baseline; declared theme font still loads")
            ThemeManager.selectTheme(originalTheme, layoutMode = CustomKeyboards.MODE_THEME); pause()
            val actualThemeId = ThemeManager.activeTheme.fontConfigId
            touchedMenus[actualThemeId] = ToolbarOverrides.hiddenMenuItems(actualThemeId)
            touchedMenus[unique] = ToolbarOverrides.hiddenMenuItems(unique)
            val originalCustomHidden = CustomKeyboards.get("custom_pink")!!.hiddenMenuItems
            main {
                ToolbarOverrides.setHiddenMenuItems(actualThemeId, setOf("static:InputStats"))
                check(ToolbarOverrides.hiddenMenuItems(unique).isEmpty())
                check(CustomKeyboards.get("custom_pink")!!.hiddenMenuItems == originalCustomHidden)
                val panel = SwitchOptionWindow(input().di)
                (member(input(), "windowManager") as BoardWindowManager).attachWindow(panel)
                val edit = member(panel, "editButton") as View
                check(edit.visibility == View.VISIBLE); edit.performClick()
                val dialog = member(service(), "showingDialog") as android.app.AlertDialog
                check(dialog.isShowing)
                val index = FunctionMenuEditor.builtins.keys.indexOf("InputStats")
                check(!dialog.listView.isItemChecked(index))
                dialog.dismiss()
                ToolbarOverrides.setHiddenMenuItems(actualThemeId, emptySet()); check(ToolbarOverrides.flush())
            }
            pass("theme mode exposes functional menu editor; unchecked InputStats reflects scoped setting; custom layouts and other themes unaffected")
            result.putString("result", "PASS")
        } catch (e: Throwable) { result.putString("result", "FAIL: ${e.message}"); result.putString("trace", e.stackTraceToString()) }
        finally {
            main {
                CustomKeyboards.importJson(originalLayouts, true); CustomKeyboards.activeId = originalId
                savedFonts.forEach { (key, value) -> FontOverrides.set(key, value, refresh = false) }
                touchedMenus.forEach { (key, value) -> ToolbarOverrides.setHiddenMenuItems(key, value) }; ToolbarOverrides.flush()
                val edit = pref.edit()
                modeKeys.forEach { key -> when(val old = saved[key.key]) { is Boolean -> edit.putBoolean(key.key, old); is Int -> edit.putInt(key.key, old); else -> edit.remove(key.key) } }
                edit.commit(); com.osfans.trime.data.stats.InputStats.setEnabled(oldStats)
            }
            fixtureFont?.delete(); DirectoryFontFallback.refresh()
            runCatching { ThemeManager.selectTheme(originalTheme, layoutMode = originalMode) }
            CustomKeyboards.flush(); activity?.let { main { it.finish() } }
        }
        finish(if(result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
