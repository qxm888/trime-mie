/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.fragment.app.FragmentActivity
import com.osfans.trime.R
import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.core.data.CandidateRecordFormats
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.core.*
import com.osfans.trime.ime.keyboard.*
import com.osfans.trime.ime.symbol.BuiltinSymbolsWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.*
import java.io.File
import kotlinx.coroutines.*

/** Synthetic content only. Host must back up and restore current phone data. */
internal class Release325Checks(private val host:Instrumentation,private val editor:Activity,private val fixture:File) {
    private val context get()=host.targetContext
    private fun main(block:()->Unit) { var error:Throwable?=null;host.runOnMainSync { try { block() } catch(t:Throwable) { error=t } };error?.let { throw it } }
    private fun member(v:Any,name:String):Any? {
        var c:Class<*>?=v.javaClass
        while(c!=null) { try { return c.getDeclaredField(name).apply { isAccessible=true }.get(v) } catch(_:NoSuchFieldException) {}
            try { return c.getDeclaredMethod("get"+name.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(v) } catch(_:NoSuchMethodException) {}
            c=c.superclass };error("Missing $name on ${v.javaClass}")
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun ime()=member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
    private fun input()=member(ime(),"inputView") as InputView
    private fun manager()=member(input(),"windowManager") as BoardWindowManager
    private fun keys()=views(input()).filterIsInstance<KeyView>().filter { it.isShown && it.width>0 }
    private fun active()=CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
    private fun await(label:String,block:()->Boolean) { repeat(240) { var yes=false;main { yes=block() };if(yes) return;SystemClock.sleep(70) };error(label) }
    private fun pass(value:String)=host.sendStatus(0,Bundle().apply { putString("case","PASS: $value") })
    private fun shot(name:String) { SystemClock.sleep(200);host.uiAutomation.takeScreenshot()?.let { b->File(context.cacheDir,"v325-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() } }
    private fun shell(cmd:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(host.uiAutomation.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
    private suspend fun nav(id:String) { KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",id,id));await("Attach $id") { active()==id && keys().isNotEmpty() };SystemClock.sleep(250) }
    private fun launch(type:Class<out Activity>):Activity {
        val monitor=host.addMonitor(type.name,null,false)
        try { main { context.startActivity(Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) };return monitor.waitForActivityWithTimeout(10000) ?: error("No activity ${type.name}") }
        finally { host.removeMonitor(monitor) }
    }
    private fun point(v:View):Pair<Float,Float> { val a=IntArray(2);main { v.getLocationOnScreen(a) };return a[0]+v.width/2f to a[1]+v.height/2f }
    private fun event(t:Long,action:Int,x:Float,y:Float) { val e=MotionEvent.obtain(t,SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;try { check(host.uiAutomation.injectInputEvent(e,true)) } finally { e.recycle() } }
    private fun text(v:View,s:String)=views(v).filterIsInstance<TextView>().first { it.text.toString()==s && it.isShown }
    private fun floatingIconUpgrade() {
        val root=CustomKeyAssets.root(context)
        val old=mutableMapOf<String,ByteArray>()
        java.util.zip.ZipInputStream(context.assets.open("legacy-floating-icons.zip")).use { z ->
            while(true) { val entry=z.nextEntry ?: break;old[entry.name]=z.readBytes();z.closeEntry() }
        }
        val expected=mutableMapOf<String,ByteArray>()
        java.util.zip.ZipInputStream(context.resources.openRawResource(R.raw.mie_premium_materials)).use { z ->
            while(true) { val entry=z.nextEntry ?: break;if(entry.name.endsWith("/tb_float.png")) expected[entry.name]=z.readBytes();z.closeEntry() }
        }
        check(expected.size==5)
        expected.keys.forEach { name -> File(root,name).writeBytes(old.getValue(name)) }
        File(root,"内置材质/.精雕一辑-v2").delete();File(root,"内置材质/.精雕一辑-v1").writeText("1\n")
        check(BuiltinPremiumAssets.install(context))
        expected.forEach { (name,bytes) -> check(File(root,name).readBytes().contentEquals(bytes)) }
        val name=expected.keys.first();val userFile=File(root,name);val custom=expected.values.last()
        userFile.writeBytes(custom);File(root,"内置材质/.精雕一辑-v2").delete()
        BuiltinPremiumAssets.install(context);check(userFile.readBytes().contentEquals(custom));userFile.writeBytes(expected.getValue(name))
        check(!BuiltinPremiumAssets.install(context))
        val generic=File(root,"icons/mie2_tb_float.png");generic.writeBytes(old.getValue("generic/mie2_tb_float.png"))
        File(root,"icons/.builtin_v4").delete();File(root,"icons/.builtin_v3").writeText("3\n")
        BuiltinToolbarAssets.install(context)
        var fresh:ByteArray?=null
        java.util.zip.ZipInputStream(context.resources.openRawResource(R.raw.mie_builtin_icons)).use { z ->
            while(true) { val entry=z.nextEntry ?: break;if(entry.name=="mie2_tb_float.png") fresh=z.readBytes();z.closeEntry() }
        }
        check(generic.readBytes().contentEquals(fresh!!));check(!File(root,"icons/.builtin_v3").exists())
        pass("floating icon upgrade: five legacy originals and managed generic icon updated; user-modified premium artwork preserved; repeat install skips")
    }
    suspend fun run() {
        floatingIconUpgrade()

        // Check footer paint on the actual dark panel, not only emoji glyphs.
        nav("custom_titanium")
        var panel:BuiltinSymbolsWindow?=null
        main { panel=BuiltinSymbolsWindow(manager().di,"emoji");manager().attachWindow(panel!!) }
        await("Emoji catalog") { (member(panel!!,"items") as List<*>).isNotEmpty() }
        main {
            val palette=member(panel!!,"palette") as com.osfans.trime.data.symbols.SymbolPanelColors.Palette
            val buttons=member(panel!!,"footerButtons") as List<*>
            check(buttons.size==4)
            buttons.forEach { val v=it as TextView;check(v.currentTextColor==palette.text)
                check(com.osfans.trime.data.symbols.SymbolPanelColors.contrast(v.currentTextColor,palette.key)>=4.5) }
        };shot("titanium-emoji-readable")
        main { manager().attachWindow(KeyboardWindow) }
        shot("titanium-floating-icon")
        var floating:View?=null
        main { floating=views(input()).filterIsInstance<com.osfans.trime.ime.bar.ui.ToolButton>().first {
            (member(it,"config") as? com.osfans.trime.data.theme.model.ToolBar.Button)?.action=="__mie_floating_keyboard" && it.isShown
        } }
        val (fx,fy)=point(floating!!);val ft=SystemClock.uptimeMillis();event(ft,MotionEvent.ACTION_DOWN,fx,fy);event(ft,MotionEvent.ACTION_UP,fx,fy)
        await("Floating icon action") { input().isFloatingKeyboardEnabled };main { ime().toggleFloatingKeyboard() }
        await("Floating return") { !input().isFloatingKeyboardEnabled }
        pass("dark titanium emoji footer: four visible labels, actual text/background contrast >= 4.5")

        // All five stock solid backgrounds include the bottom navigation strip.
        for(root in listOf("pink","blue","mint","orange","purple").map { "custom_$it" }) {
            nav(root)
            if(root=="custom_mint") shot("mint-floating-icon")
            main {
                val i=input();val strip=member(i,"bottomPaddingSpace") as View
                check((strip.layoutParams as android.view.ViewGroup.MarginLayoutParams).bottomMargin==0)
                val container=member(i,"keyboardView") as View
                val a=IntArray(2);val b=IntArray(2);strip.getLocationOnScreen(a);container.getLocationOnScreen(b)
                val bmp=Bitmap.createBitmap(container.width,container.height,Bitmap.Config.ARGB_8888)
                try { container.draw(Canvas(bmp));val x=(a[0]-b[0]+strip.width/2).coerceIn(0,bmp.width-1);val y=(a[1]-b[1]+strip.height/2).coerceIn(0,bmp.height-1)
                    val expected=Color.parseColor(CustomKeyboards.get(root)!!.resolvedBackground().color)
                    val actual=bmp.getPixel(x,y);check(actual==expected) { "$root bottom color ${Integer.toHexString(actual)} != ${Integer.toHexString(expected)}" }
                } finally { bmp.recycle() }
            }
        };shot("mint-bottom-surface");pass("five stock palettes: actual navigation-strip pixels match keyboard background, no transparent margin")

        // Selector names and navigation roles remain separate.
        val rename="custom_mint_14";val original=CustomKeyboards.get(rename)!!
        CustomKeyboards.rename(rename,"真机自定义十四键")
        main { val window=KeyboardSetLayoutsWindow(manager().di,"custom_mint");manager().attachWindow(window)
            check(views(member(manager(),"currentView") as View).filterIsInstance<TextView>().any { it.text.toString()=="真机自定义十四键" }) }
        check(CustomKeyboards.pageRole(rename)=="zh_14")
        main { manager().attachWindow(KeyboardWindow) }
        CustomKeyboards.rename(rename,original.name)
        pass("renamed layout appears in live toolbar selector; zh_14 role unchanged")

        // A custom popup supports Unicode commits and short action labels.
        nav("custom_silver")
        val page=CustomKeyboards.get("custom_silver")!!
        val where=page.rows.withIndex().flatMap { (r,row)->row.keys.mapIndexedNotNull { c,k -> if(k.bindings["click"]=="k") r to c else null } }.single()
        val choices=listOf(BindingActions.command("deploy",label="部署"),BindingActions.command("refresh_theme",label="刷新主题"),"💕","👩🏽‍💻","[","(","😀","”")
        CustomKeyboards.mutate("custom_silver") { kb->val row=kb.rows[where.first];val k=row.keys[where.second];row.keys[where.second]=k.copy(appearance=k.appearance.copy(popup=choices)) }
        ThemeManager.refreshActiveTheme()
        await("Custom popup data refresh") { keys().any { (member(it,"key") as Key).popup==choices } }
        var key:KeyView?=null
        main { key=keys().first { (member(it,"key") as Key).popup==choices };views(editor.window.decorView).filterIsInstance<EditText>().single().setText("") }
        host.waitForIdleSync();val (x,y)=point(key!!);val t=SystemClock.uptimeMillis();event(t,MotionEvent.ACTION_DOWN,x,y);SystemClock.sleep(600)
        val popup=member(key!!,"popup")!!
        await("Custom popup absent") { (member(popup,"showingContainerUi") as Map<*,*>).isNotEmpty() }
        val root=member((member(popup,"showingContainerUi") as Map<*,*>).values.single()!!,"root") as View
        val labels=views(root).filterIsInstance<TextView>().map { it.text.toString() }
        check(labels.size==8 && "部" in labels && "刷" in labels && "👩🏽‍💻" in labels && "刷新主题" !in labels)
        shot("custom-popup-eight-choices")
        val (ex,ey)=point(text(root,"💕"));repeat(12) { n->val f=(n+1)/12f;event(t,MotionEvent.ACTION_MOVE,x+(ex-x)*f,y+(ey-y)*f);SystemClock.sleep(35) };event(t,MotionEvent.ACTION_UP,ex,ey)
        await("Custom popup emoji commit") { views(editor.window.decorView).filterIsInstance<EditText>().single().text.toString()=="💕" }
        pass("custom popup: exactly 8 choices, compact action labels, full ZWJ labels, real slide-to-emoji commit")

        // Real system changes apply once. Manual choices remain until the next transition.
        val pref=ThemeManager.prefs
        main { pref.followSystemDayNight.setValue(true) }
        val group=KeyboardColorGroup(name="测试日夜",day=KeyboardStyleTarget("builtin","custom_mint","薄荷"),night=KeyboardStyleTarget("builtin","custom_pink","樱花"))
        KeyboardColorGroups.save(group);KeyboardColorGroups.activate(group.id)
        shell("cmd uimode night yes");await("System night group") { active().startsWith("custom_pink") }
        nav("custom_mint");ThemeManager.refreshActiveTheme();SystemClock.sleep(700)
        check(active()=="custom_mint" && KeyboardColorGroups.activeId()==group.id)
        shell("cmd uimode night no");await("System day group") { active().startsWith("custom_mint") }
        nav("custom_pink");ThemeManager.refreshActiveTheme();SystemClock.sleep(700);check(active()=="custom_pink")
        shell("cmd uimode night yes");await("Next transition still follows") { active().startsWith("custom_pink") }
        KeyboardColorGroups.deactivate()
        pass("system day/night group transitions, manual opposite palette override and refresh without stealing selection")

        val probe=File(fixture,"symbolprobe.trime.yaml")
        probe.appendText("\n  night:\n    name: Night probe\n    light_scheme: default\n    back_color: 0xff152030\n    text_color: 0xffeeeeee\n    key_back_color: 0xff203040\n    key_text_color: 0xffeeeeee\n")
        main { pref.normalModeColor.setValue("default");pref.nightModeColor.setValue("night") }
        ThemeManager.selectTheme("symbolprobe.trime",forceReload=true,layoutMode=CustomKeyboards.MODE_THEME,requireExact=true)
        main { ColorManager.setColorScheme("default");check(ColorManager.activeColorScheme==ThemeManager.activeTheme.presetColorSchemes.getValue("default")) }
        ThemeManager.refreshActiveTheme()
        check(ColorManager.activeColorScheme==ThemeManager.activeTheme.presetColorSchemes.getValue("default"))
        shell("cmd uimode night no");SystemClock.sleep(400);shell("cmd uimode night yes")
        await("Traditional automatic night after override") { ColorManager.activeColorScheme==ThemeManager.activeTheme.presetColorSchemes.getValue("night") }
        pass("traditional theme manual day color at night persists through refresh; next real system change restores night")

        // Use synthetic local statistics only; never screenshot/export the user's words.
        main {
            val store=member(InputStats,"store")!!
            (member(store,"schemas") as MutableMap<*,*>).clear();(member(store,"schemaNames") as MutableMap<*,*>).clear()
            InputStats.setEnabled(true);InputStats.activeSchemaId="premium_probe"
            InputStats.setWordCloudEditor(android.view.inputmethod.EditorInfo().apply { inputType=1 })
        }
        repeat(12) { i->InputStats.onCommit(listOf("你好","春风","山海","日常","输入","轻盈")[i%6],"ab","premium_probe","词云测试") }
        val before=InputStats.read("premium_probe") { it.daily.wordCounts.toMap() }
        main { InputStats.setWordCloudEditor(android.view.inputmethod.EditorInfo().apply { inputType=0x81 }) }
        InputStats.onCommit("密码不留词云","ab","premium_probe","词云测试")
        check(InputStats.read("premium_probe") { it.daily.wordCounts }==before)
        main { InputStats.setWordCloudEditor(android.view.inputmethod.EditorInfo().apply { inputType=1 }) }
        val stats=launch(InputStatsActivity::class.java) as FragmentActivity
        try {
            await("Cloud measured") { (stats.findViewById<View>(R.id.stats_word_cloud)?.width ?: 0) > 0 }
            main {
                val cloud=stats.findViewById<WordCloudView>(R.id.stats_word_cloud)
                val words=member(cloud,"placed") as List<*>
                check(words.isNotEmpty())
                val boxes=words.map { member(it!!,"box") as RectF }
                boxes.forEachIndexed { i,a->boxes.drop(i+1).forEach { b->check(!RectF.intersects(a,b)) } }
                stats.findViewById<androidx.core.widget.NestedScrollView>(R.id.stats_scroll).scrollTo(0,stats.findViewById<View>(R.id.stats_card_cloud).top)
            };shot("word-cloud-real-ui")
            val modules=listOf(R.id.stats_card_overview,R.id.stats_card_cloud,R.id.stats_card_heat).map { stats.findViewById<View>(it) }
            for((name,chosen) in listOf("cloud-only" to listOf(modules[1]),"heat-only" to listOf(modules[2]),"combined" to modules)) {
                var sheet:View?=null;main { sheet=StatsImageShare.composeModules(stats,chosen,"今日 · 词云测试") }
                val file=withContext(Dispatchers.Main) { StatsImageShare.render(stats,sheet!!) }
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeFile(file.path,options)
                check(options.outWidth in 1..1080 && options.outHeight>0)
                file.copyTo(File(context.cacheDir,"v325-share-$name.png"),true)
            }
            main {
                stats.javaClass.getDeclaredMethod("showShareDialog").apply { isAccessible=true }.invoke(stats)
                val windows=android.view.inspector.WindowInspector.getGlobalWindowViews()
                check(windows.flatMap(::views).filterIsInstance<ListView>().any { it.isShown && it.adapter.count==7 })
            }
            pass("word-cloud real layout has no overlaps; password editor excluded; cloud-only/heat-only/combined share each produce one decoded PNG; seven-module picker shown")
        } finally { main { stats.finish() } }
    }
}
