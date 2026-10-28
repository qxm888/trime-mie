/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import timber.log.Timber

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val theme: Theme,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val deletedTextBuffer = ArrayDeque<String>()

    private var voiceHeld = false
    private fun voiceLongPress(): Boolean = key.longClick?.code == KeyEvent.KEYCODE_VOICE_ASSIST ||
        key.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_VOICE_ASSIST ||
        (service.voiceInput.enabled() && key.allowDefaultVoice && key.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_SPACE &&
            !key.hasAction(KeyBehavior.LONG_CLICK) && key.popup.isEmpty())

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var cachedIcon: IconicsDrawable? = null
    private var cachedIconName: String? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        setWillNotDraw(false)
        contentDescription = key.getLabel()
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor ?: false
        isSlideDelete = key.click?.isSlideDelete ?: false
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK) || voiceLongPress()
        // Shift键始终参与双击检测，以便支持双击锁定大写（可通过设置项 hookShiftLock 关闭）
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK) || key.isShift
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()

        onPress = {
            if (key.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_VOICE_ASSIST) {
                voiceHeld = true
                service.voiceInput.start()
            }
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            InputStats.onKeyHeat(key.heatLabel())
            key.importedStyle?.let { com.osfans.trime.data.baiduskin.BdsSound.currentPath = it.sound }
            try { key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) } }
            finally { com.osfans.trime.data.baiduskin.BdsSound.currentPath = "" }
            showPopupPreview()
        }

        onRelease = release@{ behavior, isFromLongPress ->
            if (voiceHeld) {
                voiceHeld = false
                service.voiceInput.release()
                setPressedState(false)
                dismissPopupPreview()
                if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
                return@release
            }
            Timber.d("KeyView release: label=${key.getLabel()}, behavior=$behavior, fromLongPress=$isFromLongPress")
            if (isFromLongPress) {
                if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(theme.resolveAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }

                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK,
                    KeyBehavior.SWIPE_UP, KeyBehavior.SWIPE_DOWN, KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT,
                    ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
        }

        onSwipe = { direction ->
            setPressedState(true)
            showPopupPreview(direction)
        }

        onSlide = { delta, _, _ ->
            if (isSlideCursor) {
                when {
                    delta > 0 -> keyboardActionListener.onAction(theme.resolveAction("Right"))
                    delta < 0 -> keyboardActionListener.onAction(theme.resolveAction("Left"))
                }
            } else if (isSlideDelete) {
                val ic = service.currentInputConnection
                when {
                    delta < 0 -> {
                        val beforeText = ic.getTextBeforeCursor(1, 0) ?: ""
                        if (beforeText.isNotEmpty()) {
                            deletedTextBuffer.addFirst(beforeText.toString())
                            ic.deleteSurroundingText(1, 0)
                        }
                    }

                    delta > 0 -> {
                        if (deletedTextBuffer.isNotEmpty()) {
                            ic.commitText(deletedTextBuffer.removeFirst(), 1)
                        }
                    }
                }
            }
        }

        onLongClick = {
            if (voiceHeld) {
                // Dedicated voice key is already recording from ACTION_DOWN.
            } else if (voiceLongPress()) {
                voiceHeld = true
                dismissPopupPreview()
                service.voiceInput.start()
            } else if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                key.getAction(KeyBehavior.LONG_CLICK)?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            if (voiceHeld) { voiceHeld = false; service.voiceInput.cancel() }
            deletedTextBuffer.clear()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    private var paintedBackground: android.graphics.drawable.Drawable? = null
    private val backgroundClip = android.graphics.Path()
    private val backgroundClipBounds = android.graphics.RectF()
    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean =
        who === paintedBackground || who === key.customBgDrawable || super.verifyDrawable(who)

    override fun onDetachedFromWindow() {
        paintedBackground?.takeIf { it.callback === this }?.callback = null
        paintedBackground = null
        key.customBgDrawable?.takeIf { it.callback === this }?.callback = null
        if (voiceHeld) { voiceHeld = false; service.voiceInput.cancel() }
        super.onDetachedFromWindow()
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            (key.customBgDrawable as? com.osfans.trime.data.baiduskin.BdsKeyDrawable)?.press(pressed)
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            invalidate()
        }
    }

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {
        Timber.d("processKeyAction: label=${key.getLabel()}, code=${action.code}, type=$behavior")

        if (action.isModifierKey) {
            keyboard.clickModifierKey(
                modifierKeyLockState(action, behavior),
                action.modifierKeyOnMask,
            )
            keyboardView.invalidateAllKeys()
            return
        }

        keyboardActionListener.onAction(action)

        val hookArrow = if (keyboardView.hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    /**
     * 计算修饰键本次触发后的锁定状态
     *
     * - 长按：取[KeyAction.isShiftLock]的反值（沿用原有的 isShiftLock xor longClick 规则）
     * - 双击Shift：设置项 hookShiftLock 开启时，锁定大写（CapsLock）；再次单击Shift解除锁定
     * - 其它（单击、滑动等）：与[KeyAction.isShiftLock]一致
     */
    private fun modifierKeyLockState(
        action: KeyAction,
        behavior: KeyBehavior,
    ): Boolean = when (behavior) {
        KeyBehavior.LONG_CLICK -> !action.isShiftLock

        KeyBehavior.DOUBLE_CLICK ->
            // 双击Shift恒等于锁定大写。不能按当前锁定状态取反：默认主题的
            // shift_lock: ascii_long 在中文态下单击即锁定，取反会让双击变成解锁。
            (keyboardView.hookShiftLock && key.isShift) || action.isShiftLock

        else -> action.isShiftLock
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        if (!keyboardView.popupOnKeyPress) return
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackground(canvas, key)

        val drawText = com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom).keys.blur || key.importedStyle == null || key.hasWholeKeyBackground
        val label = key.getLabel().let {
            if (it == "enter_labels") keyboardView.labelEnter else it
        }

        if (label.isNotEmpty() && drawText) {
            drawLabel(canvas, label)
        }

        val symbol = key.symbolLabel
        if (symbol.isNotEmpty() && drawText) {
            drawSymbol(canvas, symbol)
        }

        val hint = key.hint
        if (hint.isNotEmpty() && drawText) {
            drawSymbol(canvas, hint, isTop = false)
        }
    }

    private val surfaceBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val surfaceBorderBounds = android.graphics.RectF()

    private fun drawBackground(canvas: Canvas, k: Key) {
        val surface = com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom).keys
        val corner = surface.radius ?: k.roundCorner

        val bg = k.getBackgroundDrawable() ?: return
        if (paintedBackground !== bg) {
            paintedBackground?.takeIf { it.callback === this }?.callback = null
            paintedBackground = bg
            bg.callback = this
        }
        if (bg.callback !== this) bg.callback = this

        if (bg is GradientDrawable) {
            bg.cornerRadius = dp(corner.coerceAtLeast(0f))
            if (surface.overridesBorder) bg.setStroke(0, 0) else k.keyBorder.takeIf { it > 0 }?.let { bg.setStroke(dp(it), k.getBorderColor()) }
        }

        bg.setBounds(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )
        val saved = canvas.save()
        backgroundClipBounds.set(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        backgroundClip.reset()
        backgroundClip.addRoundRect(backgroundClipBounds, dp(corner.coerceAtLeast(0f)), dp(corner.coerceAtLeast(0f)), android.graphics.Path.Direction.CW)
        canvas.clipPath(backgroundClip)
        val blurred = surface.blur && com.osfans.trime.ime.appearance.BackdropBlur.draw(this, canvas, backgroundClipBounds)
        val layer = if (blurred) canvas.saveLayerAlpha(backgroundClipBounds, com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom).glassOpacity * 255 / 100) else -1
        bg.draw(canvas)
        if (layer >= 0) canvas.restoreToCount(layer)
        if (surface.blur) canvas.drawColor(if (k.isPressed) 0x33FFFFFF else 0x18FFFFFF)
        if (bg is com.osfans.trime.data.baiduskin.BdsKeyDrawable && bg.animating) postInvalidateOnAnimation()


        // image skins have no pressed state of their own — overlay one
        if ((k.customBgDrawable != null && k.importedStyle == null || key.hasWholeKeyBackground) && k.isPressed) {
            canvas.drawColor(0x44FFFFFF)
        }
        canvas.restoreToCount(saved)
        if (surface.overridesBorder) {
            val stroke = dp(surface.borderWidth ?: k.keyBorder.toFloat())
            if (stroke > 0f) {
                surfaceBorderBounds.set(backgroundClipBounds); surfaceBorderBounds.inset(stroke / 2, stroke / 2)
                surfaceBorderPaint.style = android.graphics.Paint.Style.STROKE
                surfaceBorderPaint.strokeWidth = stroke
                surfaceBorderPaint.color = surface.color(k.getBorderColor())
                canvas.drawRoundRect(surfaceBorderBounds, (dp(corner) - stroke / 2).coerceAtLeast(0f), (dp(corner) - stroke / 2).coerceAtLeast(0f), surfaceBorderPaint)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, label: String) {
        val textColor = key.getTextColor()
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: if (label.length > 1 && !label.isIconFont) keyboardView.keyLongTextSize else keyboardView.keyTextSize) * keyboardView.keyTextScale

        if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.key
                clearShadowLayer()
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            val fontMetrics = textPaint.fontMetrics
            val adjustmentY = -(fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(label, centerX + sp(key.keyTextOffsetX) * keyboardView.keyTextScale, centerY + adjustmentY + sp(key.keyTextOffsetY) * keyboardView.keyTextScale, textPaint)
        }
    }

    private fun drawIcon(
        canvas: Canvas,
        iconName: String,
        size: Int,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        isTop: Boolean? = null,
    ) {
        val halfSize = size / 2

        val cmdName = iconName.toIconName()
        val icon = if (cachedIconName == cmdName) {
            cachedIcon!!
        } else {
            IconicsDrawable(context, cmdName).apply {
                sizeDp = size
            }.also {
                cachedIcon = it
                cachedIconName = cmdName
            }
        }

        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX) * keyboardView.keyTextScale

        val centerY = when (isTop) {
            true -> paddingTop + halfSize + sp(offsetY) * keyboardView.keyTextScale
            false -> height - paddingBottom - size + sp(offsetY) * keyboardView.keyTextScale
            null -> (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(offsetY) * keyboardView.keyTextScale
        }

        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt(),
        )
        icon.draw(canvas)
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        if (isTop && keyboardView.hideKeySymbol) return
        if (!isTop && keyboardView.hideKeyHint) return

        val textColor = key.getSymbolColor()
        val textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize) * keyboardView.keyTextScale
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.symbol
            }

            val lines = text.split("\n")
            val fontMetrics = symbolPaint.fontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX) * keyboardView.keyTextScale
            val startY = if (isTop) {
                paddingTop - fontMetrics.top + sp(offsetY) * keyboardView.keyTextScale - (totalHeight - lineHeight) / 2
            } else {
                height - paddingBottom - fontMetrics.bottom + sp(offsetY) * keyboardView.keyTextScale - (totalHeight - lineHeight) / 2
            }

            for (i in lines.indices) {
                val lineY = startY + lineHeight * i
                canvas.drawText(lines[i], centerX, lineY, symbolPaint)
            }
        }
    }
}
