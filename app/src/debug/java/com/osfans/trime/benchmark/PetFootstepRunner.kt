/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.osfans.trime.data.pets.PetCatalog
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.*
import com.osfans.trime.ui.pets.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.abs

/** Temporary test editor, never another app's text field. Compiled only into the debug APK. */
class PetFootstepFixtureActivity:Activity() {
    lateinit var editor:EditText
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(30,80,30,10) }
        root.addView(TextView(this).apply { text="键灵踩键真机验证 · 独立测试输入框";textSize=19f })
        editor=EditText(this).apply { inputType=InputType.TYPE_CLASS_TEXT;hint="只在此输入框验证，不读取其它应用文字";minLines=3 }
        root.addView(editor);setContentView(root)
        editor.requestFocus();editor.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT) },350)
    }
}

/** Host must snapshot/restore the independent debug IME's files around this runner. */
class PetFootstepRunner:Instrumentation() {
    private var demo=false
    override fun onCreate(args:Bundle?) { demo=args?.getString("demo")=="true";super.onCreate(args);start() }
    private val out by lazy { File(targetContext.filesDir,"pet-footstep-verification").apply { mkdirs() } }
    private fun main(block:()->Unit) { var failure:Throwable?=null;runOnMainSync { try { block() } catch(e:Throwable) { failure=e } };failure?.let { throw it } }
    private fun field(value:Any,name:String):java.lang.reflect.Field {
        var type:Class<*>?=value.javaClass
        while(type!=null) { try { return type.getDeclaredField(name).apply { isAccessible=true } } catch(_:NoSuchFieldException) {};type=type.superclass }
        error("Missing field $name")
    }
    private fun get(value:Any,name:String):Any? {
        var type:Class<*>?=value.javaClass
        while(type!=null) {
            try { return type.getDeclaredField(name).apply { isAccessible=true }.get(value) } catch(_:NoSuchFieldException) {}
            try { return type.getDeclaredMethod("get"+name.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(value) } catch(_:NoSuchMethodException) {}
            type=type.superclass
        }
        error("Missing member $name")
    }
    private fun await(label:String,condition:()->Boolean) {
        repeat(600) { var ok=false;main { ok=condition() };if(ok) return;SystemClock.sleep(75) };error(label)
    }
    private fun pass(label:String)=sendStatus(0,Bundle().apply { putString("case","PASS: $label") })
    private fun shot(name:String) { waitForIdleSync();SystemClock.sleep(250);uiAutomation.takeScreenshot()?.let { image -> File(out,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle() } }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    override fun onStart() {
        val result=Bundle();var activity:PetFootstepFixtureActivity?=null
        var original:PetHouse.State?=null;var flow:MutableStateFlow<PetHouse.State>?=null
        var controller:PetCompanionController?=null
        try {
            activity=startActivitySync(Intent(targetContext,PetFootstepFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PetFootstepFixtureActivity
            uiAutomation.executeShellCommand("ime set ${targetContext.packageName}/com.osfans.trime.ime.core.TrimeInputMethodService").use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() } }
            val host=checkNotNull(activity)
            main { host.editor.postDelayed({ (host.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager).apply { restartInput(host.editor);showSoftInput(host.editor,InputMethodManager.SHOW_IMPLICIT) } },350) }
            await("IME keyboard not ready") { KeyboardWindow.instance!=null && KeyboardWindow.currentKeyboardOrNull!=null }
            val service=get(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
            val input=get(service,"inputView") as InputView
            @Suppress("UNCHECKED_CAST") val state=get(PetHouse,"mutable") as MutableStateFlow<PetHouse.State>
            flow=state;original=state.value
            val fixture=original.copy(started=true,enabled=true,mode=2,miniExpanded=true,selected="xiaomie",pets=PetCatalog.species.map { PetHouse.Pet(it.id) },miniScale=100,miniX=.15f,miniY=.18f,error="")
            main { state.value=fixture;service.preparePetCompanion() }
            await("companion controller not created") { (get(service,"petCompanionDelegate") as Lazy<*>).isInitialized() }
            controller=(get(service,"petCompanionDelegate") as Lazy<*>).value as PetCompanionController
            val c=controller
            val mini=get(c,"mini") as PetMiniKeyboardView
            val popup=get(c,"popup") as PopupWindow
            await("mini keyboard not visible") { popup.isShowing && mini.width>0 && mini.height>0 }
            val normalWidth=mini.width
            val keyboard=KeyboardWindow.currentKeyboard
            @Suppress("UNCHECKED_CAST") val keys=get(mini,"keys") as List<PetMiniKeyboardView.MiniKey>
            check(keys.isNotEmpty())
            main {
                check(abs(mini.height.toFloat()/mini.width-PetMiniScene.HEIGHT/PetMiniScene.WIDTH)<.01f)
                check(PetMiniScene.BOARD_BOTTOM-keys.maxOf { it.bounds.bottom }<8f)
                check(PetMiniScene.HEIGHT-PetMiniScene.BOARD_BOTTOM==4f)
            }
            pass("real PopupWindow uses compact 264x156 scene; last-row bottom padding under 8 units")
            shot("01-小咩站姿-紧凑键盘")
            val targets=listOf(keys.first(),keys[keys.size/2],keys.last())
            for(species in PetCatalog.species) main {
                mini.bind(PetHouse.Pet(species.id));mini.setKeyboard(keyboard)
                targets.forEach { key ->
                    mini.press(key.index)
                    val animator=get(mini,"bodyAnimation") as ValueAnimator
                    animator.currentPlayTime=(animator.duration*.4f).toLong()
                    val stance=get(mini,"stance") as PetMiniStance
                    check(stance.lift>0f) { "${species.id} did not lift" }
                    animator.currentPlayTime=(animator.duration*.9f).toLong()
                    check(abs((get(mini,"soleX") as Float)-key.bounds.centerX())<.1f)
                    check(abs((get(mini,"soleY") as Float)-key.bounds.centerY())<.1f)
                    check(get(mini,"landingIndex")==key.index)
                    val image=Bitmap.createBitmap(792,468,Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(image);canvas.scale(792f/mini.width,468f/mini.height);mini.draw(canvas)
                    if(species.id in listOf("xiaomie","monkey","cat","panther") && key==targets[1]) File(out,"站姿-${species.id}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                    image.recycle();animator.end();mini.release(key.index);mini.clearPresses()
                }
            }
            pass("${PetCatalog.species.size} real-device character renders x 3 target keys; airborne pose and sole-to-key landing checked")
            main {
                mini.bind(PetHouse.Pet("xiaomie"));mini.clearPresses()
                val key=keys.first();mini.press(key.index)
                val stance=get(mini,"stance") as PetMiniStance;val foot=stance.leftFoot
                (get(mini,"bodyAnimation") as ValueAnimator).end();mini.release(key.index)
                mini.press(key.index);check(stance.leftFoot!=foot);check((get(mini,"bodyAnimation") as ValueAnimator).isStarted)
                (get(mini,"bodyAnimation") as ValueAnimator).end();mini.release(key.index)
            }
            pass("same-key repeats restart hopping and alternate supporting feet")
            for(i in 0 until 30) {
                main { val k=keys[(i*7)%keys.size];mini.press(k.index);mini.release(k.index) }
                SystemClock.sleep(90)
            }
            SystemClock.sleep(350)
            main { check((get(mini,"soleX") as Float).isFinite());check((get(mini,"soleY") as Float).isFinite());check((get(mini,"bodyAnimation") as? ValueAnimator)?.isRunning!=true) }
            pass("30 rapid cross-key presses settle without stale animations or non-finite positions")
            SystemClock.sleep(850)
            main { check(abs((get(mini,"soleX") as Float)-PetMiniScene.CENTER)<.1);check(abs((get(mini,"soleY") as Float)-PetMiniScene.BOARD_TOP)<.1) }
            pass("key release returns to the top edge and cancels active motion")
            // Real onscreen KeyView down/up, using Android's input dispatcher.
            val actualKeys=views(input.keyboardView).filterIsInstance<KeyView>()
            val letter=actualKeys.firstOrNull { v -> (get(v,"key") as Key).getCode(KeyBehavior.CLICK)==KeyEvent.KEYCODE_A }
                ?: actualKeys.first { v -> (get(v,"key") as Key).getCode(KeyBehavior.CLICK)==KeyEvent.KEYCODE_SPACE }
            val location=IntArray(2);var tx=0f;var ty=0f;var keyIndex=-1
            main { letter.getLocationOnScreen(location);tx=location[0]+letter.width/2f;ty=location[1]+letter.height/2f;keyIndex=keyboard.keys.indexOf(get(letter,"key") as Key) }
            val time=SystemClock.uptimeMillis()
            uiAutomation.injectInputEvent(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,tx,ty,0).also { it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN },true)
            SystemClock.sleep(90)
            main { @Suppress("UNCHECKED_CAST") val held=get(mini,"held") as Set<Int>;check(keyIndex in held) { "Real touch did not reach companion" } }
            uiAutomation.injectInputEvent(MotionEvent.obtain(time,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,tx,ty,0).also { it.source=android.view.InputDevice.SOURCE_TOUCHSCREEN },true)
            SystemClock.sleep(250);shot("02-真实按键落脚")
            pass("real touchscreen KeyView down/up reaches the companion")
            // Drag through the actual view gesture recognizer and its PopupWindow callback.
            var beforeX=0f;var beforeY=0f
            main {
                beforeX=get(c,"x") as Float;beforeY=get(c,"y") as Float
                val t=SystemClock.uptimeMillis()
                listOf(Triple(MotionEvent.ACTION_DOWN,90f,50f),Triple(MotionEvent.ACTION_MOVE,140f,100f),Triple(MotionEvent.ACTION_UP,140f,100f)).forEach { (action,x,y) -> val e=MotionEvent.obtain(t,t+50,action,x,y,0);mini.dispatchTouchEvent(e);e.recycle() }
            }
            SystemClock.sleep(200)
            main { check(abs((get(c,"x") as Float)-beforeX)>10 || abs((get(c,"y") as Float)-beforeY)>10) }
            pass("mini touch drag moves the actual popup and persists position")
            main { state.value=state.value.copy(miniScale=150) }
            await("scale 150 not applied") { mini.width>normalWidth*1.25f }
            shot("03-放大与拖动后")
            main { state.value=state.value.copy(miniScale=60) }
            SystemClock.sleep(250)
            main { check(popup.isShowing && mini.width<normalWidth*.8f) }
            pass("60% and 150% mini scale retain a visible clamped popup")
            main { c.hideInput() };main { check(!popup.isShowing);check((get(mini,"bodyAnimation") as? ValueAnimator)?.isRunning!=true) }
            pass("hiding input dismisses window and clears pending motion")
            main { c.showForInput(input,android.view.inputmethod.EditorInfo().apply { inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }) }
            main { check(!popup.isShowing) }
            pass("password editor suppresses companion")
            main { state.value=fixture.copy(selected="monkey",miniScale=100);c.showForInput(input,service.currentInputEditorInfo) }
            await("monkey companion did not reopen") { popup.isShowing }
            main { val k=keys.last();mini.press(k.index);(get(mini,"bodyAnimation") as ValueAnimator).end();mini.release(k.index) }
            shot("04-猴哥踩键")
            if(demo) {
                sendStatus(0,Bundle().apply { putString("stage","DEMO_READY") })
                SystemClock.sleep(4000)
                for(id in listOf("xiaomie","monkey")) {
                    main { state.value=state.value.copy(selected=id,miniScale=130) }
                    await("demo species not ready") { (get(mini,"pet") as PetHouse.Pet).id==id }
                    SystemClock.sleep(200)
                    @Suppress("UNCHECKED_CAST") val demoKeys=get(mini,"keys") as List<PetMiniKeyboardView.MiniKey>
                    repeat(14) { i ->
                        val k=demoKeys[(i*7)%demoKeys.size]
                        main { mini.press(k.index) };SystemClock.sleep(70)
                        main { mini.release(k.index) };SystemClock.sleep(170)
                    }
                }
                SystemClock.sleep(4000)
            }
            result.putString("result","PASS")
        } catch(e:Throwable) { result.putString("result","FAIL: "+android.util.Log.getStackTraceString(e));shot("FAIL") }
        finally {
            main { controller?.hideInput();original?.let { flow?.value=it };activity?.finish() }
            finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
        }
    }
}
