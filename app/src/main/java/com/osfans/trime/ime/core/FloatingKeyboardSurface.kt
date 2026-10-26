/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-FileCopyrightText: 2026 CatTrime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Adapted from lzlv312/CatTrime my-mod-floatingKeyboard; see CATTRIME_PORT.md.
 */
package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.PathMeasure
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Height of the floating keyboard surface, whose drop shadow renders at this Z.
 */
internal const val FLOATING_KEYBOARD_ELEVATION_DP = 8f

/**
 * Z of the key popup layer (popup preview + popup keyboard).
 *
 * MUST stay strictly above [FLOATING_KEYBOARD_ELEVATION_DP]: the popup layer and the
 * keyboard are siblings, and Android paints siblings in ascending elevation order, so
 * an equal or lower value would let the floating keyboard cover the popups.
 */
internal const val POPUP_LAYER_ELEVATION_DP = 24f

/**
 * Z of the floating preedit layer.
 *
 * Must sit strictly above [FLOATING_KEYBOARD_ELEVATION_DP] (so the vertical preedit
 * offset can push it over the card edge -- while composing dragging is not needed) and
 * strictly below [POPUP_LAYER_ELEVATION_DP] (key popups stay on top). The preedit root
 * has no background, so this Z adds no shadow of its own.
 */
internal const val PREEDIT_LAYER_ELEVATION_DP = 12f

internal fun resizedPercent(startPercent: Int, startPixels: Int, deltaPixels: Float, min: Int, max: Int): Int = (startPercent * (startPixels + deltaPixels) / startPixels).roundToInt().coerceIn(min, max)

/**
 * Narrowest the floating keyboard is allowed to get, in dp.
 *
 * The width preference is a share, and the share is taken against a reference that survives a
 * rotation (see [widthPercentForLayout]). On a device whose window is 1200px wide in portrait and
 * 2670px in landscape, a share of the *window* would make the same setting mean 1116px one way and
 * 2483px the other -- "too big in landscape, tiny in portrait". Pinning the floor to an absolute
 * size as well keeps the keyboard usable no matter how wide the window gets.
 */
internal const val FLOATING_KEYBOARD_MIN_WIDTH_DP = 160

/**
 * Lower bound of the width setting, mirroring `AppPrefs.Keyboard.floatingKeyboardWidth`'s slider.
 *
 * The absolute dp minimum above usually lands above this on a phone, so this only matters on very
 * narrow screens -- but it must never allow a width the settings slider cannot represent.
 */
internal const val MIN_FLOATING_KEYBOARD_WIDTH_PERCENT = 40

/**
 * Turns a width *share of [referenceWidthPx]* into the share of [windowWidthPx] the layout needs.
 *
 * This is what makes a chosen width survive a rotation: the reference is the screen's short side,
 * which is the same in both orientations, so the same setting produces the same physical width
 * instead of following the window around. Returns 100 when the geometry is not known yet (before
 * the first layout), which leaves the keyboard full width rather than guessing.
 */
internal fun widthPercentForLayout(
    widthPercent: Int,
    referenceWidthPx: Int,
    windowWidthPx: Int,
): Int {
    if (referenceWidthPx <= 0 || windowWidthPx <= 0) return 100
    val share = (widthPercent.toLong() * referenceWidthPx / windowWidthPx).toInt()
    return share.coerceIn(1, 100)
}

/**
 * Smallest share of [referenceWidthPx] that still leaves [minWidthDp] pixels of keyboard.
 *
 * Only bites on very wide windows (landscape, tablets); on a narrow one the ordinary lower bound
 * of the setting is already the larger of the two.
 */
internal fun minWidthPercentFor(
    minWidthDp: Int,
    referenceWidthPx: Int,
    density: Float,
): Int {
    if (referenceWidthPx <= 0 || density <= 0f) return 1
    val minPx = minWidthDp * density
    return (minPx * 100f / referenceWidthPx).roundToInt().coerceAtLeast(1)
}

/**
 * Horizontal scale that makes the composed width equal [widthPercent] percent of the width floor.
 */
internal fun floatingScaleX(widthPercent: Int, baseWidthPercent: Int): Float {
    if (baseWidthPercent <= 0) return 1f
    return widthPercent.toFloat() / baseWidthPercent
}

/**
 * Horizontal translation that keeps the floating keyboard within the window.
 *
 * The obvious `coerceIn(margin - left, window - margin - left - width)` collapses once the
 * keyboard is as wide as the window: the upper bound goes negative while the lower one stays
 * positive, and guarding it with `coerceAtLeast` pins the card to the left *margin* -- shoving it
 * 8dp to the right with its right edge hanging off the screen. That is not a corner case either:
 * 100% is the default width, and a card that cannot sit inside both margins has no such position,
 * so it is placed flush with the left edge instead. That is the only side the user can still
 * reach, and the right edge is where a full-width card has nothing left to give.
 */
internal fun floatingTranslationX(
    requestedX: Float,
    cardLeft: Int,
    visualWidth: Float,
    windowWidth: Int,
    margin: Float,
): Float {
    if (visualWidth >= windowWidth - 2 * margin) {
        val leftEdge = -cardLeft.toFloat()
        val rightEdge = (windowWidth - cardLeft - visualWidth).coerceAtLeast(leftEdge)
        return requestedX.coerceIn(leftEdge, rightEdge)
    }
    val min = margin - cardLeft
    val max = (windowWidth - margin - cardLeft - visualWidth).coerceAtLeast(min)
    return requestedX.coerceIn(min, max)
}

