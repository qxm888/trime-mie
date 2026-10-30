/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Real-time crop view for keyboard background images. The crop frame keeps the
 * keyboard's aspect ratio, so the cropped region maps 1:1 onto CENTER_CROP display.
 */
class BackgroundCropView(
    context: Context,
    private val source: Bitmap,
    private val aspect: Float,
) : View(context) {
    private val srcRect = RectF()
    private val crop = RectF()
    private var mode = Mode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private enum class Mode { NONE, MOVE, RESIZE }

    init {
        val viewWidth = max(1, (resources.displayMetrics.widthPixels * 0.92f).toInt())
        val viewHeight = max(1, (resources.displayMetrics.heightPixels * 0.42f).toInt())
        val scale = min(viewWidth.toFloat() / source.width, viewHeight.toFloat() / source.height)
        val width = source.width * scale
        val height = source.height * scale
        srcRect.set(0f, 0f, width, height)
        resetCrop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(srcRect.width().toInt() + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(srcRect.height().toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    private fun resetCrop() {
        val frame = fitAspect(srcRect.width(), srcRect.height())
        val left = srcRect.centerX() - frame.width() / 2f
        val top = srcRect.centerY() - frame.height() / 2f
        crop.set(left, top, left + frame.width(), top + frame.height())
    }

    /** Largest keyboard-proportioned rect that fits inside the given bounds. */
    private fun fitAspect(boundsWidth: Float, boundsHeight: Float): RectF {
        val width = min(boundsWidth, boundsHeight * aspect)
        val height = width / aspect
        return RectF(0f, 0f, width, height)
    }

    private fun handleRadius(): Float = resources.displayMetrics.density * 14f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawBitmap(source, null, srcRect, bitmapPaint)
        canvas.save()
        // clipOutRect needs API 26; keep the Region.Op form for minSdk 21.
        @Suppress("DEPRECATION")
        canvas.clipRect(crop, android.graphics.Region.Op.DIFFERENCE)
        canvas.drawRect(srcRect, dimPaint)
        canvas.restore()
        canvas.drawRect(crop, framePaint)
        canvas.drawCircle(crop.right, crop.bottom, handleRadius(), handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x; lastTouchY = event.y
                val handle = handleRadius() * 1.6f
                mode = if (kotlin.math.abs(event.x - crop.right) <= handle && kotlin.math.abs(event.y - crop.bottom) <= handle) Mode.RESIZE
                else if (crop.contains(event.x, event.y)) Mode.MOVE
                else Mode.NONE
                return mode != Mode.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                when (mode) {
                    Mode.MOVE -> translate(dx, dy)
                    Mode.RESIZE -> resize(event.x, event.y)
                    Mode.NONE -> return false
                }
                lastTouchX = event.x; lastTouchY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.NONE) return false
                mode = Mode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun translate(dx: Float, dy: Float) {
        val left = (crop.left + dx).coerceIn(srcRect.left, srcRect.right - crop.width())
        val top = (crop.top + dy).coerceIn(srcRect.top, srcRect.bottom - crop.height())
        crop.offsetTo(left, top)
    }

    private fun resize(touchX: Float, touchY: Float) {
        val minWidth = handleRadius() * 3f
        val width = (touchX - crop.left).coerceIn(minWidth, srcRect.right - crop.left)
        var height = width / aspect
        if (crop.top + height > srcRect.bottom) {
            height = srcRect.bottom - crop.top
            height = max(height, minWidth / aspect)
        }
        val clampedWidth = min(width, height * aspect)
        crop.right = crop.left + clampedWidth
        crop.bottom = crop.top + clampedWidth / aspect
    }

    /** Map the crop frame back to source bitmap pixels. */
    fun cropRectInSource(): RectF {
        val scaleX = source.width / srcRect.width()
        val scaleY = source.height / srcRect.height()
        val matrix = Matrix()
        matrix.postTranslate(-srcRect.left, -srcRect.top)
        matrix.postScale(scaleX, scaleY)
        val out = RectF(crop)
        matrix.mapRect(out)
        out.intersect(0f, 0f, source.width.toFloat(), source.height.toFloat())
        return out
    }
}
