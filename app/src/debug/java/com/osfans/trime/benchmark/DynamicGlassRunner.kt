/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.view.inspector.WindowInspector
import com.osfans.trime.core.*
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.sqrt

/** Actual separate-package editor; only deterministic test pixels and text are used. */
@androidx.annotation.RequiresApi(31)
class DynamicGlassRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); if(android.os.Build.VERSION.SDK_INT<31) { finish(Activity.RESULT_CANCELED,Bundle().apply { putString("result","UNSUPPORTED_API_BELOW_31") });return }; start() }
    private fun <T> ui(action: () -> T): T { var r: Result<T>?=null;runOnMainSync { r=runCatching(action) };return checkNotNull(r).getOrThrow() }
    private fun member(value: Any, name: String): Any? {
        var t:Class<*>?=value.javaClass
        while(t!=null) {
            try { return t.getDeclaredField(name).apply { isAccessible=true }.get(value) } catch(_:NoSuchFieldException) {}
            try { return t.getDeclaredMethod("get"+name.replaceFirstChar { it.uppercase() }).apply { isAccessible=true }.invoke(value) } catch(_:NoSuchMethodException) {}
            t=t.superclass
        };error("Missing $name")
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(label:String, condition:()->Boolean) { repeat(300) { if(ui(condition))return;SystemClock.sleep(40) };error(label) }
    private fun glassCount()=WindowInspector.getGlobalWindowViews().count { (it.layoutParams as? android.view.WindowManager.LayoutParams)?.title?.toString()=="Trime dynamic glass" }
    private fun shot(name:String):Bitmap { SystemClock.sleep(400);val b=checkNotNull(uiAutomation.takeScreenshot());File(targetContext.cacheDir,"dynamic-final-$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };return b }
    private fun shell(command:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun source(phase:Int)=Intent().setClassName("dev.codex.glassfixture","dev.codex.glassfixture.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("phase",phase)
    private data class Pixels(val mean:List<Double>,val deviation:Double)
    private fun pixels(b:Bitmap,area:Rect):Pixels {
        val sums=DoubleArray(3);val luma=ArrayList<Double>()
        for(y in area.top until area.bottom)for(x in area.left until area.right) {
            val c=b.getPixel(x,y);sums[0]+=Color.red(c);sums[1]+=Color.green(c);sums[2]+=Color.blue(c);luma+=(Color.red(c)+Color.green(c)+Color.blue(c))/3.0
        }
        val avg=luma.average();return Pixels(sums.map { it/luma.size },sqrt(luma.sumOf { (it-avg)*(it-avg) }/luma.size))
    }
    override fun onStart()=runBlocking {
        val result=Bundle();var savedBlur:String?=null;var injectedPopup:CandidatesView?=null
        try {
            check(targetContext.packageName.endsWith(".debug.sentences"))
            await("Preferences not ready") { runCatching { AppPrefs.defaultInstance() }.isSuccess }
            val prefs=AppPrefs.defaultInstance();CustomKeyboards.awaitBuiltinLayouts()
            ui { prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE) }
            CustomKeyboards.init(targetContext);CustomKeyboards.awaitBuiltinLayouts()
            val root="custom_liquid_glass_live";CustomKeyboards.resetBuiltinSet(root);CustomKeyboards.resetBuiltinSet("custom_liquid_glass")
            ui {
                prefs.keyboard.floatingKeyboard.setValue(false);prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0);prefs.keyboard.hideInputBar.setValue(false)
                prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED);prefs.candidates.floatingBall.setValue(false)
                prefs.general.inputStatsEnabled.setValue(false);prefs.general.sentenceLearningEnabled.setValue(false)
                KeyboardColorGroups.deactivate();KeyboardModeBinding.save(true,KeyboardModeBinding());KeyboardModeBinding.save(false,KeyboardModeBinding())
            }
            check(KeyboardLayoutCatalog.roots(targetContext,KeyboardLayoutCatalog.BUILTIN).any { it.id==root && it.name=="液态玻璃·动态" })
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",root,"液态玻璃·动态"))
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 0 --ei orientation 1")
            fun ime()=member(KeyboardWindow.instance!!,"service") as TrimeInputMethodService
            fun input()=member(ime(),"inputView") as InputView
            fun public()=member(input(),"publicWindowBackdrop") as? PublicWindowBackdrop
            fun checkBounds(host:View, controller:PublicWindowBackdrop) {
                val origin=IntArray(2);host.getLocationOnScreen(origin)
                val expectedSize=Rect();check(host.getGlobalVisibleRect(expectedSize))
                val actualView=(member(controller,"dialog") as android.app.Dialog).window!!.decorView
                val actual=IntArray(2);actualView.getLocationOnScreen(actual)
                check(kotlin.math.abs(actual[0]-origin[0])<=1 && kotlin.math.abs(actual[1]-origin[1])<=1 && actualView.width==expectedSize.width() && actualView.height==expectedSize.height()) {
                    "Screen bounds mismatch: host=${origin.toList()} ${expectedSize.width()}x${expectedSize.height()}, native=${actual.toList()} ${actualView.width}x${actualView.height}"
                }
            }
            fun field()=ime().currentInputConnection?.getTextBeforeCursor(128,0)?.toString().orEmpty()
            await("Independent app editor unavailable") { KeyboardWindow.instance!=null && ime().currentInputEditorInfo?.packageName=="dev.codex.glassfixture" && input().isShown && targetContext.resources.displayMetrics.widthPixels<targetContext.resources.displayMetrics.heightPixels }
            await("Native bounded glass layer missing") { public()?.active==true && glassCount()==1 }
            val original=ui { SurfaceAppearance.current(true) };val id=ui { CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId) }
            ui { SurfaceAppearance.save(original.copy(backdrop=original.backdrop.copy(systemBlur=false)),true,id);ThemeManager.requestCustomizationRefresh() }
            await("Blur did not turn off") { glassCount()==0 && !SurfaceAppearance.current(true).backdrop.systemBlur && views(input()).filterIsInstance<KeyView>().count { it.isShown && it.width>0 && it.height>40 }>=30 }
            val sample=ui {
                val k=views(input()).filterIsInstance<KeyView>().filter { it.isShown && it.width>0 && it.height>40 }.take(10).sortedBy { it.x }
                val a=IntArray(2);val b=IntArray(2);k[0].getLocationOnScreen(a);k[1].getLocationOnScreen(b)
                val x=(a[0]+k[0].width+b[0])/2;Rect(x-2,a[1]+k[0].height/4,x+2,a[1]+k[0].height*3/4)
            }
            check(!sample.isEmpty);result.putString("sample_rect",sample.toShortString())
            val raw=shot("cross-app-unblurred")
            ui { SurfaceAppearance.save(original,true,id);ThemeManager.requestCustomizationRefresh() }
            await("Blur did not resume") { public()?.active==true && glassCount()==1 }
            val blue=shot("cross-app-blue")
            result.putString("native_geometry",ui {
                val d=member(public()!!,"dialog") as android.app.Dialog
                val child=d.window!!.decorView;val xy=IntArray(2);child.getLocationOnScreen(xy)
                "target=${member(public()!!,"lastBounds")}; actual=${Rect(xy[0],xy[1],xy[0]+child.width,xy[1]+child.height)}"
            })
            val rawPixels=pixels(raw,sample);val bluePixels=pixels(blue,sample)
            check(rawPixels.deviation>10 && bluePixels.deviation<rawPixels.deviation*.45) { "No measurable blur: $rawPixels -> $bluePixels" }
            val keyboardRect=ui { val v=input().keyboardView;val xy=IntArray(2);v.getLocationOnScreen(xy);checkBounds(v,public()!!);Rect(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height) }
            val outside=Rect(30,keyboardRect.top-180,120,keyboardRect.top-100)
            check(pixels(blue,outside).deviation>pixels(raw,outside).deviation*.8) { "Blur escaped above keyboard" }
            val controller=ui { public() }
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 1 --ei orientation 1")
            await("Warm source lost editor") { ime().currentInputEditorInfo?.packageName=="dev.codex.glassfixture" && public()?.active==true }
            val warm=shot("cross-app-warm");val warmPixels=pixels(warm,sample)
            check(bluePixels.mean.zip(warmPixels.mean).sumOf { (a,b)->kotlin.math.abs(a-b) }>60) { "Live app background did not change: $bluePixels -> $warmPixels" }
            check(ui { public()===controller && glassCount()==1 }) { "Background colour change unnecessarily rebuilt the blur window" }
            result.putString("cross_app_pixels","unblurred=$rawPixels; blue=$bluePixels; warm=$warmPixels")
            raw.recycle();blue.recycle();warm.recycle()
            val rime=member(ime(),"rime") as RimeSession
            ui { KeyboardWindow.instance!!.switchKeyboardFromAction(root+"_en") }
            rime.runOnReady { setRuntimeOption("ascii_mode",true) }
            await("English keys not ready") { KeyboardWindow.currentKeyboardOrNull?.keyboardId==root+"_en" && public()?.active==true }
            val before=ui { field() };val key=ui { views(input()).filterIsInstance<KeyView>().first { it.isShown } }
            val xy=ui { IntArray(2).also { key.getLocationOnScreen(it) } };val x=xy[0]+key.width/2f;val y=xy[1]+key.height/2f;val now=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);val up=MotionEvent.obtain(now,now+30,MotionEvent.ACTION_UP,x,y,0)
            try { check(uiAutomation.injectInputEvent(down,true));SystemClock.sleep(30);check(uiAutomation.injectInputEvent(up,true)) } finally { down.recycle();up.recycle() }
            await("Media layer intercepted keyboard input") { field().length>before.length }
            result.putString("input","TOUCH_KEY_DELIVERED_TO_SEPARATE_PACKAGE_EDITOR")
            ui {
                prefs.candidates.mode.setValue(PopupCandidatesMode.ALWAYS_SHOW)
            }
            await("IME rebuild missing") { public()?.active==true }
            lateinit var popup:CandidatesView
            ui {
                ime().refreshCandidatesGeometry();popup=member(ime(),"candidatesView") as CandidatesView;injectedPopup=popup;popup.handleMessages=false
                popup.javaClass.getDeclaredField("composition").apply { isAccessible=true }.set(popup,CompositionProto("ni"))
                popup.javaClass.getDeclaredField("candidates").apply { isAccessible=true }.set(popup,Candidates.Paged(candidates=arrayOf(CandidateProto("你好","","1"),CandidateProto("清风","","2"))))
                popup.javaClass.getDeclaredMethod("updateUi").apply { isAccessible=true }.invoke(popup)
            }
            await("Floating glass window missing") { popup.isShown && (member(popup,"publicWindowBackdrop") as? PublicWindowBackdrop)?.active==true && glassCount()==2 }
            shot("floating-candidates").recycle()
            ui { checkBounds(popup,member(popup,"publicWindowBackdrop") as PublicWindowBackdrop) }
            ui { popup.handleMessages=true;popup.visibility=View.INVISIBLE;prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED) }
            await("Hidden candidate blur leaked") { glassCount()==1 }
            ui { prefs.keyboard.oneHandMode.setValue(1) }
            await("One-hand bounds did not update") { input().keyboardView.width<targetContext.resources.displayMetrics.widthPixels*.95f && public()?.active==true }
            SystemClock.sleep(200);ui { checkBounds(input().keyboardView,public()!!) }
            shot("onehand").recycle()
            ui { prefs.keyboard.oneHandMode.setValue(0);prefs.keyboard.floatingKeyboard.setValue(true) }
            await("Floating keyboard did not activate") { input().isFloatingKeyboardEnabled && public()?.active==true }
            shot("floating-keyboard").recycle()
            ui { checkBounds(input().keyboardView,public()!!) }
            ui { prefs.keyboard.floatingKeyboard.setValue(false) }
            await("Docked keyboard did not return") { !input().isFloatingKeyboardEnabled && public()?.active==true }
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 1 --ei orientation 0")
            await("Landscape keyboard did not settle") { targetContext.resources.displayMetrics.widthPixels>targetContext.resources.displayMetrics.heightPixels && public()?.active==true }
            SystemClock.sleep(1100);shot("landscape").recycle();ui { checkBounds(input().keyboardView,public()!!) }
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 1 --ei orientation 1")
            await("Portrait keyboard did not return") { targetContext.resources.displayMetrics.widthPixels<targetContext.resources.displayMetrics.heightPixels && public()?.active==true }
            SystemClock.sleep(900)
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 0 --ei orientation 1 --ez resize true")
            await("Resizing editor lost native blur") { public()?.active==true && ime().currentInputEditorInfo?.packageName=="dev.codex.glassfixture" }
            val resizedBlue=shot("resize-blue")
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 1 --ei orientation 1 --ez resize true")
            val resizedWarm=shot("resize-warm")
            result.putString("adjust_resize_pixels","blue=${pixels(resizedBlue,sample)}; warm=${pixels(resizedWarm,sample)}")
            resizedBlue.recycle();resizedWarm.recycle()
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 1 --ei orientation 1")
            savedBlur=shell("settings get global disable_window_blurs")
            check(savedBlur in listOf("0","1","null"))
            shell("settings put global disable_window_blurs 1")
            await("System-off did not remove native blur") { public()?.active!=true && glassCount()==0 }
            shot("system-disabled-fallback").recycle()
            shell(if(savedBlur=="null") "settings delete global disable_window_blurs" else "settings put global disable_window_blurs $savedBlur")
            savedBlur=null
            await("System-on did not restore native blur") { public()?.active==true && glassCount()==1 }
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin","custom_liquid_glass","液态玻璃"))
            await("Retained theme still has live windows") { !SurfaceAppearance.current(true).backdrop.publicWindow && glassCount()==0 }
            shot("retained-theme").recycle()
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin",root,"液态玻璃·动态"))
            await("Dynamic theme did not restore") { public()?.active==true && glassCount()==1 }
            ui { ime().requestHideSelf(0) }
            await("Native glass leaked after keyboard hide") { glassCount()==0 }
            shell("am start -W -n dev.codex.glassfixture/.MainActivity --ei phase 0 --ei orientation 1")
            await("Native glass did not return with keyboard") { public()?.active==true && glassCount()==1 }
            result.putString("lifecycle","CANDIDATES_ONEHAND_FLOATING_ROTATION_RESIZE_SYSTEM_TOGGLE_THEME_SWITCH_HIDE_AND_RESHOW_PASS")
            result.putString("result","PASS_CROSS_APP_PUBLIC_WINDOW_DYNAMIC_GAUSSIAN_AND_INPUT")
        } catch(t:Throwable) { result.putString("result","FAIL: ${t.stackTraceToString()}") }
        finally {
            savedBlur?.let { shell(if(it=="null") "settings delete global disable_window_blurs" else "settings put global disable_window_blurs $it") }
            ui { injectedPopup?.handleMessages=true };shell("am start -W -n dev.codex.glassfixture/.MainActivity --ez finish true")
        }
        finish(if(result.getString("result")?.startsWith("PASS") ==true) Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
