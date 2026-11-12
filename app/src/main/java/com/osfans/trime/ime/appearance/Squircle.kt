/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * G2（曲率连续）圆角路径。
 *
 * 普通圆角是四分之一圆弧：在它与直边的接点处，曲率从 0 突跳到 1/r，半径一大就能
 * 看出一道「从这里开始拐弯」的折感。这里换成曲率沿弧长按 sin² 分布的曲线：
 * 接点处曲率为 0，和直边（曲率也是 0）连续接上，中段才长到最大 —— 这就是 G2 连续。
 *
 * 曲线的方向角是曲率的积分：
 *
 *     κ(s) = sin²(s)      （半径归一化，s ∈ [0, π]）
 *     φ(s) = s/2 - sin(2s)/4
 *
 * φ(π) = π/2，正好转过 90°，端点切线分别贴着两条直边。曲线在角部占的范围比圆弧大，
 * 这也是 iOS 那种连续圆角看起来「更方但更顺」的原因，见 [EXTENT_RATIO]。
 */
object Squircle {
    /**
     * 曲线离开直边处距角点的距离 = 设置半径 × 该值。
     *
     * 圆弧是 1.0（半径 r 的角就占 r × r），iOS 的连续圆角是 1.528。取 1.3 是折中：
     * 保留「过渡更长、更顺」的观感，又不至于让同一个半径值看起来明显变大。
     */
    const val EXTENT_RATIO = 1.3f

    /** 每个角拆成多少段三次贝塞尔。段数只影响平滑度，20 段在 200dp 圆角下也看不出接缝。 */
    private const val SEGMENTS = 20

    /** 单位曲线（ρ = 1）：起点 (0,0)，终点 (unitExtent, -unitExtent)。 */
    private val unitX = FloatArray(SEGMENTS + 1)
    private val unitY = FloatArray(SEGMENTS + 1)
    private val unitExtent: Float

    /** 归一化后的曲线表：unitU 从 0 涨到 1，unitV 同样从 0 涨到 1，两者都单调。 */
    private val unitU = FloatArray(SEGMENTS + 1)
    private val unitV = FloatArray(SEGMENTS + 1)

    /** 逐点坐标缓冲。只在上屏线程同步使用，不跨线程共享。 */
    private val pointX = FloatArray(SEGMENTS + 1)
    private val pointY = FloatArray(SEGMENTS + 1)

    init {
        val total = Math.PI.toFloat()
        val step = total / SEGMENTS
        var x = 0f
        var y = 0f
        for (i in 0 until SEGMENTS) {
            // 中点法积分：方向角取格子中点，误差远小于一步的长度。
            val mid = step * (i + 0.5f)
            val theta = mid / 2f - sin(2f * mid) / 4f
            x += sin(theta) * step
            y -= cos(theta) * step
            unitX[i + 1] = x
            unitY[i + 1] = y
        }
        unitExtent = x.coerceAtLeast(0.001f)
        for (i in 0..SEGMENTS) {
            unitU[i] = unitX[i] / unitExtent
            unitV[i] = -unitY[i] / unitExtent
        }
    }

    /** 四角同半径。 */
    fun into(
        out: Path,
        rect: RectF,
        radius: Float,
        ratio: Float = EXTENT_RATIO,
    ): Path = into(out, rect, FloatArray(8) { radius }, ratio)

    /**
     * 四角各自半径。[radii] 顺序与 [Path.addRoundRect] 一致：左上、右上、右下、左下，
     * 每个角两个分量（横向、纵向），单位是像素。
     */
    fun into(
        out: Path,
        rect: RectF,
        radii: FloatArray,
        ratio: Float = EXTENT_RATIO,
    ): Path {
        out.reset()
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f) {
            out.addRect(rect, Path.Direction.CW)
            return out
        }
        val r = FloatArray(8) { radii.getOrElse(it) { 0f }.coerceAtLeast(0f) }
        if (r.none { it > 0f }) {
            out.addRect(rect, Path.Direction.CW)
            return out
        }
        val ex = FloatArray(4)
        val ey = FloatArray(4)
        extents(w, h, r, ratio, ex, ey)

