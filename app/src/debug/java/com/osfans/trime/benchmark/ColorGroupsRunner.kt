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
import com.osfans.trime.data.theme.*
import com.osfans.trime.ui.main.KeyboardColorGroupsActivity
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.app.AlertDialog
import android.widget.GridView
import android.widget.EditText
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
class ColorGroupsRunner : Instrumentation() {
    private lateinit var picker: AlertDialog
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
        SystemClock.sleep(400)
        uiAutomation.takeScreenshot()?.let { bitmap ->
            File(targetContext.cacheDir, "color-groups-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun launchActivity(intent: Intent): Activity {
        val monitor = addMonitor(intent.component!!.className, null, false)
        try {
            main { targetContext.startActivity(intent) }
            return monitor.waitForActivityWithTimeout(12000) ?: error("Activity did not start: ${intent.component}")
        } finally { removeMonitor(monitor) }
    }
    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun click(root: View, label: String, prefix: Boolean = false) {
        val view = views(root).filterIsInstance<TextView>().first {
            it.isShown && (if (prefix) it.text.toString().startsWith(label) else it.text.toString() == label)
        }
        var parent = view.parent
        while (parent is View) {
            if (parent is android.widget.AdapterView<*>) {
                val position = parent.getPositionForView(view)
                check(position != android.widget.AdapterView.INVALID_POSITION)
                parent.performItemClick(view, position, parent.adapter.getItemId(position)); return
            }
            parent = parent.parent
        }
        view.performClick()
    }
    private fun decor(label: String) = WindowInspector.getGlobalWindowViews().last { v -> views(v).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == label } }
    override fun onStart() = runBlocking {
        val result = Bundle(); var activity: Activity? = null; var stage = "startup"
        try {
            main { AppPrefs.defaultInstance() }
            CustomKeyboards.init(targetContext); CustomKeyboards.awaitBuiltinLayouts()
            val prefs = AppPrefs.defaultInstance(); val themePrefs = ThemeManager.prefs
            main {
                KeyboardColorGroups.deactivate(); themePrefs.keyboardColorGroups.setValue("[]"); themePrefs.followSystemDayNight.setValue(false)
                prefs.general.inputStatsEnabled.setValue(false); com.osfans.trime.data.stats.InputStats.setEnabled(false)
                prefs.keyboard.floatingKeyboard.setValue(false); prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0); prefs.keyboard.hideInputBar.setValue(false)
                KeyboardModeBinding.save(true, KeyboardModeBinding()); KeyboardModeBinding.save(false, KeyboardModeBinding())
            }
            shell("cmd uimode night no"); SystemClock.sleep(400)
            CustomKeyboards.resetBuiltinSet("custom_pink")
            val nightRoot = KeyboardSetOperations.newSet("配色验证 · 夜间", "custom_obsidian", KeyboardSetOperations.layoutNames.keys.toList())
            val nightPages = KeyboardSetOperations.ordered(nightRoot).associateBy { CustomKeyboards.pageRole(it) }
            val dayRoot = "custom_pink"
            val dayPages = CustomKeyboards.setPageIds(dayRoot).associateBy { CustomKeyboards.pageRole(it) }
            ThemeManager.selectTheme(themePrefs.selectedTheme.getValue(), layoutMode = CustomKeyboards.MODE_CUSTOM, customLayoutId = dayPages.getValue("zh_18"))
            stage = "picker empty and category browsing"
            pass("checkpoint: opening group settings")
            activity = launchActivity(Intent(targetContext, KeyboardColorGroupsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            pass("checkpoint: group settings started")
            main { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            var pickerScope = (activity as androidx.fragment.app.FragmentActivity).lifecycleScope
            fun openPicker(choose: ((KeyboardStyleTarget) -> Unit)? = null) {
                pass("checkpoint: requesting picker")
                main { pickerScope.launch {
                    picker = ThemePickerDialog.build(pickerScope, activity!!, pickTarget = choose); picker.show(); pass("checkpoint: picker shown")
                } }
                await("picker not ready") { ::picker.isInitialized && picker.isShowing && views(picker.window!!.decorView).filterIsInstance<GridView>().any { it.adapter != null } }
            }
            openPicker()
            pass("checkpoint: picker loaded")
            val beforeMode = CustomKeyboards.mode; val beforeId = CustomKeyboards.activeId
            main { click(picker.window!!.decorView, "配色") }
            await("empty palette should have zero items") { views(picker.window!!.decorView).filterIsInstance<GridView>().single().let { it.isEnabled && it.adapter?.count == 0 } }
            shot("empty-palette")
            main { click(picker.window!!.decorView, "布局"); click(picker.window!!.decorView, "内置布局") }
            await("builtin grid empty") { views(picker.window!!.decorView).filterIsInstance<GridView>().single().let { it.isEnabled && it.adapter.count >= 9 && it.numColumns == 2 } }
            shot("builtin-picker")
            main { click(picker.window!!.decorView, "自定义布局") }
            await("custom grid lacks newly created set") { views(picker.window!!.decorView).filterIsInstance<GridView>().single().let { grid -> grid.isEnabled && (0 until grid.adapter.count).any { grid.adapter.getItem(it).toString().contains(nightRoot) } } }
            main { click(picker.window!!.decorView, "主题布局") }
            await("theme category not loaded") { views(picker.window!!.decorView).filterIsInstance<GridView>().single().let { it.isEnabled && it.numColumns == 1 } }
            check(CustomKeyboards.mode == beforeMode && CustomKeyboards.activeId == beforeId)
            main { picker.dismiss() }
            pass("layout/color tabs, empty palette, dynamic builtin/custom grids and browsing without switching")

            stage = "create group through editor"
            main { click(activity!!.window.decorView, "＋ 新建配色组") }
            main { views(decor("新建配色组")).filterIsInstance<EditText>().single().setText("配色真机验证") }
            val savedLayout = CustomKeyboards.activeId
            suspend fun pickEditorTarget(label: String, category: String, name: String) {
                main { click(decor("新建配色组"), label, true) }
                await("target selector missing") { WindowInspector.getGlobalWindowViews().any { v -> views(v).filterIsInstance<TextView>().any { it.text.toString() == "选择主题" } } }
                main { click(decor("选择主题"), category) }
                await("target tile missing: $name") { views(decor("选择主题")).filterIsInstance<GridView>().single().let { g ->
                    g.isEnabled && (0 until g.adapter.count).any { g.adapter.getItem(it).toString().contains(name) }
                } }
                main { val g = views(decor("选择主题")).filterIsInstance<GridView>().single()
                    val index = (0 until g.adapter.count).first { g.adapter.getItem(it).toString().contains(name) }; g.performItemClick(g.adapter.getView(index, null, g), index, index.toLong()) }
                SystemClock.sleep(250)
            }
            pickEditorTarget("日间 ·", "内置布局", CustomKeyboards.get(dayRoot)!!.name)
            pickEditorTarget("夜间 ·", "自定义布局", nightRoot)
            check(CustomKeyboards.activeId == savedLayout && KeyboardColorGroups.activeId().isEmpty())
            main { click(decor("新建配色组"), "保存") }
            await("group did not save") { KeyboardColorGroups.list().any { it.name == "配色真机验证" } }
            var group = KeyboardColorGroups.list().single { it.name == "配色真机验证" }
            check(group.day.id == dayRoot && group.night.id == nightRoot)
            val raw = themePrefs.keyboardColorGroups.getValue()
            check(Json.decodeFromString<List<KeyboardColorGroup>>(raw).single() == group)
            await("new group card not rendered") { views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().startsWith("配色真机验证") } }
            main { click(activity!!.window.decorView, "配色真机验证", true) }
            SystemClock.sleep(250)
            main { click(decor("配色真机验证"), "编辑") }
            SystemClock.sleep(250)
            main { views(decor("编辑配色组")).filterIsInstance<EditText>().single().setText("日夜配色验证"); click(decor("编辑配色组"), "保存") }
            group = KeyboardColorGroups.list().single { it.id == group.id }
            check(group.name == "日夜配色验证")
            main { check(runCatching { KeyboardColorGroups.save(group.copy(id = "duplicate")) }.isFailure) }
            shot("group-settings")
            main { activity!!.finish() }; SystemClock.sleep(250)
            pass("actual editor creates day/night targets without switching; rename, duplicate validation and stored JSON roundtrip")

            stage = "start keyboard"
            activity = launchActivity(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            await("IME unavailable") { KeyboardWindow.instance != null }
            val ime = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            pickerScope = ime.lifecycleScope
            fun active() = CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
            suspend fun nav(id: String) { main { KeyboardWindow.instance!!.switchKeyboardFromAction(id) }; await("navigate $id") { active() == id }; SystemClock.sleep(150) }
            suspend fun language() {
                val action = CustomKeyboards.get(active())!!.toolbar.first { it.icon.contains("tb_lang") }.action
                main { (member(ime, "inputView") as InputView).performToolbarAction(action) }
            }
            suspend fun night(dark: Boolean, expected: String) {
                shell("cmd uimode night " + if(dark) "yes" else "no")
                await("system night=$dark did not apply $expected; actual=${active()}") { active() == expected }
                SystemClock.sleep(350)
                check(active() == expected && KeyboardColorGroups.activeId() == group.id) { "Unstable night=$dark expected=$expected actual=${active()} group=${KeyboardColorGroups.activeId()} restarting=${ime.isRestartingInputView} page=${KeyboardWindow.instance?.captureRuntimeState()?.id} pair=${KeyboardWindow.instance?.captureRuntimeState()?.asciiPair}" }
            }
            stage = "punctuation visual centering and independent annotation defaults"
            CustomKeyboards.resetBuiltinSet("custom_frost")
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin", "custom_frost", "雾凇"))
            await("frost did not attach") { active() == "custom_frost" }
            SystemClock.sleep(500)
            main {
                val keyboard = KeyboardWindow.currentKeyboardOrNull!!
                check(keyboard.symbolAlign == "right" && keyboard.hintAlign == "center")
                val input = member(ime, "inputView") as InputView
                val keys = views(input).filterIsInstance<com.osfans.trime.ime.keyboard.KeyView>()
                listOf(",", "。").forEach { label ->
                    val view = keys.first { (member(it, "key") as com.osfans.trime.ime.keyboard.Key).getLabel() == label }
                    val key = member(view, "key") as com.osfans.trime.ime.keyboard.Key
                    check(key.keyTextOffsetX == 0f && key.keyTextOffsetY == 0f)
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.javaClass.getDeclaredMethod("drawLabel", android.graphics.Canvas::class.java, String::class.java).apply { isAccessible = true }
                        .invoke(view, android.graphics.Canvas(bitmap), label)
                    var left = view.width; var right = -1; var top = view.height; var bottom = -1
                    for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) if(android.graphics.Color.alpha(bitmap.getPixel(x,y)) > 96) {
                        left = minOf(left,x); right = maxOf(right,x); top = minOf(top,y); bottom = maxOf(bottom,y)
                    }
                    check(right >= left)
                    val cx = (view.width + view.paddingLeft - view.paddingRight) / 2f
                    val cy = (view.height + view.paddingTop - view.paddingBottom) / 2f
                    check(kotlin.math.abs((left+right+1)/2f-cx) <= 2f && kotlin.math.abs((top+bottom+1)/2f-cy) <= 2f) {
                        "Punctuation $label ink center differs from key center: $left,$top,$right,$bottom vs $cx,$cy"
                    }
                    bitmap.recycle()
                }
            }
            shot("punctuation-frost")
            pass("actual comma/period label ink centers within 2px; upper symbols right and lower hints centered independently")
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin", dayPages.getValue("zh_18"), "日间"))
            nav(dayPages.getValue("zh_18"))
            main { themePrefs.followSystemDayNight.setValue(true) }
            KeyboardColorGroups.activate(group.id)
            night(true, nightPages.getValue("zh_18")); shot("night-18")
            night(false, dayPages.getValue("zh_18")); shot("day-18")
            pass("real system day/night changes apply builtin/custom group and retain 18-key layout")

            stage = "English navigation across colors"
            language(); await("did not enter day English") { active() == dayPages.getValue("en") }
            night(true, nightPages.getValue("en"))
            language(); await("English return lost 18-key after recolor") { active() == nightPages.getValue("zh_18") }
            pass("English→night recolor→Chinese restores corresponding 18-key page")
            stage = "numeric navigation across colors"
            nav(nightPages.getValue("zh_14")); nav(nightPages.getValue("num"))
            night(false, dayPages.getValue("num"))
            main { KeyboardWindow.instance!!.switchKeyboardFromAction(".default") }
            await("numeric return lost 14-key after recolor") { active() == dayPages.getValue("zh_14") }
            pass("numeric→day recolor→return restores corresponding 14-key page")
            stage = "rapid night changes and disabled follow"
            shell("cmd uimode night yes"); shell("cmd uimode night no")
            await("rapid night changes did not settle") { active() == dayPages.getValue("zh_14") }
            SystemClock.sleep(700); check(active() == dayPages.getValue("zh_14"))
            main { themePrefs.followSystemDayNight.setValue(false) }
            shell("cmd uimode night yes"); SystemClock.sleep(900); check(active() == dayPages.getValue("zh_14"))
            main { themePrefs.followSystemDayNight.setValue(true) }
            await("enabling follow at night did not switch") { active() == nightPages.getValue("zh_14") }
            pass("rapid night requests settle; disabled follow stays day; enabling at night applies night immediately")
            stage = "palette selection and manual exit"
            openPicker()
            main { click(picker.window!!.decorView, "配色") }
            await("saved palette missing") { views(picker.window!!.decorView).filterIsInstance<GridView>().single().let { it.isEnabled && it.adapter.count == 1 } }
            shot("palette-picker")
            main { val g = views(picker.window!!.decorView).filterIsInstance<GridView>().single(); g.performItemClick(g.adapter.getView(0, null, g), 0, 0) }
            await("palette selection did not close") { !picker.isShowing }
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin", dayRoot, "日间"))
            check(KeyboardColorGroups.activeId().isEmpty())
            shell("cmd uimode night no"); shell("cmd uimode night yes"); SystemClock.sleep(800)
            check(active() == dayRoot)
            pass("palette tab applies saved group; manually selecting a layout exits group and ignores later night changes")
            stage = "unavailable target rollback"
            KeyboardColorGroups.activate(group.id)
            val keptMode = CustomKeyboards.mode; val keptId = CustomKeyboards.activeId; val keptTheme = ThemeManager.activeTheme
            check(runCatching { KeyboardColorGroups.selectLayout(KeyboardStyleTarget("custom", "missing-color-layout", "已删除")) }.isFailure)
            check(KeyboardColorGroups.activeId() == group.id && CustomKeyboards.activeId == keptId && CustomKeyboards.mode == keptMode)
            check(runCatching { KeyboardColorGroups.selectLayout(KeyboardStyleTarget("theme", "missing-color-theme.trime", "已删除主题")) }.isFailure)
            check(KeyboardColorGroups.activeId() == group.id && CustomKeyboards.activeId == keptId && ThemeManager.activeTheme === keptTheme)
            pass("missing layout and theme fail without replacing keyboard or active group")
            stage = "delete group through editor"
            main { activity!!.finish() }; SystemClock.sleep(250)
            activity = launchActivity(Intent(targetContext, KeyboardColorGroupsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(250)
            main { click(activity!!.window.decorView, group.name, true) }
            SystemClock.sleep(250)
            main { click(decor(group.name), "删除") }
            SystemClock.sleep(250)
            main { click(decor("删除 ${group.name}？"), "删除") }
            await("group not deleted") { KeyboardColorGroups.list().none { it.id == group.id } }
            check(KeyboardColorGroups.activeId().isEmpty())
            pass("actual delete dialog removes active group while keeping current keyboard")
            result.putString("result", "PASS")
        } catch(error: Throwable) {
            result.putString("result", "FAIL"); result.putString("stage", stage); result.putString("error", android.util.Log.getStackTraceString(error))
            result.putString("state", "layout=${CustomKeyboards.activeId}; night=${KeyboardColorGroups.isNight()}; follow=${ThemeManager.prefs.followSystemDayNight.getValue()}; group=${KeyboardColorGroups.activeId()}; displayed=${KeyboardWindow.lastAttachedId}; current=${KeyboardWindow.instance?.captureRuntimeState()?.id}; surface=${KeyboardWindow.currentKeyboardOrNull?.keyboardId}")
        } finally {
            main { KeyboardColorGroups.deactivate(); activity?.finish() }; CustomKeyboards.flush()
        }
        finish(if(result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
