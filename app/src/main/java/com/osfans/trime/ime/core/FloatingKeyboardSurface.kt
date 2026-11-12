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
import android.view.ViewOutlineProvider
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
 * 工具栏图标上沿到工具栏上沿的距离。
 *
 * 用来算圆角弧线在图标高度处吃掉多少横向空间：图标行高度相同，留白越小，
 * 需要让开的宽度越大。6dp 偏保守，宁可多让一点也不要切到图标。
 */
internal const val TOOLBAR_ICON_TOP_GAP_DP = 6f

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
    private val clipPath = Path()
    private var radii = FloatArray(8)

    /** 面板描边层，铺满整块面板、不接触摸；圆角与容器同一条 G2 曲线。 */
    private val borderOverlay = KeyboardBorderView(context)

    init {
        addView(borderOverlay, LayoutParams(0, 0).apply {
            startToStart = LayoutParams.PARENT_ID
            endToEnd = LayoutParams.PARENT_ID
            topToTop = LayoutParams.PARENT_ID
            bottomToBottom = LayoutParams.PARENT_ID
        })
    }

    /** 描边的实时来源（颜色 + 宽度 px），由 InputView 注入；见 [KeyboardBorderView.syncIfStale]。 */
    var borderProvider: (() -> Pair<Int, Float>)?
        get() = borderOverlay.borderProvider
        set(value) { borderOverlay.borderProvider = value }

    /** 亮边强度的实时来源；null 时按 0 处理。 */
    var glowProvider: (() -> Int)?
        get() = borderOverlay.glowProvider
        set(value) { borderOverlay.glowProvider = value }

    fun setCornerRadii(corners: List<Float>) {
        val next = FloatArray(8) { corners.getOrElse(it / 2) { 0f } }
        if (next.contentEquals(radii)) return
        radii = next
        borderOverlay.setCornerRadii(radii)
        rebuildClip(); rebuildOutline(); invalidate()
    }

    fun setGlow(strength: Int) = borderOverlay.setGlow(strength)

    fun setBorder(color: Int, widthPx: Float) {
        borderOverlay.setBorder(color, widthPx)
    }

    private fun rebuildClip() {
        clipPath.reset()
        if (radii.any { it > 0 }) {
            // G2 圆角：与背景层、描边层同一条曲率连续曲线。
            com.osfans.trime.ime.appearance.Squircle.into(
                clipPath,
                RectF(0f, 0f, width.toFloat(), height.toFloat()),
                radii,
            )
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); rebuildClip(); rebuildOutline()
    }

    /**
     * 圆角的系统级裁剪。
     *
     * `canvas.clipPath` 要经过整条绘制链（容器 → 子视图 → 背景 ImageView → drawable），
     * 中间任何一层建立离屏缓冲或硬件层都可能把 clip 丢掉，表现成「值设进去了但键盘还是直角」。
     * [View.setClipToOutline] 是渲染节点自己的 outline，由系统在合成时裁剪，不依赖画布状态，
     * 顺带让阴影也贴着圆角走。
     */
    private fun rebuildOutline() {
        if (radii.none { it > 0f }) {
            clipToOutline = false
            outlineProvider = ViewOutlineProvider.BACKGROUND
            invalidateOutline()
            return
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    // 系统级裁剪同样走 G2 路径：它和 canvas.clipPath 是两条独立的路径，
                    // 两边曲线不一致时圆角边缘会出现一道重复的轮廓。
                    outline.setPath(
                        com.osfans.trime.ime.appearance.Squircle.into(
                            Path(),
                            RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                            radii,
                        ),
                    )
                } else {
                    // 旧系统只支持四角同半径。
                    outline.setRoundRect(0, 0, view.width, view.height, radii.maxOrNull() ?: 0f)
                }
            }
        }
        clipToOutline = true
        invalidateOutline()
    }

    /**
     * 四角圆角在这里裁：背景、工具栏、键区和底部留白都是本容器的子视图，
     * 一个 clipPath 就能把整块面板削成圆角面板。
     *
     * 只有窗口背景是透明的时候才看得见——否则削掉的四角露的是窗口底色，
     * 和键盘底色接近，表现成「设置不生效」。见 TrimeInputMethodService
     * .setInputWindowBackdropTransparent。
     */
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        if (radii.any { it > 0 }) canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restoreToCount(save)
    }
}

