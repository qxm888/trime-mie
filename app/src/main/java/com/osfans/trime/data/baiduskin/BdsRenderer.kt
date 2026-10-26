/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.baiduskin

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import kotlin.math.cos

object SkinBitmaps {
    fun get(path: String): Bitmap? = com.osfans.trime.data.theme.BitmapAssets.load(path)?.bitmap
}

object SkinPainter {
    fun duration(animation: SkinTween) = animation.delay + animation.duration * (animation.repeats + 1)
    class Buffer {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val source = Rect()
        val target = RectF()
    }
    @JvmOverloads
    fun paint(canvas: Canvas, style: ImportedKeyStyle, box: RectF, active: Boolean = false, elapsed: Long = 0, alpha: Float = 1f,
        buffer: Buffer = Buffer(), image: (String) -> com.osfans.trime.data.theme.BitmapAssets.Image? = { com.osfans.trime.data.theme.BitmapAssets.load(it) }) {
        val paint = buffer.paint
        val scaleX = box.width() / style.sourceWidth.coerceAtLeast(1f)
        val scaleY = box.height() / style.sourceHeight.coerceAtLeast(1f)
        style.layers.forEach { layer ->
            val source = if (active) layer.pressed ?: layer.normal else layer.normal
            if (source == null || source.crop.size != 4) return@forEach
            val decoded = image(source.file) ?: return@forEach
            val bitmap = decoded.bitmap
            val crop = source.crop
            if (crop[0] < 0 || crop[1] < 0 || crop[2] <= 0 || crop[3] <= 0 || crop[0].toLong() + crop[2] > decoded.sourceWidth || crop[1].toLong() + crop[3] > decoded.sourceHeight) return@forEach
            var sx = 1f; var sy = 1f; var dx = 0f; var dy = 0f; var opacity = if (layer.normal == null) alpha else 1f
            if (active) layer.animations.forEach { anim ->
                val progress = progress(anim, elapsed)
                when (anim.type) {
                    0 -> opacity *= value(anim, progress, 0, 255f) / 255f
                    2 -> { dx += value(anim, progress, 0, 0f) * scaleX; dy += value(anim, progress, 1, 0f) * scaleY }
                    4 -> { sx *= value(anim, progress, 0, 100f) / 100f; sy *= value(anim, progress, 1, value(anim, progress, 0, 100f)) / 100f }
                }
            }
            val target = buffer.target
            if (layer.fit) target.set(box) else {
                val w = crop[2] * scaleX; val h = crop[3] * scaleY
                target.set(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2)
            }
            paint.alpha = (opacity.coerceIn(0f, 1f) * 255).toInt()
            canvas.save()
            canvas.translate(dx, dy)
            val pivot = layer.animations.firstOrNull()?.pivot.orEmpty()
            canvas.scale(sx, sy, box.left + box.width() * pivot.getOrElse(0) { 50f } / 100f,
                box.top + box.height() * pivot.getOrElse(1) { 50f } / 100f)
            val x = bitmap.width.toFloat() / decoded.sourceWidth
            val y = bitmap.height.toFloat() / decoded.sourceHeight
            buffer.source.set((crop[0] * x).toInt(), (crop[1] * y).toInt(),
                ((crop[0].toLong() + crop[2]) * x).toInt().coerceAtMost(bitmap.width),
                ((crop[1].toLong() + crop[3]) * y).toInt().coerceAtMost(bitmap.height))
            canvas.drawBitmap(bitmap, buffer.source, target, paint)
            canvas.restore()
        }
    }
    private fun value(animation: SkinTween, progress: Float, index: Int, fallback: Float): Float {
        val start = animation.from.getOrNull(index) ?: return fallback
        return start + (animation.to.getOrElse(index) { start } - start) * progress
    }
    private fun progress(animation: SkinTween, elapsed: Long): Float {
        val local = (elapsed - animation.delay).coerceAtLeast(0)
        val duration = animation.duration.coerceAtLeast(1)
        val cycle = minOf((local / duration).toInt(), animation.repeats)
        var progress = if (local >= duration.toLong() * (animation.repeats + 1)) 1f else (local % duration).toFloat() / duration
        if (animation.reverse && cycle % 2 == 1) progress = 1f - progress
        progress = when(animation.interpolator) {
            1 -> progress * progress
            2 -> 1 - (1 - progress) * (1 - progress)
            3 -> ((1 - cos(Math.PI * progress)) / 2).toFloat()
            else -> progress
        }
        return progress
    }
}

class BdsKeyDrawable(val style: ImportedKeyStyle) : Drawable(), com.osfans.trime.data.theme.BitmapAssets.Observer {
    private val images = mutableMapOf<String, com.osfans.trime.data.theme.BitmapAssets.Image>()
    private val buffer = SkinPainter.Buffer()
    private val box = RectF()
    private var generation = -1L
    override fun onImageReady() { invalidateSelf() }
    private val lookup: (String) -> com.osfans.trime.data.theme.BitmapAssets.Image? = { path ->
        images[path] ?: com.osfans.trime.data.theme.BitmapAssets.request(path, this)?.also { images[path] = it }
    }
    private var pressed = false
    private var started = -1L
    private var released = -1L
    private val duration = style.layers.flatMap { it.animations }.maxOfOrNull(SkinPainter::duration)?.coerceAtMost(6000) ?: 120
    val animating: Boolean get() {
        val now = SystemClock.uptimeMillis()
        return started >= 0 && (now - started < duration || released >= 0 && now - released < 120)
    }
    fun press(value: Boolean) {
        if (pressed == value) return
        pressed = value
        if (value) { started = SystemClock.uptimeMillis(); released = -1 }
        else released = SystemClock.uptimeMillis()
        invalidateSelf()
    }
    override fun draw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val active = pressed || animating
        val opacity = if (released < 0) 1f else (1f - (now - released).toFloat() / 120f).coerceIn(0f, 1f)
        if (generation != com.osfans.trime.data.theme.BitmapAssets.generation) {
            images.clear(); generation = com.osfans.trime.data.theme.BitmapAssets.generation
        }
        box.set(bounds)
        SkinPainter.paint(canvas, style, box, active, (now - started).coerceAtLeast(0), opacity, buffer, lookup)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
