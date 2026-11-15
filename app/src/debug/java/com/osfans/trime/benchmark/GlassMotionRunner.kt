/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.*
import android.os.*
import android.content.Intent
import android.view.*
import android.graphics.RectF
import com.osfans.trime.core.*
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ime.candidates.popup.*
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.composition.PopupPosition
import com.osfans.trime.ime.core.*
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.daemon.RimeSession
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Synthetic editor only. Records the same motion path before and after optimization. */
@androidx.annotation.RequiresApi(31)
class GlassMotionRunner: Instrumentation() {
    private var baseline=false
    override fun onCreate(args:Bundle?) {super.onCreate(args);baseline=args?.getString("baseline")=="true";if(Build.VERSION.SDK_INT<31){finish(Activity.RESULT_CANCELED,Bundle());return};start()}
    private fun <T> ui(f:()->T):T {var r:Result<T>?=null;runOnMainSync {r=runCatching(f)};return r!!.getOrThrow()}
    private fun member(v:Any,n:String):Any? {var t:Class<*>?=v.javaClass;while(t!=null){try{return t.getDeclaredField(n).apply{isAccessible=true}.get(v)}catch(_:NoSuchFieldException){};try{return t.getDeclaredMethod("get"+n.replaceFirstChar{it.uppercase()}).apply{isAccessible=true}.invoke(v)}catch(_:NoSuchMethodException){};t=t.superclass};error(n)}
    private fun await(s:String,f:()->Boolean){repeat(300){if(ui(f))return;SystemClock.sleep(40)};error(s)}
    private fun shell(s:String)=ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(s)).bufferedReader().use{it.readText().trim()}
    private fun measure(window:Window, host:View, action:(Int)->Unit):String {
        val durations=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val frameTimes=mutableListOf<Long>()
        var layouts=0
        val layout=ViewTreeObserver.OnGlobalLayoutListener {layouts++}
        val listener=Window.OnFrameMetricsAvailableListener {_,m,_->durations.add(m.getMetric(FrameMetrics.TOTAL_DURATION))}
        val latch=CountDownLatch(1)
        ui {
            host.viewTreeObserver.addOnGlobalLayoutListener(layout)
            window.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper()))
            var n=0
            Choreographer.getInstance().postFrameCallback(object:Choreographer.FrameCallback {
                override fun doFrame(time:Long){frameTimes.add(time);try{action(n++)}catch(t:Throwable){latch.countDown();throw t};if(n<180)Choreographer.getInstance().postFrameCallback(this)else latch.countDown()}
            })
        }
        check(latch.await(25,TimeUnit.SECONDS));SystemClock.sleep(200)
        ui {host.viewTreeObserver.removeOnGlobalLayoutListener(layout);window.removeOnFrameMetricsAvailableListener(listener)}
        val all=durations.toList().sorted();check(all.isNotEmpty());val budget=1e9/host.display.refreshRate
        val gaps=frameTimes.zipWithNext{a,b->(b-a)/1e6}.sorted()
        return "callback_gap_median_ms=${gaps[gaps.size/2]}; callback_gap_p95_ms=${gaps[(gaps.size*.95).toInt().coerceAtMost(gaps.lastIndex)]}; frames=${all.size}; median_ms=${all[all.size/2]/1e6}; p95_ms=${all[(all.size*.95).toInt().coerceAtMost(all.lastIndex)]/1e6}; over_refresh_budget=${all.count{it>budget}}; global_layouts=$layouts"
    }
    override fun onStart()=runBlocking {
        val result=Bundle();var popup:CandidatesView?=null
        try {
            check(targetContext.packageName.endsWith(".debug.sentences"))
            await("prefs"){runCatching{AppPrefs.defaultInstance()}.isSuccess};val p=AppPrefs.defaultInstance();CustomKeyboards.awaitBuiltinLayouts()
            ui {p.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)};CustomKeyboards.init(targetContext);CustomKeyboards.awaitBuiltinLayouts()
            val live="custom_liquid_glass_live"
            ui {KeyboardColorGroups.deactivate();p.general.inputStatsEnabled.setValue(false);p.general.sentenceLearningEnabled.setValue(false);p.keyboard.oneHandMode.setValue(0);p.keyboard.floatingKeyboard.setValue(false);p.keyboard.floatingKeyboardAutoLandscape.setValue(false);p.candidates.mode.setValue(PopupCandidatesMode.DISABLED);p.candidates.floatingBall.setValue(false);KeyboardModeBinding.save(true,KeyboardModeBinding());KeyboardModeBinding.save(false,KeyboardModeBinding())}
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",live,"液态玻璃·动态"))
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 0 --ei orientation 1")
            fun ime()=member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
            fun input()=member(ime(),"inputView") as InputView
            fun native()=member(input(),"publicWindowBackdrop") as? PublicWindowBackdrop
            await("IME live"){KeyboardWindow.instance!=null && ime().currentInputEditorInfo?.packageName=="dev.codex.glassfixture" && native()?.active==true}
            val roots=KeyboardLayoutCatalog.roots(targetContext,KeyboardLayoutCatalog.BUILTIN).map{it.id}.filter{it!=live && it!="custom_liquid_glass"}
            val leaked=ui {roots.filter{SurfaceAppearance.panelBackdrop(true,it)?.publicWindow==true}}
            result.putString("cross_family_leaks",leaked.joinToString(",").ifEmpty{"NONE"})
            if(!baseline)check(leaked.isEmpty()){"Glass leaked to $leaked"}
            // Explicit disabled global theme effects must not override an owned stock theme.
            val global=ui{SurfaceAppearance.current(false)}
            ui{SurfaceAppearance.save(global.copy(backdrop=PanelBackdrop(enabled=true,publicWindow=true),gradient=PanelGradient(enabled=true)),false)}
            val globalLeaks=ui{roots.filter{SurfaceAppearance.panelBackdrop(true,it)?.publicWindow==true}}
            ui{SurfaceAppearance.save(global,false)}
            result.putString("global_theme_leaks",globalLeaks.joinToString(",").ifEmpty{"NONE"});if(!baseline)check(globalLeaks.isEmpty())
            ui{p.keyboard.floatingKeyboard.setValue(true)}
            await("floating"){input().isFloatingKeyboardEnabled && native()?.active==true};SystemClock.sleep(800)
            val firstInput=ui{input()};val host=firstInput.keyboardView
            val xy=ui{IntArray(2).also{host.getLocationOnScreen(it)}};val startX=xy[0]+host.width/2f;val startY=xy[1]+10f
            val downTime=SystemClock.uptimeMillis()
            ui{MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,startX,startY,0).also{firstInput.dragFloatingKeyboardFromToolbar(it);it.recycle()}}
            result.putString("keyboard_motion",measure(ui{ime().window!!.window!!},host){n->
                val wave=kotlin.math.sin(n*Math.PI/90).toFloat();val e=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,startX+100*wave,startY-180*(1-kotlin.math.cos(n*Math.PI/90).toFloat()),0)
                firstInput.dragFloatingKeyboardFromToolbar(e);e.recycle()
            })
            ui{MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,startX,startY,0).also{firstInput.dragFloatingKeyboardFromToolbar(it);it.recycle()}}
            SystemClock.sleep(350);check(ui{native()?.active==true})
            ui{p.keyboard.floatingKeyboard.setValue(false);p.candidates.mode.setValue(PopupCandidatesMode.ALWAYS_SHOW);p.candidates.position.setValue(PopupPosition.FOLLOW)}
            await("candidate rebuild"){native()?.active==true};SystemClock.sleep(500)
            val candidate=ui{
                (member(ime(),"candidatesView") as CandidatesView).also{v->popup=v;v.handleMessages=false
                    v.javaClass.getDeclaredField("composition").apply{isAccessible=true}.set(v,CompositionProto("ni"))
                    v.javaClass.getDeclaredField("candidates").apply{isAccessible=true}.set(v,Candidates.Paged(candidates=arrayOf(CandidateProto("你好","","1"),CandidateProto("清风","","2"))))
                    v.javaClass.getDeclaredMethod("updateUi").apply{isAccessible=true}.invoke(v)
                }
            }
            await("candidate live"){candidate.isShown && (member(candidate,"publicWindowBackdrop") as? PublicWindowBackdrop)?.active==true}
            val size=ui{ime().refreshCandidatesGeometry();(member(ime(),"contentSize") as FloatArray).copyOf()}
            result.putString("candidate_motion",measure(ui{ime().window!!.window!!},candidate){n->
                candidate.updateCursorAnchor(RectF(120f+n%90*5,450f,120f+n%90*5,500f),size)
            })
            SystemClock.sleep(300)
            val b=uiAutomation.takeScreenshot()!!;File(targetContext.cacheDir,"glass-motion.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            if (!baseline) {
                // Prove draw-only traversals update Android's touchable region:
                // move a real key outside the previous card and tap through the OS.
                ui {candidate.handleMessages=true;candidate.visibility=View.INVISIBLE;p.candidates.mode.setValue(PopupCandidatesMode.DISABLED);p.keyboard.floatingKeyboard.setValue(true)}
                await("Floating input target"){input().isFloatingKeyboardEnabled && native()?.active==true}
                ui {KeyboardWindow.instance!!.switchKeyboardFromAction(live+"_en")}
                val rime=ui{member(ime(),"rime") as RimeSession};rime.runOnReady{setRuntimeOption("ascii_mode",true)}
                await("English floating page"){KeyboardWindow.currentKeyboardOrNull?.keyboardId==live+"_en"}
                SystemClock.sleep(400)
                val card=ui{input()};val origin=ui{IntArray(2).also{card.keyboardView.getLocationOnScreen(it)}}
                val oldTop=origin[1];val whenDown=SystemClock.uptimeMillis()
                ui {
                    listOf(Triple(MotionEvent.ACTION_DOWN,origin[0]+120f,origin[1]+20f),Triple(MotionEvent.ACTION_MOVE,origin[0]+120f,origin[1]-430f),Triple(MotionEvent.ACTION_UP,origin[0]+120f,origin[1]-430f)).forEach{(action,x,y)->
                        MotionEvent.obtain(whenDown,SystemClock.uptimeMillis(),action,x,y,0).also{card.dragFloatingKeyboardFromToolbar(it);it.recycle()}
                    }
                }
                SystemClock.sleep(400)
                fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
                val key=ui{all(card.keyboardView).filterIsInstance<KeyView>().first{it.isShown}}
                val keyXY=ui{IntArray(2).also{key.getLocationOnScreen(it)}}
                val x=keyXY[0]+key.width*card.keyboardView.scaleX/2f;val y=keyXY[1]+key.height*card.keyboardView.scaleY/2f
                check(y<oldTop){"Moved key still inside old touch region"}
                val before=ui{ime().currentInputConnection?.getTextBeforeCursor(256,0)?.toString().orEmpty()}
                val now=SystemClock.uptimeMillis();val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);val up=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,x,y,0)
                try {check(uiAutomation.injectInputEvent(down,true));SystemClock.sleep(40);check(uiAutomation.injectInputEvent(up,true))}finally{down.recycle();up.recycle()}
                await("Moved key did not receive system touch"){(ime().currentInputConnection?.getTextBeforeCursor(256,0)?.length ?:0)>before.length}
                result.putString("input_after_move","PASS_REAL_TOUCH_OUTSIDE_OLD_CARD_REGION")
            }
            // Switch away and verify active runtime, not only explicit editor queries.
            for(id in listOf("custom_cloudsea","custom_mint","custom_porcelain")) {
                KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",id,id))
                await("switch $id"){CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)==id && input().isShown}
                SystemClock.sleep(250)
                if(!baseline)check(ui{SurfaceAppearance.panelBackdrop(true)?.publicWindow!=true && native()?.active!=true}){"Runtime glass leaked to $id"}
            }
            result.putString("result",if(baseline)"BASELINE_RECORDED" else "PASS_THEME_ISOLATION_AND_MOTION")
        }catch(t:Throwable){result.putString("result","FAIL: ${t.stackTraceToString()}")}
        finally{ui{popup?.handleMessages=true};shell("am start -W -n dev.codex.glassfixture/.MainActivity --ez finish true")}
        finish(if(result.getString("result")?.startsWith("FAIL")==true)Activity.RESULT_CANCELED else Activity.RESULT_OK,result)
    }
}
