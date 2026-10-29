/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View

/** Background-only glass; labels and icons are drawn by the host afterwards. */
class SurfaceDrawable(
    private val host: View,
    private val fallback: Drawable?,
    private val style: SurfaceStyle,
    private val defaultRadius: Float = 8f,
    private val inset: Float = 3f,
    private val defaultBorderColor: Int = 0x66FFFFFF,
    private val effects: KeyEffects = KeyEffects(),
) : Drawable(), Drawable.Callback {
    private val fill = if (style.background.isBlank()) fallback else if (style.background.startsWith("/"))
        com.osfans.trime.data.theme.AssetBitmapDrawable(style.background)
    else android.graphics.drawable.ColorDrawable(runCatching { Color.parseColor(style.background) }.getOrDefault(Color.TRANSPARENT))
    private val area = RectF()
    private val borderArea = RectF()
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var effectSize = ""
    private var shadow: EffectBitmap? = null
    private var inner: EffectBitmap? = null
    private var shader: Shader? = null
    private var borderShader: Shader? = null
    init { fill?.callback = this }
    private fun prepare(radius: Float, d: Float) {
        val key = "${area.width()}:${area.height()}:$radius:$d"
        if (key == effectSize) return
        effectSize = key
        shadow = if (effects.shadow.enabled) effects.shadow.let { KeyEffectPainter.shadowBitmap(area.width(),area.height(),radius,it.blur*d,it.spread*d,KeyEffectPainter.color(it.color,0x22000000)) } else null
        inner = if (effects.innerShadow.enabled) effects.innerShadow.let { KeyEffectPainter.innerShadowBitmap(area.width(),area.height(),radius,it.blur*d,it.offsetX*d,it.offsetY*d,KeyEffectPainter.color(it.color,0x22000000)) } else null
        shader = if(effects.gradient.enabled) KeyEffectPainter.gradientShader(area,effects.gradient) else null
        borderShader = if(effects.borderGradient.enabled) effects.borderGradient.let { KeyEffectPainter.gradientShader(area,KeyGradient(enabled=true,startColor=it.startColor,endColor=it.endColor,angle=it.angle)) } else null
    }
    override fun draw(canvas: Canvas) {
        val d = host.resources.displayMetrics.density
        area.set(bounds); area.inset(inset * d, inset * d)
        if (area.width() <= 0 || area.height() <= 0) return
        val radius = ((style.radius ?: defaultRadius) * d).coerceAtLeast(0f)
        prepare(radius,d)
        paint.style=Paint.Style.FILL; paint.shader=null; paint.alpha=255
        shadow?.draw(canvas,area.left+effects.shadow.offsetX*d,area.top+effects.shadow.offsetY*d,paint)
        val saved = canvas.save()
        clip.reset(); clip.addRoundRect(area, radius, radius, Path.Direction.CW); canvas.clipPath(clip)
        val blurred = style.blur && BackdropBlur.draw(host, canvas, area)
        fill?.let {
            it.bounds = Rect(area.left.toInt(), area.top.toInt(), area.right.toInt(), area.bottom.toInt())
            val layer = if (blurred) canvas.saveLayerAlpha(area, SurfaceAppearance.current().glassOpacity * 255 / 100) else -1
            it.draw(canvas)
            if (layer >= 0) canvas.restoreToCount(layer)
        }
        if (style.blur) canvas.drawColor(if (host.isPressed) 0x33FFFFFF else 0x18FFFFFF)
        paint.style=Paint.Style.FILL; paint.shader=null; paint.alpha=255
        if (effects.edge3d) { paint.color=KeyEffectPainter.color(effects.edgeColor,0x22000000); canvas.drawRect(area.left,area.bottom-effects.edgeDepth*d,area.right,area.bottom,paint) }
        inner?.draw(canvas,area.left,area.top,paint)
        shader?.let { paint.shader=it; paint.alpha=effects.gradient.opacity*255/100; canvas.drawRect(area,paint); paint.shader=null; paint.alpha=255 }
        if (effects.gloss) {
            paint.shader=LinearGradient(0f,area.top,0f,area.bottom,Color.argb(effects.glossStrength*255/100,255,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            canvas.drawRect(area,paint); paint.shader=null
        }
        if (effects.noise) { paint.shader=KeyEffectPainter.noiseShader; paint.alpha=effects.noiseStrength*255/100; canvas.drawRect(area,paint); paint.shader=null; paint.alpha=255 }
        if(host.isPressed && effects.pressDarken>0) canvas.drawColor(Color.argb(effects.pressDarken*255/100,0,0,0))
        canvas.restoreToCount(saved)
        if(effects.borderGradient.enabled) {
            val width=effects.borderGradient.width*d
            borderArea.set(area); borderArea.inset(width/2,width/2)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=width; paint.shader=borderShader
            canvas.drawRoundRect(borderArea,(radius-width/2).coerceAtLeast(0f),(radius-width/2).coerceAtLeast(0f),paint)
            paint.shader=null
        }
        val stroke = (style.borderWidth ?: if (style.borderColor.isNotEmpty()) 1f else 0f) * d
        if (stroke > 0f) {
            borderArea.set(area); borderArea.inset(stroke / 2, stroke / 2)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = style.color(defaultBorderColor)
            canvas.drawRoundRect(borderArea, (radius - stroke / 2).coerceAtLeast(0f), (radius - stroke / 2).coerceAtLeast(0f), paint)
        }
    }
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { fill?.state = state; invalidateSelf(); return true }
    override fun setAlpha(alpha: Int) { fill?.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill?.colorFilter = colorFilter }
    @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}
