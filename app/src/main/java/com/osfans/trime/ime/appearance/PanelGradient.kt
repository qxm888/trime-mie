/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * 整块键盘面板的渐变背景（工具栏 + 候选栏 + 键盘区共用一条）。
 *
 * 日间/夜间各一组三色：夜间那三个留空就沿用日间的，改一处不用重复填两遍。
 * [direction] 的 0–9 与 `GradientDrawable.Orientation` 的顺序一致，
 * 8 = 径向、9 = 扫描；扫描中心用面板宽高的比例表示。
 *
 * 这是一个**覆盖层**：只要 [enabled] 为真，纯色、透明、图片背景全部让位；
 * 关掉之后原来的背景设置照旧生效，参数本身不会被清掉。
 */
@Serializable
data class PanelGradient(
    val enabled: Boolean = false,
    val start: String = "#FF6C7BD9",
    val center: String = "#FFB173C9",
    val end: String = "#FFF08A7A",
    /** 夜间三色；留空=沿用同名日间色。 */
    val startNight: String = "",
    val centerNight: String = "",
    val endNight: String = "",
    /** 0 上→下、1 右上→左下、2 右→左、3 右下→左上、4 下→上、5 左下→右上、6 左→右、7 左上→右下、8 径向、9 扫描。 */
    val direction: Int = 0,
    /** 方向 8 的半径，占面板较短边的百分比（10–200）。 */
    val radius: Int = 120,
    /** 方向 9 的中心，0–1 的面板宽高比例。 */
    val sweepX: Float = 0.5f,
    val sweepY: Float = 0.5f,
) {
    fun sanitized(): PanelGradient = copy(
        start = valid(start, DEFAULT_START),
        center = valid(center, DEFAULT_CENTER),
        end = valid(end, DEFAULT_END),
        startNight = startNight.trim().uppercase().takeIf { it.isEmpty() || isHex(it) }.orEmpty(),
        centerNight = centerNight.trim().uppercase().takeIf { it.isEmpty() || isHex(it) }.orEmpty(),
        endNight = endNight.trim().uppercase().takeIf { it.isEmpty() || isHex(it) }.orEmpty(),
        direction = direction.coerceIn(0, 9),
        radius = radius.coerceIn(10, 200),
        sweepX = if (sweepX.isFinite()) sweepX.coerceIn(0f, 1f) else 0.5f,
        sweepY = if (sweepY.isFinite()) sweepY.coerceIn(0f, 1f) else 0.5f,
    )

    /** 当前模式下的三色：夜间没单独设就沿用日间。 */
    fun colors(night: Boolean): IntArray {
        val spec = sanitized()
        fun pick(lit: String, dark: String, fallback: String): Int =
            parse(if (night && dark.isNotBlank()) dark else lit, parse(fallback, Color.DKGRAY))
        return intArrayOf(
            pick(spec.start, spec.startNight, DEFAULT_START),
            pick(spec.center, spec.centerNight, DEFAULT_CENTER),
            pick(spec.end, spec.endNight, DEFAULT_END),
        )
    }

    companion object {
        const val DEFAULT_START = "#FF6C7BD9"
        const val DEFAULT_CENTER = "#FFB173C9"
        const val DEFAULT_END = "#FFF08A7A"
        private val HEX = Regex("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")

        private fun isHex(value: String) = HEX.matches(value)

        private fun valid(value: String, fallback: String): String =
            value.trim().uppercase().takeIf { isHex(it) } ?: fallback

        internal fun parse(value: String, fallback: Int): Int =
            runCatching { Color.parseColor(value.trim()) }.getOrDefault(fallback)
    }
}

/**
 * 按自身 bounds 铺满一条渐变；尺寸变化时重建 shader，不用每帧算。
 *
 * 界面是整块面板，所以起点/终点都取整块 bounds，工具栏和候选栏不各自再画一条，
 * 中间不会出现断开或色阶跳变。
 */
class PanelGradientDrawable(
    private val gradient: PanelGradient,
    private val night: Boolean,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var alphaScale = 255

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            paint.shader = null
            return
        }
        paint.shader = buildShader(bounds)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        if (paint.shader == null) paint.shader = buildShader(b)
        paint.alpha = alphaScale
        canvas.drawRect(b, paint)
    }

    private fun buildShader(bounds: Rect): Shader? {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        val spec = gradient.sanitized()
        val colors = gradient.colors(night)
        val positions = floatArrayOf(0f, 0.5f, 1f)
        return when (spec.direction) {
            8 -> RadialGradient(
                bounds.left + w / 2f,
                bounds.top + h / 2f,
                (min(w, h) * spec.radius / 200f).coerceAtLeast(1f),
                colors, positions, Shader.TileMode.CLAMP,
            )
            9 -> SweepGradient(
                bounds.left + w * spec.sweepX,
                bounds.top + h * spec.sweepY,
                colors, positions,
            )
            else -> {
                val (x0, y0, x1, y1) = endpoints(spec.direction, bounds, w, h)
                LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
            }
        }
    }

    private fun endpoints(direction: Int, b: Rect, w: Float, h: Float): FloatArray = when (direction) {
        1 -> floatArrayOf(b.left + w, b.top.toFloat(), b.left.toFloat(), b.top + h)      // 右上 → 左下
        2 -> floatArrayOf(b.left + w, b.top.toFloat(), b.left.toFloat(), b.top.toFloat()) // 右 → 左
        3 -> floatArrayOf(b.left + w, b.top + h, b.left.toFloat(), b.top.toFloat())       // 右下 → 左上
        4 -> floatArrayOf(b.left.toFloat(), b.top + h, b.left.toFloat(), b.top.toFloat()) // 下 → 上
        5 -> floatArrayOf(b.left.toFloat(), b.top + h, b.left + w, b.top.toFloat())       // 左下 → 右上
        6 -> floatArrayOf(b.left.toFloat(), b.top.toFloat(), b.left + w, b.top.toFloat()) // 左 → 右
        7 -> floatArrayOf(b.left.toFloat(), b.top.toFloat(), b.left + w, b.top + h)       // 左上 → 右下
        else -> floatArrayOf(b.left.toFloat(), b.top.toFloat(), b.left.toFloat(), b.top + h) // 上 → 下
    }

    override fun setAlpha(alpha: Int) {
        alphaScale = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
