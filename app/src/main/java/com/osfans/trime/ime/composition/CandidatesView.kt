/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.annotation.SuppressLint
import android.graphics.RectF
import android.os.Build
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import androidx.annotation.Size
import androidx.core.graphics.component1
import androidx.core.graphics.component2
import androidx.core.graphics.component3
import androidx.core.graphics.component4
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.sp
import com.osfans.trime.ime.candidates.popup.PagedCandidatesUi
import com.osfans.trime.ime.core.BaseInputView
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding
import splitties.views.setPaddingDp
import splitties.views.verticalPadding
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class CandidatesView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    scope: ThemeScope,
) : BaseInputView(service, rime, scope) {
    private val ctx = context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private val layout by AppPrefs.defaultInstance().candidates.layout
    private val position by AppPrefs.defaultInstance().candidates.position

    private var candidates = Candidates.Paged()
    private var composition = CompositionProto()

    private val anchorPosition = RectF()
    private val parentSize = floatArrayOf(0f, 0f)
    private val floatingObstruction = RectF()

    private var shouldUpdatePosition = false

    /**
     * layout update may or may not cause [CandidatesView]'s size [onSizeChanged],
     * in either case, we should reposition it
     */
    private val layoutListener =
        OnGlobalLayoutListener {
            shouldUpdatePosition = true
        }

    /**
     * [CandidatesView]'s position is calculated based on it's size,
     * so we need to recalculate the position after layout,
     * and before any actual drawing to avoid flicker
     */
    private val preDrawListener =
        OnPreDrawListener {
            if (shouldUpdatePosition) {
                updatePosition()
            }
            true
        }

    private val preeditUi =
        PreeditUi(
            ctx,
            scope,
            setupPreeditView = { setPaddingDp(3, 1, 3, 1) },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        )

    private val candidatesUi =
        PagedCandidatesUi(
            ctx,
            scope,
            onCandidateClick = { index -> rime.launchOnReady { it.selectCandidate(index, global = false) } },
            onCandidateAction = { index, text, view ->
                showCandidateActionMenu(index, text, view, global = false, onFullDisplay = if (!AppPrefs.defaultInstance().candidates.fullDisplay.getValue()) null else fun() {
                    val item = candidates.candidates.getOrNull(index)
                    if (item != null) {
                        val content = android.widget.TextView(ctx).apply {
                            this.text = listOf(item.text, item.comment).filter { it.isNotBlank() }.joinToString("\n\n")
                            textSize = 18f
                            setTextIsSelectable(true)
                            setPaddingDp(20, 12, 20, 12)
                        }
                        service.showDialog(android.app.AlertDialog.Builder(ctx)
                            .setTitle("完整显示")
                            .setView(android.widget.ScrollView(ctx).apply { addView(content) })
                            .setPositiveButton("收起", null).create())
                    }
                })
            },
            onPrevPage = { rime.launchOnReady { it.changeCandidatePage(true) } },
            onNextPage = { rime.launchOnReady { it.changeCandidatePage(false) } },
        )

    private var showingTools = false
    private val closeTools = Runnable { showingTools = false; updateUi() }
    private val ball = android.widget.TextView(ctx).apply {
        text = "●"; textSize = 28f; gravity = android.view.Gravity.CENTER; minWidth = dp(44); minHeight = dp(44)
        setTextColor(scope.colors.candidateTextColor)
        contentDescription = "候选悬浮球；点击显示工具栏，长按展开键盘菜单"
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(scope.colors.textBackColor) }
        setOnClickListener {
            showingTools = !showingTools
            removeCallbacks(closeTools)
            if (showingTools) postDelayed(closeTools, 6000)
            updateUi()
        }
        setOnLongClickListener { showingTools = false; service.openKeyboardMenu(); updateUi(); true }
    }
    private val tools = android.widget.HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        val row = android.widget.LinearLayout(ctx)
        com.osfans.trime.data.keymap.ToolbarOverrides.forKeyboard(theme).buttons.forEach { config ->
            row.addView(com.osfans.trime.ime.bar.ui.ToolButton(ctx, config, scope).apply {
                setOnClickListener { service.performFloatingToolbarAction(config.action); showingTools = false; updateUi() }
            }, android.widget.LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        addView(row)
    }
    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    private var bottomInsets = 0

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CompositionMessage -> {
                if (composition.length == 0 && it.data.length > 0) { showingTools = false }
                composition = it.data
                updateUi()
            }

            is RimeMessage.PagedCandidatesMessage -> {
                candidates = it.data
                updateUi()
            }

            else -> {}
        }
    }

    private fun evaluateVisibility(): Boolean = !composition.preedit.isNullOrEmpty() ||
        candidates.candidates.isNotEmpty()

    fun refreshVisibility() = updateUi()

    private fun updateUi() {
        preeditUi.update(composition)
        val expanded = evaluateVisibility()
        val idle = !expanded && AppPrefs.defaultInstance().keyboard.hideInputBar.getValue() && AppPrefs.defaultInstance().candidates.floatingBall.getValue()
        if (!idle) showingTools = false
        preeditUi.root.visibility = if (expanded && preeditUi.visible) VISIBLE else GONE
        candidatesUi.root.visibility = if (expanded) VISIBLE else GONE
        ball.visibility = if (idle) VISIBLE else GONE
        tools.visibility = if (idle && showingTools) VISIBLE else GONE
        alpha = if (idle && !showingTools) AppPrefs.defaultInstance().candidates.ballOpacity.getValue() / 100f else theme.window.alpha
        background = if (idle && !showingTools) null else scope.decorDrawable("text_back_color", "candidate_border_color", dp(theme.window.border), dp(theme.window.cornerRadius))
        // the candidate layout is queried natively with the page itself
        candidatesUi.update(candidates, layout)
        candidatesUi.paginationUi.root.visibility = if (expanded && (candidates.hasPrevPage || candidates.hasNextPage)) VISIBLE else GONE
        visibility = if (expanded || idle) {
            VISIBLE
        } else {
            // RecyclerView won't update its items when ancestor view is GONE
            INVISIBLE
        }
    }

    /** Restyles after a scheme switch without rebuilding this view. */
    fun refreshColors() {
        background =
            scope.decorDrawable(
                colorKey = "text_back_color",
                borderColorKey = "candidate_border_color",
                borderPx = dp(theme.window.border),
                cornerRadius = dp(theme.window.cornerRadius),
            )
        preeditUi.refreshColors()
        preeditUi.update(composition)
        candidatesUi.refreshColors()
    }

    private fun updatePosition() {
        if (visibility != VISIBLE) return
        // Rotation can settle the keyboard after the latest cursor-anchor callback.
        service.refreshCandidatesGeometry()
        val (parentWidth, parentHeight) = parentSize
        if (parentWidth <= 0 || parentHeight <= 0) {
            translationX = 0f
            translationY = 0f
            return
        }
        val (horizontal, top, _, bottom) = anchorPosition
        val w = width
        val h = height
        val selfWidth = w.toFloat()
        val selfHeight = h.toFloat()
        val spacingDp = dp(SPACING)
        val anchorTop = top - spacingDp
        val anchorBottom = bottom + spacingDp
        val bottomLimit = parentHeight - bottomInsets
        val bottomSpace = bottomLimit - anchorBottom

        val tX: Float
        val tY: Float

        val minX = spacingDp
        val minY = spacingDp
        val maxX = parentWidth - selfWidth - spacingDp
        val flipAbove = anchorBottom + selfHeight > bottomLimit && // bottom space is not enough
            anchorTop > bottomSpace // top space is larger than bottom
        val maxY = if (flipAbove) anchorTop - selfHeight else bottomLimit - selfHeight - spacingDp
        when (position) {
            PopupPosition.TOP_RIGHT -> {
                tX = maxX
                tY = minY
            }

            PopupPosition.TOP_LEFT -> {
                tX = minX
                tY = minY
            }

            PopupPosition.BOTTOM_RIGHT -> {
                tX = maxX
                tY = maxY
            }

            PopupPosition.BOTTOM_LEFT -> {
                tX = minX
                tY = maxY
            }

            PopupPosition.FOLLOW -> {
                tX =
                    if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                        val rtlOffset = parentWidth - horizontal
                        if (rtlOffset + selfWidth > parentWidth - spacingDp) {
                            selfWidth - parentWidth + spacingDp
                        } else {
                            -rtlOffset
                        }
                    } else {
                        if (horizontal + selfWidth > parentWidth - spacingDp) {
                            parentWidth - selfWidth - spacingDp
                        } else {
                            horizontal
                        }
                    }
                tY = if (flipAbove) anchorTop - selfHeight else anchorBottom
            }
        }
        val viewport = availableViewport()
        val resolvedX = viewport.left + popupCandidatePosition(tX - viewport.left, selfWidth, viewport.width(), spacingDp)
        val resolvedY = viewport.top + popupCandidatePosition(
            candidatesWindowTranslationY(tY, floatingObstruction.top.takeUnless { floatingObstruction.isEmpty }, selfHeight, spacingDp, minY) - viewport.top,
            selfHeight, viewport.height(), spacingDp,
        )
        translationX = resolvedX - left
        translationY = resolvedY - this.top
        // update touchEventReceiverWindow's position after CandidatesView's
        touchEventReceiverWindow.show()
        shouldUpdatePosition = false
    }

    /** Adopted from CatTrime: update geometry without replacing the editor cursor anchor. */
    fun updateFloatingGeometry(parent: FloatArray, obstruction: RectF?) {
        val sameBounds = if (obstruction == null) floatingObstruction.isEmpty else floatingObstruction == obstruction
        if (parentSize[0] == parent[0] && parentSize[1] == parent[1] && sameBounds) return
        parentSize[0] = parent[0]
        parentSize[1] = parent[1]
        if (obstruction == null) floatingObstruction.setEmpty() else floatingObstruction.set(obstruction)
        shouldUpdatePosition = true
        requestLayout()
        invalidate()
    }

    fun updateCursorAnchor(
        anchorPosition: RectF,
        @Size(2) parent: FloatArray,
    ) {
        this.anchorPosition.set(anchorPosition)
        val (parentWidth, parentHeight) = parent
        val resized = parentSize[0] != parentWidth || parentSize[1] != parentHeight
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        if (resized) requestLayout()
        updatePosition()
    }

    /**
     * Anchor candidates view to bottom-left corner, takes navbar bottom insets into consideration.
     * Should only be used when [CursorAnchorInfo][android.view.inputmethod.CursorAnchorInfo] is invalid
     */
    fun updateCursorAnchor(@Size(2) parent: FloatArray) {
        val (parentWidth, parentHeight) = parent
        val bottom = parentHeight - bottomInsets
        anchorPosition.set(0f, bottom, 0f, bottom)
        val resized = parentSize[0] != parentWidth || parentSize[1] != parentHeight
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        if (resized) requestLayout()
        updatePosition()
    }

    init {
        visibility = INVISIBLE

        minWidth = dp(theme.window.minWidth)
        verticalPadding = dp(theme.window.insets.vertical)
        horizontalPadding = dp(theme.window.insets.horizontal)
        alpha = theme.window.alpha
        background =
            scope.decorDrawable(
                colorKey = "text_back_color",
                borderColorKey = "candidate_border_color",
                borderPx = dp(theme.window.border),
                cornerRadius = dp(theme.window.cornerRadius),
            )
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(theme.window.shadow)
        add(
            preeditUi.root,
            lParams(wrapContent, wrapContent) {
                topOfParent()
                startOfParent()
            },
        )
        add(
            candidatesUi.root,
            lParams(matchConstraints, wrapContent) {
                constrainedHeight = true
                below(preeditUi.root)
                centerHorizontally()
                bottomOfParent()
            },
        )

        addView(ball); addView(tools); addView(candidatesUi.paginationUi.root)
        updateUi()
        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(wrapContent, wrapContent)
    }

    private fun availableHeight(): Int {
        val parentHeight = parentSize[1].takeIf { it > 0 } ?: (parent as? android.view.View)?.height?.toFloat()
            ?: resources.displayMetrics.heightPixels.toFloat()
        return (parentHeight - bottomInsets).toInt().coerceAtLeast(0)
    }

    /** Visible display frame is in screen coordinates; cursor/keyboard geometry is parent-local. */
    private val visibleFrame = android.graphics.Rect()
    private val parentScreenOrigin = IntArray(2)
    private fun availableViewport(): RectF {
        val parentWidth = parentSize[0].takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val bottom = availableHeight().toFloat()
        val viewport = RectF(0f, 0f, parentWidth, bottom)
        val owner = parent as? android.view.View
        if (isAttachedToWindow && owner != null) {
            getWindowVisibleDisplayFrame(visibleFrame)
            owner.getLocationOnScreen(parentScreenOrigin)
            if (!visibleFrame.isEmpty) {
                val safe = RectF(visibleFrame).apply { offset(-parentScreenOrigin[0].toFloat(), -parentScreenOrigin[1].toFloat()) }
                if (!viewport.intersect(safe)) viewport.set(0f, 0f, parentWidth, bottom)
            }
        }
        val region = popupCandidateArea(
            CandidateArea(viewport.left, viewport.top, viewport.right, viewport.bottom),
            if (floatingObstruction.isEmpty) null else CandidateArea(floatingObstruction.left, floatingObstruction.top, floatingObstruction.right, floatingObstruction.bottom),
            minOf(dp(96f), viewport.width()),
            dp(32f) + ctx.sp(theme.window.foreground.textFontSize),
        )
        return RectF(region.left, region.top, region.right, region.bottom)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spacing = dp(2 * SPACING).roundToInt()
        val viewport = availableViewport()
        val offeredWidth = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) viewport.width().toInt()
            else minOf(viewport.width().toInt(), MeasureSpec.getSize(widthMeasureSpec))
        val maxWidth = (offeredWidth - spacing).coerceAtLeast(1)
        val cap = popupCandidateHeightLimit(resources.displayMetrics.heightPixels, viewport.height().toInt(), spacing)
        val maxHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
            else minOf(cap, MeasureSpec.getSize(heightMeasureSpec)).coerceAtLeast(1)
        val innerWidth = (maxWidth - paddingLeft - paddingRight).coerceAtLeast(1)
        val innerHeight = (maxHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        val list = candidatesUi.root
        candidatesUi.constrainWidth(innerWidth)
        val extra = if (ball.visibility == VISIBLE) { if (tools.visibility == VISIBLE) tools else ball } else null
        extra?.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(44), MeasureSpec.EXACTLY))
        val extraHeight = (extra?.measuredHeight ?: 0).coerceAtMost(innerHeight)
        val pager = candidatesUi.paginationUi.root
        val pagerHeight = if (pager.visibility == VISIBLE) dp(44).coerceAtMost(innerHeight - extraHeight) else 0
        val contentHeight = (innerHeight - extraHeight - pagerHeight).coerceAtLeast(0)
        val hasItems = list.visibility == VISIBLE && (list.adapter?.itemCount ?: 0) > 0
        val preedit = preeditUi.root
        val preeditHeight = if (preedit.visibility != GONE) {
            val preeditLimit = if (hasItems) contentHeight / 3 else contentHeight
            preedit.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(preeditLimit, MeasureSpec.AT_MOST))
            preedit.measuredHeight.coerceAtMost(preeditLimit)
        } else 0
        val listLimit = (contentHeight - preeditHeight).coerceAtLeast(0)
        list.measure(MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(listLimit, MeasureSpec.AT_MOST))
        val desiredWidth = maxOf(if (preedit.visibility == GONE) 0 else preedit.measuredWidth,
            if (hasItems) list.measuredWidth else 0, extra?.measuredWidth ?: 0, if (pagerHeight > 0) dp(144) else 0, if (ball.visibility == VISIBLE && tools.visibility != VISIBLE) dp(44) else dp(theme.window.minWidth) - paddingLeft - paddingRight).coerceIn(1, innerWidth)
        val listHeight = if (hasItems) list.measuredHeight.coerceAtMost(listLimit) else 0
        // The RecyclerView's own viewport must match the visible space, not its unbounded content.
        list.measure(MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(listHeight, MeasureSpec.EXACTLY))
        pager.measure(MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(pagerHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension((desiredWidth + paddingLeft + paddingRight).coerceAtMost(maxWidth),
            (extraHeight + preeditHeight + listHeight + pagerHeight + paddingTop + paddingBottom).coerceIn(1, maxHeight))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val extra = if (ball.visibility == VISIBLE) { if (tools.visibility == VISIBLE) tools else ball } else null
        val extraHeight = extra?.measuredHeight ?: 0
        extra?.layout(paddingLeft, paddingTop, paddingLeft + extra.measuredWidth, paddingTop + extraHeight)
        if (tools.visibility == VISIBLE) ball.layout(0, 0, 0, 0)
        val contentTop = paddingTop + extraHeight
        val preedit = preeditUi.root
        val hasPreedit = preedit.visibility != GONE
        val preeditHeight = if (hasPreedit) preedit.measuredHeight.coerceAtMost((height - paddingTop - paddingBottom).coerceAtLeast(0)) else 0
        if (hasPreedit) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - preedit.measuredWidth else paddingLeft
            preedit.layout(x, contentTop, x + preedit.measuredWidth, contentTop + preeditHeight)
        }
        val pager = candidatesUi.paginationUi.root
        val pagerHeight = if (pager.visibility == VISIBLE) pager.measuredHeight else 0
        val contentBottom = (height - paddingBottom - pagerHeight).coerceAtLeast(contentTop + preeditHeight)
        candidatesUi.root.layout(paddingLeft, contentTop + preeditHeight,
            (width - paddingRight).coerceAtLeast(paddingLeft), contentBottom)
        pager.layout(paddingLeft, contentBottom, width - paddingRight, contentBottom + pagerHeight)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val inset = getNavBarBottomInset(insets)
            if (bottomInsets != inset) { bottomInsets = inset; requestLayout(); shouldUpdatePosition = true }
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun setVisibility(visibility: Int) {
        if (visibility != VISIBLE) {
            touchEventReceiverWindow.dismiss()
        }
        super.setVisibility(visibility)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        removeCallbacks(closeTools)
        touchEventReceiverWindow.dismiss()
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * Spacing in density-independent pixels (dp) kept between the candidate
         * window and the parent edges whenever the window docks to them.
         */
        private const val SPACING = 5f
    }
}

/** CatTrime's floating keyboard obstruction rule; see CATTRIME_PORT.md. */
internal fun candidatesWindowTranslationY(
    positionedY: Float, obstructionTop: Float?, selfHeight: Float, spacing: Float, minY: Float,
): Float = if (obstructionTop == null) positionedY else (obstructionTop - spacing - selfHeight).coerceAtLeast(minY)
