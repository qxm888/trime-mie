/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupWindow
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.appearance.BackdropBlur
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.pets.PetCompanionController
import com.osfans.trime.ui.pets.PetMiniKeyboardBase
import com.osfans.trime.ui.pets.PetMiniKeyboardView
import com.osfans.trime.ui.pets.PetTentacleMiniView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File

/** Own editor/save only: blur lifecycle, real key events and renderer switching. */
class JizaiAppearanceRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun member(value: Any, name: String): Any? {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) {}
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) {}
            type = type.superclass
        }
        error("Missing member $name")
    }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(400) { if (ui(condition)) return; SystemClock.sleep(30) }
        error(label)
    }
    private fun status(message: String) = sendStatus(0, Bundle().apply { putString("case", message) })
    private fun picture(name: String) {
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.filesDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun inject(action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
        try { check(uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
    }
    override fun onStart() = runBlocking {
        val result = Bundle()
        var own: SentenceLearningActivity? = null
        var fixture: SyntheticInputSchema? = null
        var blurRoot: FrameLayout? = null
        @Suppress("UNCHECKED_CAST")
        val pets = member(PetHouse,"mutable") as MutableStateFlow<PetHouse.State>
        val savedPets = pets.value
        try {
            waitForIdleSync()
            val prefs = AppPrefs.defaultInstance()
            ui { prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE); prefs.keyboard.oneHandMode.setValue(0); prefs.keyboard.floatingKeyboard.setValue(false) }
            own = startActivitySync(Intent(targetContext,SentenceLearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SentenceLearningActivity
            await("IME unavailable") { KeyboardWindow.instance != null }
            val ime = ui { member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService }
            await("Own editor unavailable") { ime.isInputViewShown && ime.currentInputConnection != null }
            fixture = SyntheticInputSchema.create(member(ime,"rime") as RimeSession,"的")
            var captures = 0
            val root = ui {
                FrameLayout(checkNotNull(own)).also {
                    blurRoot = it
                    checkNotNull(own).findViewById<FrameLayout>(android.R.id.content).addView(it,FrameLayout.LayoutParams(280,180))
                }
            }
            val blur = ui { BackdropBlur(root, active={true}, capture={ captures++;it.drawColor(Color.rgb(73,118,165)) }) }
            await("Initial blur missing") { captures>0 && member(blur,"front") != null }
            val before = ui { captures }
            repeat(8) { ui { root.invalidate() };SystemClock.sleep(40) }
            check(ui { captures } == before) { "Unchanged background recaptured every frame" }
            ui { root.visibility=View.INVISIBLE;BackdropBlur.invalidateBackground(root) }
            SystemClock.sleep(1600)
            check(ui { captures } == before)
            ui { root.visibility=View.VISIBLE }
            await("Pending blur did not resume on visibility") { captures>before && member(blur,"front")!=null }
            ui { (root.parent as FrameLayout).removeView(root) }
            check(ui { member(blur,"observedTree")==null && member(blur,"scheduled")==false })
            val detached = ui { captures }
            ui { checkNotNull(own).findViewById<FrameLayout>(android.R.id.content).addView(root,FrameLayout.LayoutParams(280,180)) }
            await("Blur reattach missing") { captures>detached && member(blur,"front")!=null }
            ui { (root.parent as FrameLayout).removeView(root) };blurRoot=null
            status("PASS_BLUR capture, unchanged-frame coalescing, hidden pending work, visibility resume, detach/reattach")
            val testState = PetHouse.State(started=true,enabled=true,mode=2,miniExpanded=true,selected="sheep",pets=listOf("sheep","jige","xiaomie","jizai").map { PetHouse.Pet(it) },miniX=.12f,miniY=.18f)
            ui { pets.value=testState;ime.preparePetCompanion() }
            await("Controller missing") { (member(ime,"petCompanionDelegate") as Lazy<*>).isInitialized() }
            val controller = ui { (member(ime,"petCompanionDelegate") as Lazy<*>).value as PetCompanionController }
            fun mini() = checkNotNull(member(controller,"mini")) as PetMiniKeyboardBase
            val popup = member(controller,"popup") as PopupWindow
            for ((index,id) in listOf("jizai","jige","xiaomie","sheep","jizai").withIndex()) {
                ui { pets.value=testState.copy(selected=id) }
                await("Renderer missing for $id") {
                    popup.isShowing && mini().isAttachedToWindow && mini().width>0 &&
                        (member(mini(),"pet") as PetHouse.Pet).id==id &&
                        (if(id=="jizai") mini() is PetTentacleMiniView else mini() is PetMiniKeyboardView)
                }
                val current=ui { mini() }
                if(id=="jizai") {
                    check(ui { member(current,"ticking")==true })
                    val keyboard=ui { member(KeyboardWindow.instance!!,"visibleKeyboardView") as View }
                    for(code in listOf(KeyEvent.KEYCODE_H,KeyEvent.KEYCODE_SPACE)) {
                        val key=ui { KeyboardWindow.currentKeyboardOrNull!!.keys.first { it.code==code } }
                        val at=ui { IntArray(2).also { keyboard.getLocationOnScreen(it) } }
                        val x=at[0]+key.x+key.width/2f;val y=at[1]+key.y+key.height/2f
                        val down=SystemClock.uptimeMillis()
                        try {
                            inject(MotionEvent.ACTION_DOWN,x,y,down)
                            await("Actual key did not reach tentacles") {
                                val held=member(current,"held") as Map<*,*>
                                held.isNotEmpty() && listOf("left","right").any { member(checkNotNull(member(current,it)),"index")==KeyboardWindow.currentKeyboardOrNull!!.keys.indexOf(key) }
                            }
                            SystemClock.sleep(80)
                            if(code==KeyEvent.KEYCODE_SPACE) ui {
                                val left=checkNotNull(member(current,"left"));val right=checkNotNull(member(current,"right"))
                                check(member(left,"index")==member(right,"index"))
                                check((member(left,"pressure") as Float)>.9f && (member(right,"pressure") as Float)>.9f)
                            }
                            picture("jizai-$index-$code")
                        } finally { inject(MotionEvent.ACTION_UP,x,y,down) }
                        await("Tentacle release stuck") { (member(current,"held") as Map<*,*>).isEmpty() }
                    }
                    val times=ui {
                        val bitmap=Bitmap.createBitmap(current.width,current.height,Bitmap.Config.ARGB_8888)
                        val canvas=Canvas(bitmap)
                        val samples=List(30) { val start=SystemClock.elapsedRealtimeNanos();current.draw(canvas);(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0 }.sorted()
                        File(targetContext.filesDir,"jizai-mini-preview-$index.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                        }
                        bitmap.recycle();samples
                    }
                    status("DRAW_COST id=jizai medianMs=${times[15]} p95Ms=${times[28]} (offscreen draw, not end-to-end input latency)")
                    ui { controller.hideInput() }
                    await("Hidden mini did not stop its idle loop") {
                        !popup.isShowing && !current.isAttachedToWindow &&
                            member(current,"ticking")==false && (member(current,"held") as Map<*,*>).isEmpty()
                    }
                    ui { controller.showForInput(checkNotNull(member(ime,"inputView")) as View,ime.currentInputEditorInfo) }
                    await("Mini did not return") { popup.isShowing && member(current,"ticking")==true }
                    status("PASS_TENTACLES actual H and space keys, six-arm space press, release, hide/reshow idle lifecycle")
                } else picture("existing-$id")
                status("PASS_RENDERER_SWITCH $id")
            }
            result.putString("result","PASS")
        } catch(error:Throwable) {
            result.putString("result","FAIL");result.putString("failure",android.util.Log.getStackTraceString(error))
        } finally {
            ui { blurRoot?.let { (it.parent as? FrameLayout)?.removeView(it) };pets.value=savedPets }
            runCatching { fixture?.restore() }
            ui { own?.finish() }
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
