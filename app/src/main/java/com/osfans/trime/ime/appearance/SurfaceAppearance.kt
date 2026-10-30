/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Color
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SurfaceStyle(
    val background: String = "",
    val borderWidth: Float? = null,
    val borderColor: String = "",
    val radius: Float? = null,
    val blur: Boolean = false,
) {
    fun sanitized() = copy(radius=radius?.takeIf { it.isFinite() }?.coerceIn(0f,64f),borderWidth=borderWidth?.takeIf { it.isFinite() }?.coerceIn(0f,32f))
    val overridesBorder get() = borderWidth != null || borderColor.isNotBlank()
    val customized get() = background.isNotEmpty() || overridesBorder || radius != null || blur
    fun color(fallback: Int) = runCatching { Color.parseColor(borderColor) }.getOrDefault(fallback)
}

/**
 * 按键外阴影。阴影由键盘层统一绘制在按键下方，因此可以超出单个按键的
 * View 边界，也不会被按键自身的圆角裁掉。
 *
 * [blur] 为 0 时是硬边投影；[spread] 为正时阴影向外扩张，为负时收缩。
 * 深色主题可以把 [color] 设成低透明度亮色，得到霓虹发光效果。
 */
@Serializable
data class KeyShadow(
    val enabled: Boolean = false,
    val color: String = "#66000000",
    val blur: Float = 6f,
    val offsetX: Float = 0f,
    val offsetY: Float = 3f,
    val spread: Float = 0f,
)

/** 按键内阴影：从按键边缘向内渐隐，做出凹陷/内嵌质感。 */
@Serializable
data class KeyInnerShadow(
    val enabled: Boolean = false,
    val color: String = "#55000000",
    val blur: Float = 4f,
    val offsetX: Float = 0f,
    val offsetY: Float = 2f,
)

/** 覆盖在按键背景上的渐变。type: linear / radial / sweep。 */
@Serializable
data class KeyGradient(
    val enabled: Boolean = false,
    val type: String = "linear",
    val startColor: String = "#33FFFFFF",
    val endColor: String = "#00000000",
    val angle: Float = 90f,
    val opacity: Int = 100,
) {
    val safeType get() = type.takeIf { it == "linear" || it == "radial" || it == "sweep" } ?: "linear"
}

/** 文字/符号阴影，使用 Android 原生文字阴影层，开销很低。 */
@Serializable
data class KeyTextShadow(
    val enabled: Boolean = false,
    val color: String = "#80000000",
    val blur: Float = 2f,
    val offsetX: Float = 0f,
    val offsetY: Float = 1f,
)

/** 按键边框渐变；开启后覆盖普通单色边框。 */
@Serializable
data class KeyBorderGradient(
    val enabled: Boolean = false,
    val startColor: String = "#66FFFFFF",
    val endColor: String = "#00000000",
    val angle: Float = 90f,
    val width: Float = 1f,
)

/**
 * 按键特效总配置。所有字段都有默认值，旧版 JSON 缺少字段时可以无损升级。
 */
