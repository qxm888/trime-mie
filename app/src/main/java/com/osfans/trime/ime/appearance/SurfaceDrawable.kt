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
) : Drawable(), Drawable.Callback {
    private val fill = if (style.background.isBlank()) fallback else if (style.background.startsWith("/"))
        com.osfans.trime.data.theme.AssetBitmapDrawable(style.background)
    else android.graphics.drawable.ColorDrawable(runCatching { Color.parseColor(style.background) }.getOrDefault(Color.TRANSPARENT))
    private val area = RectF()
    private val borderArea = RectF()
    private val clip = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { fill?.callback = this }
    override fun draw(canvas: Canvas) {
        val d = host.resources.displayMetrics.density
        area.set(bounds); area.inset(inset * d, inset * d)
        if (area.width() <= 0 || area.height() <= 0) return
        val radius = ((style.radius ?: defaultRadius) * d).coerceAtLeast(0f)
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
        canvas.restoreToCount(saved)
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
