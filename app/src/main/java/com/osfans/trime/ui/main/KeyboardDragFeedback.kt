/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.ClipData
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/** Presentation-only drag feedback. Model changes happen once, when dropped. */
internal object KeyboardDragFeedback {
    data class Token(val keyboard: String, val row: Int, val key: Int, val version: Long, val source: View) {
        private var restored = false
        fun restore() {
            if (restored) return
            restored = true
            source.animate().cancel()
            source.alpha = 1f; source.scaleX = 1f; source.scaleY = 1f
        }
    }
    fun start(source: View, keyboard: String, row: Int, key: Int, version: Long): Boolean {
        val token = Token(keyboard, row, key, version, source)
        val data = ClipData.newPlainText("keyboard-key", "")
        val shadow = object : View.DragShadowBuilder(source) {
            val margin = (8 * source.resources.displayMetrics.density).toInt()
            override fun onProvideShadowMetrics(size: Point, touch: Point) {
                size.set((source.width * 1.08f).toInt() + margin * 2, (source.height * 1.08f).toInt() + margin * 2)
                touch.set(size.x / 2, size.y / 2)
            }
            override fun onDrawShadow(canvas: Canvas) {
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(185, 91, 127); style = Paint.Style.STROKE; strokeWidth = 2f }
                canvas.save(); canvas.translate(margin.toFloat(), margin.toFloat()); canvas.scale(1.08f, 1.08f)
                source.draw(canvas)
                canvas.drawRoundRect(1f, 1f, source.width - 1f, source.height - 1f, margin.toFloat(), margin.toFloat(), edge)
                canvas.restore()
            }
        }
        val started = if (android.os.Build.VERSION.SDK_INT >= 24) source.startDragAndDrop(data, shadow, token, 0)
            else @Suppress("DEPRECATION") source.startDrag(data, shadow, token, 0)
        if (started) {
            source.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            source.animate().alpha(.25f).scaleX(.94f).scaleY(.94f).setDuration(110).start()
        }
        return started
    }
    fun attach(scroll: HorizontalScrollView, strip: LinearLayout, row: Int, keyboard: String, onDrop: (Token, Int) -> Unit) {
        val density = scroll.resources.displayMetrics.density
        fun dp(n: Int) = (n * density).toInt()
        val marker = object : Drawable() {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            var x = 0f
            var position = 0
            override fun draw(canvas: Canvas) {
                paint.color = Color.rgb(185, 91, 127); paint.style = Paint.Style.FILL
                canvas.drawRoundRect(x - dp(2), dp(2).toFloat(), x + dp(2), bounds.height() - dp(2).toFloat(), dp(2).toFloat(), dp(2).toFloat(), paint)
                val label = "第${row + 1}行 · 第${position + 1}位"
                paint.textSize = 11 * density
                val width = paint.measureText(label) + dp(12)
                val left = (x - width / 2).coerceIn(0f, (bounds.width() - width).coerceAtLeast(0f))
                canvas.drawRoundRect(left, 0f, left + width, dp(19).toFloat(), dp(5).toFloat(), dp(5).toFloat(), paint)
                paint.color = Color.WHITE
                canvas.drawText(label, left + dp(6), dp(13).toFloat(), paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
            @Suppress("DEPRECATION") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        var active = false
        var lastX = 0f; var lastY = 0f
        var previous = -1
        var token: Token? = null
        fun boundaryAt(x: Float): Int = (0 until strip.childCount - 1).firstOrNull {
            x < strip.getChildAt(it).let { view -> (view.left + view.right) / 2f }
        } ?: (strip.childCount - 1).coerceAtLeast(0)
        fun clear() {
            active = false; previous = -1; scroll.overlay.remove(marker)
            for (i in 0 until strip.childCount) strip.getChildAt(i).run {
                animate().cancel(); translationX = 0f
            }
        }
        fun update() {
            val current = token ?: return
            val boundary = boundaryAt(lastX + scroll.scrollX)
            marker.position = boundary - if (current.row == row && boundary > current.key) 1 else 0
            marker.x = ((strip.getChildAt(boundary)?.left ?: strip.width) - scroll.scrollX).toFloat().coerceIn(dp(3).toFloat(), (scroll.width - dp(3)).coerceAtLeast(dp(3)).toFloat())
            marker.setBounds(0, 0, scroll.width, scroll.height)
            marker.invalidateSelf()
            if (boundary == previous) return
            if (previous >= 0) scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            previous = boundary
            for (i in 0 until strip.childCount) strip.getChildAt(i).animate()
                .translationX(if (i >= boundary) dp(6).toFloat() else -dp(6).toFloat()).setDuration(110).start()
        }
        val autoScroll = object : Runnable {
            override fun run() {
                if (!active || !scroll.isAttachedToWindow) return
                val edge = dp(36)
                val dx = when { lastX < edge -> -dp(5); lastX > scroll.width - edge -> dp(5); else -> 0 }
                if (dx != 0) { scroll.scrollBy(dx, 0); update() }
                var owner = scroll.parent
                while (owner is View && owner !is ScrollView) owner = owner.parent
                (owner as? ScrollView)?.let { vertical ->
                    val rect = android.graphics.Rect(); scroll.getDrawingRect(rect); vertical.offsetDescendantRectToMyCoords(scroll, rect)
                    val y = rect.top + lastY - vertical.scrollY
                    if (y < edge) vertical.scrollBy(0, -dp(4))
                    else if (y > vertical.height - edge) vertical.scrollBy(0, dp(4))
                }
                scroll.postOnAnimation(this)
            }
        }
        fun stop() { scroll.removeCallbacks(autoScroll); clear() }
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { stop() }
        })
        scroll.setOnDragListener { _, event ->
            val current = event.localState as? Token
            if (current == null || current.keyboard != keyboard) return@setOnDragListener false
            token = current
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    lastX = event.x; lastY = event.y
                    if (!active) { active = true; scroll.overlay.add(marker); scroll.postOnAnimation(autoScroll) }
                    update(); true
                }
                DragEvent.ACTION_DROP -> {
                    val boundary = boundaryAt(event.x + scroll.scrollX)
                    stop(); current.restore(); onDrop(current, boundary); true
                }
                DragEvent.ACTION_DRAG_EXITED -> { stop(); true }
                DragEvent.ACTION_DRAG_ENDED -> { stop(); current.restore(); token = null; true }
                else -> true
            }
        }
    }
}
