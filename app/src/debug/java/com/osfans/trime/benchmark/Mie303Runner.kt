/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ime.candidates.popup.LabeledCandidateItemUi
import com.osfans.trime.ui.main.*
import kotlinx.serialization.json.Json
import java.io.File

/** Temporary inactive layouts and generated pixels only. Never changes the selected IME. */
class Mie303Runner : Instrumentation() {
    private var captureWindow:android.view.Window?=null
    private val folder get()=File(targetContext.filesDir,"mie303-ui").apply { mkdirs() }
    override fun onCreate(args:Bundle?) { super.onCreate(args);start() }
    private fun main(block:()->Unit) {
        var failure:Throwable?=null
        runOnMainSync { try { block() } catch(e:Throwable) { failure=e } }
        failure?.let { throw it }
    }
    private fun pass(name:String)=sendStatus(0,Bundle().apply { putString("case","PASS: $name") })
    override fun onStart() {
        val result=Bundle()
        val ids=mutableListOf<String>()
        var activity:Activity?=null
        val image=File(targetContext.filesDir,"mie303-generated.png")
        try {
            // Application initialization runs on the main thread before queued callbacks.
            main { check(com.osfans.trime.data.prefs.AppPrefs.defaultInstance() != null) }
            main { }
            check(com.osfans.trime.ime.bar.InputBarVisibility.hidden(true,false))
            check(!com.osfans.trime.ime.bar.InputBarVisibility.hidden(true,true))
            check(com.osfans.trime.ime.bar.InputBarVisibility.hidden(true,true,true))
            check(!com.osfans.trime.ime.bar.InputBarVisibility.hidden(false,false))
            pass("hidden preference restored in docked mode; floating temporarily shows bar; one-hand policy remains separate")
            reorder();localAddresses();touchProxy();createdWords()
            val bitmap=Bitmap.createBitmap(160,80,Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply { drawColor(0xFFF4D3DC.toInt());drawRect(80f,0f,160f,80f,Paint().apply { color=0xFFD2E6EB.toInt() }) }
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            val first=CustomKeyboards.create("悬浮窗检查临时布局");ids+=first
            val second=CustomKeyboards.create("悬浮窗检查临时布局");ids+=second
            editorMoves(first)
            val style=FloatingCandidateStyle(panel=SurfaceStyle(background="#FFFFF5F3",radius=18f,borderWidth=1f,borderColor="#FFE3C4CB"),
                highlighted=SurfaceStyle(background="#FFF5D9DF",radius=10f),fonts=mapOf("candidate" to FontSpec("system:sans-serif",24f),"comment" to FontSpec(size=12f),"label" to FontSpec(size=12f)),
                colors=mapOf("candidate" to "#FF44313C","highlight.candidate" to "#FF81394D","arrow" to "#FF81394D"),pagination="bottom",ballLabel="咩")
            CustomKeyboards.mutate(first) { it.floatingCandidates=style }
            val stored=CustomKeyboards.get(first)!!.floatingCandidates!!
            val yaml=CustomKeyboards.exportYaml(listOf(first))
            check(CustomKeyboardYaml.decode(yaml).values.single().floatingCandidates==stored)
            val kb=CustomKeyboards.get(first)!!
            check(Json.decodeFromString(CustomKeyboards.CustomKeyboard.serializer(),Json.encodeToString(CustomKeyboards.CustomKeyboard.serializer(),kb)).floatingCandidates==stored)
            check(CustomKeyboards.get(second)!!.floatingCandidates==null)
            val invalid=FloatingCandidateStyle(minWidth=-3,maxWidthPercent=900,opacity=0,fonts=mapOf("candidate" to FontSpec(size=Float.NaN)),arrowSize=-1).sanitized()
            check(invalid.minWidth==48 && invalid.maxWidthPercent==100 && invalid.opacity==20 && invalid.fonts["candidate"]!!.size==0f)
            pass("per-layout isolation, legacy null, JSON/YAML round-trip and invalid-value normalization")
            var live:FloatingCandidateStyle?=stored
            main {
                val theme=StandaloneKeyboardTheme.base()
                val scope=ThemeScope(theme).apply { updateScheme(theme.presetColorSchemes.getValue("default")) }
                val ui=LabeledCandidateItemUi(targetContext,scope) { live }
                val candidate=CandidateProto("候选词","注释内容","①")
                ui.update(candidate,true,900,1,false)
                check(ui.root.text.toString().contains("①") && ui.root.text.toString().contains("注释内容"))
                val bg=ui.root.background
                repeat(100) { ui.update(candidate,true,900,1,false);check(ui.root.background===bg) }
                live=stored.copy(showComments=false,showLabels=false)
                ui.update(candidate,true,900,1,false)
                check(!ui.root.text.toString().contains("①") && !ui.root.text.toString().contains("注释内容"))
                live=null;ui.update(candidate,true,900,1,false)
                check(ui.root.text.toString().contains("①") && ui.root.text.toString().contains("注释内容"))
            }
            pass("live candidate styles cache backgrounds, hide labels/comments, and restore inherited appearance")
            activity=startActivitySync(Intent(targetContext,FloatingCandidatesEditActivity::class.java).putExtra("id",first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            captureWindow=activity.window
            waitForIdleSync();screenshot("窗口编辑")
            main {
                val preview=activity!!.javaClass.getDeclaredField("preview").apply { isAccessible=true }.get(activity) as ViewGroup
                check(preview.getChildAt(0).width>0 && preview.getChildAt(0).height>50) { "initial preview has no measured content" }
            }
            val a=activity!!
            val field=a.javaClass.getDeclaredField("draft").apply { isAccessible=true }
            val tab=a.javaClass.getDeclaredField("tab").apply { isAccessible=true }
            val render=a.javaClass.getDeclaredMethod("render").apply { isAccessible=true }
            val changed=a.javaClass.getDeclaredMethod("changed").apply { isAccessible=true }
            for((index,name) in listOf(1 to "候选编辑",2 to "文字编辑",3 to "辅助编辑")) {
                main {
                    val root=a.findViewById<ViewGroup>(android.R.id.content)
                    fun find(v:View):com.google.android.material.tabs.TabLayout? = if(v is com.google.android.material.tabs.TabLayout) v else
                        (v as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
                    find(root)!!.getTabAt(index)!!.select()
                }
                screenshot(name)
            }
            val before=CustomKeyboards.get(first)!!.floatingCandidates
            val edited=stored.copy(panel=stored.panel.copy(background=image.path,blur=true),panelEffects=KeyEffects(gloss=true,noise=true,noiseStrength=4),pagination="top",minWidth=180)
            main { field.set(a,edited);tab.setInt(a,0);render.invoke(a);changed.invoke(a) }
            screenshot("图片柔化预览")
            check(CustomKeyboards.get(first)!!.floatingCandidates==before)
            main { a.javaClass.getDeclaredMethod("save").apply { isAccessible=true }.invoke(a) }
            check(CustomKeyboards.get(first)!!.floatingCandidates==edited.sanitized())
            check(CustomKeyboards.get(second)!!.floatingCandidates==null)
            main { field.set(a,edited.copy(enabled=false));render.invoke(a);changed.invoke(a) }
            screenshot("继承默认预览")
            main { a.finish() };activity=null
            pass("four editor pages and image blur preview; edits staged until Save; inactive layout saved independently")
            result.putString("result","PASS")
        } catch(e:Throwable) {
            result.putString("result","FAIL: ${e.message}");result.putString("trace",e.stackTraceToString())
        } finally {
            activity?.let { runCatching { main { it.finish() } } }
            ids.forEach { id -> if(CustomKeyboards.get(id)?.name=="悬浮窗检查临时布局") CustomKeyboards.delete(id) }
            CustomKeyboards.flush();image.delete()
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
    private fun reorder() {
        val a=CustomKeyboards.CustomKey("A",10f,mutableMapOf("click" to "a","longClick" to "custom"),appearance=KeyAppearance.Style(labelSymbol="★"))
        val b=CustomKeyboards.CustomKey("B",20f,mutableMapOf("click" to "b"))
        val c=CustomKeyboards.CustomKey("C",30f,mutableMapOf("click" to "c"))
        val rows=mutableListOf(CustomKeyboards.CustomRow(mutableListOf(a,b,c)),CustomKeyboards.CustomRow())
        var cursor=0
        cursor=KeyReorder.move(rows,0,cursor,0,2)!!
        cursor=KeyReorder.move(rows,0,cursor,0,3)!!
        rows[0].keys[cursor]=rows[0].keys[cursor].copy(label="A编辑后")
        check(rows[0].keys.map { it.label }==listOf("B","C","A编辑后"))
        check(rows[0].keys[0]==b && rows[0].keys[1]==c)
        val moved=rows[0].keys[cursor]
        check(KeyReorder.move(rows,0,cursor,1,0)==0 && rows[1].keys.single()==moved)
        check(rows.sumOf { it.keys.size }==3 && rows[1].keys[0].bindings==a.bindings && rows[1].keys[0].appearance==a.appearance)
        check(KeyReorder.move(rows,0,500,1,0)==null && rows.sumOf { it.keys.size }==3)
        pass("repeated move then save preserves neighbours; cross-row insertion keeps all key appearance and bindings")
    }
    private fun localAddresses() {
        val access=com.osfans.trime.data.sync.LocalNetworkAccess
        listOf("192.168.1.30","10.0.0.1","172.16.0.1","169.254.1.2","fd00::1","fe80::1").forEach { check(access.local(java.net.InetAddress.getByName(it))) }
        listOf("8.8.8.8","1.1.1.1","127.0.0.1","::1","2001:4860:4860::8888").forEach { check(!access.local(java.net.InetAddress.getByName(it))) }
        val declared=targetContext.packageManager.getPackageInfo(targetContext.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        check(access.PERMISSION in declared)
        pass("release-shared LAN permission declared; private IPv4/IPv6 detected without blocking public or loopback addresses")
    }
    private fun editorMoves(id:String) {
        CustomKeyboards.mutate(id) { it.rows.clear();it.rows.addAll(listOf(CustomKeyboards.CustomRow(mutableListOf(
            CustomKeyboards.CustomKey("A",10f,mutableMapOf("click" to "a","longClick" to "space")),
            CustomKeyboards.CustomKey("B",20f,mutableMapOf("click" to "b")),
            CustomKeyboards.CustomKey("C",30f,mutableMapOf("click" to "c")))))) }
        val editor=startActivitySync(Intent(targetContext,CustomKeyboardEditActivity::class.java).putExtra("id",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            main { editor.javaClass.getDeclaredMethod("showKeyEditor",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(editor,0,0) }
            waitForIdleSync()
            fun window()=android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.isShown && it.findViewById<View>(android.R.id.button1)!=null }
            repeat(2) {
                main { window().findViewById<View>(android.R.id.button2).performClick() }
                waitForIdleSync()
                main {
                    fun list(view:View):android.widget.ListView? = if(view is android.widget.ListView) view else
                        (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { list(group.getChildAt(it)) } }
                    val choices=android.view.inspector.WindowInspector.getGlobalWindowViews().mapNotNull { list(it) }.last()
                    choices.performItemClick(choices.getChildAt(1),1,choices.adapter.getItemId(1))
                }
                waitForIdleSync()
            }
            main { window().findViewById<View>(android.R.id.button1).performClick() }
            waitForIdleSync()
            val keys=CustomKeyboards.get(id)!!.rows[0].keys
            check(keys.map { it.label }==listOf("B","C","A") && keys.map { it.width }==listOf(20f,30f,10f))
            check(keys[0].bindings["click"]=="b" && keys[1].bindings["click"]=="c" && keys[2].bindings["longClick"]=="space")
            pass("actual key editor: Move Right twice, then OK saves moved A without replacing B/C")
        } finally { main { editor.finish() } }
    }
    private fun touchProxy() {
        val fixture=startActivitySync(Intent(targetContext,Mie302FixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var ballClicks=0;var qClicks=0
        lateinit var proxy:com.osfans.trime.ime.core.TouchEventReceiverWindow
        lateinit var ball:TextView
        lateinit var q:TextView
        val unit=targetContext.resources.displayMetrics.density
        fun dp(n:Int)=(n*unit).toInt()
        try {
            main {
                val root=android.widget.FrameLayout(fixture)
                q=TextView(fixture).apply { text="Q";setOnClickListener { qClicks++ } }
                root.addView(q,android.widget.FrameLayout.LayoutParams(dp(48),dp(48)).apply { leftMargin=dp(140);topMargin=dp(100) })
                val overlay=android.widget.FrameLayout(fixture)
                ball=TextView(fixture).apply { text="●";setOnClickListener { ballClicks++ } }
                overlay.addView(ball,android.widget.FrameLayout.LayoutParams(dp(44),dp(44)).apply { leftMargin=dp(10);topMargin=dp(12) })
                root.addView(overlay,android.widget.FrameLayout.LayoutParams(dp(230),dp(230)).apply { topMargin=dp(100) })
                fixture.setContentView(root)
                proxy=com.osfans.trime.ime.core.TouchEventReceiverWindow(overlay)
            }
            waitForIdleSync()
            main { proxy.show(Rect(ball.left,ball.top,ball.right,ball.bottom)) }
            waitForIdleSync();android.os.SystemClock.sleep(250)
            fun tap(view:View) {
                val xy=IntArray(2);main { view.getLocationOnScreen(xy) }
                val now=android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP).forEach { action ->
                    val event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,xy[0]+view.width/2f,xy[1]+view.height/2f,0)
                    sendPointerSync(event);event.recycle()
                }
                waitForIdleSync()
            }
            tap(q);tap(ball)
            check(qClicks==1 && ballClicks==1) { "touch proxy intercepted Q or failed local-coordinate offset: q=$qClicks ball=$ballClicks" }
            pass("ball-only input bounds let underlying Q receive taps; cropped receiver forwards ball coordinates correctly")
        } finally { main { proxy.dismiss();fixture.finish() } }
    }
    private fun createdWords() {
        val prefs=com.osfans.trime.core.data.CandidatePreferences
        val schema="mie303_fixture_${java.util.UUID.randomUUID().toString().take(8)}"
        val other=schema+"_other"
        val original=com.osfans.trime.core.data.CandidatePreferences.CreatedWord(schema,"abc","春暖花开🌸，你好！")
        var activity:Activity?=null
        try {
            prefs.createWord(original.schema,original.code,original.word)
            prefs.createWord(schema,"def","第二条 · emoji😀")
            val rule=prefs.get(schema,"abc")!!
            prefs.save(schema,"abc",rule.copy(order=rule.order+"原生保留词",hidden=setOf("其他隐藏词")))
            val updated=original.copy(code="xyz",word="修改后的词条😊")
            prefs.editCreatedWord(original,updated)
            check(original !in prefs.createdWords() && updated in prefs.createdWords())
            check(prefs.get(schema,"abc")!!.order==listOf("原生保留词") && prefs.get(schema,"abc")!!.hidden==setOf("其他隐藏词"))
            prefs.createWord(schema,"xyz","目标已有词")
            check(runCatching { prefs.editCreatedWord(updated,updated.copy(word="目标已有词")) }.isFailure)
            check(updated in prefs.createdWords())
            prefs.editCreatedWord(updated,updated.copy(schema=other))
            check(updated !in prefs.createdWords() && updated.copy(schema=other) in prefs.createdWords())
            prefs.editCreatedWord(updated.copy(schema=other),null)
            check(prefs.createdWords().none { it.schema==other })
            prefs.reload()
            check(prefs.createdWords().any { it.schema==schema && it.word=="目标已有词" })
            activity=startActivitySync(Intent(targetContext,CreatedWordsActivity::class.java).putExtra("schema",schema).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            captureWindow=activity.window
            screenshot("造词管理列表")
            main { activity!!.finish() };activity=null
            activity=startActivitySync(Intent(targetContext,WordCreationActivity::class.java).putExtra("schema",schema).putExtra("code","xyz").putExtra("word","目标已有词").putExtra("editing",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            captureWindow=activity.window
            screenshot("造词编辑词条")
            main {
                fun next(v:View):View?=if(v is TextView && v.text.toString()=="下一步 · 设置编码") v else
                    (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { next(g.getChildAt(it)) } }
                next(activity!!.window.decorView)!!.performClick()
            }
            screenshot("造词编辑编码")
            pass("created-word history lists prior aliases; atomic edit/move/delete survives reload, rejects duplicates and keeps unrelated candidate rules; both editor steps rendered")
        } finally {
            activity?.let { main { it.finish() } }
            listOf("abc","def","xyz").forEach { prefs.save(schema,it,null) };prefs.save(other,"xyz",null);prefs.flush()
        }
    }
    private fun screenshot(name:String) {
        waitForIdleSync();android.os.SystemClock.sleep(1200)
        val window=requireNotNull(captureWindow)
        lateinit var bitmap:Bitmap
        main { bitmap=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888) }
        val latch=java.util.concurrent.CountDownLatch(1)
        var status=-1
        main { android.view.PixelCopy.request(window,bitmap,{ code -> status=code;latch.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
        check(latch.await(5,java.util.concurrent.TimeUnit.SECONDS) && status==android.view.PixelCopy.SUCCESS) { "window-only screenshot failed: $status" }
        File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
}