/**
 * 面板描边层：沿键盘外轮廓内侧画一圈描边，再在描边带里叠一层自上而下的白色高光。
 *
 * 颜色 / 宽度 / 高光都由 [borderProvider]、[glowProvider] 实时提供——保存外观不一定会
 * 重建输入法，键盘可能一直挂在屏幕上，所以每次绘制前按配置版本自查一次。
 */
internal class KeyboardBorderView(context: Context) : View(context) {
    private var color = 0
    private var widthPx = 0f
    /** 描边的实时来源（颜色 + 宽度 px），由 InputView 注入；见 [syncIfStale]。 */
    var borderProvider: (() -> Pair<Int, Float>)? = null
    /** 亮边强度的实时来源；null 时按 0 处理。 */
    var glowProvider: (() -> Int)? = null
    private var glow = 0
    private val glowPaths = Array(GLOW_LAYERS) { Path() }
    /** 单独的裁剪路径：不能复用 [path]，那是描边用的向内收过的轮廓。 */
    private val glowClipPath = Path()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var radii = FloatArray(8)
    private var syncedVersion = Long.MIN_VALUE

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    fun setCornerRadii(corners: FloatArray) {
        if (corners.contentEquals(radii)) return
        radii = corners
        rebuild()
    }

    fun setGlow(strength: Int) {
        val clamped = strength.coerceIn(0, 100)
        if (clamped == glow) return
        glow = clamped
        invalidate()
    }

    fun setBorder(color: Int, widthPx: Float) {
        if (color == this.color && widthPx == this.widthPx) return
        this.color = color
        this.widthPx = widthPx.coerceAtLeast(0f)
        rebuild()
    }

