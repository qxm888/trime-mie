/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.core.data.CandidateRecordFormats
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.CreatedWordsActivity
import com.osfans.trime.ui.main.KeyboardSetActivity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Host must back up private files and linked keyboard assets before running. Debug only. */
class ModeBindingsRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { block() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
    }
    private fun member(value: Any, name: String): Any? {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) {}
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) {}
            type = type.superclass
        }
        error("No member $name")
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(label: String, condition: () -> Boolean) {
        repeat(240) { var yes = false; main { yes = condition() }; if (yes) return; SystemClock.sleep(60) }
        error(label)
    }
    private fun pass(text: String) = sendStatus(0, Bundle().apply { putString("case", "PASS: $text") })
    private fun shot(name: String) {
        waitForIdleSync(); SystemClock.sleep(320)
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(targetContext.cacheDir, "mode-bindings-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    override fun onStart() = runBlocking {
        val result = Bundle(); var activity: Activity? = null; var stage = "startup"
        try {
            waitForIdleSync()
            CustomKeyboards.init(targetContext); CustomKeyboards.awaitBuiltinLayouts()
            CustomKeyboards.resetBuiltinSet("custom_frost")
            val prefs = AppPrefs.defaultInstance()
            main {
                prefs.general.inputStatsEnabled.setValue(false)
                com.osfans.trime.data.stats.InputStats.setEnabled(false)
                prefs.keyboard.floatingKeyboard.setValue(false); prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0); prefs.keyboard.oneHandSide.setValue(AppPrefs.Keyboard.OneHandSide.RIGHT)
                prefs.keyboard.hideInputBar.setValue(false)
                KeyboardModeBinding.save(true, KeyboardModeBinding()); KeyboardModeBinding.save(false, KeyboardModeBinding())
            }
            stage = "set dimensions"
            val root = KeyboardSetOperations.newSet("模式绑定验证", "custom_frost", KeyboardSetOperations.layoutNames.keys.toList())
            val pages = KeyboardSetOperations.ordered(root).associateBy { CustomKeyboards.pageRole(it) }
            val template = CustomKeyboards.builtinTemplates()
            pages.forEach { (role, id) ->
                val value = CustomKeyboards.get(id)!!
                if (!role.startsWith("zh_7_")) check(value.keyboardHeightDp == 260 && value.rowSpacing == 10) { "Wrong defaults: $role" }
                else {
                    val original = template.getValue("custom_frost_" + if (role == "zh_7_1") "7_1" else "7_2")
                    check(value.keyboardHeightDp == original.keyboardHeightDp && value.rowSpacing == original.rowSpacing) { "Seven-column size changed" }
                }
            }
            val enter = CustomKeyboards.get(root)!!.rows.flatMap { it.keys }.first { it.bindings["click"] == "Return" }
            check(enter.appearance.keyBackColor.uppercase().endsWith("507F97")) { "Frost Enter is not dark: ${enter.appearance.keyBackColor}" }
            pass("all eight layouts: nonseven 260/10; seven unchanged; dark frost Enter")
            stage = "English inheritance"
            CustomKeyboards.mutate(root) { kb -> kb.rows.forEach { row -> row.keys.replaceAll { key ->
                if (key.identity == "char:c") key.copy(bindings = key.bindings.toMutableMap().apply { put("longClick", "copy") },
                    appearance = key.appearance.copy(popup = listOf("paste"), sendBindings = true)) else key
            } } }
            fun englishC() = CustomKeyboards.get(pages.getValue("en"))!!.rows.flatMap { it.keys }.first { it.identity == "char:c" }
            check(englishC().bindings["longClick"] == "copy" && englishC().appearance.popup == listOf("paste") && englishC().appearance.sendBindings == true)
            CustomKeyboards.mutate(pages.getValue("en")) { it.rowMargin = 3 }
            CustomKeyboards.mutate(root) { it.rows.forEach { row -> row.keys.replaceAll { key -> if (key.identity == "char:c") key.copy(bindings = key.bindings.toMutableMap().apply { put("longClick", "cut") }) else key } } }
            check(englishC().bindings["longClick"] == "cut")
            pass("actual stored English layout inherits main functions after unrelated page edit")
            stage = "label copy and independent annotations"
            CustomKeyboards.mutate(root) { kb -> kb.rows.forEach { row -> row.keys.replaceAll { key ->
                if (key.identity == "char:c") key.copy(label = "复制", appearance = key.appearance.copy(keyTextSize = 22f, symbolAlign = "right", hintAlign = "left")) else key
            } } }
            val duplicate = KeyboardSetOperations.copySet(root, "标签绑定验证副本")
            CustomKeyboards.mutate(duplicate) { kb -> kb.rows.forEach { row -> row.keys.replaceAll { key ->
                if (key.identity == "char:c") key.copy(label = "C", appearance = key.appearance.copy(keyTextSize = 18f, symbolAlign = "center", hintAlign = "center")) else key
            } } }
            KeyboardFamilyBindingCopy.apply(KeyboardFamilyBindingCopy.plan(root, duplicate))
            val copied = CustomKeyboards.get(duplicate)!!.rows.flatMap { it.keys }.first { it.identity == "char:c" }
            check(copied.label == "复制" && copied.appearance.keyTextSize == 22f && copied.appearance.symbolAlign == "right" && copied.appearance.hintAlign == "left")
            CustomKeyboards.mutate(root) { kb -> kb.rows.forEach { row -> row.keys.replaceAll { key ->
                when(key.identity) {
                    "char:c" -> key.copy(label = "C", appearance = key.appearance.copy(keyTextSize = 20f))
                    "char:a" -> key.copy(appearance = key.appearance.copy(labelSymbol = "右", hint = "左", symbolAlign = "right", hintAlign = "left", symbolTextSize = 10f, hintTextSize = 10f))
                    else -> key
                }
            } } }
            pass("whole-family binding copy includes main label size and distinct upper-right/lower-left annotation alignment")
            stage = "export UI"
            activity = startActivitySync(Intent(targetContext, CreatedWordsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("missing visible export button") { views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "导出造词 · YAML / JSON / TXT" } }
            main { views(activity!!.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "导出造词 · YAML / JSON / TXT" }.performClick() }
            await("missing export format dialog") { WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<TextView>().count { it.text.toString() in CandidateRecordFormats.Format.entries.map { f -> f.title } } == 3 }
            main { WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<TextView>().first { it.text.toString() == "取消" }.performClick() }
            val words = listOf("💕", "造词", "句子，标点！", "a\tb\nc\\d")
            val data = mapOf(Json.encodeToString(listOf("mode_test", "ab")) to CandidatePreferences.Rule(aliases = words))
            CandidateRecordFormats.Format.entries.forEach { format -> check(CandidateRecordFormats.decode(CandidateRecordFormats.encode(data, format, true)).records.single().aliases == words) }
            main { activity!!.finish() }; activity = null
            pass("visible export UI offers all 3 formats; Unicode words survive every codec on phone")
            stage = "geometry UI"
            activity = startActivitySync(Intent(targetContext, KeyboardSetActivity::class.java).putExtra("root", root).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("set editor not ready") { views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("添加布局") } }
            main { views(activity!!.window.decorView).filterIsInstance<TabLayout>().single().getTabAt(1)!!.select() }
            main { views(activity!!.window.decorView).filterIsInstance<TextView>().first { it.text.toString().startsWith("尺寸与间距") }.performClick() }
            await("geometry fields missing") { WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<android.widget.EditText>().size == 4 }
            shot("geometry")
            main { WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<TextView>().first { it.text.toString() == "取消" }.performClick() }
            waitForIdleSync()
            var savedOptions: KeyboardOptions? = null
            main { com.osfans.trime.ui.main.KeyboardOptionsEditor.show(activity!!, CustomKeyboards.get(root)!!.options) { savedOptions = it } }
            waitForIdleSync()
            main {
                val dialogRoot = WindowInspector.getGlobalWindowViews().last { view -> views(view).filterIsInstance<TextView>().any { it.text.toString() == "布局与切换行为" } }
                val fields = views(dialogRoot).filterIsInstance<com.google.android.material.textfield.MaterialAutoCompleteTextView>()
                fields.first { ((it.parent?.parent) as? com.google.android.material.textfield.TextInputLayout)?.hint.toString() == "按键上方符号对齐" }.setText("靠右", false)
                fields.first { ((it.parent?.parent) as? com.google.android.material.textfield.TextInputLayout)?.hint.toString() == "按键下方注解对齐" }.setText("靠左", false)
                views(dialogRoot).first { it.id == android.R.id.button1 && it.isShown }.performClick()
            }
            check(savedOptions?.asciiKeyboard == pages.getValue("en")) { "Saving alignment removed the internal English association: saved=$savedOptions" }
            check(savedOptions?.symbolAlignment == "right" && savedOptions?.hintAlignment == "left")
            CustomKeyboards.mutate(root) { it.options = savedOptions!! }
            main { activity!!.finish() }; activity = null
            pass("whole-set dimensions dialog renders all four controls")
            pass("actual alignment editor saves independent top/bottom choices without clearing English association")
            stage = "start IME"
            ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue(), layoutMode = CustomKeyboards.MODE_CUSTOM, customLayoutId = root)
            await("explicit custom layout was replaced by a queued editor refresh") { CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM && ThemeManager.activeTheme.isCustom }
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("IME unavailable; unlock phone") { KeyboardWindow.instance != null }
            val ime = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            val rime = member(ime, "rime") as RimeSession
            await("IME input not visible") { (member(ime, "inputView") as? InputView)?.isShown == true && ime.currentInputConnection != null }
            fun active() = CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
            suspend fun nav(id: String) { main { KeyboardWindow.instance!!.switchKeyboardFromAction(id) }; await("navigate $id") { active() == id }; SystemClock.sleep(200) }
            nav(pages.getValue("zh_18"))
            val language = CustomKeyboards.get(root)!!.toolbar.first { it.icon.contains("tb_lang") }.action
            for(role in listOf("zh_26", "zh_18")) {
                val origin = pages.getValue(role); nav(origin)
                main { (member(ime, "inputView") as InputView).performToolbarAction(language) }
                await("Chinese layout did not enter its English sibling") { active() == pages.getValue("en") }
                main { (member(ime, "inputView") as InputView).performToolbarAction(language) }
                await("English did not return to selected Chinese layout") { active() == origin }
            }
            pass("toolbar Chinese-English-Chinese preserves both 26-key and 18-key origins after symbol edits")
            val originSchema = rime.runOnReady { selectedSchemaId() }
            val schemas = rime.runOnReady { selectedSchemata().map { it.id } }
            val schemaA = schemas.firstOrNull { it != originSchema } ?: originSchema
            main {
                KeyboardModeBinding.save(true, KeyboardModeBinding(schemaId = schemaA, layoutId = pages.getValue("zh_14"), layoutMode = CustomKeyboards.MODE_CUSTOM))
                KeyboardModeBinding.save(false, KeyboardModeBinding(schemaId = originSchema, layoutId = pages.getValue("zh_9"), layoutMode = CustomKeyboards.MODE_CUSTOM))
            }
            stage = "one hand binding"
            sendStatus(0, Bundle().apply { putString("binding_before_enter", "mode=${CustomKeyboards.mode}; visible=${active()}; oneHand=${prefs.keyboard.oneHandMode.getValue()}; floating=${prefs.keyboard.floatingKeyboard.getValue()}; previous=${member(member(ime, "modeBindingController")!!, "previous")}") })
            main { ime.toggleOneHandMode() }
            await("one hand layout not applied") { active() == pages.getValue("zh_14") && (member(ime, "inputView") as? InputView)?.isOneHandMode == true }
            check(rime.runOnReady { selectedSchemaId() } == schemaA)
            val controller = member(ime, "modeBindingController")!!
            sendStatus(0, Bundle().apply { putString("binding_before_exit", "previous=${member(controller, "previous")}; visible=${active()}") })
            main { ime.toggleOneHandMode() }
            try { await("one hand did not restore 18-key") { active() == pages.getValue("zh_18") && (member(ime, "inputView") as? InputView)?.isOneHandMode == false } }
            catch(error: Exception) {
                result.putString("binding_failure_state", "expected=${pages.getValue("zh_18")}; visible=${active()}; stored=${CustomKeyboards.activeId}; oneHand=${prefs.keyboard.oneHandMode.getValue()}; shown=${(member(ime, "inputView") as? InputView)?.isOneHandMode}; previous=${member(controller, "previous")}; ascii=${rime.run { statusCached }.isAsciiMode}")
                throw error
            }
            check(rime.runOnReady { selectedSchemaId() } == originSchema)
            pass("one-hand selects bound schema/14-key and restores exact schema/18-key on exit; enabled schemas=${schemas.size}")
            stage = "floating and cross-mode restore"
            main { ime.toggleFloatingKeyboard() }
            await("floating binding missing") { active() == pages.getValue("zh_9") && (member(ime, "inputView") as? InputView)?.isFloatingKeyboardEnabled == true }
            main { ime.toggleOneHandMode() }
            await("cross-mode one hand binding missing") { active() == pages.getValue("zh_14") && (member(ime, "inputView") as? InputView)?.isOneHandMode == true }
            main { ime.toggleOneHandMode() }
            await("cross-mode original lost") { active() == pages.getValue("zh_18") && (member(ime, "inputView") as? InputView)?.isOneHandMode == false }
            check(rime.runOnReady { selectedSchemaId() } == originSchema)
            pass("floating→one-hand→normal restores original 18-key, not intermediate 9-key")
            stage = "rapid toggles"
            main { repeat(4) { ime.toggleOneHandMode() } }
            await("rapid toggle final mode wrong") { active() == pages.getValue("zh_18") && (member(ime, "inputView") as? InputView)?.isOneHandMode == false && !prefs.keyboard.oneHandMode.sharedPreferences.contains("keyboard_mode_return") }
            SystemClock.sleep(700)
            check(active() == pages.getValue("zh_18"))
            check(!prefs.keyboard.oneHandMode.sharedPreferences.contains("keyboard_mode_return"))
            pass("four rapid toggles settle at original page and clear persistent return point")
            stage = "menu redraw"
            nav(root)
            main {
                (member(ime, "inputView") as InputView).performToolbarAction(BindingActions.command("__mie_set_layouts"))
            }
            val inputView = member(ime, "inputView") as InputView
            val manager = member(inputView, "windowManager") as BoardWindowManager
            await("layout grid missing") { views(manager.view).filterIsInstance<TextView>().any { it.text.toString() == "26键 主键盘" } }
            check(views(manager.view).filterIsInstance<TextView>().none { it.text.toString().contains("数学符号") })
            shot("layout-grid")
            main { check(views(manager.view).filterIsInstance<TextView>().first { it.text.toString() == "26键 主键盘" }.let { it.height >= it.textSize * 1.4f }) { "Layout choice label is clipped" } }
            main { views(manager.view).filterIsInstance<TextView>().first { it.text.toString() == "返回" }.performClick() }
            await("layout picker return changed current layout") { active() == root && manager.isAttached(KeyboardWindow.instance!!) }
            pass("layout toolbar opens in-keyboard two-column selector; return preserves current page")
            shot("frost-before")
            repeat(8) {
                main { (member(ime, "inputView") as InputView).showKeyboardMenu() }
                SystemClock.sleep(150)
                if(it == 0) shot("frost-menu")
                main { (member(member(ime, "inputView")!!, "windowManager") as BoardWindowManager).attachWindow(KeyboardWindow) }
                SystemClock.sleep(180)
            }
            shot("frost-after")
            main {
                val window = KeyboardWindow.instance!!
                val view = member(window, "currentKeyboardView") as ViewGroup
                val keys = views(view).filterIsInstance<com.osfans.trime.ime.keyboard.KeyView>()
                check(keys.isNotEmpty())
                check(keys.all { !it.isPressed && it.scaleX == 1f && it.scaleY == 1f })
                val a = keys.first { (member(it, "key") as com.osfans.trime.ime.keyboard.Key).hint == "左" }
                val anchor = a.javaClass.getDeclaredMethod("annotationCenterX", Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
                check((anchor.invoke(a, 10f, true) as Float) > (anchor.invoke(a, 10f, false) as Float)) { "Bottom hint followed top symbol alignment" }
            }
            pass("eight frost menu roundtrips complete; no held key/scale remains; before/menu/after screenshots captured")
            stage = "pink legacy empty English link"
            CustomKeyboards.resetBuiltinSet("custom_pink")
            val pink = KeyboardSetOperations.newSet("樱花粉符号验证", "custom_pink", KeyboardSetOperations.layoutNames.keys.toList())
            val pinkPages = KeyboardSetOperations.ordered(pink).associateBy { CustomKeyboards.pageRole(it) }
            CustomKeyboards.mutate(pink) { kb ->
                kb.options = kb.options.copy(asciiKeyboard = "", symbolAlignment = "right", hintAlignment = "left")
                kb.rows.forEach { row -> row.keys.replaceAll { key -> if(key.identity == "char:a") key.copy(appearance = key.appearance.copy(labelSymbol = "@", hint = "全选")) else key } }
            }
            check(CustomKeyboards.get(pink)!!.rows.flatMap { it.keys }.first { it.bindings["click"] == "Return" }.appearance.keyBackColor == "#F4AEB8")
            ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue(), layoutMode = CustomKeyboards.MODE_CUSTOM, customLayoutId = pink)
            await("pink theme not applied") { CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM && ThemeManager.activeTheme.isCustom }
            nav(pink)
            val pinkLanguage = CustomKeyboards.get(pink)!!.toolbar.first { it.icon.contains("tb_lang") }.action
            main { (member(ime, "inputView") as InputView).performToolbarAction(pinkLanguage) }
            await("legacy empty English association not recovered") { active() == pinkPages.getValue("en") }
            main { (member(ime, "inputView") as InputView).performToolbarAction(pinkLanguage) }
            await("pink English return failed") { active() == pink }
            shot("pink-symbols")
            pass("pink symbol editing retains dark Enter; old blank English link automatically recovers; language roundtrip succeeds")
            stage = "landscape automatic floating"
            nav(pinkPages.getValue("zh_18"))
            main { prefs.keyboard.floatingKeyboardAutoLandscape.setValue(true); activity!!.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await("landscape did not activate bound floating layout") {
                ime.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                    active() == pages.getValue("zh_9") && (member(ime, "inputView") as? InputView)?.isFloatingKeyboardEnabled == true
            }
            main { activity!!.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("portrait did not restore pink 18-key") {
                ime.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT &&
                    active() == pinkPages.getValue("zh_18") && (member(ime, "inputView") as? InputView)?.isFloatingKeyboardEnabled == false
            }
            main { prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false) }
            pass("portrait pink 18-key→landscape bound frost 9-key→portrait restores pink 18-key across families")
            result.putString("result", "PASS")
        } catch(e: Throwable) {
            result.putString("result", "FAIL"); result.putString("stage", stage); result.putString("error", android.util.Log.getStackTraceString(e))
        } finally {
            main { activity?.finish() }; CustomKeyboards.flush()
        }
        finish(if(result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
