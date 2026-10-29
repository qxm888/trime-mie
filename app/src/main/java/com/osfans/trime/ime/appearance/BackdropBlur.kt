/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import com.osfans.trime.R
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** One low-resolution background capture/blur shared by every key and toolbar icon. */
class BackdropBlur(
    private val root: ViewGroup,
    private val appearance: () -> SurfaceAppearanceConfig = { SurfaceAppearance.current() },
    private val capture: (Canvas) -> Unit,
) : View.OnAttachStateChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attached = false
    private var busy = false
    private var generation = 0
    private var requestedVersion = 0L
    private var renderedVersion = -1L
    private var blurPixels = IntArray(0)
    private var blurWork = IntArray(0)
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val source = RectF()
    private val target = RectF()
    private val location = RectF()
    private val rootLocation = IntArray(2)
    private val viewLocation = IntArray(2)
    private val tick = object : Runnable {
        override fun run() {
            if (!attached) return
            if (root.isShown && root.windowVisibility == View.VISIBLE && !busy && requestedVersion != renderedVersion && appearance().needsBlur) update()
            root.postDelayed(this, 100)
        }
    }

    init {
        root.setTag(R.id.keyboard_backdrop_blur, this)
        root.addOnAttachStateChangeListener(this)
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) requestedVersion++
        }
        if(root.isAttachedToWindow) onViewAttachedToWindow(root)
    }
    override fun onViewAttachedToWindow(v: View) { attached = true; generation++; requestedVersion++; root.removeCallbacks(tick); root.post(tick) }
    override fun onViewDetachedFromWindow(v: View) {
        attached = false; generation++; root.removeCallbacks(tick)
        // A worker may still own the back bitmap. Let its coroutine finish before releasing it.
        front = null
        if (!busy) back = null
    }

    private fun update() {
        if (root.width <= 0 || root.height <= 0) return
        val scale = minOf(0.125f, 256f / root.width, 256f / root.height)
        val w = (root.width * scale).roundToInt().coerceAtLeast(1)
        val h = (root.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = back?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        back = null
        bitmap.eraseColor(Color.TRANSPARENT)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(w.toFloat() / root.width, h.toFloat() / root.height)
            capture(canvas)
        } catch (_: RuntimeException) { back = bitmap; return }
        val version = generation
        val capturedVersion = requestedVersion
        val radius = (appearance().blurRadius * root.resources.displayMetrics.density * scale).roundToInt().coerceIn(1, 24)
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.Default) {
                    val size = bitmap.width * bitmap.height
                    if (blurPixels.size != size) { blurPixels = IntArray(size); blurWork = IntArray(size) }
                    blur(bitmap, radius, blurPixels, blurWork)
                }
                if (attached && generation == version) {
                    back = front
                    front = bitmap
                    renderedVersion = capturedVersion
                    invalidateChildren(root)
                }
            } finally { busy = false; if (!attached) back = null }
        }
    }

    private fun invalidateChildren(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) invalidateChildren(view.getChildAt(i))
    }

    private fun draw(view: View, canvas: Canvas, area: RectF): Boolean {
        val bitmap = front ?: return false
        if (view.width == 0 || view.height == 0 || root.width == 0 || root.height == 0) return false
        relativeBounds(root, view, location, rootLocation, viewLocation)
        if (location.width() <= 0 || location.height() <= 0) return false
        val sx = bitmap.width.toFloat() / root.width
        val sy = bitmap.height.toFloat() / root.height
        val vx = location.width().toFloat() / view.width
        val vy = location.height().toFloat() / view.height
        source.set((location.left + area.left * vx) * sx, (location.top + area.top * vy) * sy,
            (location.left + area.right * vx) * sx, (location.top + area.bottom * vy) * sy)
        // Canvas drawBitmap's source Rect overload does not filter at subpixel boundaries.
        val saved = canvas.save()
        canvas.clipRect(area)
        target.set(area.left - source.left * area.width() / source.width(), area.top - source.top * area.height() / source.height(),
            area.left + (bitmap.width - source.left) * area.width() / source.width(), area.top + (bitmap.height - source.top) * area.height() / source.height())
        canvas.drawBitmap(bitmap, null, target, paint)
        canvas.restoreToCount(saved)
        return true
    }

    companion object {
        /** Coalesce video frames/background changes; static skins keep their existing blur. */
        fun invalidateBackground(view: View) {
            var ancestor: View? = view
            while (ancestor != null) {
                (ancestor.getTag(R.id.keyboard_backdrop_blur) as? BackdropBlur)?.let { it.requestedVersion++; return }
                ancestor = ancestor.parent as? View
            }
        }

        /** Keyboard keys are positioned with translationX/Y, not their layout left/top. */
        private fun relativeBounds(root: ViewGroup, view: View, out: RectF, rootLocation: IntArray, viewLocation: IntArray) {
            root.getLocationInWindow(rootLocation)
            view.getLocationInWindow(viewLocation)
            var rootX = 1f; var rootY = 1f
            var ancestor: View? = root
            while (ancestor != null) {
                rootX *= ancestor.scaleX; rootY *= ancestor.scaleY
                ancestor = ancestor.parent as? View
            }
            var sx = 1f; var sy = 1f
            ancestor = view
            while (ancestor != null && ancestor !== root) {
                sx *= ancestor.scaleX; sy *= ancestor.scaleY
                ancestor = ancestor.parent as? View
            }
            if (rootX == 0f || rootY == 0f) { out.setEmpty(); return }
            val x = (viewLocation[0] - rootLocation[0]) / rootX
            val y = (viewLocation[1] - rootLocation[1]) / rootY
            out.set(x, y, x + view.width * sx, y + view.height * sy)
        }

        fun draw(view: View, canvas: Canvas, area: RectF): Boolean {
            var ancestor: View? = view
            while (ancestor != null) {
                val blur = ancestor.getTag(R.id.keyboard_backdrop_blur) as? BackdropBlur
                if (blur != null) return blur.draw(view, canvas, area)
                ancestor = ancestor.parent as? View
            }
            return false
        }

        fun drawLayer(root: ViewGroup, view: View, canvas: Canvas, draw: (Canvas) -> Unit) {
            if (!view.isShown || view.width <= 0 || view.height <= 0) return
            val rect = RectF()
            relativeBounds(root, view, rect, IntArray(2), IntArray(2))
            val saved = canvas.save()
            canvas.translate(rect.left.toFloat(), rect.top.toFloat())
            canvas.scale(rect.width().toFloat() / view.width, rect.height().toFloat() / view.height)
            canvas.clipRect(0, 0, view.width, view.height)
            draw(canvas)
            canvas.restoreToCount(saved)
        }

        /** Two separable box passes at thumbnail resolution. No UI thread pixel processing. */
        private fun blur(bitmap: Bitmap, radius: Int, pixels: IntArray, work: IntArray) {
            val w = bitmap.width; val h = bitmap.height
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            fun pass(input: IntArray, output: IntArray, horizontal: Boolean) {
                val length = if (horizontal) w else h
                val lines = if (horizontal) h else w
                val count = radius * 2 + 1
                for (line in 0 until lines) {
                    fun index(p: Int) = if (horizontal) line * w + p.coerceIn(0, w - 1) else p.coerceIn(0, h - 1) * w + line
                    var a = 0; var r = 0; var g = 0; var b = 0
                    for (p in -radius..radius) {
                        val c = input[index(p)]
                        a += c ushr 24; r += c ushr 16 and 255; g += c ushr 8 and 255; b += c and 255
                    }
                    for (p in 0 until length) {
                        output[index(p)] = (a / count shl 24) or (r / count shl 16) or (g / count shl 8) or (b / count)
                        val old = input[index(p - radius)]; val next = input[index(p + radius + 1)]
                        a += (next ushr 24) - (old ushr 24)
                        r += (next ushr 16 and 255) - (old ushr 16 and 255)
                        g += (next ushr 8 and 255) - (old ushr 8 and 255)
                        b += (next and 255) - (old and 255)
                    }
                }
            }
            repeat(2) { pass(pixels, work, true); pass(work, pixels, false) }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }
}
