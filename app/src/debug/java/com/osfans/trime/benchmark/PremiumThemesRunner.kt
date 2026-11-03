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
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.core.data.DataStorageMode
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
class PremiumThemesRunner : Instrumentation() {
    private fun cancelInjectedTouch() {
        val now=SystemClock.uptimeMillis()
        val event=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,100f,100f,0)
        event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { uiAutomation.injectInputEvent(event,true) } finally { event.recycle() }
    }
    private lateinit var picker: AlertDialog
    private var checks325 = false
    private var exportOnly = false
    private var candidatesOnly = false
    private var featuresOnly = false
    private var interactionsOnly = false
    override fun onCreate(args: Bundle?) { checks325=args?.getString("checks325")=="true"; exportOnly=args?.getString("exportOnly")=="true"; candidatesOnly = args?.getString("candidatesOnly") == "true"; featuresOnly = args?.getString("featuresOnly") == "true"; interactionsOnly=args?.getString("interactionsOnly")=="true"; super.onCreate(args); start() }
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
            File(targetContext.cacheDir, "premium-themes-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
    private fun tap(view: View) {
        val xy = IntArray(2)
        main { check(view.isShown && view.width > 0 && view.height > 0); view.getLocationOnScreen(xy) }
        val x = xy[0] + view.width / 2f; val y = xy[1] + view.height / 2f
        val now = SystemClock.uptimeMillis()
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        try {
            check(uiAutomation.injectInputEvent(down, true)); SystemClock.sleep(24)
            val up = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, x, y, 0)
            try { check(uiAutomation.injectInputEvent(up, true)) } finally { up.recycle() }
        } finally { down.recycle() }
    }
    override fun onStart() = runBlocking {
        val result = Bundle(); var activity: Activity? = null; var stage = "startup"; var session: RimeSession? = null
        var fixture: File? = null; var restoreStorage: (() -> Unit)? = null
        val roots = listOf("silver", "titanium", "cloudsea", "inkweave", "celadon").map { "custom_" + it }
        val suffixes = listOf("", "_9", "_14", "_18", "_7_1", "_7_2", "_en", "_num", "_symbols", "_symbols_cn", "_symbols_math", "_symbols_more", "_themes", "_layouts", "_hub")
        fun active() = CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
        try {
            cancelInjectedTouch()
            main { AppPrefs.defaultInstance() }
            CustomKeyboards.init(targetContext); CustomKeyboards.awaitBuiltinLayouts()
            CustomKeyboards.flush()
            val prefs = AppPrefs.defaultInstance()
            val oldMode = prefs.profile.dataStorageMode.getValue(); val oldPath = prefs.profile.directExternalPath.getValue(); val oldReady = prefs.profile.directExternalReady.getValue()
            restoreStorage = { RimeDaemon.changeStorage {
                prefs.profile.dataStorageMode.setValue(oldMode); prefs.profile.directExternalPath.setValue(oldPath); prefs.profile.directExternalReady.setValue(oldReady)
            } }
            fixture = java.nio.file.Files.createTempDirectory(targetContext.getExternalFilesDir(null)!!.toPath(), "premium-themes-fixture-").toFile()
            fun write(name: String, content: String) = File(fixture!!,name).writeText(content.trimIndent())
            write("default.yaml", "config_version: '1'\nschema_list: [{schema: premium_probe}]\nmenu: {page_size: 3}\n")
            write("default.custom.yaml", "patch: {}\n")
            write("premium_probe.schema.yaml", """
                schema: {schema_id: premium_probe, name: Premium probe, version: '1'}
                switches: [{name: ascii_mode, reset: 0, states: [中文, 西文]}]
                engine:
                  processors: [ascii_composer, speller, selector, navigator, express_editor]
                  segmentors: [abc_segmentor, fallback_segmentor]
                  translators: [table_translator]
                speller: {alphabet: abcdefghijklmnopqrstuvwxyz}
                translator: {dictionary: premium_probe, enable_user_dict: false, enable_sentence: false, enable_completion: false}
                menu: {page_size: 3}
            """)
            write("premium_probe.dict.yaml", "---\nname: premium_probe\nversion: '1'\nsort: original\n...\n你好\tni\t100\n霁月\tni\t90\n清风\tni\t80\n青釉\tni\t70\n清泉\tqq\t100\n")
            RimeDaemon.changeStorage {
                prefs.profile.dataStorageMode.setValue(DataStorageMode.DIRECT_EXTERNAL)
                prefs.profile.directExternalPath.setValue(fixture!!.path); prefs.profile.directExternalReady.setValue(true)
            }
            CustomKeyboards.init(targetContext); CustomKeyboards.awaitBuiltinLayouts()
            stage = "material installation and repair"
            val assetRoot = CustomKeyAssets.root(targetContext)
            val expected = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(targetContext.resources.openRawResource(com.osfans.trime.R.raw.mie_premium_materials)).use { zip ->
                while(true) { val e = zip.nextEntry ?: break; expected[e.name] = zip.readBytes(); zip.closeEntry() }
            }
            check(expected.size == 108)
            fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toList()
            expected.forEach { (name, bytes) ->
                val file = File(assetRoot, name); check(file.isFile) { "Missing installed asset: $name" }
                check(digest(file.readBytes()) == digest(bytes)) { "Installed material differs: $name" }
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, options)
                check(options.outWidth > 0 && options.outHeight > 0)
            }
            check(!BuiltinPremiumAssets.install(targetContext))
            val repairName = expected.keys.first { it.endsWith("/tb_theme.png") }
            val repair = File(assetRoot, repairName); check(repair.delete())
            check(BuiltinPremiumAssets.install(targetContext)); check(digest(repair.readBytes()) == digest(expected.getValue(repairName)))
            pass("108 installed PNGs match APK, Android decodes headers, unchanged pack skips, missing asset repairs")
            stage = "stock roots and test fixture"
            val stock = KeyboardLayoutCatalog.roots(targetContext, KeyboardLayoutCatalog.BUILTIN)
            check(roots.all { root -> stock.any { it.id == root } })
            val templates = CustomKeyboards.builtinTemplates()
            CustomKeyboards.editSets { pages, profiles ->
                roots.forEach { root ->
                    check(suffixes.all { root+it in templates })
                    KeyboardSets.family(root, templates).forEach { pages[it] = templates.getValue(it) }
                    KeyboardSetOperations.adoptBuiltinData(root, pages, profiles, templates)
                }
            }
            if(exportOnly) {
                CustomKeyboards.restoreBuiltins(true)
                val allRoots=KeyboardLayoutCatalog.roots(targetContext,KeyboardLayoutCatalog.BUILTIN)
                CustomKeyboards.editSets { pages,profiles -> allRoots.forEach { KeyboardSetOperations.adoptBuiltinData(it.id,pages,profiles,templates) } }
                val output=File(targetContext.cacheDir,"builtin-share").apply { mkdirs() }
                val manifest=mutableListOf<String>()
                for(entry in allRoots) {
                    val archive=KeyboardSetArchive.create(targetContext,listOf(entry.id))
                    val target=File(output,entry.id+".mie-keyboard.zip"); archive.copyTo(target,true); archive.delete()
                    manifest+="${entry.id}\t${entry.name}\t${CustomKeyboards.familyIds(listOf(entry.id)).size}\t${target.name}"
                }
                val archive=KeyboardSetArchive.create(targetContext,allRoots.map { it.id })
                archive.copyTo(File(output,"all-builtin.mie-keyboard.zip"),true); archive.delete()
                File(output,"manifest.tsv").writeText(manifest.joinToString("\n"))
                result.putString("export","Created ${allRoots.size} stock theme archives with production exporter; import regression pending")
            } else {
            main {
                KeyboardColorGroups.deactivate(); ThemeManager.prefs.followSystemDayNight.setValue(false)
                prefs.general.inputStatsEnabled.setValue(false); com.osfans.trime.data.stats.InputStats.setEnabled(false)
                prefs.keyboard.floatingKeyboard.setValue(false); prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0); prefs.keyboard.hideInputBar.setValue(false)
                prefs.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.DISABLED)
                prefs.candidates.floatingBall.setValue(false)
                KeyboardModeBinding.save(true, KeyboardModeBinding()); KeyboardModeBinding.save(false, KeyboardModeBinding())
            }
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",roots.first(),"材质测试"))
            activity = launchActivity(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            await("IME unavailable; unlock phone") { KeyboardWindow.instance != null }
            val ime = member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
            val rime = member(ime,"rime") as RimeSession; session = rime
            rime.runOnReady {
                // Explicit deployment affects only the app-owned temporary root.
                deploy(); check(selectSchema("premium_probe")); setRuntimeOption("ascii_mode",false)
            }
            fun currentIme() = member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
            fun input() = member(currentIme(),"inputView") as InputView
            fun keyViews() = views(input()).filterIsInstance<com.osfans.trime.ime.keyboard.KeyView>().filter { it.isShown }
            suspend fun nav(id: String) {
                main { KeyboardWindow.instance!!.switchKeyboardFromAction(id) }
                await("navigation failed: $id; actual=${active()}") { active() == id && KeyboardWindow.currentKeyboardOrNull?.keyboardId == id }
                SystemClock.sleep(90)
            }
            fun loaded() = keyViews().isNotEmpty() && keyViews().all { view ->
                val key = member(view,"key") as com.osfans.trime.ime.keyboard.Key
                val drawable = key.customBgDrawable
                drawable !is com.osfans.trime.data.theme.AssetBitmapDrawable || member(drawable,"image") != null
            }
            suspend fun language(root: String, origin: String) {
                main { input().performToolbarAction("Mode_switch") }
                await("English lost $origin") { active() == root+"_en" && rime.run { statusCached }.isAsciiMode }
                main { input().performToolbarAction("Mode_switch") }
                await("Chinese return lost $origin; actual=${active()}") { active() == origin && !rime.run { statusCached }.isAsciiMode }
            }
            suspend fun candidates(root: String) {
                nav(root); main { prefs.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.ALWAYS_SHOW) }
                rime.runOnReady { clearComposition(); setRuntimeOption("ascii_mode",false); check(simulateKeySequence("ni")) }
                await("candidate composition missing") { rime.run { statusCached }.isComposing }
                var preeditState=""
                try { await("floating preedit readiness") {
                    val candidateView = member(currentIme(),"candidatesView")!!
                    val preedit = member(member(candidateView,"preeditUi")!!,"preedit") as TextView
                    preeditState="shown=${preedit.isShown}, attached=${preedit.isAttachedToWindow}, text=${preedit.text.toString().map { it.code.toString(16) }}, windows="+
                        WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<com.osfans.trime.ime.composition.PreeditTextView>().map { "${it.isShown}/${it.text.toString().map { ch->ch.code.toString(16) }}" }
                    // The engine may append its U+2038 caret marker after moving the
                    // floating window. Assert exact engine output, not raw input bytes.
                    val expected=rime.run { compositionCached.preedit }.orEmpty()
                    preedit.isShown && expected.startsWith("ni") && preedit.text.toString()==expected
                } } catch(t:Throwable) { error("Floating preedit: $preeditState; raw=${rime.runOnReady { getRawInput() }}; $t") }
                main {
                    val preedit = member(member(member(currentIme(),"candidatesView")!!,"preeditUi")!!,"preedit") as TextView
                    val colors = CustomKeyboards.get(root)!!.floatingCandidates!!.colors
                    check(preedit.currentTextColor == android.graphics.Color.parseColor(colors.getValue("text")))
                    val text = preedit.text as android.text.Spanned
                    val spans = text.getSpans(0,text.length,android.text.style.ForegroundColorSpan::class.java)
                    check(spans.isNotEmpty() && spans.all { it.foregroundColor == android.graphics.Color.parseColor(colors.getValue("highlight.text")) })
                }
                SystemClock.sleep(450); shot(root+"-candidates")
                rime.runOnReady { clearComposition() }
                main { prefs.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.DISABLED) }
            }
            for((familyIndex,root) in (if(featuresOnly) emptyList() else roots).withIndex()) {
                stage = "$root main render"
                KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",root,root))
                await("theme did not attach: $root") { active() == root }
                await("material drawable did not decode: $root") { loaded() }
                main {
                    val keys = keyViews().associateBy { (member(it,"key") as com.osfans.trime.ime.keyboard.Key).getLabel().uppercase() }
                    fun left(label: String) = IntArray(2).also { keys.getValue(label).getLocationOnScreen(it) }[0]
                    val first = left("Q") + left("P") + keys.getValue("P").width
                    val second = left("A") + left("L") + keys.getValue("L").width
                    check(kotlin.math.abs(first-second) <= 4) { "Shorter second row is not centered" }
                    val kb = CustomKeyboards.get(root)!!
                    check(kb.keyboardHeightDp == 251 && kb.rowSpacing == 8)
                }
                shot(root+"-26")
                if(candidatesOnly) {
                    stage = "$root candidate contrast follow-up"
                    candidates(root)
                    pass("$root final candidate palette displayed with real composition")
                    continue
                }
                stage = "$root skin button actual tap"
                var skin: View? = null
                main { skin = views(input()).filterIsInstance<com.osfans.trime.ime.bar.ui.ToolButton>().first { view ->
                    val cfg = member(view,"config") as? com.osfans.trime.data.theme.model.ToolBar.Button
                    view.isShown && cfg?.action == "__mie_theme_picker"
                } }
                tap(skin!!)
                await("skin picker missing after actual tap") { WindowInspector.getGlobalWindowViews().any { views(it).filterIsInstance<GridView>().isNotEmpty() } }
                main { val window = WindowInspector.getGlobalWindowViews().last { views(it).filterIsInstance<GridView>().isNotEmpty() }; click(window,"内置布局") }
                await("new stock themes not listed dynamically") {
                    val grid = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<GridView>().last()
                    grid.isEnabled && grid.numColumns == 2 && roots.all { id -> (0 until grid.adapter.count).any { grid.adapter.getItem(it).toString().contains(id) } }
                }
                if(familyIndex == 0) shot("builtin-picker")
                val nextRoot = roots[(familyIndex+1)%roots.size]
                main { val grid = WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<GridView>().last()
                    val index = (0 until grid.adapter.count).first { grid.adapter.getItem(it).toString().contains(nextRoot) }
                    grid.performItemClick(grid.adapter.getView(index,null,grid),index,index.toLong())
                }
                await("picker selection did not apply $nextRoot") { active() == nextRoot }
                KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",root,root)); await("root restore") { active() == root }
                pass("$root actual skin button opens picker, all five stock entries visible, cross-family selection applies")
                stage = "$root all 15 pages"
                for(suffix in suffixes) {
                    nav(root+suffix)
                    check(CustomKeyboards.get(root+suffix)!!.rows.isNotEmpty())
                    await("blank page or missing material $root$suffix") { loaded() }
                    if(suffix == "_9" || familyIndex == 0 && suffix.startsWith("_7_")) shot(root+suffix)
                }
                pass("$root all 15 pages attach with real image backgrounds, including 9/14/18 and both seven-column layouts")
                stage = "$root return navigation and language"
                for(suffix in listOf("", "_9", "_14", "_18", "_7_1", "_7_2")) {
                    val origin = root+suffix; nav(origin); nav(root+"_num")
                    main { KeyboardWindow.instance!!.switchKeyboardFromAction(".default") }
                    await("numeric return lost $origin") { active() == origin }
                    language(root,origin)
                }
                pass("$root numeric and English roundtrips retain each of six Chinese layouts")
                stage = "$root menu redraw"
                nav(root)
                repeat(3) {
                    main { input().showKeyboardMenu() }; SystemClock.sleep(120)
                    // A queued theme refresh can replace InputView after selecting a main
                    // layout. Resolve its manager each time, just like a current UI action.
                    main { (member(input(),"windowManager") as BoardWindowManager).attachWindow(KeyboardWindow) }; SystemClock.sleep(180)
                }
                await("menu left pressed/scaled/blank keys") { loaded() && keyViews().all { !it.isPressed && it.scaleX==1f && it.scaleY==1f } }
                shot(root+"-after-menu")
                stage = "$root rapid typing"
                main { (activity!! as CandidateViewportActivity).window.decorView.let { view -> views(view).filterIsInstance<EditText>().single().setText("") } }
                rime.runOnReady { clearComposition(); setRuntimeOption("ascii_mode",false) }
                var q: View? = null; main { q = keyViews().first { (member(it,"key") as com.osfans.trime.ime.keyboard.Key).getLabel().equals("q",true) } }
                tap(q!!); tap(q!!); SystemClock.sleep(250)
                var committed = ""
                main { committed = views(activity!!.window.decorView).filterIsInstance<EditText>().single().text.toString() }
                check((committed + rime.runOnReady { getRawInput() }).lowercase() == "qq") { "Two Q taps did not produce exactly two input characters" }
                rime.runOnReady { clearComposition() }
                stage = "$root floating keyboard"
                nav(root+"_18"); main { currentIme().toggleFloatingKeyboard() }
                await("floating keyboard not active") { input().isFloatingKeyboardEnabled && active() == root+"_18" }
                SystemClock.sleep(300); shot(root+"-floating")
                language(root,root+"_18")
                main { currentIme().toggleFloatingKeyboard() }
                await("floating keyboard exit lost original") { !input().isFloatingKeyboardEnabled && active() == root+"_18" }
                stage = "$root floating candidates"
                candidates(root)
                pass("$root menu roundtrips, two rapid Q taps, floating keyboard language roundtrip and floating candidate composition")
            }
            if(!candidatesOnly) {
                stage = "V3.2.4 annotation, drag, popup and portable archive checks"
                Release324Checks(this@PremiumThemesRunner,activity!!,fixture!!).run(skipBounds=interactionsOnly,exportArchives=!checks325)
                if(checks325) Release325Checks(this@PremiumThemesRunner,activity!!,fixture!!).run()
            }
            }
            result.putString("result","PASS")
        } catch(error: Throwable) {
            result.putString("result","FAIL"); result.putString("stage",stage);result.putString("error",android.util.Log.getStackTraceString(error))
            result.putString("state","stored=${CustomKeyboards.activeId}; visible=${KeyboardWindow.lastAttachedId}; surface=${KeyboardWindow.currentKeyboardOrNull?.keyboardId}")
            runCatching { shot("failure") }
        } finally {
            runCatching { cancelInjectedTouch() }
            runCatching { session?.runOnReady { clearComposition() } }
            main { KeyboardColorGroups.deactivate(); activity?.finish() }; CustomKeyboards.flush()
            runCatching { restoreStorage?.invoke() }.onFailure { result.putString("restoreError",it.toString()); result.putString("result","FAIL") }
            fixture?.deleteRecursively()
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