@Serializable
data class KeyEffects(
    val shadow: KeyShadow = KeyShadow(),
    val innerShadow: KeyInnerShadow = KeyInnerShadow(),
    val gradient: KeyGradient = KeyGradient(),
    val borderGradient: KeyBorderGradient = KeyBorderGradient(),
    val textShadow: KeyTextShadow = KeyTextShadow(),
    /** 顶部高光/玻璃反光。 */
    val gloss: Boolean = false,
    val glossStrength: Int = 14,
    /** 磨砂颗粒，适合配合动态模糊背景使用。 */
    val noise: Boolean = false,
    val noiseStrength: Int = 5,
    /** 3D 键帽底边。 */
    val edge3d: Boolean = false,
    val edgeDepth: Float = 3f,
    val edgeColor: String = "#33000000",
    /** 按下时整体缩放百分比（0 表示不缩放）。 */
    val pressScale: Float = 0f,
    /** 按下时整体下沉 dp。 */
    val pressSink: Float = 0f,
    /** 按下时叠加黑色遮罩百分比。 */
    val pressDarken: Int = 0,
    /** 按下波纹。 */
    val ripple: Boolean = false,
    val rippleColor: String = "#33FFFFFF",
    /** 按压动画时长毫秒。 */
    val animationMs: Int = 90,
) {
    val customized get() = shadow.enabled || innerShadow.enabled || gradient.enabled ||
        borderGradient.enabled || textShadow.enabled || gloss || noise || edge3d || pressScale > 0f ||
        pressSink > 0f || pressDarken > 0 || ripple

    fun sanitized(): KeyEffects = copy(
        shadow = shadow.copy(
            blur = shadow.blur.finiteOr(6f).coerceIn(0f, 32f),
            offsetX = shadow.offsetX.finiteOr(0f).coerceIn(-16f, 16f),
            offsetY = shadow.offsetY.finiteOr(3f).coerceIn(-16f, 16f),
            spread = shadow.spread.finiteOr(0f).coerceIn(-8f, 8f),
        ),
        innerShadow = innerShadow.copy(
            blur = innerShadow.blur.finiteOr(4f).coerceIn(0f, 24f),
            offsetX = innerShadow.offsetX.finiteOr(0f).coerceIn(-12f, 12f),
            offsetY = innerShadow.offsetY.finiteOr(2f).coerceIn(-12f, 12f),
        ),
        gradient = gradient.copy(
            type = gradient.safeType,
            angle = gradient.angle.finiteOr(90f).let { ((it % 360f) + 360f) % 360f },
            opacity = gradient.opacity.coerceIn(0, 100),
        ),
        borderGradient = borderGradient.copy(
            angle = borderGradient.angle.finiteOr(90f).let { ((it % 360f) + 360f) % 360f },
            width = borderGradient.width.finiteOr(1f).coerceIn(0f, 8f),
        ),
        textShadow = textShadow.copy(
            blur = textShadow.blur.finiteOr(2f).coerceIn(0f, 16f),
            offsetX = textShadow.offsetX.finiteOr(0f).coerceIn(-8f, 8f),
            offsetY = textShadow.offsetY.finiteOr(1f).coerceIn(-8f, 8f),
        ),
        glossStrength = glossStrength.coerceIn(0, 60),
        noiseStrength = noiseStrength.coerceIn(0, 30),
        edgeDepth = edgeDepth.finiteOr(3f).coerceIn(0f, 8f),
        pressScale = pressScale.finiteOr(0f).coerceIn(0f, 10f),
        pressSink = pressSink.finiteOr(0f).coerceIn(0f, 8f),
        pressDarken = pressDarken.coerceIn(0, 60),
        animationMs = animationMs.coerceIn(0, 300),
    )

    private fun Float.finiteOr(fallback: Float) = if (isFinite()) this else fallback
}

@Serializable
data class SurfaceAppearanceConfig(
    val keyboardCorners: List<Float> = emptyList(), // TL, TR, BR, BL; empty inherits floating defaults
    val keys: SurfaceStyle = SurfaceStyle(),
    val toolbar: SurfaceStyle = SurfaceStyle(),
    val linkToolbar: Boolean = false,
    val bar: SurfaceStyle = SurfaceStyle(),
    val barBackground: String = "",
    val barBlur: Boolean = false,
    val blurRadius: Int = 16,
    val glassOpacity: Int = 25,
    /** 按键特效（两种键盘各自保存一份）。 */
    val effects: KeyEffects = KeyEffects(),
    val toolbarEffects: KeyEffects = KeyEffects(),
    val barEffects: KeyEffects = KeyEffects(),
    val retainToolbarBackground: Boolean = false,
    /** 悬浮窗卡片美化（主题键盘）：背景为空=跟随主题键背色，"#00000000"=透明，/path=图片。 */
    val floatingBackground: String = "",
    val floatingBorderColor: String = "",
    val floatingBorderWidth: Float = 0f,
) {
    fun sanitized() = copy(
        keyboardCorners=keyboardCorners.takeIf { it.size==4 }?.map { if(it.isFinite()) it.coerceIn(0f,64f) else 0f }.orEmpty(),
        keys=keys.sanitized(),toolbar=toolbar.sanitized(),bar=bar.sanitized(),effects=effects.sanitized(),toolbarEffects=toolbarEffects.sanitized(),barEffects=barEffects.sanitized(),
        blurRadius=blurRadius.coerceIn(2,32),glassOpacity=glassOpacity.coerceIn(0,100),
        floatingBorderWidth=floatingBorderWidth.coerceIn(0f,8f))
    val effectiveToolbarEffects get() = if (linkToolbar) effects else toolbarEffects
    val effectiveToolbar get() = if (linkToolbar) keys else toolbar
    val effectiveBar get() = bar.copy(background=bar.background.ifBlank { barBackground },blur=bar.blur || barBlur)
    val needsBlur get() = keys.blur || effectiveToolbar.blur || effectiveBar.blur
}

