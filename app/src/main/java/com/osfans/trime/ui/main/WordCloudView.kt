/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.*

/** A stable, non-overlapping typographic cloud. Work is cached outside drawing frames. */
class WordCloudView @JvmOverloads constructor(context: Context, attrs: AttributeSet?=null) : View(context,attrs) {
    private data class Word(val text:String,val x:Float,val baseline:Float,val size:Float,val color:Int,val bold:Boolean,val box:RectF)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var counts=emptyMap<String,Long>()
    private var placed=emptyList<Word>()
    private val density=resources.displayMetrics.density
    private val fontScale=resources.displayMetrics.scaledDensity
    private var cloudName = "词云"
    fun setWords(values:Map<String,Long>, name:String = "词云") {
        cloudName=name
        counts=values.filter { it.key.isNotBlank() && it.value>0 }.toMap()
        arrange();invalidate()
        contentDescription=if(counts.isEmpty()) "$cloudName，开始输入后显示常用${if(cloudName=="字云") "单字" else "词语"}" else
            "$cloudName："+counts.entries.sortedByDescending { it.value }.take(20).joinToString("、") { it.key }
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) { arrange() }
    private fun arrange() {
        if(width<=0 || height<=0) return
        val primary=MaterialColors.getColor(context,androidx.appcompat.R.attr.colorPrimary,0xff446580.toInt())
        val ink=MaterialColors.getColor(context,com.google.android.material.R.attr.colorOnSurface,0xff283948.toInt())
        val palette=intArrayOf(primary,ColorUtils.blendARGB(primary,ink,.24f),ColorUtils.blendARGB(primary,ink,.48f),ink)
        val sorted=counts.entries.sortedWith(compareByDescending<Map.Entry<String,Long>> { it.value }.thenBy { it.key }).take(64)
        val max=sorted.maxOfOrNull { it.value }?.toDouble()?.coerceAtLeast(1.0) ?: 1.0
        val min=sorted.minOfOrNull { it.value }?.toDouble() ?: 0.0
        val result=mutableListOf<Word>();val glyph=Rect()
        val bounds=RectF(14*density,16*density,width-14*density,height-16*density)
        sorted.forEachIndexed { index,item ->
            val ratio=if(max==min) .25f else sqrt((ln(item.value+1.0)-ln(min+1.0))/(ln(max+1.0)-ln(min+1.0))).toFloat()
            paint.textSize=((12f+29f*ratio)*fontScale).coerceAtMost(height*.22f)
            paint.typeface=if(index<6) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            // Long phrases remain readable without forcing every other word out.
            while(paint.measureText(item.key)>bounds.width()*.78f && paint.textSize>12*fontScale) paint.textSize*=.9f
            paint.getTextBounds(item.key,0,item.key.length,glyph)
            var found:Word?=null
            for(step in 0 until 700) {
                val angle=step*2.3999632f+index*.12f
                val radius=sqrt(step.toFloat())*5.5f*density
                val cx=width/2f+cos(angle)*radius
                val cy=height/2f+sin(angle)*radius*.62f
                val box=RectF(cx-glyph.width()/2f,cy-glyph.height()/2f,cx+glyph.width()/2f,cy+glyph.height()/2f)
                val padded=RectF(box).apply { inset(-3*density,-3*density) }
                if(bounds.contains(padded) && result.none { RectF.intersects(it.box,padded) }) {
                    found=Word(item.key,box.left-glyph.left,box.top-glyph.top,paint.textSize,palette[index%palette.size],index<6,padded);break
                }
            }
            found?.let(result::add)
        }
        placed=result
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        if(placed.isEmpty()) {
            paint.typeface=Typeface.DEFAULT;paint.textSize=14*fontScale
            paint.color=MaterialColors.getColor(context,com.google.android.material.R.attr.colorOnSurfaceVariant,0xff697681.toInt())
            val label=if(cloudName=="字云") "开始输入，让常用单字在这里相遇" else if(cloudName=="字词云") "开始输入，让常用字词在这里相遇" else "输入词语，让常用词在这里相遇"
            val visible=android.text.TextUtils.ellipsize(label,android.text.TextPaint(paint),
                (width-28*density).coerceAtLeast(0f),android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(visible,(width-paint.measureText(visible))/2,(height-paint.ascent()-paint.descent())/2,paint)
            return
        }
        placed.forEach { word -> paint.typeface=if(word.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize=word.size;paint.color=word.color;canvas.drawText(word.text,word.x,word.baseline,paint) }
    }
}