/**
 * Vertical scale for the floating keyboard.
 *
 * 与 [floatingScaleX] 对称的绝对档位换算：scaleY = 高度档位 / 基准档位。曾经用
 * 「窗口高度/键盘内容高度」作放大系数（1 + ref/cont·(h-base)/base），但悬浮模式下窗口
 * 比键盘内容高得多，一档高度变化被放大数倍，表现为内容纵向过度拉伸、底边大幅溢出、
 * 卡片被补偿逻辑一路推到屏幕顶部。档位对档位的绝对换算与宽度行为一致。
 */
internal fun floatingScaleY(
    heightPercent: Int,
    baseHeightPercent: Int,
): Float {
    if (baseHeightPercent <= 0) return 1f
    return heightPercent.toFloat() / baseHeightPercent
}

/**
 * 悬浮键盘的容器。缩放走卡片两个下角的粉色直角手柄（[FloatingResizeHandleView]），
 * 位置移动走收起后的空白工具栏拖拽——容器本身不再识别任何手势。
 */
internal class FloatingKeyboardContainer(
    context: Context,
    floating: Boolean,
) : ConstraintLayout(context) {
    var floating: Boolean = floating
        private set
}

/**
 * 白色主体、粉色细边的连续缩放条，沿 [setCornerGeometry] 给出的实际卡片圆角绘制。
 * 可见直线臂为 7dp；触摸轮廓仍保留 16dp 臂和 28dp 宽度，缩短外观不缩小操作区域。
 * 拖动使用 raw 坐标，避免手柄随键盘移动时局部坐标漂移。
 */
@SuppressLint("ViewConstructor")
internal class FloatingResizeHandleView(
    context: Context,
    private val fromLeft: Boolean,
    private val onResizeStart: () -> Unit,
    private val onResize: (dx: Float, dy: Float) -> Unit,
    private val onResizeEnd: () -> Unit,
) : View(context) {
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(5f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = 0xFFF48FB1.toInt()
    }
    private val innerPaint = Paint(outerPaint).apply { strokeWidth = context.dp(3.5f); color = android.graphics.Color.WHITE }
    private val hitPaint = Paint(outerPaint).apply { strokeWidth = context.dp(28f) }
    private val contour = Path()
    private val touchContour = Path()
    private val hitPath = Path()
    private val hitRegion = Region()
    private val arm = context.dp(7f)
    private val touchArm = context.dp(16f)
    private var radiusX = 0f
    private var radiusY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var started = false

    fun setCornerGeometry(x: Float, y: Float) {
        val rx = x.coerceAtLeast(0f)
        val ry = y.coerceAtLeast(0f)
        if (radiusX == rx && radiusY == ry && width > 0) return
        radiusX = rx; radiusY = ry
        val boxWidth = kotlin.math.ceil(2 * (rx + touchArm + context.dp(16f))).toInt()
        val boxHeight = kotlin.math.ceil(2 * (ry + touchArm + context.dp(16f))).toInt()
        layoutParams?.let { params ->
            if (params.width != boxWidth || params.height != boxHeight) {
                params.width = boxWidth; params.height = boxHeight; layoutParams = params
            }
        }
        rebuildContour(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildContour()
    }

    private fun rebuildContour() {
        val x = width / 2f
        val y = height / 2f
        val offset = outerPaint.strokeWidth / 2f
        fun trace(path: Path, length: Float) {
            path.reset()
            if (fromLeft) {
                path.moveTo(x - offset, y - radiusY - length)
                path.lineTo(x - offset, y - radiusY)
                path.arcTo(RectF(x - offset, y - 2 * radiusY - offset,
                    x + 2 * radiusX + offset, y + offset), 180f, -90f)
                path.lineTo(x + radiusX + length, y + offset)
            } else {
                path.moveTo(x + offset, y - radiusY - length)
                path.lineTo(x + offset, y - radiusY)
                path.arcTo(RectF(x - 2 * radiusX - offset, y - 2 * radiusY - offset,
                    x + offset, y + offset), 0f, 90f)
                path.lineTo(x - radiusX - length, y + offset)
            }
        }
        trace(contour, arm)
        trace(touchContour, touchArm)
        hitPaint.getFillPath(touchContour, hitPath)
        hitRegion.setPath(hitPath, Region(0, 0, width, height))
    }

    /** Local midpoint on the actual curved grip; also used by app-owned gesture regression. */
    fun gripPoint(): FloatArray = FloatArray(2).also {
        val measure = PathMeasure(contour, false)
        measure.getPosTan(measure.length / 2f, it, null)
    }

    fun unionTouchRegion(region: Region, windowX: Int, windowY: Int) {
        val translated = Region(hitRegion)
        translated.translate(windowX, windowY)
        region.op(translated, Region.Op.UNION)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(contour, outerPaint)
        canvas.drawPath(contour, innerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitRegion.contains(event.x.toInt(), event.y.toInt())) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                downRawX = event.rawX
                downRawY = event.rawY
                started = false
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!started && hypot(dx, dy) < context.dp(DEAD_ZONE_DP)) return true
                if (!started) {
                    started = true
                    onResizeStart()
                    // 重新以判定点为基准，避免起步位移一次性计入尺寸。
                    downRawX = event.rawX
                    downRawY = event.rawY
                    return true
                }
                onResize(event.rawX - downRawX, event.rawY - downRawY)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (started) onResizeEnd()
                started = false
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        private const val DEAD_ZONE_DP = 2f

        /** 手柄触摸盒边长：角线一半长度再加余量，保证拇指好按。 */
        fun boxSizeDp(context: Context): Int = context.dp(56)
    }
}
