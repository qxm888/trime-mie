/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import android.view.View
import com.osfans.trime.ime.voice.VoiceRecordingState
import com.osfans.trime.ime.voice.VoiceRecordingState.Phase
import kotlin.math.*

/** Quiet, theme-colored voice feedback. Waves follow real RMS; busy states never imitate audio. */
class VoiceRecordingView(context: Context, color: Int) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private var foreground = color
    private var glow: Shader? = null
    private val glowMatrix = Matrix()
    private var state = VoiceRecordingState()
    private var amplitude = 0f
    private var lastFrame = 0L
    private val frame = Runnable { invalidate() }
    private fun motionEnabled() = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f }.getOrDefault(true)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        isClickable = false; isFocusable = false
    }
    fun update(next: VoiceRecordingState) {
        if (state.phase != next.phase) {
            contentDescription = when(next.phase) {
                Phase.RECORDING -> "正在录音，松开空格结束"
                Phase.CONNECTING -> "正在连接语音服务"
                Phase.LOADING_MODEL -> "正在加载语音模型"
                Phase.PROCESSING -> "正在识别语音"
                Phase.IDLE -> ""
            }
        }
        state = next.copy(level=if(next.level.isFinite()) next.level.coerceIn(0f,1f) else 0f)
        if (!state.active) { amplitude=0f;lastFrame=0L;removeCallbacks(frame) }
        invalidate()
    }
    fun setForegroundColor(color: Int) { if (foreground==color) return;foreground=color;rebuildGlow();invalidate() }
    private fun tinted(alpha: Int) = Color.argb(Color.alpha(foreground)*alpha/255,Color.red(foreground),Color.green(foreground),Color.blue(foreground))
    private fun rebuildGlow() {
        if (width<=0 || height<=0) return
        glow=RadialGradient(width/2f,height/2f,22*d,intArrayOf(tinted(25),tinted(8),Color.TRANSPARENT),floatArrayOf(0f,0.65f,1f),Shader.TileMode.CLAMP)
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);rebuildGlow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!state.active || width<=0 || height<=0) return
        val now=SystemClock.elapsedRealtime()
        val motion=motionEnabled()
        val elapsed=if(lastFrame==0L) 33f else (now-lastFrame).coerceIn(0L,100L).toFloat()
        lastFrame=now
        val target=if(state.recording) state.level.pow(0.8f) else 0f
        val easing=1f-exp(-elapsed/if(target>amplitude) 55f else 190f)
        amplitude=if(motion) amplitude+(target-amplitude)*easing else target
        val cx=width/2f
        val busy=state.phase!=Phase.RECORDING
        val cy=if(busy) height/2f-4*d else height/2f
        val scale=(height/(40*d)).coerceIn(0.6f,1f)
        val save=canvas.save()
        canvas.scale(scale,scale,cx,cy)
        glowMatrix.setTranslate(0f,cy-height/2f);glow?.setLocalMatrix(glowMatrix)
        paint.style=Paint.Style.FILL;paint.shader=glow;paint.alpha=255
        canvas.drawCircle(cx,cy,(18f+amplitude*3f)*d,paint)
        paint.shader=null
        // A fine amplitude ring is still at silence; it expands only with actual input.
        paint.style=Paint.Style.STROKE;paint.strokeWidth=d;paint.color=tinted(if(busy) 24 else (24+amplitude*40).toInt())
        canvas.drawCircle(cx,cy,(15f+amplitude*2f)*d,paint)
        paint.color=tinted(235);paint.strokeWidth=1.8f*d
        rect.set(cx-3.5f*d,cy-9*d,cx+3.5f*d,cy+2*d)
        canvas.drawRoundRect(rect,3.5f*d,3.5f*d,paint)
        rect.set(cx-6.5f*d,cy-4*d,cx+6.5f*d,cy+6*d)
        canvas.drawArc(rect,0f,180f,false,paint)
        canvas.drawLine(cx,cy+6*d,cx,cy+9.5f*d,paint)
        canvas.drawLine(cx-3.5f*d,cy+9.5f*d,cx+3.5f*d,cy+9.5f*d,paint)
        if(busy) {
            val rotation=if(motion) now%1600/1600f*360f else -90f
            rect.set(cx-15*d,cy-15*d,cx+15*d,cy+15*d)
            paint.strokeWidth=1.5f*d;paint.color=tinted(125)
            canvas.drawArc(rect,rotation,85f,false,paint)
        }
        canvas.restoreToCount(save)
        // Three thin harmonics share the same RMS envelope and taper into quiet end caps.
        val span=minOf(142*d,(width/2f-44*d).coerceAtLeast(0f))
        val waveHeight=minOf(11*d,height*0.28f)*amplitude
        val phase=if(motion && state.recording) (now%100000L)/420.0 else 0.0
        repeat(2) { side ->
            val direction=if(side==0) -1 else 1
            repeat(if(state.recording) 3 else 1) { layer ->
                path.reset()
                for(i in 0..48) {
                    val t=i/48f
                    val x=cx+direction*(25*d+span*t)
                    val envelope=sin(PI*t).toFloat().pow(1.4f)
                    val harmonic=(sin(t*PI*4-phase+layer*2.094395)*0.72+sin(t*PI*7.5-phase*1.25-layer*1.1)*0.28).toFloat()
                    val y=cy+waveHeight*envelope*harmonic*(1f-layer*0.08f)
                    if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                paint.style=Paint.Style.STROKE;paint.strokeWidth=(1.7f-layer*0.2f)*d
                paint.color=tinted(if(layer==0) if(state.recording) 205 else 28 else if(layer==1) 145 else 100)
                canvas.drawPath(path,paint)
            }
        }
        if(busy && height>=32*d) {
            paint.style=Paint.Style.FILL;paint.color=tinted(170);paint.textAlign=Paint.Align.CENTER
            paint.textSize=9*d;paint.typeface=Typeface.DEFAULT
            val label=when(state.phase) { Phase.CONNECTING->"连接中";Phase.LOADING_MODEL->"加载中";else->"识别中" }
            canvas.drawText(label,cx,height-3*d,paint)
        }
        removeCallbacks(frame)
        if(motion && isAttachedToWindow && isShown && windowVisibility==VISIBLE) postDelayed(frame,33)
    }
    override fun onVisibilityChanged(changedView: View,visibility: Int) {
        super.onVisibilityChanged(changedView,visibility)
        if(visibility==VISIBLE && state.active) invalidate() else removeCallbacks(frame)
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if(visibility==VISIBLE && state.active) invalidate() else removeCallbacks(frame)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();if(state.active) invalidate() }
    override fun onDetachedFromWindow() { removeCallbacks(frame);lastFrame=0L;super.onDetachedFromWindow() }
}
