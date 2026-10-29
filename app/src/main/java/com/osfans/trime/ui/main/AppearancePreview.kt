/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.ime.appearance.*

/** Uses the same background renderer as the toolbar; touch a key to preview its pressed state. */
class AppearancePreview(context: Context) : View(context) {
    var config = SurfaceAppearanceConfig()
        set(value) { field=value; rebuild(); invalidate() }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    private var samples=emptyList<SurfaceDrawable>()
    private var bar: SurfaceDrawable?=null
    private val d=resources.displayMetrics.density
    init { contentDescription="外观预览，按住查看按压效果"; isClickable=true }
    private fun rebuild() {
        samples=(0..2).map { SurfaceDrawable(this,ColorDrawable(0x99FFFFFF.toInt()),config.keys,defaultRadius=12f,inset=4f,effects=config.effects) } +
            (0..2).map { SurfaceDrawable(this,ColorDrawable(0x33FFFFFF),config.effectiveToolbar,defaultRadius=10f,inset=3f,effects=config.effectiveToolbarEffects) }
        bar=SurfaceDrawable(this,ColorDrawable(0x22FFFFFF),config.effectiveBar,inset=0f,effects=config.barEffects)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { rebuild() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        rect.set(0f,0f,width.toFloat(),height.toFloat())
        paint.shader=LinearGradient(0f,0f,width.toFloat(),height.toFloat(),intArrayOf(0xFFAAC8DC.toInt(),0xFFE9D5CD.toInt(),0xFFB4C5AE.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,18*d,18*d,paint); paint.shader=null
        bar?.let { it.setBounds((8*d).toInt(),(8*d).toInt(),width-(8*d).toInt(),(51*d).toInt()); it.draw(canvas) }
        (0..5).forEach { i ->
            val top=if(i<3) 58*d else 8*d
            val height=if(i<3) 66*d else 42*d
            val left=8*d+(i%3)*(width-16*d)/3f
            val w=(width-16*d)/3f
            val saved=canvas.save()
            canvas.translate(left,top)
            if(isPressed && i<3) { val scale=1f-config.effects.pressScale/100; canvas.translate(0f,config.effects.pressSink*d); canvas.scale(scale,scale,w/2,height/2) }
            samples.getOrNull(i)?.let { it.setBounds(0,0,w.toInt(),height.toInt()); it.draw(canvas) }
            paint.style=Paint.Style.FILL; paint.color=0xFF29343B.toInt(); paint.textAlign=Paint.Align.CENTER; paint.textSize=if(i<3) 26*d else 17*d
            canvas.drawText(listOf("Q","W","E","•••","中 / EN","⌄")[i],w/2,height/2-(paint.ascent()+paint.descent())/2,paint)
            canvas.restoreToCount(saved)
        }
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) { MotionEvent.ACTION_DOWN -> isPressed=true; MotionEvent.ACTION_UP -> { isPressed=false; performClick() }; MotionEvent.ACTION_CANCEL -> isPressed=false }
        invalidate(); return true
    }
    override fun performClick():Boolean { super.performClick(); return true }
}
