/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import com.osfans.trime.ime.voice.VoiceRecordingState
import kotlin.math.PI
import kotlin.math.sin

/** One animation loop while visible, with amplitude supplied by the actual microphone. */
class VoiceRecordingView(context: Context, color: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val mic = RectF()
    private var recording = false
    private var target = 0f
    private var amplitude = 0f
    private var lastFrame = 0L
    private val frame = Runnable { invalidate() }

    init {
        contentDescription = "正在录音，松开空格结束"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun update(state: VoiceRecordingState) {
        recording = state.recording
        target = state.level
        if (!recording) { amplitude = 0f; lastFrame = 0L; removeCallbacks(frame) }
        invalidate()
    }

    fun setForegroundColor(color: Int) { paint.color = color; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!recording) return
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 33f else (now - lastFrame).coerceIn(0L, 100L).toFloat()
        lastFrame = now
        amplitude += (target - amplitude) * (1f - kotlin.math.exp(-dt / 90f))
        val cx = width / 2f
        val cy = height / 2f
        val d = density
        mic.set(cx - 3.5f * d, cy - 10f * d, cx + 3.5f * d, cy + 2f * d)
        canvas.drawRoundRect(mic, 3.5f * d, 3.5f * d, paint)
        mic.set(cx - 7f * d, cy - 4f * d, cx + 7f * d, cy + 7f * d)
        canvas.drawArc(mic, 0f, 180f, false, paint)
        canvas.drawLine(cx, cy + 7f * d, cx, cy + 11f * d, paint)
        canvas.drawLine(cx - 4f * d, cy + 11f * d, cx + 4f * d, cy + 11f * d, paint)
        val span = minOf(112f * d, (width / 2f - 30f * d).coerceAtLeast(0f))
        val height = minOf(13f * d, height / 2f - 3f * d).coerceAtLeast(0f) * amplitude
        repeat(2) { sideIndex ->
            val side = if (sideIndex == 0) -1 else 1
            path.reset()
            for (i in 0..64) {
                val t = i / 64f
                val x = cx + side * (22f * d + span * t)
                val envelope = sin(PI * t).toFloat()
                val y = cy + height * envelope * sin(t * PI * 5 - now / 180.0).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        removeCallbacks(frame)
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) postDelayed(frame, 33)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && recording) invalidate() else removeCallbacks(frame)
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && recording) invalidate() else removeCallbacks(frame)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (recording) invalidate() }
    override fun onDetachedFromWindow() { removeCallbacks(frame); lastFrame = 0L; super.onDetachedFromWindow() }
}
