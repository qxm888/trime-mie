/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.OvalShape
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.keyboard.KeyboardWindow
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.views.gravityCenter
import splitties.views.imageDrawable
import splitties.views.imageResource
import splitties.views.padding

class ToolButton(context: Context, private val scope: ThemeScope) : GestureFrame(context) {

    private val image = imageView {
        isClickable = false
        isFocusable = false
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    private val label = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        isSingleLine = true
        includeFontPadding = false
        gravity = gravityCenter
        scaleMode = AutoScaleTextView.Mode.Horizontal
    }

    var option: String? = null
        private set
    private var optionEnabled = false
        set(value) {
            if (field == value) return
            field = value
            updateContent()
        }

    private var optionStyles: List<String> = listOf()
    private var singleStyle = ""

    private var actionLabel = ""
    private var config: ToolBar.Button? = null
    private var normalSurface: com.osfans.trime.ime.appearance.SurfaceDrawable? = null
    private var pressedSurface: com.osfans.trime.ime.appearance.SurfaceDrawable? = null

    fun drawOuterShadow(canvas: android.graphics.Canvas) {
        val surface = (if (isPressed) pressedSurface else normalSurface) ?: return
        surface.setBounds(0,0,width,height)
        surface.drawOuterShadow(canvas)
    }

    private var localImage = false
    private fun updateImageSize() {
        val requested = config?.foreground?.iconSize ?: 0
        val size = if (requested > 0) minOf(dp(requested) + maxOf(image.paddingLeft + image.paddingRight, image.paddingTop + image.paddingBottom), width, height).coerceAtLeast(0) else -1
        val actual = if (size == 0) -1 else size
        image.layoutParams = android.widget.FrameLayout.LayoutParams(if (localImage) actual else -2, if (localImage) actual else -2, gravityCenter)
        image.scaleType = if (localImage) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_INSIDE
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (localImage) updateImageSize()
    }

    private var fontSize = 0f
    private var colorStateList: ColorStateList? = null

    constructor(
        context: Context,
        @DrawableRes icon: Int,
        scope: ThemeScope,
    ) : this(context, scope) {
        applyIconTint()
        image.padding = dp(4)
        setIcon(icon)
        applySurfaceBackground()
    }

    constructor(
        context: Context,
        config: ToolBar.Button,
        scope: ThemeScope,
    ) : this(context, scope) {
        this.config = config
        val keyAction = scope.theme.resolveAction(config.action)
        isRepeatable = keyAction.isRepeatable

        val fg = config.foreground
        val toggle = keyAction.toggle
        if (toggle.isNotEmpty() && config.foreground.optionStyles.size == 2) {
            option = toggle
            optionStyles = fg.optionStyles
        } else {
            singleStyle = fg.style
        }
        actionLabel = keyAction.getLabel(KeyboardWindow.currentKeyboard)

        contentDescription = fg.style.ifBlank { actionLabel }
        val padding = dp(fg.padding)
        image.padding = padding
        label.padding = padding

        fontSize = config.appearance.keyTextSize.takeIf { it > 0 } ?: com.osfans.trime.data.keymap.LayoutTypography.size("toolbar", fg.fontSize)
        label.textSize = fontSize

        label.typeface = scope.theme.fonts.toolbar

        applyConfigColors(config)
    }

