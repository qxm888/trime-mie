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
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.KeyboardSetActivity
import kotlinx.coroutines.runBlocking
import java.io.File

/** Host makes a new backup before running; all fixtures stay in the independent IME. */
class Mie320Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(block: () -> Unit) { var failure: Throwable?=null; runOnMainSync { try { block() } catch(e:Throwable) { failure=e } };failure?.let { throw it } }
    private fun member(value: Any,name: String): Any? {
        var t: Class<*>?=value.javaClass
        while(t!=null) { try { return t.getDeclaredField(name).apply { isAccessible=true }.get(value) } catch(_:NoSuchFieldException) {}
            try { return t.getDeclaredMethod("get"+name.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(value) } catch(_:NoSuchMethodException) {};t=t.superclass }
        error("No member $name")
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(label:String,condition:()->Boolean) { repeat(240) { var yes=false;main { yes=condition() };if(yes)return;SystemClock.sleep(60) };error(label) }
    private fun pass(value:String) = sendStatus(0,Bundle().apply { putString("case","PASS: $value") })
    private fun shot(name:String) { uiAutomation.takeScreenshot()?.let { image -> File(targetContext.cacheDir,"mie320-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle() } }
    override fun onStart() = runBlocking {
        val result=Bundle();val cleanup=mutableListOf<File>();var activity:Activity?=null;var stage="startup"
        try {
            waitForIdleSync()
            CustomKeyboards.init(targetContext)
            CustomKeyboards.awaitBuiltinLayouts()
            if(CustomKeyboards.get("custom_blue")==null) CustomKeyboards.resetBuiltinSet("custom_blue")
            stage="create"; val start=SystemClock.elapsedRealtime()
            val root=KeyboardSetOperations.newSet("V320整套测试","custom_blue",KeyboardSetOperations.layoutNames.keys.toList())
            check(KeyboardSetOperations.ordered(root).size==8)
            val pages=KeyboardSetOperations.ordered(root).associateBy { CustomKeyboards.pageRole(it) }
            val scope=KeyboardSets.EDIT_PREFIX+root
            CustomKeyboards.mutate(scope) { it.typography=mapOf("key" to FontSpec("system:serif",23f));it.toolbar=mutableListOf(ToolbarOverrides.Entry("Mode_switch"));it.unifiedBackground=true }
            check(pages.values.all { CustomKeyboards.get(it)!!.typography["key"]==FontSpec("system:serif",23f) && CustomKeyboards.get(it)!!.toolbar.size==1 })
            CustomKeyboards.mutate(pages.getValue("num")) { it.typography=it.typography+ ("key" to FontSpec("system:serif",31f)) }
            CustomKeyboards.mutate(scope) { it.typography=mapOf("key" to FontSpec("system:monospace",25f)) }
            check(CustomKeyboards.get(pages.getValue("num"))!!.typography["key"]==FontSpec("system:monospace",31f))
            KeyboardSetOperations.follow(pages.getValue("num"),"fonts")
            check(CustomKeyboards.get(pages.getValue("num"))!!.typography["key"]==FontSpec("system:monospace",25f))
            pass("eight-page set; common toolbar/fonts; nested page size override follows font changes; restore follows (${SystemClock.elapsedRealtime()-start}ms)")
            stage="bindings"
            CustomKeyboards.mutate(scope) { it.setBindings=mapOf("char:c" to mapOf("longClick" to "copy")) }
            val c=CustomKeyboards.get(root)!!.rows.flatMap { it.keys }.first { it.identity=="char:c" }
            check(c.bindings["longClick"]=="copy")
            CustomKeyboards.mutate(root) { it.rowSpacing=6 }
            CustomKeyboards.mutate(scope) { it.setBindings=emptyMap() }
            check(CustomKeyboards.get(root)!!.rows.flatMap { it.keys }.first { it.identity=="char:c" }.bindings["longClick"]==c.setBaseBindings!!["longClick"])
            val group=CustomKeyboards.get(pages.getValue("zh_9"))!!.rows.flatMap { it.keys }.first { it.label=="ABC" }
            CustomKeyboards.mutate(scope) { it.setBindings=mapOf("char:a" to mapOf("longClick" to "copy")) }
            check(CustomKeyboards.get(pages.getValue("zh_9"))!!.rows.flatMap { it.keys }.first { it.label=="ABC" }.bindings["longClick"]==group.bindings["longClick"])
            pass("shared bindings clear to base after page edit; grouped ABC is isolated from single A")
            stage="profiles"
            val other=KeyboardSetOperations.copySet(root,"V320副本")
            val profile=KeyboardSetOperations.saveProfile(root,"fonts","V320共享字体")
            KeyboardSetOperations.useProfile(other,"fonts",profile)
            CustomKeyboards.mutate(scope) { it.typography=mapOf("key" to FontSpec("system:sans-serif",26f)) }
            KeyboardSetOperations.saveProfile(root,"fonts","V320共享字体",profile)
            check(CustomKeyboards.get(other)!!.typography["key"]==FontSpec("system:sans-serif",26f))
            KeyboardSetOperations.useProfile(other,"fonts",null)
            CustomKeyboards.mutate(scope) { it.typography=mapOf("key" to FontSpec("system:serif",28f)) }
            KeyboardSetOperations.saveProfile(root,"fonts","V320共享字体",profile)
            check(CustomKeyboards.get(other)!!.typography["key"]==FontSpec("system:sans-serif",26f))
            pass("whole-set copy remapped; shared profile propagates; detach freezes effective values")
            stage="archive"
            val image=File(CustomKeyAssets.root(targetContext),"mie320-literal.png").also { cleanup+=it }
            Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).also { bitmap -> bitmap.eraseColor(0xffabcdef.toInt());image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
            CustomKeyboards.mutate(scope) { it.background=CustomBackground(CustomBackground.Type.IMAGE,image=image.path) }
            CustomKeyboards.mutate(root) { val key=it.rows.first().keys.first();it.rows.first().keys[0]=key.copy(label="[1, 2]",appearance=key.appearance.copy(hint="mie320-literal.png")) }
            val archive=KeyboardSetArchive.create(targetContext,listOf(root)).also { cleanup+=it }
            val imported=KeyboardSetArchive.import(targetContext,archive).single()
            val importedKb=CustomKeyboards.get(imported)!!
            check(imported!=root && KeyboardSetOperations.ordered(imported).size==8)
            check(importedKb.rows.first().keys.first().label=="[1, 2]")
            check(importedKb.rows.first().keys.first().appearance.hint=="mie320-literal.png")
            val restored=File(importedKb.background!!.image);check(restored.readBytes().contentEquals(image.readBytes()))
            cleanup+=restored.parentFile!!.parentFile!!
            check(importedKb.setConfig!!.profiles.isEmpty())
            pass("portable ZIP restores 8 pages and image bytes; literal label/hint unchanged; imported profiles independent")
            stage="builtin"
            CustomKeyboards.resetBuiltinSet("custom_blue")
            val builtinTemplates=CustomKeyboards.builtinTemplates()
            val builtinIds=KeyboardSetOperations.ordered("custom_blue").filter { it in builtinTemplates }
            CustomKeyboards.mutate(KeyboardSets.EDIT_PREFIX+"custom_blue") { it.toolbar=mutableListOf(ToolbarOverrides.Entry("paste")) }
            check(builtinIds.all { CustomKeyboards.get(it)!!.toolbar.singleOrNull()?.action=="paste" }) { "stock toolbar pages do not follow" }
            val duplicate=KeyboardSetOperations.copySet("custom_blue","V320内置另存")
            check(duplicate!="custom_blue" && CustomKeyboards.get(duplicate)!=null)
            pass("builtin migration: all stock page toolbars follow; save-as independent copy")
            stage="add-undo"
            val blank=KeyboardSetOperations.newBlank("V320补页")
            KeyboardSetOperations.addLayout(blank,"zh_9","custom_blue");KeyboardSetOperations.addLayout(blank,"en","custom_blue")
            val added=KeyboardSetOperations.ordered(blank);val en=added.first { CustomKeyboards.pageRole(it)=="en" }
            check(added.filter { CustomKeyboards.pageRole(it).startsWith("zh_") }.all { CustomKeyboards.get(it)!!.options.asciiKeyboard==en })
            val undoCopy=KeyboardSetOperations.copySet(blank,"V320撤销")
            CustomKeyboards.undoSetOperation();check(CustomKeyboards.get(undoCopy)==null)
            pass("add layouts auto-links English; atomic whole-set undo restores previous store")
            stage="ui"
            activity=startActivitySync(Intent(targetContext,KeyboardSetActivity::class.java).putExtra("root",root).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("set editor loading") { views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("添加布局") } }
            shot("set-layouts")
            val tabs=views(activity!!.window.decorView).filterIsInstance<TabLayout>().single()
            for(i in listOf(1,2,0)) { main { tabs.getTabAt(i)!!.select() };waitForIdleSync();SystemClock.sleep(200);shot("set-tab-$i") }
            main { activity!!.finish() };activity=null
            pass("real set Activity opens; all three tabs render without crash; screenshots captured")
            stage="navigation"
            main { AppPrefs.defaultInstance().general.inputStatsEnabled.setValue(false);com.osfans.trime.data.stats.InputStats.setEnabled(false) }
            ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue(),layoutMode=CustomKeyboards.MODE_CUSTOM,customLayoutId=root)
            activity=startActivitySync(Intent(targetContext,CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("IME unavailable") { KeyboardWindow.instance!=null }
            val ime=member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
            val input=member(ime,"inputView") as InputView
            await("input not visible") { input.isShown && ime.currentInputConnection!=null }
            suspend fun nav(id:String, expected:String=id) { main { KeyboardWindow.instance!!.switchKeyboardFromAction(id) };await("navigate $id") { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)==expected };SystemClock.sleep(160) }
            for(role in listOf("zh_26","zh_9","zh_14","zh_18","zh_7_1","zh_7_2")) {
                val id=pages.getValue(role);nav(id);nav(pages.getValue("num"));nav(".default",id)
                check(CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)==id)
                nav(pages.getValue("en"));nav(".default",id);check(CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)==id)
            }
            shot("typing")
            pass("26/9/14/18/both seven-column layouts return exactly from numeric and English pages")
            stage="ball"
            main {
                val prefs=AppPrefs.defaultInstance()
                prefs.candidates.mode.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.ALWAYS_SHOW)
                prefs.keyboard.hideInputBar.setValue(true);prefs.candidates.floatingBall.setValue(true)
            }
            await("floating candidates unavailable") { member(ime,"candidatesView")!=null }
            val candidates=member(ime,"candidatesView")!!
            for(role in listOf("zh_9","zh_18")) {
                val origin=pages.getValue(role);nav(origin)
                repeat(2) {
                    main { candidates.javaClass.getDeclaredMethod("toggleBallTools").apply { isAccessible=true }.invoke(candidates) }
                    val row=member(candidates,"toolsRow") as android.widget.LinearLayout
                    await("ball toolbar unavailable") { row.childCount>0 }
                    // ToolButton uses touch gestures; exercise its action via the same current config dispatch.
                    val config=member(row.getChildAt(0),"config") as com.osfans.trime.data.theme.model.ToolBar.Button
                    main { ime.performFloatingToolbarAction(config.action) }
                    await("ball to English") { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)==pages.getValue("en") }
                    main { candidates.javaClass.getDeclaredMethod("toggleBallTools").apply { isAccessible=true }.invoke(candidates) }
                    val next=member(row.getChildAt(0),"config") as com.osfans.trime.data.theme.model.ToolBar.Button
                    main { ime.performFloatingToolbarAction(next.action) }
                    await("ball to Chinese") { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)==origin }
                }
            }
            shot("ball")
            pass("floating toolbar current config switches Chinese-English-Chinese repeatedly, returning to 9/18-key origin")
            result.putString("result","PASS")
        } catch(e:Throwable) { result.putString("result","FAIL");result.putString("stage",stage);result.putString("error",android.util.Log.getStackTraceString(e)) }
        finally { main { activity?.finish() };cleanup.forEach { if(it.isDirectory) it.deleteRecursively() else it.delete() };CustomKeyboards.flush() }
        finish(if(result.getString("result")=="PASS")Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