    /** 配置版本变了才重读，避免每帧都走一遍外观查找链。 */
    fun syncIfStale(version: Long) {
        if (version == syncedVersion) return
        val provider = borderProvider ?: return
        syncedVersion = version
        val (liveColor, liveWidth) = provider()
        setBorder(liveColor, liveWidth)
        setGlow(glowProvider?.invoke() ?: 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        path.reset()
        if (widthPx > 0f && android.graphics.Color.alpha(color) != 0 && width > 0 && height > 0) {
            val inset = widthPx / 2f
            if (width > 2 * inset && height > 2 * inset) {
                // 整体向内收半个线宽再描边，圆角也跟着走同一条 G2 曲线（半径按内缩量同步减）。
                com.osfans.trime.ime.appearance.Squircle.into(
                    path,
                    RectF(inset, inset, width - inset, height - inset),
                    FloatArray(8) { (radii[it] - inset).coerceAtLeast(0f) },
                )
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        syncIfStale(com.osfans.trime.ime.appearance.SurfaceAppearance.version)
        if (widthPx <= 0f || android.graphics.Color.alpha(color) == 0 || path.isEmpty) return
        paint.color = color
        paint.strokeWidth = widthPx
        canvas.drawPath(path, paint)
        drawGlow(canvas)
    }

    /**
     * 描边带上的白色高光。
     *
     * 只画在描边自身的宽度里：外沿最亮 → 内沿透明，宽度永远跟着描边宽度走，
     * 所以细描边是细亮线、粗描边是柔和的厚边，永远不会超出用户设的那条带。
     * 四层等分这条带，透明度依次衰减；每层用一条垂直渐变描边（顶白、底 45%），
     * 光线看起来是从上方打下来的，四个角自动过渡，不用额外算圆弧。
     *
     * 白色最亮也要封顶在 [GLOW_MAX_ALPHA]：一旦到 255 完全不透明，就会把描边
     * 本身的颜色整个抹掉，在浅色/白色背景上看起来像沿外沿漏出一道白边。
     */
    private fun drawGlow(canvas: Canvas) {
        if (glow <= 0 || widthPx <= 0f || width <= 0 || height <= 0) return
        val outer = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val clip = canvas.save()
        com.osfans.trime.ime.appearance.Squircle.into(glowClipPath, outer, radii)
        canvas.clipPath(glowClipPath)
        val layerWidth = widthPx / GLOW_LAYERS
        for (i in 0 until GLOW_LAYERS) {
            val centerInset = layerWidth * (i + 0.5f)
            val layerPath = glowPaths[i]
            layerPath.reset()
            com.osfans.trime.ime.appearance.Squircle.into(
                layerPath,
                RectF(centerInset, centerInset, width - centerInset, height - centerInset),
                FloatArray(8) { (radii[it] - centerInset).coerceAtLeast(0f) },
            )
            val base = glow / 100f * GLOW_ALPHA[i]
            val top = android.graphics.Color.argb((255 * base).toInt().coerceIn(0, GLOW_MAX_ALPHA), 255, 255, 255)
            val bottom = android.graphics.Color.argb((255 * base * 0.45f).toInt().coerceIn(0, GLOW_MAX_ALPHA), 255, 255, 255)
            glowPaint.strokeWidth = layerWidth
            glowPaint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, height.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP,
            )
            canvas.drawPath(layerPath, glowPaint)
        }
        glowPaint.shader = null
        canvas.restoreToCount(clip)
    }

    private companion object {
        /** 描边带切成几层做渐变；4 层在 1–3dp 上看不出分界。 */
        const val GLOW_LAYERS = 4
        /** 高光最亮的白色封顶值（0–255）：保留描边本身颜色，不出现不透明的实白。 */
        const val GLOW_MAX_ALPHA = 165
        /** 由外到内的透明度系数：外沿最亮，内沿几乎透明。 */
        val GLOW_ALPHA = floatArrayOf(1f, 0.72f, 0.42f, 0.16f)
    }
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
        // 盒子要容下「角曲线的沿边长度 + 触摸臂 + 余量」：G2 的角比同半径圆弧长
        // （半径 × EXTENT_RATIO），按半径算会让大圆角下的长臂顶出边界被裁掉。
        val reach = com.osfans.trime.ime.appearance.Squircle.EXTENT_RATIO
        val boxWidth = kotlin.math.ceil(2 * (rx * reach + touchArm + context.dp(16f))).toInt()
        val boxHeight = kotlin.math.ceil(2 * (ry * reach + touchArm + context.dp(16f))).toInt()
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
        // 卡片描边、背景裁切、系统 outline 都是 G2 曲线，手柄也必须同一条：
        // 圆弧在这个尺寸下与卡片的角会差出一两个像素，转角处能看出一条错位的细线。
        val ex = radiusX * com.osfans.trime.ime.appearance.Squircle.EXTENT_RATIO
        val ey = radiusY * com.osfans.trime.ime.appearance.Squircle.EXTENT_RATIO
        fun trace(path: Path, length: Float) {
            path.reset()
            val dir = if (fromLeft) 1f else -1f
            // 竖直臂贴卡片的侧边（外沿让半个线宽），走完角曲线后落到卡片底边。
            val edgeX = x - dir * offset
            val startY = y - ey
            val dx = dir * (ex + offset)
            val dy = ey + offset
            path.moveTo(edgeX, startY - length)
            path.lineTo(edgeX, startY)
            com.osfans.trime.ime.appearance.Squircle.cornerInto(path, edgeX, startY, dx, dy)
            path.lineTo(edgeX + dx + dir * length, y + offset)
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
