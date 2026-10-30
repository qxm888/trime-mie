/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate

// TODO: move layout calculation responsibilities from Keyboard to KeyboardView using ConstraintLayout
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    val popup: PopupDelegate,
    val service: TrimeInputMethodService,
    private val keyboardActionListener: KeyboardActionListener,
    private val enterKeyDisplay: EnterKeyDisplayDelegate,
) : FrameLayout(context) {

    private val keys get() = keyboard.keys

    private lateinit var keyShadowLayer: KeyShadowLayer
    private val keyViews = ArrayList<KeyView>()
    internal val hasKeyShadows get() = com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom).effects.shadow.enabled

    internal val labelEnter: String
        get() = enterKeyDisplay.keyLabel
    internal val enterActionDynamic: Boolean
        get() = enterKeyDisplay.showingActionLabel
    internal val keyTextScale = keyboard.keyTextScale
    internal val keyTextSize = theme.style.keyTextSize
    internal val keyLongTextSize = theme.style.keyLongTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val symbolTextSize = theme.style.symbolTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val popupOnKeyPress by AppPrefs.defaultInstance().keyboard.popupOnKeyPress
    internal val hookShiftArrow: Boolean by AppPrefs.defaultInstance().keyboard.hookShiftArrow
    internal val hookShiftLock: Boolean by AppPrefs.defaultInstance().keyboard.hookShiftLock
    internal val hideKeySymbol: Boolean by AppPrefs.defaultInstance().keyboard.hideKeySymbol
    internal val hideKeyHint: Boolean by AppPrefs.defaultInstance().keyboard.hideKeyHint

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        buildKeyViews()
        // 阴影层位于视频背景之后、按键之前；放在最底部，视频背景会插到它前面。
        keyShadowLayer = KeyShadowLayer(context, this)
        addView(keyShadowLayer, 0, LayoutParams(-1, -1))
    }

    private fun buildKeyViews() {
        keyViews.clear()
        for (i in childCount - 1 downTo 0) {
            if (getChildAt(i) is KeyView) removeViewAt(i)
        }

        keys.forEachIndexed { index, key ->
            val keyView = createKeyView(index, key)
            keyViews.add(keyView)
            addView(keyView)
        }
    }

    private fun createKeyView(index: Int, key: Key): KeyView = KeyView(context, theme, key, keyboard, this, keyboardActionListener).apply {
        id = index

        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        layoutParams = LayoutParams(totalWidth, key.height)

        translationX = (key.x - key.extraWidthLeft).toFloat()
        translationY = key.y.toFloat()

        setPadding(
            (if (key.importedStyle != null) 0 else keyboard.horizontalGap / 2) + key.extraWidthLeft,
            keyboard.verticalGap / 2,
            (if (key.importedStyle != null) 0 else keyboard.horizontalGap / 2) + key.extraWidthRight,
            keyboard.verticalGap - keyboard.verticalGap / 2,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = keyboard.minWidth + paddingLeft + paddingRight
        val fullHeight = keyboard.height + paddingTop + paddingBottom

        val measuredWidth = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            fullWidth,
        )

        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, fullHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    fun invalidateAllKeys() {
        keyViews.forEach { it.invalidate() }
        invalidateKeyShadows()
    }

    fun invalidateKeyShadows() {
        if (::keyShadowLayer.isInitialized) {
            keyShadowLayer.invalidate()
            // Shadows extend outside individual keys and may cross this view's edge.
            // Include their parent surface when clearing the previous frame.
            (parent as? View)?.invalidate()
        }
    }

    fun invalidateKeyByIndex(index: Int) {
        keyViews.getOrNull(index)?.invalidate()
    }

    val isCapsOn: Boolean
        get() = keyboard.mShiftKey?.isOn == true

    fun onDetach() {
        popup.dismissAll()
    }
}

/**
 * 所有按键的外阴影集中画在这一层：它排在视频背景之后、按键之前，
 * 因此阴影可以越过单个按键的 View 边界，又不会盖住按键本身。
 */
private class KeyShadowLayer(
    context: Context,
    private val host: KeyboardView,
) : View(context) {
    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (!host.hasKeyShadows) return
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child is KeyView && child.visibility == View.VISIBLE) {
                canvas.save()
                canvas.translate(child.left + child.translationX, child.top + child.translationY)
                canvas.scale(child.scaleX, child.scaleY, child.pivotX, child.pivotY)
                child.drawOuterShadow(canvas)
                canvas.restore()
            }
        }
    }
}