        // 顺时针：左上角之后沿上边 → 右上角 → 右边 → 右下 → 下边 → 左下 → 左边。
        // 每对角的入口方向不同：左上、右下从竖直的直边进入，右上、左下从上/下边进入，
        // 后者要把曲线的两个分量对调，否则会先往矩形里面拐，角部变成折角。
        out.moveTo(rect.left + ex[0], rect.top)
        out.lineTo(rect.right - ex[1], rect.top)
        corner(out, rect.right - ex[1], rect.top, ex[1], ey[1], 1f, 1f, swapAxes = true)
        out.lineTo(rect.right, rect.bottom - ey[2])
        corner(out, rect.right, rect.bottom - ey[2], -ex[2], ey[2], -1f, 1f, swapAxes = false)
        out.lineTo(rect.left + ex[3], rect.bottom)
        corner(out, rect.left + ex[3], rect.bottom, -ex[3], -ey[3], -1f, -1f, swapAxes = true)
        out.lineTo(rect.left, rect.top + ey[0])
        corner(out, rect.left, rect.top + ey[0], ex[0], -ey[0], 1f, -1f, swapAxes = false)
        out.close()
        return out
    }

    /** 一条边上的两个角放不下时，按比例一起缩小。返回 1 表示不用动。 */
    private fun pairScale(a: Float, b: Float, limit: Float): Float {
        val sum = a + b
        return if (sum > limit && sum > 0f) limit / sum else 1f
    }

    /**
     * 算出四角曲线在两条边上的占用长度（已乘 [ratio]，并按边界夹紧）。
     *
     * [ex] 是每个角沿水平方向的长度，[ey] 沿竖直方向；顺序同 [into] 的 radii。
     */
    private fun extents(
        w: Float,
        h: Float,
        r: FloatArray,
        ratio: Float,
        ex: FloatArray,
        ey: FloatArray,
    ) {
        val halfW = w / 2f
        val halfH = h / 2f
        for (i in 0 until 4) {
            ex[i] = (r[i * 2] * ratio).coerceAtMost(halfW)
            ey[i] = (r[i * 2 + 1] * ratio).coerceAtMost(halfH)
        }
        // 相邻两个角的曲线不能在边上撞上：按边分别夹一次，再按角取两边的较小者。
        val top = pairScale(ex[0], ex[1], w)
        val bottom = pairScale(ex[3], ex[2], w)
        val left = pairScale(ey[0], ey[3], h)
        val right = pairScale(ey[1], ey[2], h)
        val scale = floatArrayOf(
            minOf(top, left),
            minOf(top, right),
            minOf(bottom, right),
            minOf(bottom, left),
        )
        for (i in 0 until 4) {
            ex[i] *= scale[i]
            ey[i] *= scale[i]
        }
    }

    /**
     * 只追加一个角的曲线：从 [startX]/[startY] 出发，净位移 (dx, dy)。
     *
     * 调用前当前点必须已经在曲线上（一般紧跟一条 `lineTo`）。[swapAxes] 与 [into] 同义：
     * false 表示起点落在竖直边上（先竖后横），true 表示起点落在上/下边上（先横后竖）。
     * 缩放过的卡片（悬浮键盘缩放手柄）要靠它单独画角，没法走整块面板那条路径。
     */
    fun cornerInto(
        out: Path,
        startX: Float,
        startY: Float,
        dx: Float,
        dy: Float,
        swapAxes: Boolean = false,
    ) {
        if (dx == 0f || dy == 0f) {
            out.lineTo(startX + dx, startY + dy)
            return
        }
        corner(out, startX, startY, dx, dy, if (dx > 0f) 1f else -1f, if (dy > 0f) 1f else -1f, swapAxes)
    }

    /**
     * 距矩形上沿 [distFromTop] 处，轮廓从左右两边各自内收了多少像素，取两侧较大者。
     *
     * 工具栏两端要躲开顶部的两个角（图标不能被曲线切到），让位量必须按实际曲线算：
     * G2 的角比同半径圆弧长，拿圆弧公式量会少让一截。距离一旦越过角的竖直长度就没有内收。
     */
    fun topInset(
        rect: RectF,
        radii: FloatArray,
        distFromTop: Float,
        ratio: Float = EXTENT_RATIO,
    ): Float {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0f || h <= 0f || distFromTop < 0f) return 0f
        val r = FloatArray(8) { radii.getOrElse(it) { 0f }.coerceAtLeast(0f) }
        if (r.none { it > 0f }) return 0f
        val ex = FloatArray(4)
        val ey = FloatArray(4)
        extents(w, h, r, ratio, ex, ey)
        var inset = 0f
        // 左上角：曲线从左边 (top + ey) 走到上边 (left + ex)，竖直进度 v = 1 - d/ey。
        if (ey[0] > 0f) {
            val v = 1f - distFromTop.coerceAtMost(ey[0]) / ey[0]
            inset = maxOf(inset, lookup(v, byVertical = true) * ex[0])
        }
        // 右上角：曲线从上边 (right - ex) 走到右边 (top + ey)，竖直进度就是表的 u。
        if (ey[1] > 0f) {
            val u = distFromTop.coerceAtMost(ey[1]) / ey[1]
            inset = maxOf(inset, (1f - lookup(u, byVertical = false)) * ex[1])
        }
        return inset
    }

    /**
     * 在单位曲线表上按一轴查另一轴。
     *
     * [byVertical] 为 true 时以竖直进度为输入、返回水平进度（起点在竖直边的角）；
     * 为 false 时以表的水平进度为输入、返回竖直进度（起点在上/下边的角）。
     */
    private fun lookup(target: Float, byVertical: Boolean): Float {
        for (i in 0 until SEGMENTS) {
            val k0 = if (byVertical) unitV[i] else unitU[i]
            val k1 = if (byVertical) unitV[i + 1] else unitU[i + 1]
            if (target <= k1 || i == SEGMENTS - 1) {
                val o0 = if (byVertical) unitU[i] else unitV[i]
                val o1 = if (byVertical) unitU[i + 1] else unitV[i + 1]
                val t = if (k1 > k0) ((target - k0) / (k1 - k0)).coerceIn(0f, 1f) else 0f
                return o0 + (o1 - o0) * t
            }
        }
        return 0f
    }

    /**
     * 画一个角：从 [startX]/[startY] 出发，净位移 (dx, dy)。
     *
     * [mx]/[my] 是镜像方向。单位曲线是「先竖直、后水平」，[swapAxes] 为 true 时把两个
     * 分量对调，用来处理从上/下边进入的角（先水平、后竖直）。
     */
    private fun corner(
        out: Path,
        startX: Float,
        startY: Float,
        dx: Float,
        dy: Float,
        mx: Float,
        my: Float,
        swapAxes: Boolean,
    ) {
        val ax = abs(dx)
        val ay = abs(dy)
        for (i in 0..SEGMENTS) {
            val alongX = if (swapAxes) unitV[i] else unitU[i]
            val alongY = if (swapAxes) unitU[i] else unitV[i]
            pointX[i] = startX + mx * alongX * ax
            pointY[i] = startY + my * alongY * ay
        }
        // 数值积分有千分之一的误差，端点直接钉在直边上，接缝处才不会漏一丝缝。
        pointX[0] = startX
        pointY[0] = startY
        pointX[SEGMENTS] = startX + dx
        pointY[SEGMENTS] = startY + dy
        for (i in 0 until SEGMENTS) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i3 = (i + 2).coerceAtMost(SEGMENTS)
            // Catmull-Rom 转三次贝塞尔：控制点由前后相邻点定，采样点之间没有折角。
            val c1x = pointX[i] + (pointX[i + 1] - pointX[i0]) / 6f
            val c1y = pointY[i] + (pointY[i + 1] - pointY[i0]) / 6f
            val c2x = pointX[i + 1] - (pointX[i3] - pointX[i]) / 6f
            val c2y = pointY[i + 1] - (pointY[i3] - pointY[i]) / 6f
            out.cubicTo(c1x, c1y, c2x, c2y, pointX[i + 1], pointY[i + 1])
        }
    }
}
