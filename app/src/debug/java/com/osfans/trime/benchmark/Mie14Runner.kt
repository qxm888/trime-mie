/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.MotionEvent
import com.osfans.trime.ime.core.FloatingResizeHandleView
import android.graphics.Rect
import android.graphics.Region
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.core.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.*
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode

class Mie14Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun member(v: Any, name: String): Any {
        var c: Class<*>? = v.javaClass
        while(c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible=true }.get(v)!! } catch(_:NoSuchFieldException){}
            try { return c.getDeclaredMethod("get"+name.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(v)!! } catch(_:NoSuchMethodException){}
            c=c.superclass
        }; error(name)
    }
    private fun main(f:()->Unit) { var e:Throwable?=null;runOnMainSync{try{f()}catch(t:Throwable){e=t}};e?.let{throw it} }
    private fun await(label:String, f:()->Boolean) { repeat(150){var ok=false;main{ok=f()};if(ok)return;SystemClock.sleep(60)};error(label) }
    private fun service() = member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
    private fun input() = member(service(),"inputView") as InputView
    private fun emit(data:RimeMessage<*>) { val iv=input();iv.javaClass.getDeclaredMethod("handleRimeMessage",RimeMessage::class.java).apply{isAccessible=true}.invoke(iv,data) }
    private fun pause() { waitForIdleSync();SystemClock.sleep(400) }
    private fun pass(name:String){sendStatus(0,Bundle().apply{putString("case",name)})}
    override fun onStart() {
        val result=Bundle()
        val activity=startActivitySync(Intent(targetContext,CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val p=AppPrefs.defaultInstance()
        try {
            main{activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;p.keyboard.floatingKeyboardWidth.setValue(80);p.keyboard.floatingKeyboardHeight.setValue(80);p.keyboard.floatingKeyboardOffsetX.setValue(0);p.keyboard.floatingKeyboardOffsetY.setValue(0);p.keyboard.floatingKeyboardCornerRadius.setValue(24);p.keyboard.oneHandMode.setValue(0);p.keyboard.floatingKeyboard.setValue(false);p.keyboard.floatingKeyboardAutoLandscape.setValue(true);p.keyboard.hideInputBar.setValue(false);p.candidates.mode.setValue(PopupCandidatesMode.INPUT_DEVICE)}
            await("IME missing"){KeyboardWindow.instance!=null && input().isShown};pause()
            fun candidateCheck() {
                val words=Array(6){CandidateProto("测试候选$it","注释",(it+1).toString())}
                main{emit(RimeMessage.BulkCandidatesMessage(Candidates.Bulk(6,0,words)))};pause()
                main{
                    val bar=member(input(),"inputBar")
                    val view=member(bar,"view") as android.widget.ViewAnimator
                    val ui=member(bar,"candidateUi")
                    val list=member(ui,"compatView") as RecyclerView
                    check(view.isShown && view.displayedChild==1 && list.isShown && list.width>0 && list.height>0 && list.childCount>0){"Candidates missing: bar=${view.isShown}/${view.displayedChild} list=${list.isShown}/${list.width}x${list.height} children=${list.childCount}"}
                }
                pass("compact candidates visible")
                main{emit(RimeMessage.BulkCandidatesMessage(Candidates.Bulk()))};pause()
            }
            main{checkGripDrawing(targetContext)};pass("continuous white/pink grips at 0/12/24/48dp radii")
            candidateCheck()
            // Exercise native Rime -> message flow -> visible list, not only synthetic UI data.
            val job=service().postRimeJob { clearComposition(); setRuntimeOption("ascii_mode",false); simulateKeySequence("a") }
            kotlinx.coroutines.runBlocking { job.join() };pause()
            await("Native Rime candidates not shown") {
                val bar=member(input(),"inputBar")
                val ui=member(bar,"candidateUi")
                val list=member(ui,"compatView") as RecyclerView
                list.isShown && list.childCount>0 && (list.adapter?.itemCount?:0)>0
            };pass("real Rime key -> candidate list")
            kotlinx.coroutines.runBlocking { service().postRimeJob { clearComposition() }.join() }
            main{
                p.candidates.mode.setValue(PopupCandidatesMode.ALWAYS_SHOW)
                p.candidates.layout.setValue(com.osfans.trime.ime.candidates.popup.PopupCandidatesLayout.VERTICAL_REVERSE)
            };pause()
            kotlinx.coroutines.runBlocking { service().postRimeJob { simulateKeySequence("a") }.join() };pause()
            await("Native Rime reverse popup missing") {
                val popup=member(service(),"candidatesView") as View
                val list=member(member(popup,"candidatesUi"),"root") as RecyclerView
                popup.isShown && list.height>0 && list.childCount>0
            };pass("real Rime reverse vertical popup visible")
            kotlinx.coroutines.runBlocking { service().postRimeJob { clearComposition() }.join() }
            main{p.candidates.mode.setValue(PopupCandidatesMode.INPUT_DEVICE)};pause()
            main{p.keyboard.floatingKeyboard.setValue(true)};pause()
            await("floating mode"){input().isFloatingKeyboardEnabled};pause()
            main{
                val iv=input();val card=iv.keyboardView
                for(name in listOf("leftResizeHandle","rightResizeHandle")){
                    val h=member(iv,name) as FloatingResizeHandleView
                    val cornerX=card.x + if(name.startsWith("right"))card.width*card.scaleX else 0f
                    val cornerY=card.y+card.height*card.scaleY
                    check(kotlin.math.abs(h.x+h.width/2f-cornerX)<2 && kotlin.math.abs(h.y+h.height/2f-cornerY)<2){"Handle misplaced: $name ${h.x},${h.y} corner=$cornerX,$cornerY"}
                    val region=Region();iv.addResizeHandlesToTouchableRegion(region)
                    val pos=IntArray(2);h.getLocationInWindow(pos)
                    val grip=h.gripPoint()
                    check(region.contains(pos[0]+grip[0].toInt(),pos[1]+grip[1].toInt())){"Handle outside touch region"}
                }
                val always=member(member(iv,"inputBar"),"alwaysUi")
                always.javaClass.getMethod("setToolbarCollapsed",Boolean::class.javaPrimitiveType).invoke(always,true)
                val animator=member(always,"animator") as View
                val before=card.translationX
                val now=SystemClock.uptimeMillis()
                fun touch(action:Int,x:Float){ val e=MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,x,20f,0);animator.dispatchTouchEvent(e);e.recycle() }
                touch(MotionEvent.ACTION_DOWN,20f);touch(MotionEvent.ACTION_MOVE,70f);touch(MotionEvent.ACTION_UP,70f)
                check(card.translationX!=before){"Collapsed blank toolbar did not drag"}
            };pass("corner placement, touch region, collapsed toolbar drag")
            candidateCheck()
            // Touch the actual handle through the window manager, including its outside-card hitbox.
            val point=IntArray(2);var oldWidth=0;val handleTrace=StringBuilder()
            main{
                val h=member(input(),"rightResizeHandle") as FloatingResizeHandleView;h.getLocationOnScreen(point)
                h.setOnTouchListener { _,e -> handleTrace.append("${e.actionMasked}@${e.x},${e.y};");false }
                val grip=h.gripPoint();point[0]+=grip[0].toInt();point[1]+=grip[1].toInt()
                val inset=android.inputmethodservice.InputMethodService.Insets();service().onComputeInsets(inset)
                val origin=IntArray(2);service().window.window!!.decorView.getLocationOnScreen(origin)
                check(inset.touchableRegion.contains(point[0]-origin[0],point[1]-origin[1])){"Grip not in window touchable region"}
                oldWidth=p.keyboard.floatingKeyboardWidth.getValue()
            }
            val down=SystemClock.uptimeMillis()
            for((action,delta) in listOf(MotionEvent.ACTION_DOWN to 0,MotionEvent.ACTION_MOVE to -15,MotionEvent.ACTION_MOVE to -90,MotionEvent.ACTION_UP to -90)) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,(point[0]+delta).toFloat(),(point[1]+delta).toFloat(),0)
                event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;sendPointerSync(event);event.recycle();SystemClock.sleep(80)
            }
            pause()
            main{check(p.keyboard.floatingKeyboardWidth.getValue()!=oldWidth){"Corner gesture did not resize: trace=$handleTrace point=${point.toList()}"}}
            pass("real corner touch resizes keyboard")
            main{p.keyboard.floatingKeyboard.setValue(false);p.keyboard.oneHandMode.setValue(1)};pause()
            await("one hand mode"){input().isOneHandMode};pause()
            main{
                val iv=input();val card=iv.keyboardView
                val bar=member(member(iv,"inputBar"),"view") as View
                check(card.width < iv.width && kotlin.math.abs(bar.width-card.width)<=2){"One hand bar not shrunk"}
                val systemBottom=androidx.core.view.ViewCompat.getRootWindowInsets(iv)?.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.navigationBars() or androidx.core.view.WindowInsetsCompat.Type.mandatorySystemGestures())?.bottom?:0
                check(kotlin.math.abs(card.y+card.height*card.scaleY-(iv.height-systemBottom))<2){"One hand bottom does not match system row top"}
                val before=card.y+card.height*card.scaleY
                iv.dragFloatingKeyboardFromToolbar(MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,10f,10f,0))
                iv.dragFloatingKeyboardFromToolbar(MotionEvent.obtain(0,10,MotionEvent.ACTION_MOVE,110f,-100f,0))
                iv.dragFloatingKeyboardFromToolbar(MotionEvent.obtain(0,20,MotionEvent.ACTION_UP,110f,-100f,0))
                check(kotlin.math.abs(before-(card.y+card.height*card.scaleY))<2){"One hand moved vertically"}
                check((member(iv,"leftResizeHandle") as View).visibility==View.GONE && (member(iv,"rightResizeHandle") as View).visibility==View.GONE){"One hand still has resize handles"}
                val always=member(member(iv,"inputBar"),"alwaysUi")
                check((member(always,"collapseButton") as View).visibility==View.GONE){"One hand has collapse button"}
                val buttons=member(member(always,"buttonsUi"),"viewport") as View
                check(buttons.isShown){"One hand toolbar is collapsed"}
                check(kotlin.math.abs(card.x)<2){"Left hand is not fixed to left edge"}
                val insets=android.inputmethodservice.InputMethodService.Insets();service().onComputeInsets(insets)
                check(insets.contentTopInsets < iv.height && insets.contentTopInsets>0){"One hand does not push editor"}
            };pass("one hand whole card, fixed left edge, no handles/collapse, editor insets")
            var narrow=0
            main{p.keyboard.oneHandWidth.setValue(55)};pause()
            main{narrow=input().keyboardView.width;p.keyboard.oneHandWidth.setValue(90)};pause()
            main{check(input().keyboardView.width>narrow){"One hand width preference not applied"};p.keyboard.oneHandMode.setValue(2)};pause()
            main{val iv=input();val card=iv.keyboardView;check(kotlin.math.abs(card.x+card.width*card.scaleX-iv.width)<2){"Right hand not fixed to right edge"}}
            pass("independent one hand width and fixed right edge")
            main{p.keyboard.oneHandMode.setValue(0);activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};pause()
            await("automatic phone landscape floating"){input().isFloatingKeyboardEnabled && activity.resources.configuration.orientation==2};pause();candidateCheck();pass("phone landscape automatically floating")
            main{activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT};pause()
            await("portrait returns docked"){!input().isFloatingKeyboardEnabled};pass("portrait returns docked")
            main{service().voiceInput.start(mock=true)}
            main{service().voiceInput.release()};SystemClock.sleep(350);pause()
            val edit=member(activity,"input") as? android.widget.EditText
            if(edit!=null) main{check(edit.text.toString().contains("内置语音测试")){"Voice result not committed"}}
            pass("built-in voice mock callback (no microphone)")
            val beforeVoice=edit?.text.toString()
            main{service().voiceInput.start(mock=true);service().voiceInput.release();service().voiceInput.cancel()}
            SystemClock.sleep(350);pause()
            main{check(edit?.text.toString()==beforeVoice){"Canceled speech leaked into editor"}}
            pass("canceled speech callback ignored")
            // Real AAR signature check without loading a recognizer or downloading a model.
            val cls=Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            val stream=cls.getMethod("createStream").returnType
            check(cls.getMethod("decode",stream)!=null && cls.getMethod("getResult",stream)!=null)
            pass("sherpa OfflineStream method signatures")
            result.putString("result","PASS")
        }catch(t:Throwable){result.putString("result","FAIL: "+t.stackTraceToString())}
        finally{main{activity.finish()}}
        finish(android.app.Activity.RESULT_OK,result)
    }
}
