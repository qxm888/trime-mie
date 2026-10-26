/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import com.osfans.trime.ime.core.FloatingResizeHandleView

internal fun checkGripDrawing(context: android.content.Context) {
    for(left in listOf(true,false)) for(radius in listOf(0f,12f,24f,48f)) {
        val h=FloatingResizeHandleView(context,left,{},{_,_->},{})
        h.layoutParams=ViewGroup.LayoutParams(1,1)
        val density=context.resources.displayMetrics.density
        h.setCornerGeometry(radius*density,radius*density)
        h.measure(View.MeasureSpec.makeMeasureSpec(h.layoutParams.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h.layoutParams.height,View.MeasureSpec.EXACTLY))
        h.layout(0,0,h.measuredWidth,h.measuredHeight)
        val bitmap=Bitmap.createBitmap(h.width,h.height,Bitmap.Config.ARGB_8888)
        h.draw(Canvas(bitmap))
        val path=h.javaClass.getDeclaredField("contour").apply{isAccessible=true}.get(h) as Path
        val pm=PathMeasure(path,false)
        val xy=FloatArray(2)
        for(i in 5..95 step 5) {
            pm.getPosTan(pm.length*i/100f,xy,null)
            val color=bitmap.getPixel(xy[0].toInt(),xy[1].toInt())
            check(Color.red(color)>245 && Color.green(color)>245 && Color.blue(color)>245 && Color.alpha(color)>240){"Grip white center has a gap radius=$radius left=$left"}
        }
        check(!pm.nextContour()) { "Grip uses disconnected contours" }
        val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
        check(pixels.any { Color.red(it)>225 && Color.green(it) in 110..170 && Color.blue(it) in 150..210 && Color.alpha(it)>200 }) { "Missing pink outline" }
        bitmap.recycle()
    }
    // Export only synthetic app-owned geometry, never the phone screen or user keyboard.
    val density=context.resources.displayMetrics.density
    fun px(value:Float)=value*density
    val preview=Bitmap.createBitmap(px(360f).toInt(),px(220f).toInt(),Bitmap.Config.ARGB_8888)
    val canvas=Canvas(preview)
    canvas.drawColor(Color.rgb(255,248,251))
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color=Color.rgb(61,52,64)
    canvas.drawRoundRect(RectF(px(40f),px(34f),px(320f),px(174f)),px(24f),px(24f),paint)
    paint.color=Color.rgb(238,225,233)
    for(row in 0..2) for(col in 0..6) {
        val x=px(54f+col*36f);val y=px(61f+row*30f)
        canvas.drawRoundRect(RectF(x,y,x+px(30f),y+px(23f)),px(5f),px(5f),paint)
    }
    paint.color=Color.rgb(115,79,100);paint.textSize=px(12f)
    canvas.drawText("缩放条外观示意 · 实际原生控件绘制",px(66f),px(207f),paint)
    for(left in listOf(true,false)) {
        val h=FloatingResizeHandleView(context,left,{},{_,_->},{})
        h.layoutParams=ViewGroup.LayoutParams(1,1)
        h.setCornerGeometry(px(24f),px(24f))
        h.measure(View.MeasureSpec.makeMeasureSpec(h.layoutParams.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h.layoutParams.height,View.MeasureSpec.EXACTLY))
        h.layout(0,0,h.measuredWidth,h.measuredHeight)
        canvas.save();canvas.translate(px(if(left)40f else 320f)-h.width/2f,px(174f)-h.height/2f)
        h.draw(canvas);canvas.restore()
    }
    java.io.File(context.cacheDir,"mie15-compact-grips.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)}
    preview.recycle()

}
