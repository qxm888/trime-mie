/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.ime.game.TypingGame
import com.osfans.trime.data.stats.WordCloudVocabulary
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.PetHouseActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** A non-focusable IME child window; dismissed with the IME, with no overlay permission. */
class PetCompanionController(private val service:TrimeInputMethodService) {
    private val mini=PetMiniKeyboardView(service)
    private val popup=PopupWindow(mini,1,1,false).apply {
        isTouchable=true;isOutsideTouchable=false;isSplitTouchEnabled=true
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));animationStyle=0
        inputMethodMode=PopupWindow.INPUT_METHOD_NOT_NEEDED
        // We clamp to system bars ourselves, and need placement above the parent keyboard.
        isClippingEnabled=false
        if(Build.VERSION.SDK_INT>=22) isAttachedInDecor=false
        if(Build.VERSION.SDK_INT>=29) setIsLaidOutInScreen(true)
    }
    private var anchor:View?=null
    private var inputVisible=false
    private var editorAllowed=false
    private var x=0f;private var y=0f
    private var popupWidth=0;private var popupHeight=0
    private var lastSpec=""
    private var appearance=""
    private var menu:PopupMenu?=null
    private var disposed=false
    private var moveQueued=false
    private val moveFrame=Runnable { moveQueued=false;place() }
    private val layoutListener=View.OnLayoutChangeListener { _,_,_,_,_,_,_,_,_ -> update(PetHouse.state.value) }
    private val attachListener=object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v:View) { v.post { if(anchor===v) update(PetHouse.state.value) } }
        override fun onViewDetachedFromWindow(v:View) {
            if(anchor===v) { dismiss();lastSpec="" }
        }
    }
    private val collector:Job
    init {
        mini.onDrag={ dx,dy ->
            if(popup.isShowing) { x+=dx;y+=dy;clamp();if(!moveQueued) { moveQueued=true;mini.postOnAnimation(moveFrame) } }
        }
        mini.onDrop={ mini.removeCallbacks(moveFrame);moveQueued=false;place();persistPosition() }
        mini.onControls={ controls() }
        collector=service.lifecycleScope.launch { PetHouse.state.collect { update(it) } }
    }
    fun showForInput(view:View,info:EditorInfo?) {
        if(disposed) return
        inputVisible=true
        editorAllowed=info!=null && WordCloudVocabulary.allows(info.inputType,info.imeOptions)
        if(anchor!==view) {
            anchor?.removeOnAttachStateChangeListener(attachListener)
            anchor?.removeOnLayoutChangeListener(layoutListener)
            dismiss();anchor=view;lastSpec=""
            view.addOnAttachStateChangeListener(attachListener);view.addOnLayoutChangeListener(layoutListener)
        }
        update(PetHouse.state.value)
    }
    fun hideInput() { TypingGame.stop();inputVisible=false;dismiss();lastSpec="" }
    fun editor(info:EditorInfo?) {
        editorAllowed=info!=null && WordCloudVocabulary.allows(info.inputType,info.imeOptions)
        if(!editorAllowed) dismiss()
    }
    fun toggle() {
        if(!editorAllowed) { Toast.makeText(service,"私密输入框暂不展示迷你键盘",Toast.LENGTH_SHORT).show();return }
        action("mini_toggle")
    }
    fun keyboardChanged(keyboard:Keyboard) { if(popup.isShowing) mini.setKeyboard(keyboard,force=true) }
    fun key(keyboard:Keyboard,key:Key,down:Boolean,cancel:Boolean=false) {
        if(!popup.isShowing || !editorAllowed || KeyboardWindow.currentKeyboardOrNull!==keyboard) return
        mini.setKeyboard(keyboard)
        val index=keyboard.keys.indexOf(key)
        if(index>=0) { if(down) mini.press(index) else mini.release(index,cancel) }
    }
    fun physicalKey(code:Int,down:Boolean) {
        if(!popup.isShowing || !editorAllowed) return
        mini.setKeyboard(KeyboardWindow.currentKeyboardOrNull)
        mini.pressCode(code,down)
    }
    private fun bounds():Rect {
        val metrics=service.resources.displayMetrics
        val insets=anchor?.let { ViewCompat.getRootWindowInsets(it) }?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val edge=(6*metrics.density).roundToInt()
        return Rect((insets?.left ?: 0)+edge,(insets?.top ?: (24*metrics.density).roundToInt())+edge,
            metrics.widthPixels-(insets?.right ?: 0)-edge,metrics.heightPixels-(insets?.bottom ?: 0)-edge)
    }
    private fun landscape()=service.resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE
    private fun update(state:PetHouse.State) {
        if(disposed) return
        val view=anchor
        val pet=state.companion()
        if(!inputVisible || !editorAllowed || !state.started || state.mode==0 || !state.miniExpanded || state.error.isNotEmpty() || pet==null || view?.isAttachedToWindow!=true) {
            dismiss();return
        }
        val signature="${pet.id}/${pet.nickname}/${pet.stage}/${pet.accessory}"
        if(signature!=appearance) { appearance=signature;mini.bind(pet) }
        mini.setKeyboard(KeyboardWindow.currentKeyboardOrNull)
        val area=bounds()
        if(area.width()<40 || area.height()<40) { dismiss();return }
        val density=service.resources.displayMetrics.density
        val sceneHeight=PetMiniScene.HEIGHT
        popupWidth=minOf((154*density*state.miniScale/100f*PetMiniScene.WIDTH/212f).roundToInt(),area.width(),(area.height()*PetMiniScene.WIDTH/sceneHeight).toInt()).coerceAtLeast(1)
        popupHeight=(popupWidth*sceneHeight/PetMiniScene.WIDTH).roundToInt().coerceAtLeast(1)
        val land=landscape()
        val nx=if(land) state.miniLandscapeX else state.miniX
        val ny=if(land) state.miniLandscapeY else state.miniY
        val spec="$land/${area.flattenToString()}/$popupWidth/$popupHeight/$nx/$ny"
        if(spec!=lastSpec || !popup.isShowing) {
            x=if(nx>=0) area.left+(area.width()-popupWidth)*nx else area.left+(12*density)
            y=if(ny>=0) area.top+(area.height()-popupHeight)*ny else service.petKeyboardTopOnScreen()-popupHeight-8*density
            lastSpec=spec
        }
        clamp()
        if(popup.isShowing) place() else {
            popup.width=popupWidth;popup.height=popupHeight
            try {
                val (px,py)=windowPosition()
                popup.showAtLocation(view,Gravity.TOP or Gravity.LEFT,px,py)
            } catch(e:RuntimeException) {
                dismiss();lastSpec=""
                com.osfans.trime.util.DiagnosticLog.event("pet","mini_window_failed","type=${e.javaClass.simpleName}")
            }
        }
    }
    private fun clamp() {
        val area=bounds()
        x=x.coerceIn(area.left.toFloat(),maxOf(area.left,area.right-popupWidth).toFloat())
        y=y.coerceIn(area.top.toFloat(),maxOf(area.top,area.bottom-popupHeight).toFloat())
    }
    private fun windowPosition():Pair<Int,Int> {
        if(Build.VERSION.SDK_INT>=29) return x.roundToInt() to y.roundToInt()
        // Older PopupWindow versions use the containing IME window's origin.
        val root=anchor?.rootView ?: return x.roundToInt() to y.roundToInt()
        val onScreen=IntArray(2);val inWindow=IntArray(2)
        root.getLocationOnScreen(onScreen);root.getLocationInWindow(inWindow)
        return (x-onScreen[0]+inWindow[0]).roundToInt() to (y-onScreen[1]+inWindow[1]).roundToInt()
    }
    private fun place() {
        if(!popup.isShowing) return
        val (px,py)=windowPosition()
        runCatching { popup.update(px,py,popupWidth,popupHeight) }
    }
    private fun persistPosition() {
        val area=bounds()
        val nx=if(area.width()>popupWidth) ((x-area.left)/(area.width()-popupWidth)).coerceIn(0f,1f) else 0f
        val ny=if(area.height()>popupHeight) ((y-area.top)/(area.height()-popupHeight)).coerceIn(0f,1f) else 0f
        action("mini_position","${if(landscape()) "l" else "p"}|$nx|$ny")
    }
    private fun action(name:String,id:String="") {
        service.lifecycleScope.launch {
            val message=PetHouse.act(name,id)
            if(message!="已保存") Toast.makeText(service,message,Toast.LENGTH_SHORT).show()
        }
    }
    private fun controls() {
        if(!popup.isShowing) return
        menu?.dismiss()
        val scale=PetHouse.state.value.miniScale
        menu=PopupMenu(service,mini).apply {
            menu.add(0,6,0,"逗逗它")
            menu.add(0,7,1,"比个心")
            menu.add(0,8,2,"挥爪打招呼")
            menu.add(0,1,3,"收起迷你键盘")
            menu.add(0,2,4,"放大").isEnabled=scale<150
            menu.add(0,3,5,"缩小").isEnabled=scale>60
            menu.add(0,4,6,"重置位置")
            menu.add(0,5,7,"打开键灵小屋")
            menu.add(0,9,8,if(TypingGame.isRunning) "退出打字游戏" else "打字游戏")
            setOnMenuItemClickListener {
                when(it.itemId) {
                    6 -> mini.playInteraction(PetInteraction.PLAY)
                    7 -> mini.playInteraction(PetInteraction.LOVE)
                    8 -> mini.playInteraction(PetInteraction.GREET)
                    1 -> action("mini_toggle")
                    2 -> action("mini_scale",(scale+15).coerceAtMost(150).toString())
                    3 -> action("mini_scale",(scale-15).coerceAtLeast(60).toString())
                    4 -> action("mini_reset")
                    5 -> PetHouseActivity.start(service)
                    9 -> { TypingGame.attach(service); TypingGame.toggle() }
                };true
            }
            runCatching { show() }
        }
    }
    private fun dismiss() { mini.removeCallbacks(moveFrame);moveQueued=false;menu?.dismiss();menu=null;mini.clearPresses();if(popup.isShowing) runCatching { popup.dismiss() } }
    fun dispose() {
        disposed=true;hideInput();collector.cancel()
        anchor?.removeOnAttachStateChangeListener(attachListener);anchor?.removeOnLayoutChangeListener(layoutListener);anchor=null
    }
}