/** Cached preferences: never parse JSON or decode images in individual key draw calls. */
object SurfaceAppearance {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** 主题模式的外观改动没有走 CustomKeyboards 版本，单独递增以触发输入法重建。 */
    @Volatile
    var version: Long = 0
        private set
    private var cacheKey: Pair<String, String>? = null
    private var cached = SurfaceAppearanceConfig()
    private var layoutVersion = -1L
    private var runtimeLayoutId = ""
    private var layout: com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard? = null
    fun currentLayout(id: String = com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId): com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard? {
        val version = com.osfans.trime.data.keymap.CustomKeyboards.version
        if (layoutVersion != version || runtimeLayoutId != id) {
            val resolved = com.osfans.trime.data.keymap.CustomKeyboards.resolveId(id)
            layout = com.osfans.trime.data.keymap.CustomKeyboards.get(resolved)
            layoutVersion = version; runtimeLayoutId = id
        }
        return layout
    }
    fun current(customMode: Boolean = isCustomMode(), id: String? = null): SurfaceAppearanceConfig {
        if (customMode) currentLayout(id ?: com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId)?.surfaceAppearance?.let { return it }

        val prefs = AppPrefs.defaultInstance().keyboard
        val raw = (if (customMode) prefs.customSurfaceAppearance else prefs.surfaceAppearance).getValue()
        val background = (if (customMode) prefs.customKeyBackground else prefs.keyBackground).getValue()
        if (cacheKey?.first != raw || cacheKey?.second != background) {
            val parsed = runCatching { json.decodeFromString<SurfaceAppearanceConfig>(raw) }.getOrDefault(SurfaceAppearanceConfig())
            cached = parsed.copy(
                keyboardCorners = parsed.keyboardCorners.takeIf { it.size == 4 }?.map { it.takeIf(Float::isFinite)?.coerceIn(0f, 64f) ?: 0f }.orEmpty(),
                keys = parsed.keys.copy(
                    background = background,
                    radius = parsed.keys.radius?.takeIf(Float::isFinite),
                    borderWidth = parsed.keys.borderWidth?.takeIf(Float::isFinite),
                ),
                toolbar = parsed.toolbar.copy(
                    radius = parsed.toolbar.radius?.takeIf(Float::isFinite),
                    borderWidth = parsed.toolbar.borderWidth?.takeIf(Float::isFinite),
                ),
                effects = parsed.effects.sanitized(),
                blurRadius = parsed.blurRadius.coerceIn(2, 32),
                glassOpacity = parsed.glassOpacity.coerceIn(0, 100),
            )
            cacheKey = raw to background
        }
        return cached
    }
    fun isCustomMode() = com.osfans.trime.data.keymap.CustomKeyboards.mode == com.osfans.trime.data.keymap.CustomKeyboards.MODE_CUSTOM
    fun unifiedBackground(customMode: Boolean = isCustomMode(), id: String? = null): Boolean {
        if (customMode) currentLayout(id ?: com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId)?.unifiedBackground?.let { return it }
        val prefs = AppPrefs.defaultInstance().keyboard
        return (if (customMode) prefs.customUnifiedBackground else prefs.unifiedBackground).getValue()
    }
    fun save(config: SurfaceAppearanceConfig, customMode: Boolean = isCustomMode(), id: String? = null) {
        version++
        if (customMode && id != null) {
            com.osfans.trime.data.keymap.CustomKeyboards.mutate(id) { it.surfaceAppearance = config }
            return
        }
        val prefs = AppPrefs.defaultInstance().keyboard
        val background = if (customMode) prefs.customKeyBackground else prefs.keyBackground
        if (background.getValue() != config.keys.background) background.setValue(config.keys.background)
        (if (customMode) prefs.customSurfaceAppearance else prefs.surfaceAppearance).setValue(json.encodeToString(config))
    }
}
