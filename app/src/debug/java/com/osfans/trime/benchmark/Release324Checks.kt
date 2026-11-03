/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.symbols.*
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.core.*
import com.osfans.trime.ime.keyboard.*
import com.osfans.trime.ime.symbol.*
import com.osfans.trime.ime.window.BoardWindowManager
import java.io.File
import java.util.zip.ZipFile

/** Runs only inside PremiumThemesRunner's backed-up, temporary Rime root. */
internal class Release324Checks(private val host: Instrumentation, private val activity: Activity, private val fixture: File) {
    private val context get()=host.targetContext
    private val ime get()=member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
    private fun input()=member(ime,"inputView") as InputView
    private fun manager()=member(input(),"windowManager") as BoardWindowManager
    private fun main(block: ()->Unit) { var error: Throwable?=null; host.runOnMainSync { try { block() } catch(e: Throwable) { error=e } }; error?.let { throw it } }
    private fun member(value: Any,name: String): Any? {
        var type: Class<*>?=value.javaClass
        while(type!=null) {
            try { return type.getDeclaredField(name).apply { isAccessible=true }.get(value) } catch(_: NoSuchFieldException) {}
            try { return type.getDeclaredMethod("get"+name.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(value) } catch(_: NoSuchMethodException) {}
            type=type.superclass
        }; error("Missing $name on ${value.javaClass}")
    }
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun keys()=views(input()).filterIsInstance<KeyView>().filter { it.isShown }
    private fun active()=CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
    private fun await(label: String,condition: ()->Boolean) { repeat(240) { var yes=false; main { yes=condition() }; if(yes) return; SystemClock.sleep(60) }; error(label) }
    private fun pass(text: String)=host.sendStatus(0,Bundle().apply { putString("case","PASS: $text") })
    private fun center(view: View): Pair<Float,Float> { val p=IntArray(2); main { check(view.isShown && view.width>0 && view.height>0) { "Unmeasured/detached touch target ${view.javaClass.simpleName} ${(view as? TextView)?.text}: shown=${view.isShown} ${view.width}x${view.height}" }; view.getLocationOnScreen(p) }; return p[0]+view.width/2f to p[1]+view.height/2f }
    private fun event(down: Long,action: Int,x: Float,y: Float) {
        val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
        e.source=InputDevice.SOURCE_TOUCHSCREEN
        try { check(host.uiAutomation.injectInputEvent(e,true)) } finally { e.recycle() }
    }
    private fun tap(view: View) { host.waitForIdleSync(); SystemClock.sleep(250); val (x,y)=center(view); val t=SystemClock.uptimeMillis(); event(t,MotionEvent.ACTION_DOWN,x,y); SystemClock.sleep(35); event(t,MotionEvent.ACTION_UP,x,y); SystemClock.sleep(250) }
    private fun drag(rv: RecyclerView,fromPosition: Int,toPosition: Int) {
        main { rv.scrollToPosition(minOf(fromPosition,toPosition)) }
        host.waitForIdleSync(); SystemClock.sleep(250)
        var from: View?=null; var to: View?=null
        await("Drag items not laid out") {
            from=rv.findViewHolderForAdapterPosition(fromPosition)?.itemView
            to=rv.findViewHolderForAdapterPosition(toPosition)?.itemView
            from?.isShown==true && to?.isShown==true && from!!.height>0 && to!!.height>0
        }
        dragViews(from!!,to!!)
    }
    private fun dragViews(from: View,to: View) {
        val (x,y)=center(from); val target=center(to)
        // Cross the destination center: ItemTouchHelper's default drop predicate uses a strict edge comparison.
        val tx=target.first+kotlin.math.sign(target.first-x)*minOf(to.width*.12f,16f*context.resources.displayMetrics.density)
        val ty=target.second+kotlin.math.sign(target.second-y)*minOf(to.height*.12f,16f*context.resources.displayMetrics.density)
        val t=SystemClock.uptimeMillis()
        event(t,MotionEvent.ACTION_DOWN,x,y); SystemClock.sleep(650)
        main {
            var parent=from.parent
            while(parent is View && parent !is RecyclerView) parent=parent.parent
            val rv=parent as? RecyclerView ?: error("Drag source has no RecyclerView")
            val helper=(0 until rv.itemDecorationCount).map { rv.getItemDecorationAt(it) }.filterIsInstance<androidx.recyclerview.widget.ItemTouchHelper>().single()
            check(member(helper,"mSelected")!=null) { "Long press did not activate drag for ${(from as? TextView)?.text ?: from.javaClass.simpleName}; point=$x,$y" }
        }
        repeat(18) { i -> val f=(i+1)/18f; event(t,MotionEvent.ACTION_MOVE,x+(tx-x)*f,y+(ty-y)*f); SystemClock.sleep(35) }
        main {
            var parent=from.parent
            while(parent is View && parent !is RecyclerView) parent=parent.parent
            val rv=parent as RecyclerView
            val helper=(0 until rv.itemDecorationCount).map { rv.getItemDecorationAt(it) }.filterIsInstance<androidx.recyclerview.widget.ItemTouchHelper>().single()
            val dx=member(helper,"mDx") as Float; val dy=member(helper,"mDy") as Float
            check(kotlin.math.abs(dx)+kotlin.math.abs(dy)>10) { "Drag did not receive move events: dx=$dx dy=$dy" }
        }
        SystemClock.sleep(180); event(t,MotionEvent.ACTION_UP,tx,ty); SystemClock.sleep(500)
    }
    private fun text(root: View,label: String)=views(root).filterIsInstance<TextView>().first { it.isShown && it.text.toString()==label }
    private fun shot(name: String) { SystemClock.sleep(250); host.uiAutomation.takeScreenshot()?.let { b ->
        File(context.cacheDir,"v324-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }; b.recycle()
    } }
    private suspend fun nav(root: String) { KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",root,root)); await("Attach $root") { active()==root && keys().isNotEmpty() }; SystemClock.sleep(250) }

    fun bounds(label: String) {
        var count=0
        main {
            keys().forEach { view ->
                val inkPaint=member(view,"symbolPaint") as Paint
                val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                val canvas=object: Canvas(bitmap) {
                    override fun drawText(text: String,x: Float,y: Float,paint: Paint) {
                        if(paint===inkPaint && text.isNotBlank()) {
                            val glyph=Rect(); paint.getTextBounds(text,0,text.length,glyph)
                            val shift=paint.measureText(text)/2
                            val ink=RectF(x+glyph.left-shift,y+glyph.top,x+glyph.right-shift,y+glyph.bottom)
                            val area=member(view,"annotationArea") as RectF
                            val body=member(view,"backgroundClipBounds") as RectF
                            val radius=(member(view,"backgroundClipRadius") as Float).coerceAtMost(minOf(body.width(),body.height())/2)
                            check(ink.left>=area.left-1 && ink.top>=area.top-1 && ink.right<=area.right+1 && ink.bottom<=area.bottom+1) { "$label $text ink=$ink area=$area" }
                            val depth=minOf(ink.top-body.top,body.bottom-ink.bottom).coerceIn(0f,radius)
                            val clearance=radius-kotlin.math.sqrt((radius*radius-(radius-depth)*(radius-depth)).coerceAtLeast(0f))
                            check(ink.left>=body.left+clearance-1 && ink.right<=body.right-clearance+1) { "$label $text escaped round cap" }
                            count++
                        }
                        super.drawText(text,x,y,paint)
                    }
                }
                view.draw(canvas); bitmap.recycle()
            }
        }
        check(count>0) { "No annotation draw calls on $label" }; pass("$label: $count actual symbol/hint draw calls stay inside inset area and round cap")
    }

    suspend fun run(skipBounds: Boolean = false, exportArchives: Boolean = true) {
        main { AppPrefs.defaultInstance().keyboard.longPressTimeout.setValue(250) }
        for(root in (if(skipBounds) emptyList() else listOf("silver","titanium","cloudsea","inkweave","celadon")).map { "custom_$it" }) {
            nav(root); bounds(root); shot("$root-insets")
        }
        val root="custom_cloudsea"; nav(root); val original=CustomKeyboards.get(root)!!
        if(!skipBounds) {
        CustomKeyboards.mutate(root) { it.keyAppearance=it.keyAppearance.copy(symbolInsetX=3f,symbolInsetY=6f,hintInsetX=9f,hintInsetY=5f,
            keySymbolOffsetX=100f,keySymbolOffsetY=-100f,keyHintOffsetX=-100f,keyHintOffsetY=100f) }
        ThemeManager.refreshActiveTheme(); await("Edited display area refresh") { keys().any { (member(it,"key") as Key).annotationInsetY(true)==6f } }
        bounds("independent insets and extreme offsets"); CustomKeyboards.mutate(root) { it.keyAppearance=original.keyAppearance }; ThemeManager.refreshActiveTheme()
        }

        // Built-in picker: category and item reordering through real injected touch events.
        var panel: BuiltinSymbolsWindow?=null
        fun open() { main { panel=BuiltinSymbolsWindow(manager().di,"common"); manager().attachWindow(panel!!) } }
        open(); await("Builtin symbol load") { (member(panel!!,"items") as List<*>).size>5 }
        val originalItems=(member(panel!!,"items") as List<*>).toList()
        var grid: RecyclerView?=null
        main { grid=member(panel!!,"grid") as RecyclerView }
        tap(text(grid!!.parent as View,"排序"))
        await("Builtin sort button did not activate sorting") { member(panel!!,"sorting")==true }
        shot("builtin-sort-mode")
        drag(grid!!,0,3)
        check((member(panel!!,"items") as List<*>).indexOf(originalItems[0])>0) { "Builtin item touch drag did not reorder" }
        val edited=(member(panel!!,"items") as List<*>).toList(); shot("builtin-items-drag")
        main { manager().attachWindow(KeyboardWindow) }; open()
        await("Saved symbol order") { (member(panel!!,"items") as List<*>)==edited }
        main { (member(panel!!,"selector") as View).performClick() }
        var chooser: RecyclerView?=null
        await("Category chooser") { chooser=android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::views).filterIsInstance<RecyclerView>().lastOrNull {
            it.isShown && (it.layoutManager as? androidx.recyclerview.widget.GridLayoutManager)?.spanCount==2 && (it.adapter?.itemCount ?: 0)>10
        }; chooser!=null }
        val beforeCategories=SymbolCategoryOrder.ordered(BuiltinSymbolCatalog.categories).map { it.id }
        drag(chooser!!,0,3)
        await("Category touch drag did not persist") { SymbolCategoryOrder.ordered(BuiltinSymbolCatalog.categories).map { it.id }!=beforeCategories }
        main { android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { views(it).any { v -> v is TextView && v.text.toString()=="关闭" } }?.let { text(it,"关闭").performClick() }; manager().attachWindow(KeyboardWindow) }
        pass("builtin category and per-item real touch dragging persist after leaving/reopening")

        File(fixture,"symbolprobe.trime.yaml").writeText("""
            name: Symbol probe
            style:
              key_width: 20
              key_height: 60
              key_text_size: 22
              key_long_text_size: 15
              symbol_text_size: 10
              keyboard_height: 240
              candidate_view_height: 34
              round_corner: 8
              popup_width: 0
              popup_key_height: 0
              popup_height: 0
              popup_text_size: 0
            preset_keyboards:
              default:
                width: 20
                height: 60
                ascii_mode: 0
                keys:
                  - {click: a}
                  - {click: b}
                  - {click: k, popup: ['[', '(', '「', '💕', '【', '《', '😀', '”']}
                  - {click: l}
                  - {click: m}
            liquid_keyboard:
              single_width: 48
              key_height: 48
              margin_x: 2
              keyboards: [tabs, marks, emojis]
              tabs: {name: 分类, type: TABS}
              marks: {name: 标点, type: SINGLE, keys: ['，','。','？','！','：','；','、','…','—','·']}
              emojis: {name: 表情, type: SINGLE, keys: ['💕','😀','😊','❤️','👍','🎉','✨','🥰','🙏','😂']}
            preset_color_schemes:
              default:
                back_color: 0xffedf2f7
                text_color: 0xff203040
                candidate_text_color: 0xff203040
                key_back_color: 0xffffffff
                key_text_color: 0xff203040
                popup_back_color: 0xfffafafa
                popup_text_color: 0xff203040
                hilited_popup_back_color: 0xff3873a8
                hilited_popup_text_color: 0xffffffff
        """.trimIndent())
        check(ThemeManager.selectTheme("symbolprobe.trime",forceReload=true,layoutMode=CustomKeyboards.MODE_THEME,requireExact=true)=="symbolprobe.trime")
        await("Theme layout refresh") { !ThemeManager.activeTheme.isCustom && keys().any { (member(it,"key") as Key).getLabel().equals("k",true) } }
        var liquid: LiquidWindow?=null
        main { manager().attachWindow(LiquidWindow); liquid=member(manager(),"currentWindow") as LiquidWindow; liquid!!.setDataByIndex(1) }
        await("Liquid items measured") { val rv=member(member(liquid!!,"liquidLayout")!!,"recyclerView") as RecyclerView; rv.findViewHolderForAdapterPosition(3)!=null }
        val adapter=member(liquid!!,"adapter") as LiquidAdapter
        val liqLayout=member(liquid!!,"liquidLayout") as LiquidLayout
        val itemsBefore=adapter.snapshot(); tap(member(liquid!!,"sortButton") as View)
        drag(liqLayout.recyclerView,0,3)
        check(adapter.snapshot()!=itemsBefore) { "Theme liquid item drag failed" }
        val saved=adapter.snapshot(); main { liquid!!.setDataByIndex(2); liquid!!.setDataByIndex(1) }
        check(adapter.snapshot()==saved) { "Theme liquid item order did not persist" }
        pass("theme liquid individual symbol touch drag persists after switching categories")
        val tabBefore=liqLayout.tabsUi.orderedSourceIndices()
        // Avoid the phone's system back-gesture strip at the first tab's center.
        drag(liqLayout.tabsUi.root,1,2)
        check(liqLayout.tabsUi.orderedSourceIndices()!=tabBefore) { "Liquid tab drag failed" }
        main { liqLayout.tabsUi.reloadOrder() }; check(liqLayout.tabsUi.orderedSourceIndices()!=tabBefore)
        pass("theme liquid categories and individual symbols use real touch dragging and persist")
        main { manager().attachWindow(KeyboardWindow) }
        host.waitForIdleSync(); SystemClock.sleep(250)

        // Popup defaults are deliberately zero in the theme, testing the new metric fallback.
        var trigger: KeyView?=null
        main { trigger=keys().first { (member(it,"key") as Key).getLabel().equals("k",true) }; views(activity.window.decorView).filterIsInstance<EditText>().single().setText("") }
        val (x,y)=center(trigger!!); val down=SystemClock.uptimeMillis(); event(down,MotionEvent.ACTION_DOWN,x,y)
        SystemClock.sleep(550)
        val popup=member(trigger!!,"popup")!!
        await("Long press popup absent") { (member(popup,"showingContainerUi") as Map<*,*>).isNotEmpty() }
        val ui=(member(popup,"showingContainerUi") as Map<*,*>).values.single()!!
        val popupRoot=member(ui,"root") as View
        val emoji=views(popupRoot).filterIsInstance<TextView>().first { it.text.toString()=="💕" }
        val displayed=views(popupRoot).filterIsInstance<TextView>().map { it.text.toString() }
        check(displayed.size==8 && displayed.contains("😀")) { "Popup dropped an alternative or split emoji: $displayed" }
        shot("theme-popup-slide")
        val (ex,ey)=center(emoji)
        repeat(12) { i -> val f=(i+1)/12f; event(down,MotionEvent.ACTION_MOVE,x+(ex-x)*f,y+(ey-y)*f); SystemClock.sleep(30) }
        event(down,MotionEvent.ACTION_UP,ex,ey)
        await("Popup slide did not commit full emoji") { views(activity.window.decorView).filterIsInstance<EditText>().single().text.toString()=="💕" }
        await("Popup did not dismiss") { (member(popup,"showingContainerUi") as Map<*,*>).isEmpty() }
        pass("theme popup: zero dimensions fallback, all 8 configured alternatives, full emoji, real long-press/slide/release commit and dismiss")

        nav("custom_silver"); main { check(ThemeManager.activeTheme.isCustom) }
        if(exportArchives) exportAndImport()
    }

    private suspend fun exportAndImport() {
        CustomKeyboards.restoreBuiltins(true); CustomKeyboards.flush()
        val roots=KeyboardLayoutCatalog.roots(context,KeyboardLayoutCatalog.BUILTIN)
        check(roots.size==14)
        val templates=CustomKeyboards.builtinTemplates()
        CustomKeyboards.editSets { pages,profiles -> roots.forEach { KeyboardSetOperations.adoptBuiltinData(it.id,pages,profiles,templates) } }
        val output=File(context.cacheDir,"builtin-share").apply { mkdirs() }
        val manifest=mutableListOf<String>()
        for(entry in roots) {
            val archive=KeyboardSetArchive.create(context,listOf(entry.id))
            val target=File(output,entry.id+".mie-keyboard.zip"); archive.copyTo(target,true); archive.delete()
            val before=CustomKeyboards.list().map { it.id }.toSet()
            val imported=KeyboardSetArchive.import(context,target); check(imported.size==1)
            val newRoot=imported.single(); val pages=CustomKeyboards.familyIds(imported)
            check(pages.size==15); check(pages.none { it in before })
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("custom",newRoot,entry.name))
            await("Imported theme blank: ${entry.name}") { active()==newRoot && keys().isNotEmpty() }
            val kb=CustomKeyboards.get(newRoot)!!
            check(kb.keyboardHeightDp==251 && kb.rowSpacing==8)
            main { keys().filter { (member(it,"key") as Key).getLabel().equals("r",true) }.forEach {
                check(!(member(it,"key") as Key).hasAction(KeyBehavior.DOUBLE_CLICK)) { "Imported R retained date double tap" }
            } }
            check(kb.rows.flatMap { it.keys }.any { it.bindings.values.any(String::isNotBlank) })
            val dep=kb.rows.flatMap { it.keys }.flatMap { listOf(it.appearance.keyBackColor,it.appearance.icon) }+kb.toolbar.map { it.icon }
            dep.filter { it.startsWith('/') }.forEach { check(File(it).isFile) { "Missing portable resource $it" } }
            val numeric=pages.single { CustomKeyboards.pageRole(it)=="num" }
            main { KeyboardWindow.instance!!.switchKeyboardFromAction(numeric) }
            await("Imported numeric navigation") { active()==numeric }
            main { KeyboardWindow.instance!!.switchKeyboardFromAction(".default") }
            await("Imported numeric return") { active()==newRoot }
            manifest+="${entry.id}\t${entry.name}\t${pages.size}\t${target.name}"
            nav("custom_silver"); CustomKeyboards.delete(newRoot)
            pass("portable ${entry.name}: 15 pages imported with assets, displayed and numeric return kept remapped root")
        }
        val all=KeyboardSetArchive.create(context,roots.map { it.id })
        val target=File(output,"all-builtin.mie-keyboard.zip"); all.copyTo(target,true); all.delete()
        val imported=KeyboardSetArchive.import(context,target)
        check(imported.size==14 && CustomKeyboards.familyIds(imported).size==210)
        ZipFile(target).use { zip ->
            val body=zip.getInputStream(zip.getEntry("keyboard.json")).bufferedReader().use { it.readText() }
            check(!body.contains("/storage/") && !body.contains("/data/user/")) { "Archive leaked device-specific paths" }
        }
        imported.forEach { CustomKeyboards.delete(it) }
        File(output,"manifest.tsv").writeText(manifest.joinToString("\n"))
        pass("all-in-one portable pack imported: 14 roots / 210 pages, no absolute device paths")
    }
}
