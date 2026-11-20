/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/**
 * A small, self-contained mock of the keyboard used by the AI assistant before
 * a change is applied. It is intentionally not a real KeyboardView: the point
 * is to show the proposed palette, corner radius, gap and text size without
 * needing Rime, a schema or the running IME service.
 */
data class ThemePalette(
    val panelBackground: Int = Color.TRANSPARENT,
    val keyBackground: Int = 0xFFF1F3F5.toInt(),
    val keyText: Int = 0xFF111827.toInt(),
    val keyBorder: Int = Color.TRANSPARENT,
    val keyBorderWidth: Float = 0f,
    val keyRadius: Float = 6f,
    val gap: Float = 4f,
    val keyTextSize: Float = 22f,
    val pressedBackground: Int = 0xFFDCE4ED.toInt(),
    val pressedText: Int = 0xFF111827.toInt(),
    val candidateBackground: Int = Color.TRANSPARENT,
    val candidateText: Int = 0xFF111827.toInt(),
    val candidateTextSize: Float = 18f,
    val highlightBackground: Int = 0xFF3B82F6.toInt(),
    val highlightText: Int = Color.WHITE,
    val accentBackground: Int? = null,
    val accentText: Int? = null,
    val accentLabel: String = "",
)

class ThemePalettePreview(context: Context) : View(context) {
    var palette: ThemePalette = ThemePalette()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val density = resources.displayMetrics.density
    private val scaled = density * resources.configuration.fontScale

    init {
        contentDescription = "主题外观预览"
        minimumHeight = (150 * density).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (170 * density).toInt()
        val height = resolveSize(desired, heightMeasureSpec)
        setMeasuredDimension(resolveSize((320 * density).toInt(), widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8f * density
        val panel = rect.apply { set(pad, pad, w - pad, h - pad) }
        val panelRadius = 18f * density

        // A soft "behind the keyboard" strip so transparent panels and keys
        // read as translucent instead of as plain white.
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF8AB4F8.toInt(), 0xFFF6C6D0.toInt(), 0xFFA8D5BA.toInt()),
            null,
            Shader.TileMode.CLAMP,
        )
        clip.reset()
        clip.addRoundRect(panel, panelRadius, panelRadius, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(panel, paint)
        canvas.restoreToCount(saved)
        paint.shader = null

        paint.style = Paint.Style.FILL
        paint.color = palette.panelBackground
        canvas.drawRoundRect(panel, panelRadius, panelRadius, paint)

        val bar = RectF(
            panel.left + 10f * density,
            panel.top + 8f * density,
            panel.right - 10f * density,
            panel.top + 42f * density,
        )
        paint.color = palette.candidateBackground
        canvas.drawRoundRect(bar, 10f * density, 10f * density, paint)

        val candidates = listOf("你好", "世界", "输入")
        var x = bar.left + 12f * density
        paint.textSize = palette.candidateTextSize * scaled
        val candidateBaseline = bar.centerY() - (paint.ascent() + paint.descent()) / 2f
        candidates.forEachIndexed { index, text ->
            val textWidth = paint.measureText(text)
            if (index == 0) {
                paint.color = palette.highlightBackground
                val highlight = RectF(
                    x - 7f * density,
                    bar.centerY() - palette.candidateTextSize * scaled * 0.75f,
                    x + textWidth + 7f * density,
                    bar.centerY() + palette.candidateTextSize * scaled * 0.75f,
                )
                canvas.drawRoundRect(highlight, 8f * density, 8f * density, paint)
            }
            paint.color = if (index == 0) palette.highlightText else palette.candidateText
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(text, x, candidateBaseline, paint)
            x += textWidth + 22f * density
        }

        val keyboardTop = bar.bottom + 10f * density
        val keyboardBottom = panel.bottom - 10f * density
        val side = 10f * density
        val gap = palette.gap.coerceIn(0f, 24f) * density
        val rowHeight = ((keyboardBottom - keyboardTop - gap) / 2f).coerceAtLeast(12f * density)
        val keyWidth = ((panel.width() - side * 2 - gap * 4) / 5f).coerceAtLeast(8f * density)
        val labels = listOf("Q", "W", "E", "R", "T", "A", "S", "D", "F", "G")

        labels.forEachIndexed { index, label ->
            val row = index / 5
            val column = index % 5
            val left = panel.left + side + column * (keyWidth + gap)
            val top = keyboardTop + row * (rowHeight + gap)
            val key = RectF(left, top, left + keyWidth, top + rowHeight)
            val pressed = row == 1 && column == 2
            val accent = row == 1 && column == 1 && palette.accentBackground != null
            paint.style = Paint.Style.FILL
            paint.color = when {
                accent -> palette.accentBackground!!
                pressed -> palette.pressedBackground
                else -> palette.keyBackground
            }
            val radius = palette.keyRadius.coerceIn(0f, 64f) * density
            canvas.drawRoundRect(key, radius, radius, paint)
            if (palette.keyBorderWidth > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = palette.keyBorderWidth.coerceIn(0f, 32f) * density
                paint.color = palette.keyBorder
                canvas.drawRoundRect(key, radius, radius, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = when {
                accent -> palette.accentText ?: palette.keyText
                pressed -> palette.pressedText
                else -> palette.keyText
            }
            paint.textSize = palette.keyTextSize.coerceIn(4f, 96f) * scaled
            paint.textAlign = Paint.Align.CENTER
            val text = if (accent && palette.accentLabel.isNotBlank()) palette.accentLabel.take(2) else label
            canvas.drawText(
                text,
                key.centerX(),
                key.centerY() - (paint.ascent() + paint.descent()) / 2f,
                paint,
            )
        }
        paint.style = Paint.Style.FILL
    }
}
