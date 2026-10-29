/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-FileCopyrightText: 2026 CatTrime contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.view.ContextThemeWrapper
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.allInstances
import org.kodein.di.bindInstance
import org.kodein.di.bindSingleton
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isFloatingEffective
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.util.isLandscape
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import kotlin.math.abs

import kotlin.math.roundToInt

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    scope: ThemeScope,
) : BaseInputView(service, rime, scope), DIAware {
    private val keyboardPrefs = AppPrefs.defaultInstance().keyboard

    // 「有效悬浮」（KeyboardPrefs.isFloatingEffective）：横屏回落普通键盘时为
    // false，恢复原始 landscape_keyboard 行为。构造期一次性求值；横屏悬浮偏好
    // 变化经 recreateInputViewPrefs 重建输入视图生效。单手模式也走悬浮卡片
    // （固定沉底变体），因此 isFloatingEffective 把单手一并算进来。
    var isFloatingKeyboardEnabled = service.isFloatingEffective()
        private set

    /** 本视图是否为单手模式（悬浮卡片 + 固定沉底、只可水平拖动、顶起输入框）。 */
    private val oneHandEnabled = keyboardPrefs.oneHandMode.getValue() != 0 &&
        !resources.configuration.isLandscape()

    val isOneHandMode: Boolean
        get() = isFloatingKeyboardEnabled && oneHandEnabled

    /**
     * 宽/高档位偏好镜像（双指缩放松手落盘时更新）。
     *
     * 缩放的「基准」不在这里：网格的像素几何由 Keyboard 对象构造时按当次档位烘焙死，
     * 基准跟随当前 Keyboard 的烘焙值（见 [floatingKeyboardBaseWidth]）——宽度变化触发的
     * refreshKeyboards 会按新偏好重新构造 Keyboard，基准随新烘焙值走、缩放立即归一，
     * 布局与 scaleY 不会把同一档位施加两次。
     */
    private var floatingKeyboardWidth = com.osfans.trime.ime.keyboard.KeyboardPrefs.run { context.presentationWidthPercent() }
    private var floatingKeyboardHeight = keyboardPrefs.floatingKeyboardHeight.getValue()

    /** 缩放基准 = 当前键盘构造时烘焙的档位；键盘尚未挂载时退回偏好镜像。 */
    private val floatingKeyboardBaseWidth: Int
        get() = keyboardWindow.currentBakedFloatingWidthPercent ?: floatingKeyboardWidth

    private val floatingKeyboardBaseHeight: Int
        get() = keyboardWindow.currentBakedFloatingHeightPercent ?: floatingKeyboardHeight
    private val floatingPreeditOffsetX = keyboardPrefs.floatingPreeditOffsetX.getValue()
    private val floatingPreeditOffsetY = keyboardPrefs.floatingPreeditOffsetY.getValue()

    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job

    override val di = DI {
        bindInstance<InputView> { this@InputView }
        bindInstance<ContextThemeWrapper> { themedContext }
        bindInstance<ThemeScope> { scope }
        bindInstance<Theme> { scope.theme }
        bindInstance<TrimeInputMethodService> { service }
        bindInstance<RimeSession> { rime }
        bindSingleton { InputBroadcaster() }
        bindSingleton { PopupDelegate(di) }
        bindSingleton { EnterKeyDisplayDelegate(di) }
        bindSingleton { PreeditDelegate(di) }
        bindSingleton { CommonKeyboardActionListener(di) }
        bindSingleton { BoardWindowManager(di) }
        bindSingleton { InputBarDelegate(di) }
        bindSingleton { CompactCandidateDelegate(di) }
        bindSingleton { KeyboardWindow(di) }
        bindSingleton { LiquidWindow(di) }
    }

    private val broadcaster: InputBroadcaster by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val preedit: PreeditDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.style.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.style.keyboardPaddingLand
    private val keyboardBottomPadding = theme.style.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.style.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    private var bottomOverlay: View? = null
    private var unifiedVideo: com.osfans.trime.ime.keyboard.VideoKeyboardBackground? = null
    private var unifiedVideoPath: String? = null

    fun setUnifiedVideoActive(active: Boolean) { unifiedVideo?.setActive(active) }

    private fun updateUnifiedBackground() {
        val enabled = com.osfans.trime.ime.appearance.SurfaceAppearance.unifiedBackground(theme.isCustom)
        val background = keyboardWindow.currentBackground.takeIf { enabled }
        keyboardBackground.imageDrawable = background?.drawable(context, resources.displayMetrics.widthPixels) ?: scope.drawable("keyboard_background")
        com.osfans.trime.ime.appearance.BackdropBlur.invalidateBackground(keyboardView)
        val path = background?.takeIf { it.type == com.osfans.trime.data.keymap.CustomBackground.Type.VIDEO }?.image
        if (path == unifiedVideoPath && unifiedVideo != null) return
        unifiedVideo?.let { it.release(); (it.parent as? android.view.ViewGroup)?.removeView(it) }
        unifiedVideo = null
        unifiedVideoPath = path
        if (path != null) {
            val video = com.osfans.trime.ime.keyboard.VideoKeyboardBackground(context, path)
            video.id = View.generateViewId()
            val params = LayoutParams(0, 0).apply {
                startToStart = LayoutParams.PARENT_ID; endToEnd = LayoutParams.PARENT_ID
                topToTop = LayoutParams.PARENT_ID; bottomToBottom = LayoutParams.PARENT_ID
            }
            (keyboardView as android.view.ViewGroup).addView(video, 1, params)
            unifiedVideo = video
        }
    }

    fun showBottomOverlay(view: View, heightPx: Int) {
        hideBottomOverlay()
        bottomOverlay = view
        add(view, lParams(matchParent, heightPx) { above(keyboardView); centerHorizontally() })
        requestInsetsUpdateIfFloatingBoundsChanged()
    }

    fun hideBottomOverlay() {
        bottomOverlay?.let { removeView(it) }
        bottomOverlay = null
        service.requestInputInsetsUpdate()
    }

    fun restoreInputState(source: com.osfans.trime.ime.keyboard.Keyboard) = keyboardWindow.restoreDockedState(source)

    val keyboardView: View

    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f
    private var dragMoved = false
    private var lastHandleTapTime = 0L
    private val dragTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartScaleX = 1f
    private var resizeStartScaleY = 1f
    private var resizeStartTranslationX = 0f
    private var resizeStartTranslationY = 0f
    private var resizeStartWidthPercent = floatingKeyboardWidth
    private var resizeStartHeightPercent = floatingKeyboardHeight
    private var resizedWidth = floatingKeyboardWidth
    private var resizedHeight = floatingKeyboardHeight

    /**
     * Window-space bounds of the floating layer as last reported to the IME window.
     *
     * Guarded by [hasLastFloatingBounds] because the bounds are unknown (and the whole
     * floating layout is inactive) until the keyboard is actually laid out in floating mode.
     */
    private val lastFloatingBounds = Rect()
    private var hasLastFloatingBounds = false
    private var floatingSystemBottomInset = 0

    /** 悬浮卡片两个下角的缩放手柄：粉色直角条，拖动调宽高（替代原双指缩放）。 */
    private val leftResizeHandle = FloatingResizeHandleView(
        context,
        fromLeft = true,
        onResizeStart = {
            beginFloatingResize()
            onFloatingDragMode(true)
        },
        onResize = { dx, dy -> updateFloatingResize(-dx, dy) },
        onResizeEnd = {
            endFloatingResize()
            onFloatingDragMode(false)
        },
    )
    private val rightResizeHandle = FloatingResizeHandleView(
        context,
        fromLeft = false,
        onResizeStart = {
            beginFloatingResize()
            onFloatingDragMode(true)
        },
        onResize = ::updateFloatingResize,
        onResizeEnd = {
            endFloatingResize()
            onFloatingDragMode(false)
        },
    )

    /**
     * The width the floating width percentage is measured against: the screen's **short** side.
     *
     * A rotation swaps the window's width and height, so a share of the window itself would mean
     * two different physical sizes for one setting — the keyboard ends up filling a landscape
     * screen and looking tiny in portrait. The short side is the one dimension a rotation leaves
     * alone, which is what makes the chosen width hold still across the turn.
     */
    private fun referenceWidthPx(): Int {
        val metrics = resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels)
    }

    /** The share [minWidthPercent] of the reference width, floored by an absolute size. */
    private fun minWidthPercent(): Int = minWidthPercentFor(
        minWidthDp = FLOATING_KEYBOARD_MIN_WIDTH_DP,
        referenceWidthPx = referenceWidthPx(),
        density = resources.displayMetrics.density,
    ).coerceIn(MIN_FLOATING_KEYBOARD_WIDTH_PERCENT, 100)

    /** Converts the stored width share into the share the layout has to use. */
    private fun layoutWidthPercent(widthPercent: Int): Int = widthPercentForLayout(
        widthPercent = widthPercent,
        referenceWidthPx = referenceWidthPx(),
        // init 阶段 InputView 尚未布局（width=0），传 view 宽度会让 widthPercentForLayout
        // 退化为 100，键盘从此贴满屏幕宽度、只有缩放手势才能纠正。屏幕宽度在构建时
        // 即可用，且与布局完成后的实际值一致（IME 窗口是全屏宽）。
        windowWidthPx = resources.displayMetrics.widthPixels,
    )

    /** 屏幕短边的像素数：横屏时即屏幕可显示高度的上限（竖屏时键盘远够不到它）。 */
    private fun screenShortSidePx(): Int = with(resources.displayMetrics) { minOf(heightPixels, widthPixels) }

    /** 双指按下时记录缩放基线（当前尺寸/缩放/百分比/位置锚点）。 */
    private fun beginFloatingResize() {
        resizeStartWidth = keyboardView.width
        resizeStartHeight = windowManager.view.height
        resizeStartScaleX = keyboardView.scaleX
        resizeStartScaleY = keyboardView.scaleY
        resizeStartWidthPercent = floatingKeyboardWidth
        resizeStartHeightPercent = floatingKeyboardHeight
        resizedWidth = resizeStartWidthPercent
        resizedHeight = resizeStartHeightPercent
        // 位置锚点：手势期间每帧从锚点重算位置（而不是在上移补偿上累加），
        // 缩小时卡片自动回到原位，“放大跑向屏幕顶部”不会再发生。
        resizeStartTranslationX = keyboardView.translationX
        resizeStartTranslationY = keyboardView.translationY
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
    }

    /** 缩放手柄拖动驱动尺寸：等比模式取主导轴的位移，非等比两轴各自驱动。 */
    private fun updateFloatingResize(widthDelta: Float, heightDelta: Float) {
        if (resizeStartWidth == 0 || resizeStartHeight == 0) return
        val keepRatio = keyboardPrefs.floatingKeyboardKeepRatio.getValue()
        // 等比模式取主导轴的位移（对角线两向相当，取哪轴都一样）；
        // 宽高偏好是同一个值：宽度是唯一的“缩放档位”，高度只是它的显示副本。
        val widthDeltaEffective = if (keepRatio) {
            if (abs(widthDelta) >= abs(heightDelta)) widthDelta else heightDelta
        } else {
            widthDelta
        }
        resizedWidth = resizedPercent(
            resizeStartWidthPercent,
            (resizeStartWidth * resizeStartScaleX).roundToInt(),
            widthDeltaEffective,
            minWidthPercent(),
            100,
        )
        resizedHeight =
            if (keepRatio) {
                resizedWidth
            } else {
                resizedPercent(
                    resizeStartHeightPercent,
                    (resizeStartHeight * resizeStartScaleY).roundToInt(),
                    heightDelta,
                    40,
                    100,
                )
            }
        keyboardView.scaleX = floatingScaleX(resizedWidth, floatingKeyboardBaseWidth)
        // 等比：Y 直接复制 X，内容严格不变形。非等比：Y 用与 X 对称的绝对档位换算
        // （档位/基准档位），两轴共用同一套捏合数理，不再被窗口/内容高度比放大。
        // 屏幕容纳量不在这里硬 clamp（clamp 的职责在 applyFloatingScale/
        // clampedMaxScaleY），超出窗口底边的部分由 applyFloatingPosition 的
        // maxTranslationY 上移钳制兜底。
        keyboardView.scaleY =
            if (keepRatio) {
                keyboardView.scaleX
            } else {
                floatingScaleY(resizedHeight, floatingKeyboardBaseHeight)
            }
        // 每帧从手势起始锚点重算位置：卡片保持在原地，放大超出底边时由
        // applyFloatingPosition 最小幅度上移钳制，缩小时自动回落——
        // 之前单调累加的上移补偿只会把卡片一路推到屏幕顶部。
        applyFloatingPosition(
            resizeStartTranslationX,
            resizeStartTranslationY,
            persist = false,
        )
    }

    /** 缩放/恢复时 Y 方向的上限：卡片缩放后不得超出 IME 窗口（横屏高度超限的修复）。 */
    private fun clampedMaxScaleY(): Float {
        if (keyboardView.height == 0) return Float.MAX_VALUE
        val top = keyboardView.top
        // 卡片底部锚定在窗口底边，top 之上的空隙就是可上移余量（applyFloatingPosition
        // 的 minTranslationY 允许卡片顶到窗口顶边下 16dp）。上限计入这块余量，否则
        // 底锚定代数下 available 恒等于“内容高+8dp”，上限恒为 ~1.0x。
        val lift = (top - dp(16)).coerceAtLeast(0)
        val available = (height - floatingSystemBottomInset - dp(8) - top + lift).toFloat()
        return if (available <= 0) Float.MAX_VALUE else available / keyboardView.height
    }

    /** 手指离开（两指变一指也算）时把当前尺寸落盘，作为下次缩放的基线。 */
    private fun endFloatingResize() {
        if (resizeStartWidth == 0 || resizeStartHeight == 0) return
        floatingKeyboardWidth = resizedWidth
        floatingKeyboardHeight = resizedHeight
        // 落盘新偏好后 scale 平滑落在新的绝对值上（floatingScaleX/Y = 档位/当前烘焙基准），
        // 手势期间看到多大，松手后就保持多大。宽度自此由布局 percent 承载（scaleX 归一）；
        // 高度先由 scaleY 短暂承载——宽度变化触发的 refreshKeyboards 会按新偏好重新构造
        // Keyboard（重新烘焙网格高度），新键盘挂载后缩放基准随新烘焙值归一（见
        // updateWindowViewHeightJob 收集器），网格视觉与手势结束时逐像素一致。
        resizeStartWidth = 0
        resizeStartHeight = 0
        keyboardView.updateLayoutParams<LayoutParams> {
            matchConstraintPercentWidth = layoutWidthPercent(resizedWidth) / 100f
        }
        keyboardView.doOnLayout { applyFloatingScale() }
        service.updateFloatingKeyboardSize(resizedWidth, resizedHeight)
        keyboardWindow.refreshKeyboards(isAll = true)
        service.requestInputInsetsUpdate()
    }

    /**
     * Pushes the persisted size preferences onto the surface as `scaleX` / `scaleY`.
     *
     * The width is carried by the layout ([`matchConstraintPercentWidth`]), so its scale is
     * normally identity and only differs while the container still measures at the previous
     * percentage. The height rides `scaleY` only until the grid is (re)baked at the current
     * preference — a rebuilt `Keyboard` bakes the new tier into its geometry, the base (the
     * Keyboard's bake) then catches up and this scale settles at 1. The base always being the
     * current Keyboard's bake is what keeps the layout bake and this scale from applying the
     * same tier twice (which read as "the keyboard gets squashed after every release").
     */
    private fun applyFloatingScale() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0 || keyboardView.height == 0) return
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
        keyboardView.scaleX = 1f
        keyboardView.scaleY =
            if (keyboardPrefs.floatingKeyboardKeepRatio.getValue()) {
                // 等比：宽度偏好是唯一的缩放档位，Y 复制 X 的期望缩放（宽度本身由
                // 布局承载，scaleX≈1，所以这里取的是“档位缩放”而非实时 scaleX）。
                floatingScaleX(floatingKeyboardWidth, floatingKeyboardBaseWidth)
            } else {
                floatingScaleY(floatingKeyboardHeight, floatingKeyboardBaseHeight)
            }.coerceAtMost(clampedMaxScaleY())
        applyFloatingPosition(
            keyboardView.translationX,
            keyboardView.translationY,
            persist = false,
        )
    }

    /**
     * 键盘（重新）挂载后的落位：缩放按当前烘焙基准归一，位置回退到用户拖拽持久化的值。
     *
     * 归一时卡片几何已随新烘焙网格变化：手势期间为贴合底边强制上移的 translation 是
     * 尺寸钳制的临时产物而非用户意图（persist=false，从未写入偏好），若沿用会让卡片
     * 在新几何下悬空——回退到 floatingKeyboardOffset 持久值（拖拽松手时才落盘），
     * 与重建路径（构造时的 post）行为一致。单手模式固定沉底：只有水平位置持久值，
     * 0 表示尚未拖过、按左右手贴边。
     */
    private fun restoreFloatingPlacement() {
        applyFloatingScale()
        if (isOneHandMode) {
            applyFloatingPosition(0f, 0f, persist = false)
        } else {
            applyFloatingPosition(
                keyboardPrefs.floatingKeyboardOffsetX.getValue().toFloat(),
                keyboardPrefs.floatingKeyboardOffsetY.getValue().toFloat(),
                persist = false,
            )
        }
    }

    private fun syncFloatingPreeditPosition() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0) return
        val preeditRoot = preedit.ui.root
        // 水平偏移同时作为左右两侧外边距：宽度按 2*offset 收缩，预编辑右缘始终与
        // 悬浮卡片对齐——旧行为只平移不收缩，右移时右缘会超出卡片宽度（用户实测）。
        // 负偏移（左移）保持根宽不变：根与卡片左缘对齐，左缘随平移探出卡片左侧，
        // 与旧行为一致。24dp 是收缩后的宽度下限，防止极端偏移把预编辑挤没了。
        val preeditOffsetX = dp(floatingPreeditOffsetX)
        val desiredWidth =
            if (preeditOffsetX > 0) {
                (keyboardView.width - 2 * preeditOffsetX).coerceAtLeast(dp(24))
            } else {
                keyboardView.width
            }
        if (preeditRoot.layoutParams.width != desiredWidth) {
            preeditRoot.updateLayoutParams {
                width = desiredWidth
            }
        }
        preeditRoot.translationX =
            keyboardView.left + keyboardView.translationX - preeditRoot.left + preeditOffsetX
        preeditRoot.pivotX = 0f
        preeditRoot.scaleX = keyboardView.scaleX
        // 与卡片顶边齐平，不加防接缝重叠：任何重叠都会压住顶部拖动条或底部拖动条
        // 布局下的输入栏，且拖动条在上/下两种位置需保持一致。
        preeditRoot.translationY = keyboardView.translationY + dp(floatingPreeditOffsetY)
        preedit.updateTouchReceiverPosition()
        bottomOverlay?.let { overlay ->
            if (overlay.layoutParams.width != keyboardView.width) overlay.updateLayoutParams { width = keyboardView.width }
            overlay.translationX = keyboardView.left + keyboardView.translationX - overlay.left
            overlay.translationY = keyboardView.translationY
            overlay.pivotX = 0f
            overlay.scaleX = keyboardView.scaleX
        }
    }

    private fun applyFloatingPosition(
        requestedX: Float,
        requestedY: Float,
        persist: Boolean,
    ) {
        if (!isFloatingKeyboardEnabled || width == 0 || keyboardView.width == 0) return

        val horizontalMargin = if (isOneHandMode) 0f else dp(8).toFloat()
        val visualWidth = keyboardView.width * keyboardView.scaleX
        // 单手模式：requestedX == 0 视为“未拖过”，按左右手贴边（超大值让钳制取边）。
        val effectiveX =
            if (isOneHandMode) {
                if (keyboardPrefs.oneHandMode.getValue() == 1) -Float.MAX_VALUE else Float.MAX_VALUE
            } else {
                requestedX
            }
        val resolvedX =
            floatingTranslationX(
                requestedX = effectiveX,
                cardLeft = keyboardView.left,
                visualWidth = visualWidth,
                windowWidth = width,
                margin = horizontalMargin,
            )
        val resolvedY =
            if (isOneHandMode) {
                // 单手固定沉底：卡片布局已 bottomOfParent + 0 边距，平移恒为 0。
                keyboardView.height * (1f - keyboardView.scaleY)
            } else {
                val floatingTop =
                    preedit.ui.root
                        .takeIf { it.visibility == View.VISIBLE && it.height > 0 }
                        ?.let { it.top + dp(floatingPreeditOffsetY) }
                        ?: keyboardView.top
                val minTranslationY = -(floatingTop - dp(16)).coerceAtLeast(0).toFloat()
                val maxTranslationY =
                    (height - floatingSystemBottomInset - dp(8) - keyboardView.top - keyboardView.height * keyboardView.scaleY)
                        .coerceAtLeast(minTranslationY)
                requestedY.coerceIn(minTranslationY, maxTranslationY)
            }

        keyboardView.translationX = resolvedX
        keyboardView.translationY = resolvedY
        syncFloatingPreeditPosition()
        syncHandlePlacement()
        // The candidate window is docked against the floating layer (see
        // TrimeInputMethodService.refreshCandidatesGeometry); hand it the new position now
        // instead of waiting for the insets round-trip, so it follows the finger.
        service.refreshCandidatesGeometry()
        requestInsetsUpdateIfFloatingBoundsChanged()

        if (persist) {
            if (isOneHandMode) {
                keyboardPrefs.oneHandOffsetX.setValue(resolvedX.roundToInt())
            } else {
                keyboardPrefs.floatingKeyboardOffsetX.setValue(resolvedX.roundToInt())
                keyboardPrefs.floatingKeyboardOffsetY.setValue(resolvedY.roundToInt())
            }
        }
    }

    /** 把两个缩放手柄对准卡片下角（视觉坐标含缩放与平移）。 */
    private fun syncHandlePlacement() {
        if (!isFloatingKeyboardEnabled || isOneHandMode) return
        val corners = com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom).keyboardCorners
        val inherited = keyboardPrefs.floatingKeyboardCornerRadius.getValue().toFloat()
        val leftRadius = minOf(dp(corners.getOrElse(3) { inherited }), keyboardView.width / 2f, keyboardView.height / 2f)
        val rightRadius = minOf(dp(corners.getOrElse(2) { inherited }), keyboardView.width / 2f, keyboardView.height / 2f)
        leftResizeHandle.setCornerGeometry(leftRadius * keyboardView.scaleX, leftRadius * keyboardView.scaleY)
        rightResizeHandle.setCornerGeometry(rightRadius * keyboardView.scaleX, rightRadius * keyboardView.scaleY)
        val visualLeft = keyboardView.left + keyboardView.translationX
        val visualBottom = keyboardView.top + keyboardView.translationY +
            keyboardView.height * keyboardView.scaleY
        val visualRight = visualLeft + keyboardView.width * keyboardView.scaleX
        leftResizeHandle.translationX = visualLeft - leftResizeHandle.left - leftResizeHandle.width / 2f
        leftResizeHandle.translationY = visualBottom - leftResizeHandle.top - leftResizeHandle.height / 2f
        rightResizeHandle.translationX = visualRight - rightResizeHandle.left - rightResizeHandle.width / 2f
        rightResizeHandle.translationY = visualBottom - rightResizeHandle.top - rightResizeHandle.height / 2f
    }

    /**
     * Re-specify the IME window's touchable region, but only when it actually moved.
     *
     * [TrimeInputMethodService.requestInputInsetsUpdate] lays the *entire* input view out
     * again and makes the framework call `onComputeInsets`, which pushes a new touchable
     * region to the window manager. Floating mode re-runs [applyFloatingPosition] for every
     * rime composition message -- that is, once per keystroke -- so an unconditional call
     * here means a full input-view measure/layout plus a window manager transaction per key.
     * The UI thread then trails the input queue and the keyboard appears to freeze.
     *
     * The region is a pure function of the floating layer's position and size, so an
     * unchanged region needs no update. Only [applyFloatingPosition] is throttled this way:
     * drags must keep the region in step with the finger, otherwise the framework would
     * send the rest of the gesture to the app behind the keyboard.
     */
    private fun requestInsetsUpdateIfFloatingBoundsChanged() {
        val bounds = Rect()
        if (getFloatingKeyboardBoundsInWindow(bounds)) {
            if (hasLastFloatingBounds && bounds == lastFloatingBounds) return
            lastFloatingBounds.set(bounds)
            hasLastFloatingBounds = true
        } else {
            // Not measurable yet; report unconditionally and stop comparing.
            hasLastFloatingBounds = false
        }
        service.requestInputInsetsUpdate()
    }

    private fun resetFloatingPosition() {
        lastHandleTapTime = 0L
        applyFloatingPosition(0f, 0f, persist = true)
    }

    private fun handleFloatingDrag(event: MotionEvent, allowTap: Boolean = true): Boolean {
        if (!isFloatingKeyboardEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                dragStartTranslationX = keyboardView.translationX
                dragStartTranslationY = keyboardView.translationY
                dragMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragDownRawX
                val deltaY = event.rawY - dragDownRawY
                if (abs(deltaX) > dragTouchSlop || abs(deltaY) > dragTouchSlop) {
                    dragMoved = true
                }
                applyFloatingPosition(
                    dragStartTranslationX + deltaX,
                    dragStartTranslationY + deltaY,
                    persist = false,
                )
            }

            MotionEvent.ACTION_UP -> {
                if (dragMoved || !allowTap) {
                    applyFloatingPosition(
                        keyboardView.translationX,
                        keyboardView.translationY,
                        persist = true,
                    )
                } else if (event.eventTime - lastHandleTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                    resetFloatingPosition()
                } else {
                    lastHandleTapTime = event.eventTime
                    keyboardView.performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                applyFloatingPosition(
                    keyboardView.translationX,
                    keyboardView.translationY,
                    persist = true,
                )
            }
        }
        return true
    }

    /**
     * 悬浮键盘收起态工具栏的空白区拖动移动卡片（InputBarDelegate 转发）。
     * 单手模式下只改水平位置（applyFloatingPosition 锁底）。allowTap=false：
     * 不触发双击复位，避免误碰工具栏时卡片跳回默认位。
     */
    fun dragFloatingKeyboardFromToolbar(event: MotionEvent): Boolean =
        isFloatingKeyboardEnabled && !isOneHandMode && handleFloatingDrag(event, allowTap = false)

    fun getFloatingKeyboardBoundsInWindow(outBounds: Rect): Boolean {
        if (!isFloatingKeyboardEnabled || !keyboardView.isShown || keyboardView.width == 0) return false
        keyboardView.getLocationInWindow(inputViewLocation)
        val visualWidth = (keyboardView.width * keyboardView.scaleX).roundToInt()
        val visualHeight = (keyboardView.height * keyboardView.scaleY).roundToInt()
        outBounds.set(
            inputViewLocation[0],
            inputViewLocation[1],
            inputViewLocation[0] + visualWidth,
            inputViewLocation[1] + visualHeight,
        )
        val preeditRoot = preedit.ui.root
        if (preeditRoot.visibility == View.VISIBLE && preeditRoot.width > 0 && preeditRoot.height > 0) {
            preeditRoot.getLocationInWindow(preeditViewLocation)
            outBounds.union(
                preeditViewLocation[0],
                preeditViewLocation[1],
                preeditViewLocation[0] + preeditRoot.width,
                preeditViewLocation[1] + preeditRoot.height,
            )
        }
        bottomOverlay?.takeIf { it.isShown }?.let { overlay ->
            overlay.getLocationInWindow(preeditViewLocation)
            outBounds.union(preeditViewLocation[0], preeditViewLocation[1],
                preeditViewLocation[0] + overlay.width, preeditViewLocation[1] + overlay.height)
        }
        return true
    }

    fun addResizeHandlesToTouchableRegion(region: android.graphics.Region) {
        if (!isFloatingKeyboardEnabled) return
        for (handle in listOf(leftResizeHandle, rightResizeHandle)) {
            if (!handle.isShown) continue
            handle.getLocationInWindow(inputViewLocation)
            handle.unionTouchRegion(region, inputViewLocation[0], inputViewLocation[1])
        }
    }

    private val inputViewLocation = intArrayOf(0, 0)
    private val preeditViewLocation = intArrayOf(0, 0)


    /**
     * 窗口手势模式切换的通知：进入时给一次长按触感震动，松手自动结束。
     * 触发点两处——缩放手柄拖动、收起态工具栏空白区拖动。
     */
    private fun onFloatingDragMode(active: Boolean) {
        if (active) {
            keyboardView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * 悬浮卡片的底色。
     *
     * keyboard_back_color 在不少主题里是图片引用（默认 trime 主题就是 `@background`），而
     * [ColorManager.getColor] 遇到图片值会把键名当颜色字符串解析，直接抛异常——所以这里
     * 必须走 [ColorManager.getDrawable]：纯色（GradientDrawable）取其色值；图片无法提炼
     * 单色，改用跟随夜间的 Material 兜底色，悬浮卡片本来就是系统气泡风格的纯色面板。
     */
    private fun resolveFloatingBackColor(): Int = if (theme.isCustom) Color.TRANSPARENT else when (val drawable = scope.drawable("keyboard_back_color")) {
        is GradientDrawable -> if (Build.VERSION.SDK_INT >= 24) drawable.color?.defaultColor ?: fallbackFloatingBackColor() else fallbackFloatingBackColor()
        else -> fallbackFloatingBackColor()
    }

    private fun fallbackFloatingBackColor(): Int = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
        0xFF1C1B1F.toInt()
    } else {
        0xFFFFFBFE.toInt()
    }

    /** Styles the floating card and resize handles; docked mode strips both. */
    private fun configureFloatingSurface(enabled: Boolean) {
        val surface = keyboardView as FloatingKeyboardContainer
        val backColor = resolveFloatingBackColor()
        val corners = com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom).keyboardCorners
        val resolvedCorners = if (corners.isNotEmpty()) corners else List(4) { if (enabled) keyboardPrefs.floatingKeyboardCornerRadius.getValue().toFloat() else 0f }
        surface.setCornerRadii(resolvedCorners.map { dp(it) })
        if (enabled) {
            surface.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = resolvedCorners.flatMap { listOf(dp(it), dp(it)) }.toFloatArray()
                setColor(backColor)
            }
            surface.clipToOutline = false // Four independent corners are clipped by the container path.
            surface.elevation = dp(FLOATING_KEYBOARD_ELEVATION_DP)
            surface.isClickable = true
            surface.contentDescription = context.getString(R.string.floating_keyboard_drag_handle)
            surface.setOnClickListener { }
        } else {
            surface.background = null
            surface.clipToOutline = false
            surface.elevation = 0f
            surface.isClickable = false
            surface.contentDescription = null
            surface.setOnClickListener(null)
        }
        // Clickable implies focusable-in-touch-mode; after a rotation the recreated IME window
        // hands focus to the first focusable view, which would be this whole surface -- and its
        // default focus highlight paints a dim overlay over the entire keyboard.
        surface.isFocusable = false
        surface.isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            surface.defaultFocusHighlightEnabled = false
        }

        leftResizeHandle.visibility = if (enabled && !isOneHandMode) View.VISIBLE else View.GONE
        rightResizeHandle.visibility = if (enabled && !isOneHandMode) View.VISIBLE else View.GONE
        leftResizeHandle.isFocusable = false
        rightResizeHandle.isFocusable = false
        leftResizeHandle.isFocusableInTouchMode = false
        rightResizeHandle.isFocusableInTouchMode = false
        leftResizeHandle.contentDescription =
            if (enabled) context.getString(R.string.floating_keyboard_resize_handle) else null
        rightResizeHandle.contentDescription =
            if (enabled) context.getString(R.string.floating_keyboard_resize_handle) else null
    }

    fun refreshColors() {
        updateUnifiedBackground()
        configureFloatingSurface(isFloatingKeyboardEnabled)
        popup.refreshColors()
        keyboardWindow.refreshColors()
        inputBar.refreshColors()
        preedit.refreshColors()
        windowManager.refreshColors()
    }

    init {
        // MUST call before any operation
        val receivers: List<InputBroadcastReceiver> by allInstances()
        receivers.forEach { broadcaster.addReceiver(it) }

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")

        keyboardView =
            FloatingKeyboardContainer(
                context,
                isFloatingKeyboardEnabled,
            ).apply {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    // A portrait bitmap must not determine the surface's wrap-content height.
                    lParams(matchConstraints, matchConstraints) {
                        startOfParent()
                        endOfParent()
                        topOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams(wrapContent, matchConstraints) {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams(wrapContent, matchConstraints) {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
            }

        keyboardWindow.onBackgroundChanged = { updateUnifiedBackground() }
        updateUnifiedBackground()
        if (com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom).needsBlur) {
            val root = keyboardView as android.view.ViewGroup
            com.osfans.trime.ime.appearance.BackdropBlur(root, { com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom) }) { canvas ->
                com.osfans.trime.ime.appearance.BackdropBlur.drawLayer(root, keyboardBackground, canvas) { keyboardBackground.draw(it) }
                unifiedVideo?.let { video ->
                    com.osfans.trime.ime.appearance.BackdropBlur.drawLayer(root, video, canvas) { video.drawBlurFrame(it) }
                }
                keyboardWindow.drawBlurBackdrop(root, canvas)
            }
        }
        configureFloatingSurface(isFloatingKeyboardEnabled)

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    if (windowManager.isAttached(keyboardWindow)) {
                        // 指定了横屏键盘但未开启横屏模式时，横屏下仍用竖屏键盘（原始设计），
                        // 其自然高度（悬浮模式还会乘高度档位）可能超过横屏可显示高度，
                        // 窗口必须钳到屏幕内，否则底部被系统裁掉；悬浮卡片随后由
                        // applyFloatingScale 的 clampedMaxScaleY 等比缩小适配。
                        windowManager.view.updateLayoutParams {
                            height = it.coerceAtMost(screenShortSidePx())
                        }
                        // 键盘（重新）挂载意味着网格几何已按当前档位重新烘焙：宽度变化触发的
                        // refreshKeyboards、键盘切换重建都会走到这里。缩放基准随新烘焙值走，
                        // 布局完成后立即归一——否则布局已烘焙新档位而 scaleY 仍持旧比例，
                        // 同一档位被施加两次，松手后高度会被额外压扁（宽度由布局承载、
                        // scaleX 恒归一，所以只有高度露馅）。doOnLayout 保证读到的是
                        // 新布局的尺寸；无布局变化时立即执行，幂等无害。
                        keyboardView.doOnLayout { restoreFloatingPlacement() }
                    }
                }
            }

        updateKeyboardSize()

        if (isFloatingKeyboardEnabled) {
            // 悬浮卡片 8dp、按键弹层 24dp，预编辑取中间档：编码区垂直偏移可把它压到
            // 顶部拖动条之上（输入时通常不需要拖动），又不遮挡按键弹层。预编辑容器
            // 无背景，不产生额外阴影；无编码文本时整体 GONE，触摸穿透，拖动条照常可拖。
            preedit.ui.root.translationZ = dp(PREEDIT_LAYER_ELEVATION_DP)
        }

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )

        add(
            keyboardView,
            lParams(if (isFloatingKeyboardEnabled) matchConstraints else matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
                if (isFloatingKeyboardEnabled) {
                    matchConstraintPercentWidth = layoutWidthPercent(floatingKeyboardWidth) / 100f
                    // 单手固定沉底：贴住屏幕底边（系统手势条高度由 onApplyWindowInsets
                    // 修正为「增高行顶为底」）；自由悬浮保留 16dp 离底空隙。
                    bottomMargin = if (isOneHandMode) 0 else dp(16)
                }
            },
        )

        // 缩放手柄最后加（画在卡片上方）：z 介于卡片(8dp)与按键弹层(24dp)之间，
        // 既不被卡片盖住，也不遮挡按键弹层。
        val handleSize = FloatingResizeHandleView.boxSizeDp(context)
        add(leftResizeHandle, lParams(handleSize, handleSize) { centerInParent() })
        add(rightResizeHandle, lParams(handleSize, handleSize) { centerInParent() })
        leftResizeHandle.elevation = dp(PREEDIT_LAYER_ELEVATION_DP)
        rightResizeHandle.elevation = dp(PREEDIT_LAYER_ELEVATION_DP)
        leftResizeHandle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncHandlePlacement() }
        rightResizeHandle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncHandlePlacement() }

        keyboardView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            syncFloatingPreeditPosition()
            syncHandlePlacement()
            // The candidate window docks against the keyboard's top edge. onComputeInsets may
            // run before this layout pass, so report the settled geometry from here.
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                service.refreshCandidatesGeometry()
            }
        }
        if (isFloatingKeyboardEnabled) {
            keyboardView.post {
                // Re-apply the persisted size and placement: the key grid's geometry comes from
                // the theme plus the tier baked into Keyboard at construction, so nothing else
                // would carry them across this rebuild.
                restoreFloatingPlacement()
            }
        }

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )
        // [popup.root] and [keyboardView] are siblings under this ConstraintLayout.
        // A ViewGroup that holds any child with a non-zero Z re-orders its children
        // by elevation, so once the floating keyboard raises itself with
        // [FLOATING_KEYBOARD_ELEVATION_DP] it would be painted *after* the popup
        // layer and hide the key popup preview / popup keyboard. Keeping the popup
        // layer above that keeps the popup on top in both floating and docked layouts.
        popup.root.elevation = dp(POPUP_LAYER_ELEVATION_DP)
    }

    private fun updateKeyboardSize() {
        // 悬浮键盘忽略主题键盘四周 padding 与系统底部边距：keyboard_padding* 是给
        // 贴底的普通键盘用的，悬浮卡片下它们只会无谓挤占可用空间（四边留白）。
        // isFloatingKeyboardEnabled 为构造期定值且本方法仅构造期调用，分支安全。
        if (isFloatingKeyboardEnabled) {
            bottomPaddingSpace.visibility = View.GONE
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = LayoutParams.UNSET
                endToStart = LayoutParams.UNSET
                startOfParent()
                endOfParent()
            }
            preedit.ui.root.setPadding(0, 0, 0, 0)
            inputBar.view.setPadding(0, 0, 0, 0)
            return
        }
        bottomPaddingSpace.updateLayoutParams { height = keyboardBottomPaddingPx }
        val (left, right) = com.osfans.trime.ime.keyboard.KeyboardPrefs.run { context.oneHandPadding(theme) }
        leftPaddingSpace.visibility = if (left == 0) View.GONE else View.VISIBLE
        rightPaddingSpace.visibility = if (right == 0) View.GONE else View.VISIBLE
        leftPaddingSpace.updateLayoutParams { width = left }
        rightPaddingSpace.updateLayoutParams { width = right }
        windowManager.view.updateLayoutParams<LayoutParams> {
            startToStart = LayoutParams.UNSET
            endToEnd = LayoutParams.UNSET
            startToEndOf(leftPaddingSpace)
            endToStartOf(rightPaddingSpace)
        }
        preedit.ui.root.setPadding(left, 0, right, 0)
        inputBar.view.setPadding(left, 0, right, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // 悬浮键盘忽略系统底部边距：卡片由拖拽/缩放自行定位，不随导航栏避让。
        if (!isFloatingKeyboardEnabled) {
            bottomPaddingSpace.updateLayoutParams<LayoutParams> {
                bottomMargin = getNavBarBottomInset(insets)
            }
        } else {
            floatingSystemBottomInset = WindowInsetsCompat.toWindowInsetsCompat(insets).getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.mandatorySystemGestures(),
            ).bottom
            keyboardView.updateLayoutParams<LayoutParams> {
                bottomMargin = floatingSystemBottomInset + if (isOneHandMode) 0 else dp(16)
            }
        }
        return insets
    }

    fun showKeyboardMenu() { windowManager.attachWindow(com.osfans.trime.ime.switches.SwitchOptionWindow(di)) }
    fun performToolbarAction(action: String) {
        if (action.isEmpty()) { showKeyboardMenu(); return }
        if (action == com.osfans.trime.data.keymap.ToolbarOverrides.BUILTIN_FLOATING) { service.toggleFloatingKeyboard(); return }
        val listener: CommonKeyboardActionListener by instance()
        listener.listener.onAction(theme.resolveAction(action))
    }
    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }
            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW || AppPrefs.defaultInstance().keyboard.hideInputBar.getValue()) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
                if (isFloatingKeyboardEnabled) {
                    preedit.ui.root.post {
                        applyFloatingPosition(
                            keyboardView.translationX,
                            keyboardView.translationY,
                            persist = false,
                        )
                    }
                }
            }
            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }
            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    fun releaseKeyboard() {
        unifiedVideo?.release()
        unifiedVideo = null
        keyboardWindow.onBackgroundChanged = null
        keyboardWindow.release()
    }

    override fun onDetachedFromWindow() {
        preedit.dispose()
        releaseKeyboard()
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        broadcaster.clear()
        super.onDetachedFromWindow()
    }
}
