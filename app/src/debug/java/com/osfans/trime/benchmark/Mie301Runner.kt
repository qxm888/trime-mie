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
import androidx.navigation.fragment.NavHostFragment
import com.osfans.trime.R
import com.osfans.trime.data.keymap.*
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ime.voice.builtin.*
import com.osfans.trime.ui.main.*
import org.json.JSONObject
import java.io.File

/** Generated fixtures only. No microphone, real ASR request, IME switch, or existing layout edits. */
class Mie301Runner : Instrumentation() {
    private val folder get()=File(targetContext.filesDir,"mie301-ui").apply { mkdirs() }
    private var cleanupId: String? = null
    override fun onCreate(args: Bundle?) { cleanupId=args?.getString("cleanupId"); super.onCreate(args); start() }
    private fun pass(text: String)=sendStatus(0,Bundle().apply { putString("case","PASS: $text") })
    private fun main(block: ()->Unit) {
        var error: Throwable?=null
        runOnMainSync { try { block() } catch(e: Throwable) { error=e } }
        error?.let { throw it }
    }
    override fun onStart() {
        val result=Bundle()
        try {
            cleanupId?.let { id -> if(CustomKeyboards.get(id)?.name=="界面检查临时布局") { CustomKeyboards.delete(id);CustomKeyboards.flush() } }
            bindingSerialization(); protocol(); alignment(); shadow(); pages()
            result.putString("result","PASS")
        } catch(e: Throwable) {
            result.putString("result","FAIL: ${e.message}"); result.putString("trace",e.stackTraceToString().take(5000))
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
    private fun bindingSerialization() {
        val preset=com.osfans.trime.data.theme.model.PresetKey(send="Mode_switch",toggle="ascii_mode",states=listOf("中文","西文"))
        check(BindingActions.resolve(BindingActions.token(preset))==preset)
        check(BindingActions.resolve(BindingActions.keyboard(".default","返回主键盘"))?.select==".default")
        val serializer=com.osfans.trime.data.theme.model.MaybeStringListSerializer
        val json=kotlinx.serialization.json.Json
        listOf(com.osfans.trime.data.theme.model.MaybeStringList.Empty,
            com.osfans.trime.data.theme.model.MaybeStringList.Scalar("字体.ttf"),
            com.osfans.trime.data.theme.model.MaybeStringList.Sequence(listOf("甲.ttf","乙.ttf"))).forEach { value ->
                check(json.decodeFromString(serializer,json.encodeToString(serializer,value))==value)
            }
        val yaml=com.charleskorn.kaml.Yaml.default
        check(yaml.decodeFromString(com.osfans.trime.data.theme.model.PresetKey.serializer(),"states:\n").states.isEmpty())
        check(yaml.decodeFromString(com.osfans.trime.data.theme.model.PresetKey.serializer(),"states: scalar\n").states.isEmpty())
        pass("action states and scalar/list/empty fonts serialize in JSON; lenient YAML remains compatible")
    }
    private fun protocol() {
        val creds=TencentProtocol.Credentials("1234567","fixture-secret-id","fixture-secret-key")
        val url=TencentProtocol.streamUrl(creds,"16k_zh",1700000000,"fixture-voice",123456)
        check(url.endsWith("signature=IhInaWNpLDvGgp8ZWCVGuvWczHY%3D"))
        val flash=TencentProtocol.flashRequest(creds,"16k_zh",1700000000)
        check(flash.second=="ptnNL6rs2ll5HippuEqA0Pr9sio=")
        val stream=TencentProtocol.Transcript()
        check(stream.accept(JSONObject("""{"result":{"index":0,"slice_type":1,"voice_text_str":"你"}}"""))=="你")
        check(stream.accept(JSONObject("""{"result":{"index":0,"slice_type":2,"voice_text_str":"你好"}}"""))=="你好")
        check(stream.accept(JSONObject("""{"result":{"index":1,"slice_type":1,"voice_text_str":"世界"}}"""))=="你好世界")
        check(stream.accept(JSONObject("""{"result":{"index":1,"slice_type":2,"voice_text_str":"世界"}}"""))=="你好世界")
        check(stream.accept(JSONObject("""{"final":1}"""))=="你好世界")
        val sentence=TencentProtocol.Transcript()
        check(sentence.accept(JSONObject("""{"sentences":{"sentence_id":0,"sentence_type":1,"sentence":"你好"}}"""))=="你好")
        check(sentence.accept(JSONObject("""{"sentences":{"sentence_id":0,"sentence_type":1,"sentence":"你好"}}"""))=="你好")
        check(sentence.accept(JSONObject("""{"sentences":{"sentence_id":1,"sentence_type":0,"sentence":"世界"}}"""))=="你好世界")
        check(TencentModels.categories.size==5 && TencentModels.categories.all { it.models.isNotEmpty() })
        pass("Tencent WS/flash signatures, both transcript formats, cumulative partials and duplicate frame handling")
    }
    private fun alignment() {
        val keys=mutableListOf(CustomKeyboards.CustomKey("A",20f),CustomKeyboards.CustomKey("B",20f))
        val row=CustomKeyboards.CustomRow(keys,48)
        val expected=mapOf("left" to listOf(20f,20f,60f),"center" to listOf(30f,20f,20f,30f),"right" to listOf(60f,20f,20f),"distributed" to listOf(20f,60f,20f))
        expected.forEach { (mode,widths)-> row.alignment=mode; val slots=ButtonAlignment.slots(row);check(slots.map { it.second }==widths);check(slots.filter { it.first!=null }.map { it.first!!.label }==listOf("A","B")) }
        val kb=CustomKeyboards.CustomKeyboard(name="fixture",rows=mutableListOf(row),toolbarAlignment="center")
        val yaml=CustomKeyboardYaml.encode(mapOf("custom_alignment_fixture" to kb))
        val copy=CustomKeyboardYaml.decode(yaml).values.single()
        check(copy.toolbarAlignment=="center" && copy.rows.single().alignment=="distributed")
        check(ButtonAlignment.slots(CustomKeyboards.CustomRow()).single().second==100f)
        val huge=CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey("A",90f),CustomKeyboards.CustomKey("B",90f)))
        check(kotlin.math.abs(ButtonAlignment.slots(huge).sumOf { it.second.toDouble() }-100)<0.001)
        pass("four row alignments, empty/oversized rows and YAML round-trip preserve toolbar/row settings")
    }
    private fun shadow() {
        main {
            val d=targetContext.resources.displayMetrics.density
            val w=(64*d).toInt(); val h=(56*d).toInt(); val pad=(24*d).toInt()
            val bitmap=Bitmap.createBitmap(w+pad*2,h+pad*2,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap); canvas.translate(pad.toFloat(),pad.toFloat())
            val surface=SurfaceDrawable(View(targetContext),null,SurfaceStyle(background="#FFFFFFFF",radius=14f),
                effects=KeyEffects(shadow=KeyShadow(enabled=true,blur=8f,offsetY=4f)),externalShadow=true)
            surface.setBounds(0,0,w,h); surface.drawOuterShadow(canvas); surface.draw(canvas)
            check(Color.alpha(bitmap.getPixel(pad-(2*d).toInt(),pad+h/2))>0)
            check(bitmap.getPixel(pad+w/2,pad+h/2)==Color.WHITE)
            File(folder,"toolbar-shadow.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
        pass("rounded surface shadow extends beyond button bounds and stays behind its body")
    }
    private fun screenshot(name: String) {
        waitForIdleSync(); android.os.SystemClock.sleep(1500)
        uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
    }
    private fun open(type: Class<out Activity>,id: String?=null): Activity = startActivitySync(Intent(targetContext,type).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);if(id!=null) putExtra("id",id) })
    private fun views(view: View): List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
    private fun pages() {
        if(com.osfans.trime.ui.setup.SetupActivity.shouldShowUp()) { val setup=open(com.osfans.trime.ui.setup.SetupActivity::class.java);main { setup.finish() };waitForIdleSync() }
        val home=open(MainActivity::class.java) as MainActivity
        android.os.SystemClock.sleep(6000); screenshot("首页")
        main {
            val tree=views(home.window.decorView)
            val toolbar=tree.filterIsInstance<androidx.appcompat.widget.Toolbar>().first()
            val search=tree.filterIsInstance<TextView>().first { it.text.toString()=="搜索" }
            val a=IntArray(2);val b=IntArray(2);toolbar.getLocationOnScreen(a);search.getLocationOnScreen(b)
            check(b[1]>=a[1]+toolbar.height)
            val nav=(home.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController
            nav.navigate(NavigationRoute.SettingsPage("search"))
        }
        screenshot("搜索")
        main { home.finish() };waitForIdleSync()
        val voice=open(VoiceSettingsActivity::class.java) as VoiceSettingsActivity
        screenshot("语音入口")
        main {
            val field=PersonalizationActivity::class.java.getDeclaredField("body").apply { isAccessible=true }
            (field.get(voice) as ViewGroup).removeAllViews()
            VoiceSettingsActivity::class.java.getDeclaredMethod("renderOnline",String::class.java).apply { isAccessible=true }.invoke(voice,"tencent")
        }
        screenshot("腾讯语音设置")
        main {
            val field=PersonalizationActivity::class.java.getDeclaredField("body").apply { isAccessible=true }
            val body=field.get(voice) as android.widget.LinearLayout
            body.removeAllViews()
            body.addView(TextView(voice).apply { text="动效检查（模拟音量，无麦克风）" })
            val phases=listOf(com.osfans.trime.ime.voice.VoiceRecordingState.Phase.RECORDING,
                com.osfans.trime.ime.voice.VoiceRecordingState.Phase.PROCESSING,
                com.osfans.trime.ime.voice.VoiceRecordingState.Phase.LOADING_MODEL,
                com.osfans.trime.ime.voice.VoiceRecordingState.Phase.CONNECTING)
            phases.forEach { phase ->
                body.addView(TextView(voice).apply { text=phase.name })
                body.addView(com.osfans.trime.ime.bar.ui.VoiceRecordingView(voice,0xFF91495D.toInt()).apply {
                    update(com.osfans.trime.ime.voice.VoiceRecordingState(phase==com.osfans.trime.ime.voice.VoiceRecordingState.Phase.RECORDING,0.8f,phase))
                },android.widget.LinearLayout.LayoutParams(-1,(64*voice.resources.displayMetrics.density).toInt()))
            }
        }
        screenshot("语音状态栏动效")
        main { voice.finish() };waitForIdleSync()
        val marker=File(targetContext.cacheDir,"mie301-fixture")
        if(marker.isFile) {
            val previous=marker.readText()
            if(CustomKeyboards.get(previous)?.name=="界面检查临时布局") CustomKeyboards.delete(previous)
        }
        val id=CustomKeyboards.create("界面检查临时布局")
        marker.writeText(id)
        try {
            CustomKeyboards.mutate(id) { kb ->
                kb.rows.clear();kb.toolbarAlignment="distributed"
                ButtonAlignment.choices.keys.forEach { mode -> kb.rows.add(CustomKeyboards.CustomRow(mutableListOf(
                    CustomKeyboards.CustomKey("",20f,background=CustomBackground(CustomBackground.Type.TRANSPARENT)),
                    CustomKeyboards.CustomKey("A",20f,bindings=mutableMapOf("click" to "a")),CustomKeyboards.CustomKey("B",20f,bindings=mutableMapOf("click" to "b"))),48,mode)) }
            }
            val editor=open(CustomKeyboardEditActivity::class.java,id) as CustomKeyboardEditActivity
            screenshot("行对齐与预览")
            main { CustomKeyboardEditActivity::class.java.getDeclaredMethod("showKeyEditor",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(editor,0,0) }
            waitForIdleSync()
            val bindingLabel=KeyBindingOverrides.EDITABLE_BEHAVIORS.first { it.first=="click" }.second.labelZh
            val label=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(bindingLabel).first { it.text?.toString()==bindingLabel }
            var target=label
            while(!target.isClickable) target=target.parent ?: error("binding row missing")
            check(target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            screenshot("空标签透明按键绑定")
            check(CustomKeyboards.get(id)!!.rows[0].keys[0].bindings.isEmpty())
            fun click(text: String) {
                var node=uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText(text).first { it.text?.toString()==text }
                while(!node.isClickable) node=node.parent ?: error("not clickable: $text")
                check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                waitForIdleSync();android.os.SystemClock.sleep(300)
            }
            click("键盘跳转");click("返回主键盘")
            screenshot("空标签透明按键已绑定")
            click(targetContext.getString(R.string.ok))
            val saved=CustomKeyboards.get(id)!!.rows[0].keys[0]
            check(saved.label.isEmpty() && BindingActions.resolve(saved.bindings.getValue("click"))?.select==".default")
            main { editor.finish() };waitForIdleSync()

        } finally { CustomKeyboards.delete(id);CustomKeyboards.flush();marker.delete() }
        pass("home/search top bounds, direct voice screen, Tencent form, blank transparent key binding picker and row editor")
    }
}
