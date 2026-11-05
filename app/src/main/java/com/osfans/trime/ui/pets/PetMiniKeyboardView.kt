/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import kotlin.math.hypot

/** A miniature of the real layout. Only live touch identities enter this view, never committed text. */
@SuppressLint("ViewConstructor")
class PetMiniKeyboardView(context:Context):View(context) {
    data class MiniKey(val index:Int,val code:Int,val label:String,val bounds:RectF)
    private val portrait=PetPortraitView(context).apply { halo=false;layout(0,0,80,80) }
    private val reactions=PetReactionPlayer(this)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val fontMetrics=Paint.FontMetrics()
    private val stance=PetMiniStance()
    private var soleX=PetMiniScene.CENTER
    private var soleY=PetMiniScene.BOARD_TOP
    private var jumpY=0f
    private var landingPressure=0f
    private var landingIndex=-1
    private var sequence=0
    private var lastPress=0L
    private var bodyAnimation:ValueAnimator?=null
    private val rest=Runnable { if(held.isEmpty()) hopTo(null) }
    private var keys:List<MiniKey> = emptyList()
    private val held=linkedSetOf<Int>()
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
        if(changed) clearPresses()
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
                val rect=RectF(36+key.x/width*192,98+key.y/height*48,36+(key.x+key.width)/width*192,98+(key.y+key.height)/height*48)
                // Very small source gaps remain visible even on tightly packed layouts.
                rect.inset(.65f,.65f)
                MiniKey(i,code,label,rect)
            }
        }
        invalidate()
    }
    fun press(index:Int) {
        val key=keys.firstOrNull { it.index==index } ?: return
        removeCallbacks(rest)
        held.remove(index);held.add(index)
        reactions.key(key.code,((key.bounds.centerX()-PetMiniScene.CENTER)/96f).coerceIn(-1f,1f))
        // Restart even on the same key: each down is a new little hop, not a hand reach.
        val now=SystemClock.uptimeMillis()
        val duration=if(lastPress==0L) 280L else ((now-lastPress)*.85f).toLong().coerceIn(110L,280L)
        lastPress=now;hopTo(key,duration);invalidate()
    }
    fun release(index:Int,cancel:Boolean=false) {
        if(!held.remove(index)) return
        if(held.isEmpty()) {
            if(!cancel) reactions.released()
            removeCallbacks(rest);postDelayed(rest,if(cancel) 260 else 700)
        }
        invalidate()
    }
    fun pressCode(code:Int,down:Boolean) {
        keys.firstOrNull { it.code==code }?.let { if(down) press(it.index) else release(it.index) }
    }
    /** The selected sole lands on the actual key center, with a short cushioned landing. */
    private fun hopTo(key:MiniKey?,durationMs:Long=280L) {
        val oldX=soleX;val fromY=soleY+jumpY
        val oldFoot=stance.soleX(pet.id)
        val previous=bodyAnimation;bodyAnimation=null;previous?.cancel()
        val targetX=key?.bounds?.centerX() ?: PetMiniScene.CENTER
        val targetY=key?.bounds?.centerY() ?: PetMiniScene.BOARD_TOP
        if(key!=null) { sequence++;stance.leftFoot=sequence%2==0;stance.variant=sequence%3 }
        val fromX=oldX+(stance.soleX(pet.id)-oldFoot)*PetMiniScene.MODEL_SIDE/128f
        val targetIndex=key?.index ?: -1
        landingIndex=-1;landingPressure=0f
        val direction=(targetX-fromX).coerceIn(-20f,20f)/20f
        if(Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled()) {
            soleX=targetX;soleY=targetY;jumpY=0f;stance.lift=0f;stance.bend=0f;stance.lean=0f
            landingIndex=targetIndex;landingPressure=if(targetIndex in held) 1f else 0f;invalidate();return
        }
        bodyAnimation=ValueAnimator.ofFloat(0f,1f).apply {
            duration=durationMs
            interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t=it.animatedValue as Float
                val flight=(t/.76f).coerceAtMost(1f)
                val ease=flight*flight*(3-2*flight)
                soleX=fromX+(targetX-fromX)*ease
                soleY=fromY+(targetY-fromY)*ease
                stance.lift=kotlin.math.sin(flight*Math.PI).toFloat().coerceAtLeast(0f)
                // Keep the entire head in the scene, including a hop from the upper row.
                jumpY=maxOf(-stance.lift*16f,PetMiniScene.MODEL_SIDE*125/128f+2-soleY).coerceAtMost(0f)
                stance.lean=direction*stance.lift*7f
                val settle=((t-.76f)/.24f).coerceIn(0f,1f)
                stance.bend=if(t<.76f) stance.lift*.22f else kotlin.math.sin(settle*Math.PI).toFloat()*.75f
                landingIndex=if(t>=.76f) targetIndex else -1
                landingPressure=if(t>=.76f && key!=null) 1f-settle*.45f else 0f
                invalidate()
            }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:Animator) {
                    if(bodyAnimation!==animation) return
                    bodyAnimation=null;stance.lift=0f;stance.bend=0f;stance.lean=0f;jumpY=0f
                    landingPressure=0f;invalidate()
                }
            })
            start()
        }
    }
    fun playInteraction(interaction:PetInteraction) { reactions.interact(interaction) }
    fun clearPresses() {
        reactions.clear();removeCallbacks(rest)
        val previous=bodyAnimation;bodyAnimation=null;previous?.cancel()
        soleX=PetMiniScene.CENTER;soleY=PetMiniScene.BOARD_TOP;jumpY=0f
        landingIndex=-1;landingPressure=0f;lastPress=0L;stance.reset();held.clear();invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val sceneHeight=PetMiniScene.HEIGHT
        val scale=minOf(width/PetMiniScene.WIDTH,height/sceneHeight)
        canvas.save();canvas.translate((width-PetMiniScene.WIDTH*scale)/2,(height-sceneHeight*scale)/2);canvas.scale(scale,scale)
        val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        val ink=if(dark) 0xffe5e9ef.toInt() else 0xff4b5662.toInt()
        val body=if(dark) 0xff34404e.toInt() else 0xffe9e9e5.toInt()
        val cap=if(dark) 0xff56616e.toInt() else 0xfffaf9f5.toInt()
        val rim=if(dark) 0xff8692a0.toInt() else 0xffc6cbc9.toInt()
        val boardBottom=PetMiniScene.BOARD_BOTTOM
        paint.style=Paint.Style.FILL;paint.color=0x1b25323d;canvas.drawRoundRect(31f,97f,234f,boardBottom+4,9f,9f,paint)
        paint.color=body;canvas.drawRoundRect(30f,92f,234f,boardBottom,9f,9f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f;paint.color=rim;canvas.drawRoundRect(30f,92f,234f,boardBottom,9f,9f,paint);paint.style=Paint.Style.FILL
        keys.forEach { key ->
            val rect=key.bounds
            val pressure=maxOf(if(key.index in held) 1f else 0f,if(landingIndex==key.index) landingPressure else 0f)
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
        // All companions stand above the caps. The shadow tightens as a foot lands.
        val ground=soleY+(if(landingIndex>=0 && (landingIndex in held || landingPressure>0)) 1.7f else 0f)
        paint.style=Paint.Style.FILL;paint.color=ColorUtils.setAlphaComponent(ink,((1-stance.lift*.65f)*48).toInt())
        canvas.drawOval(soleX-6f-stance.lift*2,ground-1.2f,soleX+6f+stance.lift*2,ground+1.2f,paint)
        drawCompanion(canvas)
        canvas.restore()
    }
    private fun drawCompanion(canvas:Canvas) {
        val scale=PetMiniScene.MODEL_SIDE/128f
        val press=if(landingIndex>=0 && (landingIndex in held || landingPressure>0)) 1.7f else 0f
        val x=soleX-stance.soleX(pet.id)*scale
        val y=soleY+jumpY+press-stance.soleY*scale
        canvas.save();canvas.translate(x,y)
        portrait.drawAt(canvas,PetMiniScene.MODEL_SIDE,PetMiniScene.MODEL_SIDE,reactions.pose,miniature=true,step=stance)
        canvas.restore()
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX=event.rawX;touchY=event.rawY;lastX=touchX;lastY=touchY;dragging=false;longClicked=false
                val sceneScale=minOf(width/PetMiniScene.WIDTH,height/PetMiniScene.HEIGHT)
                val px=(event.x-(width-PetMiniScene.WIDTH*sceneScale)/2)/sceneScale
                val py=(event.y-(height-PetMiniScene.HEIGHT*sceneScale)/2)/sceneScale
                val sx=soleX-stance.soleX(pet.id)*PetMiniScene.MODEL_SIDE/128f
                val sy=soleY+jumpY-stance.soleY*PetMiniScene.MODEL_SIDE/128f
                tappedPet=px in sx..(sx+PetMiniScene.MODEL_SIDE) && py in sy..(sy+PetMiniScene.MODEL_SIDE)
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
