/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.pets.PetCatalog
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.Keyboard
import kotlin.math.abs
import kotlin.math.hypot

/** A miniature of the real layout. Only live touch identities enter this view, never committed text. */
@SuppressLint("ViewConstructor")
class PetMiniKeyboardView(context:Context):View(context) {
    data class MiniKey(val index:Int,val code:Int,val label:String,val bounds:RectF)
    private data class Touch(val key:MiniKey,val started:Long)
    private class Arm(val homeX:Float) {
        var x=homeX;var y=112f;var pressure=0f
        var index=-1
        var animation:ValueAnimator?=null
    }
    private val portrait=PetPortraitView(context).apply { halo=false;layout(0,0,110,110) }
    private val reactions=PetReactionPlayer(this)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontMetrics=Paint.FontMetrics()
    private val path=Path()
    private val left=Arm(77f)
    private val right=Arm(136f)
    private var keys:List<MiniKey> = emptyList()
    private val held=linkedMapOf<Int,Touch>()
    private val releaseTasks=mutableMapOf<Int,Runnable>()
    private var pet=PetHouse.Pet("sheep")
    private var accent=PetCatalog.get("sheep").color
    private var layout:Keyboard?=null
    private var shifted=false
    private var touchX=0f;private var touchY=0f;private var lastX=0f;private var lastY=0f
    private var dragging=false
    private var longClicked=false
    private var tappedPet=false
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    var onDrag:((Float,Float)->Unit)?=null
    var onDrop:(()->Unit)?=null
    var onControls:(()->Unit)?=null
    private val longPress=Runnable { if(!dragging) { longClicked=true;performLongClick() } }
    init {
        isClickable=true;isFocusable=true
        contentDescription="宠物迷你键盘，可拖动摆放；轻点宠物互动，轻点小键盘或长按打开菜单"
        setOnClickListener { if(tappedPet) playInteraction(PetInteraction.PET) else onControls?.invoke() }
        setOnLongClickListener { onControls?.invoke();true }
    }
    fun bind(value:PetHouse.Pet) {
        if(pet==value) return
        val changed=pet.id!=value.id
        pet=value;accent=PetCatalog.get(value.id).color;portrait.bind(value);if(changed) reactions.species(value.id);invalidate()
    }
    fun setKeyboard(keyboard:Keyboard?,force:Boolean=false) {
        if(keyboard==null) return
        if(!force && layout===keyboard && shifted==keyboard.isShifted) return
        clearPresses();layout=keyboard;shifted=keyboard.isShifted
        val width=keyboard.minWidth.coerceAtLeast(1).toFloat()
        val height=keyboard.height.coerceAtLeast(1).toFloat()
        keys=keyboard.keys.take(160).mapIndexedNotNull { i,key ->
            if(key.width<=0 || key.height<=0) null else {
                val code=key.getCode(KeyBehavior.CLICK)
                val label=when(code) {
                    KeyEvent.KEYCODE_DEL -> "⌫";KeyEvent.KEYCODE_ENTER -> "↵"
                    KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_SHIFT_RIGHT -> "⇧"
                    KeyEvent.KEYCODE_SPACE -> "空格";KeyEvent.KEYCODE_TAB -> "⇥"
                    KeyEvent.KEYCODE_DPAD_LEFT -> "←";KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
                    KeyEvent.KEYCODE_DPAD_UP -> "↑";KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
                    else -> runCatching { key.getLabel() }.getOrDefault("").let { s ->
                        if(s.isBlank()) "·" else s.substring(0,s.offsetByCodePoints(0,minOf(4,s.codePointCount(0,s.length))))
                    }
                }
                val rect=RectF(10+key.x/width*192,98+key.y/height*48,10+(key.x+key.width)/width*192,98+(key.y+key.height)/height*48)
                // Very small source gaps remain visible even on tightly packed layouts.
                rect.inset(.65f,.65f)
                MiniKey(i,code,label,rect)
            }
        }
        invalidate()
    }
    fun press(index:Int) {
        val key=keys.firstOrNull { it.index==index } ?: return
        releaseTasks.remove(index)?.let(::removeCallbacks)
        held.remove(index);held[index]=Touch(key,SystemClock.uptimeMillis())
        reactions.key(key.code,((key.bounds.centerX()-106)/96f).coerceIn(-1f,1f))
        updateArms();invalidate()
    }
    fun release(index:Int,cancel:Boolean=false) {
        val touch=held[index] ?: return
        releaseTasks.remove(index)?.let(::removeCallbacks)
        val wait=if(cancel) 0L else (90-(SystemClock.uptimeMillis()-touch.started)).coerceAtLeast(0)
        if(wait==0L) { held.remove(index);updateArms();if(held.isEmpty() && !cancel) reactions.released();invalidate() }
        else {
            val task=Runnable { releaseTasks.remove(index);held.remove(index);updateArms();if(held.isEmpty()) reactions.released();invalidate() }
            releaseTasks[index]=task;postDelayed(task,wait)
        }
    }
    fun pressCode(code:Int,down:Boolean) {
        keys.firstOrNull { it.code==code }?.let { if(down) press(it.index) else release(it.index) }
    }
    private fun updateArms() {
        fun eligible(key:MiniKey,leftHand:Boolean):Boolean =
            if(key.bounds.width()>45 && abs(key.bounds.centerX()-106)<15) true else (key.bounds.centerX()<106)==leftHand
        animateArm(left,held.values.lastOrNull { eligible(it.key,true) }?.key)
        animateArm(right,held.values.lastOrNull { eligible(it.key,false) }?.key)
    }
    private fun animateArm(arm:Arm,key:MiniKey?) {
        arm.animation?.cancel()
        val fromX=arm.x;val fromY=arm.y;val fromPressure=arm.pressure
        val targetX=key?.bounds?.let { if(it.width()>45) it.centerX()+(if(arm===left) -14 else 14) else it.centerX() } ?: arm.homeX
        val targetY=key?.bounds?.centerY() ?: 112f
        val targetPressure=if(key!=null) 1f else 0f
        if(key!=null) arm.index=key.index
        if(Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled()) {
            arm.x=targetX;arm.y=targetY;arm.pressure=targetPressure;invalidate();return
        }
        arm.animation=ValueAnimator.ofFloat(0f,1f).apply {
            duration=if(key!=null) 65 else 145
            interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val t=it.animatedValue as Float
                arm.x=fromX+(targetX-fromX)*t;arm.y=fromY+(targetY-fromY)*t
                arm.pressure=fromPressure+(targetPressure-fromPressure)*t;invalidate()
            }
            start()
        }
    }
    fun playInteraction(interaction:PetInteraction) { reactions.interact(interaction) }
    fun clearPresses() {
        reactions.clear()
        releaseTasks.values.forEach(::removeCallbacks);releaseTasks.clear();held.clear()
        listOf(left,right).forEach { arm -> arm.animation?.cancel();arm.animation=null;arm.x=arm.homeX;arm.y=112f;arm.pressure=0f;arm.index=-1 }
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val scale=minOf(width/212f,height/160f)
        canvas.save();canvas.translate((width-212*scale)/2,(height-160*scale)/2);canvas.scale(scale,scale)
        val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val ink=if(dark) 0xffe5e9ef.toInt() else 0xff4b5662.toInt()
        val body=if(dark) 0xff34404e.toInt() else 0xffe9e9e5.toInt()
        val cap=if(dark) 0xff56616e.toInt() else 0xfffaf9f5.toInt()
        val rim=if(dark) 0xff8692a0.toInt() else 0xffc6cbc9.toInt()
        val contact=maxOf(left.pressure,right.pressure)
        canvas.save();canvas.translate(51f,contact*1.2f);portrait.drawAt(canvas,110f,110f,reactions.pose);canvas.restore()
        paint.style=Paint.Style.FILL;paint.color=0x1b25323d;canvas.drawRoundRect(5f,97f,208f,156f,9f,9f,paint)
        paint.color=body;canvas.drawRoundRect(4f,92f,208f,152f,9f,9f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f;paint.color=rim;canvas.drawRoundRect(4f,92f,208f,152f,9f,9f,paint);paint.style=Paint.Style.FILL
        keys.forEach { key ->
            val rect=key.bounds
            val pressure=if(key.index in held) 1f else maxOf(if(left.index==key.index) left.pressure else 0f,if(right.index==key.index) right.pressure else 0f)
            val push=pressure*1.7f
            paint.color=if(dark) 0xff25313d.toInt() else 0xffbfc6c3.toInt()
            canvas.drawRoundRect(rect.left,rect.top+2,rect.right,rect.bottom+2,2f,2f,paint)
            val functional=key.code==KeyEvent.KEYCODE_ENTER || key.code==KeyEvent.KEYCODE_DEL || key.code==KeyEvent.KEYCODE_SHIFT_LEFT || key.code==KeyEvent.KEYCODE_SHIFT_RIGHT
            val normal=if(functional) ColorUtils.blendARGB(cap,accent,if(dark) .30f else .42f) else cap
            paint.color=ColorUtils.blendARGB(normal,accent,pressure*.75f)
            canvas.drawRoundRect(rect.left,rect.top+push,rect.right,rect.bottom+push,2f,2f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=.5f;paint.color=rim
            canvas.drawRoundRect(rect.left,rect.top+push,rect.right,rect.bottom+push,2f,2f,paint);paint.style=Paint.Style.FILL
            paint.color=ink;paint.typeface=Typeface.DEFAULT;paint.textAlign=Paint.Align.CENTER
            paint.textSize=minOf(7.8f,rect.height()*.6f).coerceAtLeast(3f)
            val textWidth=paint.measureText(key.label)
            if(textWidth>rect.width()-2) paint.textSize*=((rect.width()-2)/textWidth).coerceIn(.4f,1f)
            paint.getFontMetrics(fontMetrics)
            canvas.drawText(key.label,rect.centerX(),rect.centerY()-(fontMetrics.ascent+fontMetrics.descent)/2+push,paint)
        }
        drawArm(canvas,left,82f,75f,dark);drawArm(canvas,right,130f,75f,dark)
        canvas.restore()
    }
    private fun drawArm(canvas:Canvas,arm:Arm,shoulderX:Float,shoulderY:Float,dark:Boolean) {
        val pose=reactions.pose
        val sx=106+(shoulderX-106)*(1+pose.squash)
        val sy=shoulderY+pose.y*.86f+(shoulderX-106)*(pose.angle*Math.PI/180).toFloat()
        var endX=arm.x;var endY=arm.y
        if(held.isEmpty() && pose.amount>0 && pose.interaction!=null) {
            val leftHand=arm===left;val side=if(leftHand) -1 else 1
            val wave=kotlin.math.sin(pose.phase*Math.PI*6).toFloat()
            val target=when(pose.interaction) {
                PetInteraction.GREET -> if(leftHand) arm.homeX to 112f else (151f+wave*6) to 65f
                PetInteraction.LOVE -> (106f+side*6) to 87f
                PetInteraction.PLAY -> (106f+side*(8+wave*4)) to 94f
                PetInteraction.EAT -> (106f+side*7) to (85f+wave*1.5f)
                PetInteraction.YAWN -> (106f+side*43) to 76f
                PetInteraction.LEVEL_UP -> (106f+side*45) to 62f
                else -> arm.homeX to 108f
            }
            endX+=(target.first-endX)*pose.amount;endY+=(target.second-endY)*pose.amount
        }
        path.reset();path.moveTo(sx,sy)
        path.quadTo(sx+(endX-sx)*.25f,endY-15,endX,endY)
        paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND;paint.strokeWidth=10f;paint.color=ColorUtils.blendARGB(accent,if(dark) Color.BLACK else 0xff64716b.toInt(),.12f)
        canvas.drawPath(path,paint);paint.strokeWidth=7.6f;paint.color=accent;canvas.drawPath(path,paint)
        paint.style=Paint.Style.FILL;paint.color=ColorUtils.blendARGB(accent,Color.WHITE,.20f)
        val y=endY+arm.pressure*1.4f
        canvas.drawOval(endX-6f,y-3.8f,endX+6f,y+3.8f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f;paint.color=ColorUtils.blendARGB(accent,0xff46505a.toInt(),.35f)
        canvas.drawLine(endX-2,y-.6f,endX-2,y+1.5f,paint);canvas.drawLine(endX+1,y-.6f,endX+1,y+1.5f,paint);paint.style=Paint.Style.FILL
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX=event.rawX;touchY=event.rawY;lastX=touchX;lastY=touchY;dragging=false;longClicked=false
                tappedPet=event.y<height*.58f
                postDelayed(longPress,ViewConfiguration.getLongPressTimeout().toLong());return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(!dragging && hypot(event.rawX-touchX,event.rawY-touchY)>touchSlop) { dragging=true;removeCallbacks(longPress) }
                if(dragging) { onDrag?.invoke(event.rawX-lastX,event.rawY-lastY);lastX=event.rawX;lastY=event.rawY };return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if(dragging) onDrop?.invoke() else if(!longClicked) performClick()
                dragging=false;return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress);if(dragging) onDrop?.invoke();dragging=false;return true }
        }
        return true
    }
    override fun performClick():Boolean=super.performClick()
    override fun onDetachedFromWindow() { removeCallbacks(longPress);clearPresses();super.onDetachedFromWindow() }
}
