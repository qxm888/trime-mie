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
    /** 这一层要不要工作。默认看按键/工具栏的 blur 开关；面板玻璃会额外把它打开。 */
    private val active: () -> Boolean = { appearance().needsBlur },
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
    /** 按键专用的一帧：同一次抓图，但按 [SurfaceAppearanceConfig.keyBlur] 糊得更狠/更轻。 */
    private var frontKey: Bitmap? = null
    private var back: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val source = RectF()
    private val target = RectF()
    private val location = RectF()
    private val rootLocation = IntArray(2)
    private val viewLocation = IntArray(2)
    private var scheduled = false
    private var lastCapture = 0L
    /** tick 撞上「窗口暂时不可见 / 还在忙」时的重排队次数，防止无限空转。 */
    private var guardRetries = 0
    private val layoutObserver = android.view.ViewTreeObserver.OnGlobalLayoutListener { requestFresh() }
    private val windowObserver=android.view.ViewTreeObserver.OnWindowVisibilityChangeListener { if(it==View.VISIBLE) requestFresh() }
    private var observedTree: android.view.ViewTreeObserver? = null
    private val tick: Runnable = Runnable {
        scheduled = false
        val ready = attached && root.isShown && root.windowVisibility==View.VISIBLE && !busy && active()
        if (ready && requestedVersion!=renderedVersion) {
            guardRetries = 0
            update()
        } else if (ready) {
            // 版本已对齐，没有待处理的请求，正常收尾。
            guardRetries = 0
        } else if (requestedVersion != renderedVersion && guardRetries < MAX_GUARD_RETRIES) {
            // 以前这里直接 return：请求被静默丢掉，要等下一次 layout/可见性事件才
            // 会重来，表现就是「模糊有时候不加载、过一会才出来」。带上限地重排队，
            // 窗口从短暂不可见恢复后能自己补上，又不会在键盘长期隐藏时空转。
            guardRetries++
            scheduled = true
            root.postDelayed(tick, GUARD_RETRY_MS)
        } else {
            guardRetries = 0
        }
    }
    private fun schedule() {
        if(!attached || scheduled || busy || requestedVersion==renderedVersion || !active() || !root.isShown || root.windowVisibility!=View.VISIBLE) return
        scheduled=true
        root.postDelayed(tick,(100L-(android.os.SystemClock.uptimeMillis()-lastCapture)).coerceAtLeast(0L))
    }
    /** 外部事件（布局/可见性）来了，重置守卫计数再排一次，保证不会因之前放弃而卡死。 */
    private fun requestFresh() { guardRetries = 0; schedule() }
    private fun request() { requestedVersion++;guardRetries = 0;schedule() }

    init {
        root.setTag(R.id.keyboard_backdrop_blur, this)
        root.addOnAttachStateChangeListener(this)
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) request()
        }
        if(root.isAttachedToWindow) onViewAttachedToWindow(root)
    }
    override fun onViewAttachedToWindow(v: View) {
        attached=true;generation++
        observedTree?.takeIf { it.isAlive }?.let { it.removeOnGlobalLayoutListener(layoutObserver);it.removeOnWindowVisibilityChangeListener(windowObserver) }
        observedTree=root.viewTreeObserver.also { it.addOnGlobalLayoutListener(layoutObserver);it.addOnWindowVisibilityChangeListener(windowObserver) }
        request()
    }
    override fun onViewDetachedFromWindow(v: View) {
        attached=false;generation++;root.removeCallbacks(tick);scheduled=false
        observedTree?.takeIf { it.isAlive }?.let { it.removeOnGlobalLayoutListener(layoutObserver);it.removeOnWindowVisibilityChangeListener(windowObserver) };observedTree=null
        // The private work bitmap may still be owned by the worker. Published frames
        // are immutable and never reused as scratch memory for a future GPU frame.
        front=null
        frontKey=null
        if(!busy) back=null
    }

    private fun update() {
        if (root.width <= 0 || root.height <= 0) return
        lastCapture=android.os.SystemClock.uptimeMillis()
        val capturedWidth=root.width;val capturedHeight=root.height
        val scale = minOf(0.125f, 256f / capturedWidth, 256f / capturedHeight)
        val w = (root.width * scale).roundToInt().coerceAtLeast(1)
        val h = (root.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = back?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        back = null
        bitmap.eraseColor(Color.TRANSPARENT)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(w.toFloat() / root.width, h.toFloat() / root.height)
            capture(canvas)
        } catch(error:RuntimeException) {
            back=bitmap;renderedVersion=requestedVersion
            com.osfans.trime.util.DiagnosticLog.event("appearance","blur_capture_failed","type=${error.javaClass.simpleName}")
            return
        }
        val version = generation
        val capturedVersion = requestedVersion
        val radius = (appearance().blurRadius * root.resources.displayMetrics.density * scale).roundToInt().coerceIn(1, 24)
        // 按键要的是"和面板不一样的糊"，所以同一次抓图再糊一遍，强度单独取。
        val saturation = appearance().blurSaturation.coerceIn(100, 200)
        val keyPercent = appearance().keyBlur.coerceIn(0, 100)
        val keyRadius = if (keyPercent <= 0) 0 else (keyPercent / 100f * 24f).roundToInt().coerceIn(1, 24)
        busy = true
        scope.launch {
            try {
                val frames=withContext(Dispatchers.Default) {
                    val size = bitmap.width * bitmap.height
                    if (blurPixels.size != size) { blurPixels = IntArray(size); blurWork = IntArray(size) }
                    val source = if (keyRadius > 0) bitmap.copy(Bitmap.Config.ARGB_8888, false) else null
                    blur(bitmap, radius, blurPixels, blurWork)
                    boostSaturation(bitmap, saturation, blurPixels)
                    val panel = bitmap.copy(Bitmap.Config.ARGB_8888,false)
                    val keys = source?.let {
                        blur(it, keyRadius, blurPixels, blurWork)
                        boostSaturation(it, saturation, blurPixels)
                        it.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    panel to keys
                }
                if (attached && generation == version && root.width==capturedWidth && root.height==capturedHeight && active()) {
                    front = frames.first
                    frontKey = frames.second
                    renderedVersion = capturedVersion
                    invalidateChildren(root)
                }
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) {
                renderedVersion=capturedVersion
                com.osfans.trime.util.DiagnosticLog.event("appearance","blur_failed","type=${error.javaClass.simpleName}")
            } finally {
                busy=false;back=if(attached) bitmap else null
                schedule()
            }
        }
    }

    private fun invalidateChildren(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) invalidateChildren(view.getChildAt(i))
    }

    private fun draw(view: View, canvas: Canvas, area: RectF, alpha: Int = 255, keyFrame: Boolean = false): Boolean {
        // 按键帧还没算出来时退回面板帧，避免开关一改就闪一下没糊。
        val bitmap = (if (keyFrame) frontKey ?: front else front) ?: run { schedule();return false }
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
        canvas.drawBitmap(bitmap, null, target, paint.also { it.alpha = alpha.coerceIn(0, 255) })
        paint.alpha = 255
        canvas.restoreToCount(saved)
        return true
    }

    companion object {
        /** tick 守卫重排队上限与间隔（约 1.4 秒），窗口长期隐藏时不空转。 */
        private const val MAX_GUARD_RETRIES = 12
        private const val GUARD_RETRY_MS = 120L

        /** Coalesce video frames/background changes; static skins keep their existing blur. */
        fun invalidateBackground(view: View) {
            var ancestor: View? = view
            while (ancestor != null) {
                (ancestor.getTag(R.id.keyboard_backdrop_blur) as? BackdropBlur)?.let { it.request(); return }
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

        fun draw(view: View, canvas: Canvas, area: RectF, alpha: Int = 255, keyFrame: Boolean = false): Boolean {
            var ancestor: View? = view
            while (ancestor != null) {
                val blur = ancestor.getTag(R.id.keyboard_backdrop_blur) as? BackdropBlur
                if (blur != null) return blur.draw(view, canvas, area, alpha, keyFrame)
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
        /**
         * 模糊会把局部色差抹平，视觉上"发灰、发白"。按比例把饱和度补回来：
         * 以每个像素的亮度为轴拉开色度，颜色回到原图的浓淡，但颗粒仍然是糊的。
         * [percent] 100 = 不动，140 = 常见毛玻璃观感。
         */
        private fun boostSaturation(bitmap: Bitmap, percent: Int, pixels: IntArray) {
            if (percent == 100) return
            val w = bitmap.width; val h = bitmap.height
            val k = percent / 100f
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val a = c ushr 24
                if (a == 0) continue
                val r = c ushr 16 and 255
                val g = c ushr 8 and 255
                val b = c and 255
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                fun ch(v: Int) = (gray + ((v - gray) * k).toInt()).coerceIn(0, 255)
                pixels[i] = (a shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

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