    /** Applies the tint of icon-only buttons; re-run on scheme switches. */
    private fun applyIconTint() {
        image.imageTintList =
            ColorStateList.valueOf(if (scope.theme.isCustom) {
                val keyboard = com.osfans.trime.data.keymap.CustomKeyboards.get(
                    com.osfans.trime.data.keymap.CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId))
                keyboard?.toolbarTextColor()?.let(android.graphics.Color::parseColor) ?: scope.colors.candidateTextColor
            } else scope.colors.candidateTextColor)
    }

    /** Applies scheme-dependent colors; re-run on scheme switches. */
    private fun applyConfigColors(config: ToolBar.Button) {
        val fg = config.foreground
        colorStateList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(
                runCatching { scope.color(fg.highlight.ifEmpty { "hilited_candidate_text_color" }) }.getOrDefault(scope.colors.candidateTextColor),
                runCatching { scope.color(fg.normal.ifEmpty { "candidate_text_color" }) }.getOrDefault(scope.colors.candidateTextColor),
            ),
        )
        label.setTextColor(colorStateList)
        updateContent()

        val bg = config.background
        background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                backgroundStateDrawable(bg, highlight = true),
            )
            addState(
                intArrayOf(),
                backgroundStateDrawable(bg, highlight = false),
            )
        }
        applySurfaceBackground()
    }

    /** Shared by primary, ordinary and pinned toolbar buttons. */
    private var voiceHeld = false
    private fun voiceInput(): com.osfans.trime.ime.voice.VoiceInput? {
        var candidate: Context = context
        while (true) {
            if (candidate is com.osfans.trime.ime.core.TrimeInputMethodService) return candidate.voiceInput
            val base = (candidate as? android.content.ContextWrapper)?.baseContext ?: return null
            if (base === candidate) return null
            candidate = base
        }
    }
    override fun onDetachedFromWindow() {
        animate().cancel()
        animate().setUpdateListener(null)
        invalidateShadowHost()
        if (voiceHeld) { voiceHeld = false; voiceInput()?.cancel() }
        super.onDetachedFromWindow()
    }
    fun bindActions(dispatch: ((String) -> Unit)?) {
        val cfg = config ?: return
        val voiceClick = scope.theme.resolveAction(cfg.action).code == android.view.KeyEvent.KEYCODE_VOICE_ASSIST
        setOnClickListener { if (!voiceClick) dispatch?.invoke(cfg.action) }
        if (voiceClick) onPress = { voiceHeld = true; voiceInput()?.start() }
        onRelease = { _, _ -> if (voiceHeld) { voiceHeld = false; voiceInput()?.release() } }
        onCancel = { if (voiceHeld) { voiceHeld = false; voiceInput()?.cancel() } }
        if (cfg.longPressAction.isNotEmpty()) setOnLongClickListener {
            if (scope.theme.resolveAction(cfg.longPressAction).code == android.view.KeyEvent.KEYCODE_VOICE_ASSIST) {
                voiceHeld = true; voiceInput()?.start()
            } else dispatch?.invoke(cfg.longPressAction)
            true
        }
        cfg.bindings["doubleClick"]?.takeIf { it.isNotEmpty() }?.let { token -> hasDouble = true; onDoubleClick = { dispatch?.invoke(token) } }
        cfg.bindings["swipeUp"]?.takeIf { it.isNotEmpty() }?.let { token -> onSwipeUp = { dispatch?.invoke(token) } }
        cfg.bindings["swipeDown"]?.takeIf { it.isNotEmpty() }?.let { token -> onSwipeDown = { dispatch?.invoke(token) } }
        cfg.bindings["swipeLeft"]?.takeIf { it.isNotEmpty() }?.let { token -> onSwipeLeft = { dispatch?.invoke(token) } }
        cfg.bindings["swipeRight"]?.takeIf { it.isNotEmpty() }?.let { token -> onSwipeRight = { dispatch?.invoke(token) } }
    }

    private fun applySurfaceBackground() {
        normalSurface = null; pressedSurface = null
        invalidateShadowHost()
        val appearance = com.osfans.trime.ime.appearance.SurfaceAppearance.current(scope.theme.isCustom)
        var style = appearance.effectiveToolbar
        if (appearance.linkToolbar && scope.theme.isCustom) {
            val keyboard = com.osfans.trime.data.keymap.CustomKeyboards.get(com.osfans.trime.data.keymap.CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId))
            if (keyboard != null) style = style.copy(
                background = keyboard.allKeysBackground.ifBlank { style.background.ifBlank { keyboard.keyAppearance.keyBackColor } },
                borderWidth = style.borderWidth ?: keyboard.keyAppearance.keyBorder.takeIf { it >= 0 }?.toFloat(),
                borderColor = style.borderColor.ifBlank { keyboard.keyAppearance.keyBorderColor },
                radius = style.radius ?: keyboard.keyAppearance.roundCorner.takeIf { it >= 0 },
            )
        }
        if (appearance.linkToolbar) style = style.copy(
            borderWidth = style.borderWidth ?: themeKeyBorder(),
            borderColor = style.borderColor.ifBlank { String.format("#%08X", scope.colors.keyBorderColor) },
            radius = style.radius ?: scope.theme.style.roundCorner,
        )
        val effects = (config?.effects ?: appearance.effectiveToolbarEffects).sanitized()
        val own = config?.appearance
        if (own != null) {
            style = style.copy(
                background = own.keyBackColor.ifBlank { style.background },
                borderWidth = own.keyBorder.takeIf { it >= 0 }?.toFloat() ?: style.borderWidth,
                borderColor = own.keyBorderColor.ifBlank { style.borderColor },
                radius = own.roundCorner.takeIf { it >= 0 } ?: style.radius,
            )
        }
        if (effects.customized || style.customized || appearance.linkToolbar || own?.hilitedKeyBackColor?.isNotEmpty() == true || own?.hilitedKeyBorderColor?.isNotEmpty() == true) {
            val fallback = if (appearance.linkToolbar) scope.drawable("key_back_color") else
                config?.background?.let { bg -> StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), backgroundStateDrawable(bg,true, false))
                    addState(intArrayOf(), backgroundStateDrawable(bg,false, false))
                } } ?: background
            val pressed = style.copy(
                background = own?.hilitedKeyBackColor?.ifBlank { style.background } ?: style.background,
                borderColor = own?.hilitedKeyBorderColor?.ifBlank { style.borderColor } ?: style.borderColor,
            )
            fun surface(style: com.osfans.trime.ime.appearance.SurfaceStyle) = com.osfans.trime.ime.appearance.SurfaceDrawable(
                this@ToolButton, fallback?.constantState?.newDrawable()?.mutate() ?: fallback, style,
                defaultRadius = config?.background?.cornerRadius ?: 8f,
                defaultBorderColor = scope.colors.keyBorderColor, effects = effects, externalShadow = true,
                circle = config?.background?.type == ToolBar.Button.Background.Type.CIRCLE)
            normalSurface = surface(style)
            pressedSurface = surface(pressed)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedSurface)
                addState(intArrayOf(), normalSurface)
            }
            if (effects.ripple) {
                val mask=android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.WHITE)
                    shape = if(config?.background?.type == ToolBar.Button.Background.Type.CIRCLE && style.radius == null) android.graphics.drawable.GradientDrawable.OVAL else android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius=(style.radius ?: config?.background?.cornerRadius ?: 8f)*resources.displayMetrics.density
                }
                background=android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(com.osfans.trime.ime.appearance.KeyEffectPainter.color(effects.rippleColor,0x33FFFFFF)),background,android.graphics.drawable.InsetDrawable(mask,dp(3)))
            }
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (childCount == 0) return
        val effects = (config?.effects ?: com.osfans.trime.ime.appearance.SurfaceAppearance.current(scope.theme.isCustom).effectiveToolbarEffects).sanitized()
        val scale = if(isPressed) 1f-effects.pressScale/100f else 1f
        val sink = if(isPressed) effects.pressSink*resources.displayMetrics.density else 0f
        animate().cancel()
        if (scaleX != scale || scaleY != scale || translationY != sink) {
            animate().scaleX(scale).scaleY(scale).translationY(sink).setDuration(effects.animationMs.toLong())
                .setUpdateListener { invalidateShadowHost() }.start()
        }
        invalidateShadowHost()
        val shadow=effects.textShadow
        label.setShadowLayer(if(shadow.enabled) shadow.blur*resources.displayMetrics.density else 0f,shadow.offsetX*resources.displayMetrics.density,shadow.offsetY*resources.displayMetrics.density,com.osfans.trime.ime.appearance.KeyEffectPainter.color(shadow.color,0x55000000))
    }

    private fun invalidateShadowHost() {
        var owner = parent
        while (owner is android.view.View) {
            if (owner is ToolbarShadowAnimator) { owner.invalidate(); (owner.parent as? android.view.View)?.invalidate(); return }
            owner = owner.parent
        }
    }

    private fun themeKeyBorder() = scope.theme.style.keyBorder.toFloat()

    /** Restyles this button after a scheme switch. */
    fun refreshColors() {
        val cfg = config
        if (cfg == null) {
            background = null
            applyIconTint()
            applySurfaceBackground()
        } else {
            applyConfigColors(cfg)
        }
    }

    init {
        add(
            label,
            lParams {
                gravity = gravityCenter
            },
        )
        add(
            image,
            lParams {
                gravity = gravityCenter
            },
        )
    }

    fun setIcon(@DrawableRes src: Int) {
        image.imageResource = src
        label.isVisible = false
        image.isVisible = true
    }

    fun updateStyle(option: String, enabled: Boolean) {
        if (option == this.option) {
            optionEnabled = enabled
        }
    }

    private fun backgroundStateDrawable(bg: ToolBar.Button.Background, highlight: Boolean, withInsets: Boolean = true): Drawable {
        val color = if (highlight) {
            scope.color(bg.highlight.ifEmpty { "hilited_candidate_button_color" })
        } else {
            bg.normal.takeIf { it.isNotEmpty() }?.let(scope::color) ?: 0
        }
        return when (bg.type) {
            ToolBar.Button.Background.Type.RECTANGLE -> GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(bg.cornerRadius)
            }

            ToolBar.Button.Background.Type.CIRCLE -> ShapeDrawable(OvalShape()).apply { paint.color = color }
        }.let {
            if (!withInsets) return@let it
            val vInset = dp(bg.verticalInset)
            val hInset = dp(bg.horizontalInset)
            LayerDrawable(arrayOf(it)).apply {
                setLayerInset(0, hInset, vInset, hInset, vInset)
            }
        }
    }

    private fun updateContent() {
        val style = if (option != null && optionStyles.size == 2) {
            optionStyles[if (optionEnabled) 1 else 0]
        } else {
            singleStyle
        }

        // drawable: local image or iconics
        var useLocalImage = false
        val drawable = if (IMAGE_PATTERN.matches(style)) {
            useLocalImage = true
            if (java.io.File(style).isAbsolute) Drawable.createFromPath(style) else scope.drawable(style)
        } else if (style.startsWith("ic@")) {
            val icon = "cmd_${style.substring(3)}"
            IconicsDrawable(context, icon).apply {
                sizeDp = fontSize.toInt()
            }
        } else {
            null
        }

        localImage = useLocalImage
        updateImageSize()
        if (drawable != null) { // image icon
            image.imageDrawable = drawable
            image.imageTintList = if (!useLocalImage) colorStateList else null
            label.isVisible = false
            image.isVisible = true
        } else { // text icon
            label.text = if (useLocalImage || style.isEmpty()) actionLabel else style
            image.isVisible = false
            label.isVisible = true
        }
    }

    companion object {
        private val IMAGE_PATTERN = ".*\\.(png|jpg|gif|webp)$".toRegex()
    }
}
