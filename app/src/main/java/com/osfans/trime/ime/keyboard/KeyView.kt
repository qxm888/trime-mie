/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.data.theme.BitmapAssets
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.appearance.EffectBitmap
import com.osfans.trime.ime.appearance.KeyEffectPainter
import com.osfans.trime.ime.appearance.KeyEffects
import com.osfans.trime.ime.appearance.KeyGradient
import com.osfans.trime.ime.appearance.KeyInnerShadow
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import kotlin.math.hypot

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val theme: Theme,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context), BitmapAssets.Observer {

    override fun onImageReady() {
        // 异步解码完成（含前景图标）；只重绘当前键。
        invalidate()
    }

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val deletedTextBuffer = ArrayDeque<String>()

    private var voiceHeld = false

    // ---- 独立前景图标 ----
    private val iconPath: String
    private var iconImage: BitmapAssets.Image? = null
    private var iconGeneration = -1L

    /** 绘制期惰性加载：异步解码完成后经 onImageReady 重绘；主题/部署刷新随 generation 失效。 */
    private fun loadIcon(): BitmapAssets.Image? {
        if (iconPath.isEmpty()) return null
        if (iconGeneration != BitmapAssets.generation) {
            iconImage = null
            iconGeneration = BitmapAssets.generation
        }
        return iconImage ?: BitmapAssets.request(iconPath, this, pixels = 1_500_000)?.also { iconImage = it }
    }

    /** 图标可用根目录：.mie/键盘背景（直接外部模式）或 filesDir/custom_kb_bg（应用存储）。 */
    private fun resolveIconPath(raw: String): String? {
        if (raw.isBlank()) return null
        val root = com.osfans.trime.core.data.DirectExternalStorage.resources(context, "custom_kb_bg")
        val file = if (raw.startsWith("/")) java.io.File(raw) else java.io.File(root, raw)
        val canonical = runCatching { file.canonicalFile.path }.getOrNull() ?: return null
        val rootPath = runCatching { root.canonicalFile.path }.getOrNull() ?: return null
        val filesRoot = java.io.File(context.filesDir, "custom_kb_bg").canonicalFile.path
        // 禁止越界读取：仅允许咩版自有素材目录内的文件。
        if (!canonical.startsWith("$rootPath${java.io.File.separator}") && !canonical.startsWith("$filesRoot${java.io.File.separator}")) return null
        return canonical.takeIf { java.io.File(it).isFile }
    }
    private fun voiceLongPress(): Boolean {
        val code = key.getCode(KeyBehavior.CLICK)
        return key.longClick?.code == KeyEvent.KEYCODE_VOICE_ASSIST || code == KeyEvent.KEYCODE_VOICE_ASSIST ||
            (code == KeyEvent.KEYCODE_SPACE && key.allowDefaultVoice && !key.hasAction(KeyBehavior.LONG_CLICK) &&
                key.popup.isEmpty() && com.osfans.trime.ime.voice.VoiceSettings.enabled)
    }

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelMetrics = Paint.FontMetrics()
    private val symbolMetrics = Paint.FontMetrics()
    private val backgroundStyle = com.osfans.trime.ime.appearance.KeyBackgroundStyle()
    private lateinit var frameAppearance: com.osfans.trime.ime.appearance.SurfaceAppearanceConfig

    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val effectBounds = RectF()
    private val effectShaders = com.osfans.trime.ime.appearance.KeyEffectShaders(resources.displayMetrics.density)
    private var shadowCache: EffectBitmap? = null
    private var shadowCacheKey: ShadowCacheKey? = null
    private var innerShadowCache: EffectBitmap? = null
    private var innerShadowCacheKey: InnerShadowCacheKey? = null
    private var rippleProgress = 0f
    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null
    private var pressProgress = 0f

    private data class ShadowCacheKey(
        val width: Float,
        val height: Float,
        val radius: Float,
        val blur: Float,
        val spread: Float,
        val color: Int,
    )

    private data class InnerShadowCacheKey(
        val width: Float,
        val height: Float,
        val radius: Float,
        val blur: Float,
        val offsetX: Float,
        val offsetY: Float,
        val color: Int,
    )

    private val density get() = resources.displayMetrics.density
    private fun dpf(value: Float) = value * density

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
        onTouchDown = { x, y ->
            rippleX = x
            rippleY = y
        }
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
            val code = key.getCode(KeyBehavior.CLICK)
            if (code == KeyEvent.KEYCODE_VOICE_ASSIST) {
                voiceHeld = true
                service.voiceInput.start()
            }
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            InputStats.onKeyHeat(key.heatLabel())
            key.importedStyle?.let { com.osfans.trime.data.baiduskin.BdsSound.currentPath = it.sound }
            try { keyboardActionListener.onPress(code) }
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
            if (voiceHeld) { voiceHeld = false; service.cancelVoiceIfActive() }
            deletedTextBuffer.clear()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    private var paintedBackground: android.graphics.drawable.Drawable? = null
    private val backgroundClip = android.graphics.Path()
    private val backgroundClipBounds = android.graphics.RectF()
    private var backgroundClipRadius = Float.NaN
    override fun verifyDrawable(who: android.graphics.drawable.Drawable): Boolean =
        who === paintedBackground || who === key.customBgDrawable || super.verifyDrawable(who)

    override fun onDetachedFromWindow() {
        paintedBackground?.takeIf { it.callback === this }?.callback = null
        paintedBackground = null
        key.customBgDrawable?.takeIf { it.callback === this }?.callback = null
        rippleAnimator?.cancel()
        rippleAnimator = null
        pressAnimator?.cancel()
        pressAnimator = null
        clearEffectCaches()
        if (voiceHeld) { voiceHeld = false; service.cancelVoiceIfActive() }
        super.onDetachedFromWindow()
    }

    private fun clearEffectCaches() {
        // HWUI display lists may still reference the previous bitmap. Release ownership;
        // explicit recycle can invalidate a frame that the render thread has not consumed.
        shadowCache = null
        shadowCacheKey = null
        innerShadowCache = null
        innerShadowCacheKey = null
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            (key.customBgDrawable as? com.osfans.trime.data.baiduskin.BdsKeyDrawable)?.press(pressed)
            if (pressed) {
                key.onPressed()
                startRipple()
            } else {
                key.onReleased()
            }
            animatePress(pressed)
            invalidate()
        }
    }

    private fun animatePress(pressed: Boolean) {
        val effects = SurfaceAppearance.current(theme.isCustom).effects
        val target = if (pressed) 1f else 0f
        pressAnimator?.cancel()
        if (effects.pressScale <= 0f && effects.pressSink <= 0f && effects.pressDarken <= 0) {
            pressProgress = target
            applyPressTransform(target, effects)
            if (effects.shadow.enabled) keyboardView.invalidateKeyShadows()
            return
        }
        val animator = ValueAnimator.ofFloat(pressProgress, target).apply {
            duration = effects.animationMs.toLong().coerceIn(0, 300)
            interpolator = if (pressed) DecelerateInterpolator() else OvershootInterpolator(1.2f)
            addUpdateListener {
                pressProgress = it.animatedValue as Float
                applyPressTransform(pressProgress, effects)
                if (effects.shadow.enabled) keyboardView.invalidateKeyShadows()
            }
        }
        pressAnimator = animator
        animator.start()
    }

    private fun applyPressTransform(progress: Float, effects: KeyEffects) {
        val scale = 1f - effects.pressScale / 100f * progress
        scaleX = scale
        scaleY = scale
        translationY = key.y + dpf(effects.pressSink) * progress
        invalidate()
    }

    private fun startRipple() {
        if (!SurfaceAppearance.current(theme.isCustom).effects.ripple) return
        rippleProgress = 0f
        rippleAnimator?.cancel()
        rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            addUpdateListener {
                rippleProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {

        if (action.code == KeyEvent.KEYCODE_CAPS_LOCK) {
            keyboard.toggleCapsLock()
            keyboardView.invalidateAllKeys()
            return
        }

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
        if (changed) keyboardView.invalidateKeyShadows()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            clearEffectCaches()
            keyboardView.invalidateKeyShadows()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        frameAppearance = SurfaceAppearance.current(theme.isCustom)

        drawBackground(canvas, key)

        val drawText = frameAppearance.keys.blur || key.importedStyle == null || key.hasWholeKeyBackground
        val label = key.getLabel().let {
            if (it == "enter_labels") keyboardView.labelEnter else it
        }

        // 独立前景图标：加载成功时替代主标签；动态回车标签（搜索/发送/完成）仅在回车键上优先显示。
        val isEnterKey = key.getLabel() == "enter_labels"
        val icon = if (isEnterKey && keyboardView.enterActionDynamic) null else loadIcon()
        if (icon != null) {
            drawForegroundIcon(canvas, icon)
        } else if (label.isNotEmpty() && drawText) {
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

    init {
        // 前景图标：仅在路径合法且文件存在时启用；失败回退文字标签。
        iconPath = resolveIconPath(key.icon()).orEmpty()
    }

    private val surfaceBorderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val surfaceBorderBounds = android.graphics.RectF()

    private fun drawBackground(canvas: Canvas, k: Key) {
        val config = frameAppearance
        val surface = config.keys
        val effects = config.effects
        val corner = (surface.radius ?: k.roundCorner).coerceAtLeast(0f)
        val cornerPx = dpf(corner)

        val bg = k.getBackgroundDrawable()
        if (paintedBackground !== bg) {
            paintedBackground?.takeIf { it.callback === this }?.callback = null
            paintedBackground = bg
            bg?.callback = this
        }
        if (bg != null && bg.callback !== this) bg.callback = this

        if (bg is GradientDrawable) {
            backgroundStyle.apply(bg, cornerPx,
                if (surface.overridesBorder) 0 else dp(k.keyBorder.coerceAtLeast(0)),
                if (surface.overridesBorder || k.keyBorder <= 0) 0 else k.getBorderColor())
        }

        bg?.setBounds(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )
        val body = effectBounds
        body.set(paddingLeft.toFloat(), paddingTop.toFloat(), (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        effectShaders.update(body, effects)
        val saved = canvas.save()
        if (backgroundClipBounds != body || backgroundClipRadius != cornerPx) {
            backgroundClipBounds.set(body)
            backgroundClipRadius = cornerPx
            backgroundClip.reset()
            backgroundClip.addRoundRect(body, cornerPx, cornerPx, Path.Direction.CW)
        }
        // Plain rounded color drawables already clip themselves. Image/overlay effects still need the mask.
        val overlay = (k.customBgDrawable != null && k.importedStyle == null || key.hasWholeKeyBackground) && k.isPressed
        if (bg !is GradientDrawable || surface.blur || effects.customized || overlay) canvas.clipPath(backgroundClip)
        val blurred = surface.blur && com.osfans.trime.ime.appearance.BackdropBlur.draw(this, canvas, body)
        val layer = if (blurred) canvas.saveLayerAlpha(body, config.glassOpacity * 255 / 100) else -1
        bg?.draw(canvas)
        if (layer >= 0) canvas.restoreToCount(layer)
        if (surface.blur) canvas.drawColor(if (k.isPressed) 0x33FFFFFF else 0x18FFFFFF)
        if (bg is com.osfans.trime.data.baiduskin.BdsKeyDrawable && bg.animating) postInvalidateOnAnimation()

        if (effects.gradient.enabled) drawGradient(canvas, body, cornerPx, effects.gradient)
        if (effects.edge3d) drawEdge3d(canvas, body, effects)
        if (effects.innerShadow.enabled) drawInnerShadow(canvas, body, cornerPx, effects.innerShadow)
        if (effects.gloss) drawGloss(canvas, body, cornerPx, effects.glossStrength)
        if (effects.noise) drawNoise(canvas, body, cornerPx, effects.noiseStrength)
        if (effects.pressDarken > 0) {
            val darken = (effects.pressDarken * pressProgress).coerceIn(0f, 60f)
            if (darken > 0.5f) canvas.drawColor(Color.argb((darken * 255 / 100).toInt(), 0, 0, 0))
        }

        // image skins have no pressed state of their own — overlay one
        if (overlay) {
            canvas.drawColor(0x44FFFFFF)
        }
        if (effects.ripple) drawRipple(canvas, body, effects)
        canvas.restoreToCount(saved)
        if (surface.overridesBorder) {
            val stroke = dp(surface.borderWidth ?: k.keyBorder.toFloat())
            if (stroke > 0f) {
                surfaceBorderBounds.set(backgroundClipBounds); surfaceBorderBounds.inset(stroke / 2, stroke / 2)
                surfaceBorderPaint.style = android.graphics.Paint.Style.STROKE
                surfaceBorderPaint.strokeWidth = stroke
                surfaceBorderPaint.color = surface.color(k.getBorderColor())
                canvas.drawRoundRect(surfaceBorderBounds, (cornerPx - stroke / 2).coerceAtLeast(0f), (cornerPx - stroke / 2).coerceAtLeast(0f), surfaceBorderPaint)
            }
        }
        if (effects.borderGradient.enabled) {
            val stroke = dpf(effects.borderGradient.width).coerceAtLeast(0.5f)
            val border = effectShaders.borderBounds
            val shader = effectShaders.border
            if (shader != null) {
                effectPaint.reset()
                effectPaint.isAntiAlias = true
                effectPaint.style = Paint.Style.STROKE
                effectPaint.strokeWidth = stroke
                effectPaint.shader = shader
                val r = (cornerPx - stroke / 2).coerceAtLeast(0f)
                canvas.drawRoundRect(border, r, r, effectPaint)
            }
        }
    }

    /** 由 KeyboardView 的阴影层调用；阴影可以超出单个按键的 View 边界。 */
    fun drawOuterShadow(canvas: Canvas) {
        val config = SurfaceAppearance.current(theme.isCustom)
        val effects = config.effects
        val shadow = effects.shadow
        if (!shadow.enabled) return
        if (key.getBackgroundDrawable() == null && !effects.gradient.enabled && !effects.edge3d) return
        val bodyWidth = (width - paddingLeft - paddingRight).toFloat()
        val bodyHeight = (height - paddingTop - paddingBottom).toFloat()
        if (bodyWidth <= 0f || bodyHeight <= 0f) return
        val color = KeyEffectPainter.color(shadow.color, 0x66000000)
        if (Color.alpha(color) == 0) return
        val radiusPx = dpf((config.keys.radius ?: key.roundCorner).coerceAtLeast(0f))
        val blurPx = dpf(shadow.blur)
        val spreadPx = dpf(shadow.spread)
        val previous = shadowCacheKey
        if (previous == null || previous.width != bodyWidth || previous.height != bodyHeight || previous.radius != radiusPx || previous.blur != blurPx || previous.spread != spreadPx || previous.color != color) {
            shadowCache = KeyEffectPainter.shadowBitmap(bodyWidth, bodyHeight, radiusPx, blurPx, spreadPx, color)
            shadowCacheKey = ShadowCacheKey(bodyWidth, bodyHeight, radiusPx, blurPx, spreadPx, color)
        }
        val bitmap = shadowCache ?: return
        effectPaint.reset()
        effectPaint.isAntiAlias = true
        effectPaint.isFilterBitmap = true
        effectPaint.alpha = (255 - 85 * pressProgress).toInt().coerceIn(0, 255)
        val saved = canvas.save()
        canvas.translate(dpf(shadow.offsetX), dpf(shadow.offsetY))
        bitmap.draw(canvas, paddingLeft.toFloat(), paddingTop.toFloat(), effectPaint)
        canvas.restoreToCount(saved)
    }

    private fun drawGradient(canvas: Canvas, body: RectF, radius: Float, gradient: KeyGradient) {
        val shader = effectShaders.gradient ?: return
        effectPaint.reset()
        effectPaint.isAntiAlias = true
        effectPaint.shader = shader
        effectPaint.alpha = gradient.opacity * 255 / 100
        canvas.drawRoundRect(body, radius, radius, effectPaint)
    }

    private fun drawEdge3d(canvas: Canvas, body: RectF, effects: KeyEffects) {
        val depth = dpf(effects.edgeDepth) * (1f - pressProgress * 0.55f)
        if (depth <= 0f) return
        val color = KeyEffectPainter.color(effects.edgeColor, 0x33000000)
        if (Color.alpha(color) == 0) return
        effectPaint.reset()
        effectPaint.isAntiAlias = true
        effectPaint.shader = effectShaders.edge(depth)
        canvas.drawRect(body.left, body.bottom - depth, body.right, body.bottom, effectPaint)
    }

    private fun drawInnerShadow(canvas: Canvas, body: RectF, radius: Float, shadow: KeyInnerShadow) {
        val widthPx = body.width()
        val heightPx = body.height()
        if (widthPx <= 0f || heightPx <= 0f) return
        val color = KeyEffectPainter.color(shadow.color, 0x55000000)
        if (Color.alpha(color) == 0) return
        val blurPx = dpf(shadow.blur)
        val offsetX = dpf(shadow.offsetX)
        val offsetY = dpf(shadow.offsetY)
        val previous = innerShadowCacheKey
        if (previous == null || previous.width != widthPx || previous.height != heightPx || previous.radius != radius || previous.blur != blurPx || previous.offsetX != offsetX || previous.offsetY != offsetY || previous.color != color) {
            innerShadowCache = KeyEffectPainter.innerShadowBitmap(widthPx, heightPx, radius, blurPx, offsetX, offsetY, color)
            innerShadowCacheKey = InnerShadowCacheKey(widthPx, heightPx, radius, blurPx, offsetX, offsetY, color)
        }
        val bitmap = innerShadowCache ?: return
        effectPaint.reset()
        effectPaint.isAntiAlias = true
        effectPaint.isFilterBitmap = true
        bitmap.draw(canvas, body.left, body.top, effectPaint)
    }

    private fun drawGloss(canvas: Canvas, body: RectF, radius: Float, strength: Int) {
        if (strength <= 0) return
        effectPaint.reset()
        effectPaint.isAntiAlias = true
        effectPaint.shader = effectShaders.gloss
        canvas.drawRoundRect(body, radius, radius, effectPaint)
    }

    private fun drawNoise(canvas: Canvas, body: RectF, radius: Float, strength: Int) {
        if (strength <= 0) return
        effectPaint.reset()
        effectPaint.isAntiAlias = true
        effectPaint.shader = KeyEffectPainter.noiseShader
        effectPaint.alpha = (strength * 255 / 100).coerceIn(0, 255)
        canvas.drawRoundRect(body, radius, radius, effectPaint)
    }

    private fun drawRipple(canvas: Canvas, body: RectF, effects: KeyEffects) {
        if (rippleProgress <= 0f || rippleProgress >= 1f) return
        val cx = rippleX.takeIf { it in body.left..body.right } ?: body.centerX()
        val cy = rippleY.takeIf { it in body.top..body.bottom } ?: body.centerY()
        val maxRadius = hypot(body.width(), body.height()) * 0.65f
        val radius = maxRadius * rippleProgress
        if (radius <= 0f) return
        val fade = ((1f - rippleProgress) * 255).toInt()
        effectPaint.reset()
        effectPaint.isAntiAlias = true
        effectPaint.shader = effectShaders.ripple(cx, cy, radius)
        effectPaint.alpha = fade
        canvas.drawCircle(cx, cy, radius, effectPaint)
    }

    private fun drawLabel(canvas: Canvas, label: String) {
        val textColor = key.getTextColor()
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: com.osfans.trime.data.keymap.LayoutTypography.size("key", if (label.length > 1 && !label.isIconFont) keyboardView.keyLongTextSize else keyboardView.keyTextSize)) * keyboardView.keyTextScale

        if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.key
                applyTextShadow(this)
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            val fontMetrics = labelMetrics.also { textPaint.getFontMetrics(it) }
            val adjustmentY = -(fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(label, centerX + sp(key.keyTextOffsetX) * keyboardView.keyTextScale, centerY + adjustmentY + sp(key.keyTextOffsetY) * keyboardView.keyTextScale, textPaint)
        }
    }

    /** 前景图标：等比例缩放、默认居中、可微调；不拉伸变形。 */
    private fun drawForegroundIcon(
        canvas: Canvas,
        image: BitmapAssets.Image,
    ) {
        val density = resources.displayMetrics.density
        val sizeDp = key.iconSize().takeIf { it > 0 } ?: (
            minOf(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / density * 0.55f
            ).toInt().coerceAtLeast(1)
        val targetSizePx = sizeDp * density * keyboardView.keyTextScale
        val scale = minOf(targetSizePx / image.sourceWidth, targetSizePx / image.sourceHeight).coerceAtLeast(0f)
        val drawW = image.sourceWidth * scale
        val drawH = image.sourceHeight * scale
        val contentWidth = width - paddingLeft - paddingRight
        val contentHeight = height - paddingTop - paddingBottom
        val centerX = paddingLeft + contentWidth / 2f + sp(key.iconOffsetX()) * keyboardView.keyTextScale
        val centerY = paddingTop + contentHeight / 2f + sp(key.iconOffsetY()) * keyboardView.keyTextScale
        // 锁大写等锁定态下按按键级文字色给图标着色（例如 Shift 变深绿底、白色箭头）。
        val tint = key.lockedTextColor()
        val paint = iconPaint.apply {
            isFilterBitmap = true; isAntiAlias = true; alpha = 255
            colorFilter = tint?.let { android.graphics.PorterDuffColorFilter(it, android.graphics.PorterDuff.Mode.SRC_IN) }
        }
        canvas.drawBitmap(image.bitmap, null, android.graphics.RectF(centerX - drawW / 2, centerY - drawH / 2, centerX + drawW / 2, centerY + drawH / 2), paint)
    }

    private val iconPaint = Paint()

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

    private fun applyTextShadow(paint: Paint) {
        val shadow = frameAppearance.effects.textShadow
        if (!shadow.enabled) {
            paint.clearShadowLayer()
            return
        }
        val blur = dpf(shadow.blur)
        val dx = dpf(shadow.offsetX)
        val dy = dpf(shadow.offsetY)
        if (blur > 0.01f || dx != 0f || dy != 0f) {
            paint.setShadowLayer(blur.coerceAtLeast(0.1f), dx, dy, KeyEffectPainter.color(shadow.color, 0x80000000.toInt()))
        } else {
            paint.clearShadowLayer()
        }
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        // 按键级覆盖优先于全局开关：showSymbol/showHint 三态（null 跟随全局）。
        val hide =
            if (isTop) key.showSymbol?.not() ?: keyboardView.hideKeySymbol
            else key.showHint?.not() ?: keyboardView.hideKeyHint
        if (hide) return

        val textColor = key.getSymbolColor()
        val textSize = sp((if (isTop) key.symbolTextSize else key.hintTextSize).takeIf { it > 0f } ?: com.osfans.trime.data.keymap.LayoutTypography.size(if (isTop) "symbol" else "hint", keyboardView.symbolTextSize)) * keyboardView.keyTextScale
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = if (isTop) theme.fonts.symbol else theme.fonts.hint
                applyTextShadow(this)
            }

            val lines = text.split("\n")
            val fontMetrics = symbolMetrics.also { symbolPaint.getFontMetrics(it) }
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            // 按键上方符号的水平对齐：left / center / right（按键级覆盖优先于键盘级，默认居中）。
            val contentWidth = width - paddingLeft - paddingRight
            val textWidth = lines.maxOf { symbolPaint.measureText(it) }
            // 靠左 / 靠右时与键缘保持 4dp 间距，避免符号贴边。
            val edgeInset = dpf(4f)
            val centerX = when (key.symbolAlign.ifBlank { keyboard.symbolAlign }) {
                "left" -> paddingLeft + edgeInset + textWidth / 2f
                "right" -> paddingLeft + contentWidth - edgeInset - textWidth / 2f
                else -> contentWidth / 2f + paddingLeft
            } + sp(offsetX) * keyboardView.keyTextScale
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
