/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 纯色圆角块，但四角走 G2 连续曲线（[Squircle]），不是 GradientDrawable 的圆弧。
 *
 * 给候选高亮背景用：高亮块的角和键盘、面板、候选窗是同一条曲线。
 * 同时可作为 RippleDrawable 的 mask，水波也被裁成 G2。
 */
class SquircleDrawable(
    private val fillColor: Int,
    private val radiusPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }
    private val path = Path()
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        Squircle.into(path, rect, radiusPx)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = if (Color.alpha(fillColor) == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
}
