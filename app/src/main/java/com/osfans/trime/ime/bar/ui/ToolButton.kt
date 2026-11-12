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
        // Legacy theme bitmaps keep their intrinsic size and never upscale just
        // because a button is larger. Explicit sizing and custom layouts opt in.
        val fit = localImage && (scope.theme.isCustom || requested > 0)
        // 显式 iconSize 的位图图标尺寸固定：按钮尚未测量（width/height==0）时也直接取
        // iconSize，别再回退成 MATCH_PARENT——否则按钮一 layout 完 onSizeChanged 会把
        // 图标从「填满按钮」跳回 iconSize，切键盘/换主题重建按钮时一眼就看到图标闪一下。
        val size = if (requested > 0) {
            // 显式 iconSize：严格按用户设定渲染，不再用 minOf(target, width, height) 卡在
            // 按钮框内——否则调到 50 也最多只有按钮那么大（有上限），且构造时未测量取
            // target、layout 后又 minOf 压回按钮框，切键盘重建按钮时图标「先大后小」闪一下。
            // 图标允许比按钮框大、溢出边界，由 clipChildren=false 负责完整显示。
            dp(requested) + maxOf(
                image.paddingLeft + image.paddingRight,
                image.paddingTop + image.paddingBottom,
            )
        } else -1
        val actual = if (size <= 0) -1 else size
        image.layoutParams = android.widget.FrameLayout.LayoutParams(if (fit) actual else -2, if (fit) actual else -2, gravityCenter)
        image.scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_INSIDE
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
        actionLabel = KeyboardWindow.currentKeyboardOrNull?.let { keyAction.getLabel(it) }.orEmpty()

        contentDescription = fg.style.ifBlank { actionLabel }
        val padding = dp(fg.padding)
        image.padding = padding
        label.padding = padding

        fontSize = config.appearance.keyTextSize.takeIf { it > 0 } ?: com.osfans.trime.data.keymap.LayoutTypography.size("toolbar", fg.fontSize, scope.theme.isCustom)
        label.textSize = fontSize

        label.typeface = scope.theme.fonts.toolbar

        applyConfigColors(config)
    }

    /** Applies the tint of icon-only buttons; re-run on scheme switches. */
    private fun applyIconTint() {
        val base = if (scope.theme.isCustom) {
            val keyboard = com.osfans.trime.data.keymap.CustomKeyboards.get(
                com.osfans.trime.data.keymap.CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId))
            keyboard?.toolbarTextColor()?.let(android.graphics.Color::parseColor) ?: scope.colors.candidateTextColor
        } else scope.colors.candidateTextColor
        image.imageTintList = ColorStateList.valueOf(
            com.osfans.trime.data.keymap.LayoutTypography.colorOr("toolbar", base),
        )
    }

    /** Applies scheme-dependent colors; re-run on scheme switches. */
    private fun applyConfigColors(config: ToolBar.Button) {
        val fg = config.foreground
        val normalColor = com.osfans.trime.data.keymap.LayoutTypography.colorOr(
            "toolbar",
            runCatching { scope.color(fg.normal.ifEmpty { "candidate_text_color" }) }.getOrDefault(scope.colors.candidateTextColor),
        )
        val pressedColor = com.osfans.trime.data.keymap.LayoutTypography.colorOr(
            "toolbar",
            runCatching { scope.color(fg.highlight.ifEmpty { "hilited_candidate_text_color" }) }.getOrDefault(scope.colors.candidateTextColor),
        )
        colorStateList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(pressedColor, normalColor),
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

    /**
     * 内置的三枚线条图标（菜单 / 悬浮 / 收起）在模板里各带过一块浅色底，
     * 夹在一排纯线条图标中间像按钮而不像图标。这里统一按线条处理：
     * 既不吃模板的 background，也不吃那个内置底色。
     */
    private fun stockLineIcon(): Boolean {
        // 图标名可能是新名（mie2_tb_*）也可能是旧别名（tb_*，主题模式下就是这样），两种都认。
        val base = config?.foreground?.style?.substringAfterLast('/') ?: return false
        return base.contains("tb_menu") || base.contains("tb_float") || base.contains("tb_hide")
    }

    /**
     * 内置皮肤给「菜单 / 悬浮 / 收起」这三枚线条图标各配过一块浅色底（各皮肤的主题色，
     * 粉 / 蓝 / 橙 / 紫 / 绿），夹在一排纯线条图标中间像贴了块色板，格格不入。
     * 这里把它们一律当透明，只留线条本身。
     */
    private val stockChipColors = setOf("E6F2EA", "FCEBED", "E7F3FC", "FFF0E2", "F1E9FC")

    private fun stripStockChip(color: String): String =
        if (stockLineIcon() && color.removePrefix("#").uppercase() in stockChipColors) "#00000000" else color

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
        // 「跟随按键外观」开着时，按钮自己那套外观整份让位——包括清成透明的底色，
        // 否则这几个显式写了 '#00000000' 的按钮会一直保持透明，和其他按钮不一致。
        val own = config?.appearance?.takeIf { !appearance.linkToolbar }
        if (own != null) {
            style = style.copy(
                background = own.keyBackColor.ifBlank { style.background },
                borderWidth = own.keyBorder.takeIf { it >= 0 }?.toFloat() ?: style.borderWidth,
                borderColor = own.keyBorderColor.ifBlank { style.borderColor },
                radius = own.roundCorner.takeIf { it >= 0 } ?: style.radius,
            )
        }
        // 模板给这几枚线条图标配的浅色底一律当透明，只留线条；跟随按键外观时不管，
        // 那时要的是「和按键一样」而不是「纯线条」。
        if (!appearance.linkToolbar) style = style.copy(background = stripStockChip(style.background))
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
            // 跟随按键外观时，按键的「不透明度」也要接管工具栏：
            // 键面被调成半透明玻璃，顶栏如果还是实心色，两截就对不上了。
            if (appearance.linkToolbar) {
                val alpha = appearance.keyOpacity.coerceIn(0, 100) * 255 / 100
                normalSurface?.setAlpha(alpha)
                pressedSurface?.setAlpha(alpha)
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
        // 图标可调大后可能比按钮框大，允许图标溢出按钮框完整显示（用户明确表示
        // 不在乎超出边界，要的就是自由控制大小）；不裁剪子 View。
        clipChildren = false
        clipToPadding = false
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
            when {
                java.io.File(style).isAbsolute -> android.graphics.drawable.Drawable.createFromPath(
                    com.osfans.trime.data.keymap.CustomKeyAssets.resolve(context, style) ?: style)
                // 相对路径优先按咩版素材目录（.mie/键盘背景）解析，主题/应用资源兜底。
                else -> com.osfans.trime.data.keymap.CustomKeyAssets.resolve(context, style)
                    ?.let { android.graphics.drawable.Drawable.createFromPath(it) }
                    ?: scope.drawable(style)
            }
        } else if (style.startsWith("ic@")) {
            val icon = "cmd_${style.substring(3)}"
            IconicsDrawable(context, icon).apply {
                sizeDp = (config?.foreground?.iconSize?.takeIf { it > 0 } ?: if (scope.theme.isCustom) fontSize.toInt() else config?.foreground?.fontSize?.toInt() ?: fontSize.toInt())
            }
        } else {
            null
        }

        localImage = useLocalImage
        updateImageSize()
        if (drawable != null) { // image icon
            image.imageDrawable = drawable
            image.imageTintList = if (!useLocalImage) colorStateList else config?.appearance?.iconTint
                ?.takeIf { it.isNotBlank() }?.let { value -> runCatching { ColorStateList.valueOf(android.graphics.Color.parseColor(value)) }.getOrNull() }
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
