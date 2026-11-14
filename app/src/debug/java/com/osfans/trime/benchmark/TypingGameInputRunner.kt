/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.RectF
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.game.FallingWord
import com.osfans.trime.ime.game.TypingGame
import com.osfans.trime.ime.game.TypingGameActivity
import com.osfans.trime.ime.game.TypingGameEffects
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.io.File

/** Real system touch injection -> keyboard -> Rime -> own editor -> attack. No synthetic onCommit. */
class TypingGameInputRunner:Instrumentation() {
    override fun onCreate(args:Bundle?) { super.onCreate(args);start() }
    private fun <T> ui(f:()->T):T { var r:Result<T>?=null;runOnMainSync { r=runCatching(f) };return checkNotNull(r).getOrThrow() }
    private fun member(o:Any,n:String):Any? {
        var type:Class<*>?=o.javaClass
        while(type!=null) {
            try { return type.getDeclaredField(n).apply { isAccessible=true }.get(o) } catch(_:NoSuchFieldException) {}
            try { return type.getDeclaredMethod("get"+n.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(o) } catch(_:NoSuchMethodException) {}
            type=type.superclass
        }
        error("Missing $n")
    }
    private fun await(label:String,f:()->Boolean) { repeat(600) { if(ui(f)) return;SystemClock.sleep(30) };error(label) }
    private fun status(s:String)=sendStatus(0,Bundle().apply { putString("case",s) })
    private fun tap(x:Float,y:Float) {
        val now=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { check(uiAutomation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
        waitForIdleSync()
    }
    private fun tapView(view:View,x:Float,y:Float) {
        val pos=ui { IntArray(2).also { view.getLocationOnScreen(it) } }
        val rect=ui { Rect().also { check(view.getGlobalVisibleRect(it)) } }
        tap(pos[0]+x*rect.width()/view.width,pos[1]+y*rect.height()/view.height)
    }
    private fun game()=checkNotNull(member(TypingGame,"gameView")) as TypingGame.GameView
    private fun host()=checkNotNull(member(TypingGame,"host")) as TypingGameActivity
    private fun picture(name:String) {
        SystemClock.sleep(250)
        val bitmap=checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.filesDir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    override fun onStart()=runBlocking {
        val result=Bundle();var own:SentenceLearningActivity?=null;var fixture:SyntheticInputSchema?=null
        @Suppress("UNCHECKED_CAST")
        val pets=member(PetHouse,"mutable") as MutableStateFlow<PetHouse.State>
        val savedPets=pets.value
        try {
            waitForIdleSync()
            val prefs=AppPrefs.defaultInstance()
            ui { prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE);prefs.keyboard.oneHandMode.setValue(0);prefs.keyboard.floatingKeyboard.setValue(false) }
            own=startActivitySync(Intent(targetContext,SentenceLearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SentenceLearningActivity
            ui { checkNotNull(own).input.setText("原编辑器测试草稿") }
            await("IME unavailable") { KeyboardWindow.instance!=null }
            val ime=ui { member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService }
            await("Original editor unavailable") { ime.isInputViewShown && ime.currentInputConnection!=null }
            val rime=member(ime,"rime") as RimeSession
            fixture=SyntheticInputSchema.create(rime,"的")
            targetContext.getSharedPreferences("typing_game",0).edit().clear().putBoolean("soundEnabled",false).commit()
            ui { pets.value=PetHouse.State(started=true,selected="jige",pets=listOf(PetHouse.Pet("jige"))) }
            for(mode in listOf(0,1,2)) {
                ui { prefs.keyboard.oneHandMode.setValue(mode) }
                await("Keyboard mode $mode missing") { (member(ime,"inputView") as InputView).isOneHandMode==(mode!=0) }
                ui { TypingGame.attach(ime);TypingGame.start() }
                await("Game own input focus missing mode=$mode") {
                    val h=member(TypingGame,"host") as? TypingGameActivity
                    h!=null && h.input.hasFocus() && h.input.hasWindowFocus() && ime.isInputViewShown && ime.currentInputEditorInfo.fieldId==h.input.id
                }
                await("Game field missing") { (member(TypingGame,"gameView") as? View)?.let { it.width>0 && it.isAttachedToWindow }==true }
                SystemClock.sleep(300)
                val view=ui { game() }
                val start=ui {
                    @Suppress("UNCHECKED_CAST")
                    val entries=member(view,"menuRects") as List<Pair<RectF,String>>
                    val rect=entries.first { it.second=="start" }.first
                    fun scalar(n:String)=view.javaClass.getDeclaredMethod("get"+n.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(view) as Float
                    floatArrayOf(scalar("menuLeft")+rect.centerX()*scalar("menuScale"),scalar("menuTop")+rect.centerY()*scalar("menuScale"))
                }
                tapView(view,start[0],start[1])
                ui {
                    @Suppress("UNCHECKED_CAST")
                    val items=member(view,"items") as MutableList<FallingWord>;items.clear();items+=FallingWord("的",view.width*.7f,view.height*.35f,0f,24*view.resources.displayMetrics.density)
                }
                val keyboard=ui { member(KeyboardWindow.instance!!,"visibleKeyboardView") as View }
                val key=ui { KeyboardWindow.currentKeyboardOrNull!!.keys.first { it.getLabel().equals("h",true) } }
                tapView(keyboard,key.x+key.width/2f,key.y+key.height/2f)
                await("No preedit/candidates after actual H tap mode=$mode") { rime.run { statusCached.isComposing && hasMenu } }
                val raw=rime.runOnReady { getRawInput() }
                check(raw=="h") { "Expected actual H tap to produce h; got $raw" }
                picture("game-real-composition-$mode")
                status("PASS_COMPOSITION mode=$mode actual system H tap produces raw h and candidate menu")
                val space=ui { KeyboardWindow.currentKeyboardOrNull!!.keys.first { it.code==android.view.KeyEvent.KEYCODE_SPACE } }
                tapView(keyboard,space.x+space.width/2f,space.y+space.height/2f)
                await("Own editor did not receive Chinese commit") { host().input.text.toString()=="的" }
                await("Actual keyboard commit did not hit target") { member(view,"score")==1 }
                ui {
                    val effects=member(view,"effects") as TypingGameEffects
                    check(effects.chickAttacks && effects.launches==1 && effects.impacts==1)
                    check(checkNotNull(own).input.text.toString()=="原编辑器测试草稿")
                    val gameAt=IntArray(2);view.getLocationOnScreen(gameAt)
                    val editorAt=IntArray(2);host().input.getLocationOnScreen(editorAt)
                    val keyboardAt=IntArray(2);keyboard.getLocationOnScreen(keyboardAt)
                    check(gameAt[1]+view.height<=editorAt[1]+2)
                    val bounds=Rect();check(ime.typingGameKeyboardBoundsOnScreen(bounds))
                    status("INPUT_GEOMETRY mode=$mode game=${gameAt.toList()} height=${view.height} editor=${editorAt.toList()} height=${host().input.height} keyboard=$bounds")
                    check(editorAt[1]+host().input.height<=bounds.top+2) { "Editor overlaps keyboard: editorBottom=${editorAt[1]+host().input.height} keyboardTop=${bounds.top}" }
                }
                picture("game-real-attack-$mode")
                status("PASS_REAL_INPUT mode=$mode own visible editor, Chinese commit, exactly one chick shot/impact/score, original draft unchanged")
                if(mode==2) {
                    tapView(keyboard,key.x+key.width/2f,key.y+key.height/2f)
                    await("Pending composition before exit missing") { rime.run { statusCached.isComposing } }
                }
                ui { TypingGame.stop() }
                await("Game did not close") { !TypingGame.isRunning && ime.currentInputEditorInfo.fieldId==checkNotNull(own).input.id }
                check(ui { checkNotNull(own).input.text.toString()=="原编辑器测试草稿" })
            }
            result.putString("result","PASS")
        } catch(e:Throwable) { result.putString("result","FAIL");result.putString("failure",android.util.Log.getStackTraceString(e)) }
        finally {
            ui { TypingGame.stop() }
            runCatching { fixture?.restore() }
            ui { pets.value=savedPets;own?.finish() }
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
