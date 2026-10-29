/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 一张带内边距的特效位图；[pad] 是位图相对按键本体的外扩像素。 */
data class EffectBitmap(
    val bitmap: Bitmap,
    val pad: Float,
    val scale: Float,
) {
    private val drawBounds = RectF()
    fun draw(canvas: Canvas, bodyLeft: Float, bodyTop: Float, paint: Paint) {
        val left = bodyLeft - pad
        val top = bodyTop - pad
        drawBounds.set(left, top, left + bitmap.width / scale, top + bitmap.height / scale)
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, null, drawBounds, paint)
    }
}

/**
 * 按键特效的纯绘制工具。所有模糊都在离屏软件位图里完成并缓存，
 * 因此不依赖 API 31 的 RenderEffect，Android 5.0 也能得到一致效果。
 */
object KeyEffectPainter {
    private const val BITMAP_SCALE = 0.5f

    fun color(raw: String, fallback: Int): Int =
        runCatching { Color.parseColor(raw) }.getOrDefault(fallback)

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            (Color.alpha(color) * alpha.coerceIn(0, 255) / 255f).roundToInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    /** 外阴影：模糊的圆角矩形轮廓。 */
    fun shadowBitmap(
        width: Float,
        height: Float,
        radius: Float,
        blur: Float,
        spread: Float,
        color: Int,
    ): EffectBitmap {
        val pad = blur + abs(spread) + 2f
        val w = ((width + pad * 2) * BITMAP_SCALE).roundToInt().coerceAtLeast(1)
        val h = ((height + pad * 2) * BITMAP_SCALE).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(BITMAP_SCALE, BITMAP_SCALE)
        val rect = RectF(pad, pad, pad + width, pad + height)
        rect.inset(-spread, -spread)
        val r = (radius + spread).coerceAtLeast(0f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(rect, r, r, paint)
        return EffectBitmap(bitmap, pad, BITMAP_SCALE)
    }

    /**
     * 内阴影：沿按键边缘画一圈模糊描边，再由调用方裁切到按键内部。
     * 偏移可以让阴影偏向某一侧，模拟光源方向。
     */
    fun innerShadowBitmap(
        width: Float,
        height: Float,
        radius: Float,
        blur: Float,
        offsetX: Float,
        offsetY: Float,
        color: Int,
    ): EffectBitmap {
        val pad = blur + max(abs(offsetX), abs(offsetY)) + 2f
        val w = ((width + pad * 2) * BITMAP_SCALE).roundToInt().coerceAtLeast(1)
        val h = ((height + pad * 2) * BITMAP_SCALE).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(BITMAP_SCALE, BITMAP_SCALE)
        val rect = RectF(
            pad + offsetX,
            pad + offsetY,
            pad + offsetX + width,
            pad + offsetY + height,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = (blur * 2f).coerceAtLeast(1f)
            if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(rect, radius.coerceAtLeast(0f), radius.coerceAtLeast(0f), paint)
        return EffectBitmap(bitmap, pad, BITMAP_SCALE)
    }

    fun gradientShader(bounds: RectF, gradient: KeyGradient): Shader? {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null
        val start = color(gradient.startColor, Color.TRANSPARENT)
        val end = color(gradient.endColor, Color.TRANSPARENT)
        val colors = intArrayOf(start, end)
        return when (gradient.safeType) {
            "radial" -> RadialGradient(
                bounds.centerX(),
                bounds.centerY(),
                hypot(bounds.width(), bounds.height()) / 2f,
                colors,
                null,
                Shader.TileMode.CLAMP,
            )

            "sweep" -> SweepGradient(bounds.centerX(), bounds.centerY(), colors, null)

            else -> {
                val radians = Math.toRadians(gradient.angle.toDouble())
                val length = max(bounds.width(), bounds.height())
                val dx = cos(radians).toFloat() * length / 2f
                val dy = sin(radians).toFloat() * length / 2f
                LinearGradient(
                    bounds.centerX() - dx,
                    bounds.centerY() - dy,
                    bounds.centerX() + dx,
                    bounds.centerY() + dy,
                    colors,
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
        }
    }

    /** 128×128 的可平铺磨砂颗粒，整个进程只生成一次。 */
    val noiseShader: Shader by lazy {
        val size = 128
        val pixels = IntArray(size * size)
        val random = Random(0x5EED)
        for (i in pixels.indices) {
            val value = random.nextInt(256)
            pixels[i] = (random.nextInt(256) shl 24) or (value shl 16) or (value shl 8) or value
        }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        android.graphics.BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
