/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * 描边带上的白色高光。
 *
 * 只画在描边自身的宽度里：外沿最亮 → 内沿透明，所以细描边是细亮线、粗描边是柔和的厚边，
 * 永远不会超出用户设的那条带；描边宽度为 0 时什么都不画。
 *
 * 四层等分这条带，透明度依次衰减；每层用一条垂直渐变描边（顶白、底 45%），
 * 光线看起来是从上方打下来的。四角统一走 [Squircle]，和面板/按键是同一条 G2 曲线。
 */
object BorderGlow {
    private const val LAYERS = 4
    private val ALPHA = floatArrayOf(1f, 0.72f, 0.42f, 0.16f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val paths = Array(LAYERS) { Path() }
    private val rect = RectF()

    /**
     * @param radiusPx 面板圆角（px）
     * @param bandPx 描边宽度（px），高光只会画在这条带里
     * @param strength 0–100
     */
    fun draw(canvas: Canvas, width: Int, height: Int, radiusPx: Float, bandPx: Float, strength: Int) {
        if (strength <= 0 || bandPx <= 0f || width <= 0 || height <= 0) return
        val clip = Path()
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        Squircle.into(clip, rect, radiusPx)
        val saved = canvas.save()
        canvas.clipPath(clip)
        val layerWidth = bandPx / LAYERS
        for (i in 0 until LAYERS) {
            val centerInset = layerWidth * (i + 0.5f)
            val path = paths[i]
            path.reset()
            Squircle.into(
                path,
                RectF(centerInset, centerInset, width - centerInset, height - centerInset),
                (radiusPx - centerInset).coerceAtLeast(0f),
            )
            val base = strength / 100f * ALPHA[i]
            val top = Color.argb((255 * base).toInt().coerceIn(0, 255), 255, 255, 255)
            val bottom = Color.argb((255 * base * 0.45f).toInt().coerceIn(0, 255), 255, 255, 255)
            paint.strokeWidth = layerWidth
            paint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        canvas.restoreToCount(saved)
    }
}
