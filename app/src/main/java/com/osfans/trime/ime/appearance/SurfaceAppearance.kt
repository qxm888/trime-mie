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
    /**
     * 按键底色的不透明度（0–100）。
     *
     * 面板背景已经糊过了，按键半透明之后透出来的就是那块糊过的背景——
     * 所以按键不需要自己再糊一次，只把颜色调透就能得到同一套磨砂观感。
     * 100 = 原来的实心按键。
     */
    val keyOpacity: Int = 100,
    /**
     * 按键自己的模糊强度（0–100），0 = 不糊。
     *
     * 和面板用的是同一次抓图，但**单独再糊一遍**，所以两边可以差着来：
     * 面板糊得轻、按键糊得重（或反过来），键面就会从那块底板上"浮"出来。
     * 和 [SurfaceStyle.blur] 的区别：那个是"糊不糊"的开关，用的是面板同一帧，没有反差。
     */
    val keyBlur: Int = 0,
    /**
     * 自糊帧的饱和度补偿（100–200）。
     *
     * 模糊会把局部色差平均掉，观感发灰发白；按亮度轴把色度拉开一点，
     * 颜色就回到原图的浓淡，但颗粒仍然是糊的。100 = 不补。
     */
    val blurSaturation: Int = 145,
    /** 按键特效（两种键盘各自保存一份）。 */
    val effects: KeyEffects = KeyEffects(),
    val toolbarEffects: KeyEffects = KeyEffects(),
    val barEffects: KeyEffects = KeyEffects(),
    val retainToolbarBackground: Boolean = false,
    /**
     * 状态栏「整排显示」：开启后不横向滑动，先把按钮间距压到最小 2dp，
     * 还不够就把整排图标等比缩小。关闭 = 原来的"放不下就滑动"。
     */
    val toolbarFitAll: Boolean = false,
    /** 工具栏图标上方留白（dp，0–60）。主题键盘专用；自定义键盘用布局自带的 toolbarTopPadDp。 */
    val toolbarTopPadDp: Int = 0,
    /** 工具栏图标下方留白（dp，0–60）。与上方留白一起：总高=上+下+图标行高。 */
    val toolbarBottomPadDp: Int = 0,
    /** 工具栏图标统一大小（dp，0=跟随主题按钮自带尺寸，8–96）。主题键盘专用；自定义键盘用布局自带的 toolbarIconSizeDp。 */
    val toolbarIconSizeDp: Int = 0,
    /** 悬浮窗卡片美化（主题键盘）：背景为空=跟随主题键背色，"#00000000"=透明，/path=图片。 */
    val floatingBackground: String = "",
    val floatingBorderColor: String = "",
    val floatingBorderWidth: Float = 0f,
    /** 整块键盘的描边颜色与宽度（dp）。空 / 0 = 不画。 */
    val keyboardBorderColor: String = "",
    val keyboardBorderWidth: Float = 0f,
    /** 描边上的白色高光强度（0–100）。亮边只占这条描边带，描边宽度为 0 时也没有亮边。 */
    val keyboardBorderGlow: Int = 60,
    /** 键盘整体左右留白（dp，0–20）：按键从两侧收进来，背景仍铺满面板。 */
    val keyboardSideMargin: Float = 0f,
    /** 键盘整体上 / 下留白（dp，0–20）：按键区从面板上下收进来，键盘总高不变。 */
    val keyboardTopMargin: Float = 0f,
    val keyboardBottomMargin: Float = 0f,
    /** 候选高亮背景圆角（dp，0–32）。小于 0 = 跟随主题的 candidateCornerRadius。 */
    val candidateHighlightRadius: Float = -1f,
    /** 候选高亮背景颜色；空 = 跟随主题的 hilitedCandidateBackColor。 */
    val candidateHighlightColor: String = "",
    /** 整块面板的渐变背景（工具栏 + 候选栏 + 键盘区共用一条）。开着时纯色/透明/图片背景都不画。 */
    val gradient: PanelGradient = PanelGradient(),
    /** 整块面板的真背景模糊背板。优先级最高：开着时纯色、渐变、图片背景都不画。 */
    val backdrop: PanelBackdrop = PanelBackdrop(),
    /** 主题键盘的面板背景；空 = 跟随主题 keyboard_background。自定义键盘不用它。 */
    val panelBackground: String = "",
) {
    fun sanitized() = copy(
        keyboardCorners=keyboardCorners.takeIf { it.size==4 }?.map { if(it.isFinite()) it.coerceIn(0f,64f) else 0f }.orEmpty(),
        keys=keys.sanitized(),toolbar=toolbar.sanitized(),bar=bar.sanitized(),effects=effects.sanitized(),toolbarEffects=toolbarEffects.sanitized(),barEffects=barEffects.sanitized(),
        blurRadius=blurRadius.coerceIn(2,32),glassOpacity=glassOpacity.coerceIn(0,100),
        keyOpacity=keyOpacity.coerceIn(0,100),keyBlur=keyBlur.coerceIn(0,100),blurSaturation=blurSaturation.coerceIn(100,200),
        floatingBorderWidth=floatingBorderWidth.coerceIn(0f,8f),
        keyboardBorderWidth=if (keyboardBorderWidth.isFinite()) keyboardBorderWidth.coerceIn(0f,5f) else 0f,
        keyboardBorderGlow=keyboardBorderGlow.coerceIn(0,100),
        keyboardSideMargin=if (keyboardSideMargin.isFinite()) keyboardSideMargin.coerceIn(0f,20f) else 0f,
        keyboardTopMargin=if (keyboardTopMargin.isFinite()) keyboardTopMargin.coerceIn(0f,20f) else 0f,
        keyboardBottomMargin=if (keyboardBottomMargin.isFinite()) keyboardBottomMargin.coerceIn(0f,20f) else 0f,
        candidateHighlightRadius=if (candidateHighlightRadius.isFinite()) candidateHighlightRadius.coerceIn(-1f,32f) else -1f,
        candidateHighlightColor=candidateHighlightColor.trim(),
        toolbarTopPadDp=toolbarTopPadDp.coerceIn(0,60),
        toolbarBottomPadDp=toolbarBottomPadDp.coerceIn(0,60),
        toolbarIconSizeDp=toolbarIconSizeDp.coerceIn(0,96),
        gradient=gradient.sanitized(),
        backdrop=backdrop.sanitized(),
        panelBackground=panelBackground.trim())
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

    /**
     * 键盘最近一次真正用上的四角圆角（dp）。
     *
     * 设置面板拿它做一行回显：如果这里已经是 40 而键盘还是直角，问题在绘制；
     * 如果这里还是 0，说明值没被读到。省得再去猜是哪一头。
     */
    @Volatile
    var lastAppliedCorners: List<Float> = emptyList()
        private set

    fun recordAppliedCorners(corners: List<Float>) {
        lastAppliedCorners = corners
    }

    /** 键盘最近一次真正用上的描边（颜色, 宽度 dp），设置页拿它做一行回显。 */
    @Volatile
    var lastAppliedBorder: Pair<String, Float> = "" to 0f
        private set

    fun recordAppliedBorder(color: String, widthDp: Float) {
        lastAppliedBorder = color to widthDp
    }
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
                keyOpacity = parsed.keyOpacity.coerceIn(0, 100),
                keyBlur = parsed.keyBlur.coerceIn(0, 100),
                blurSaturation = parsed.blurSaturation.coerceIn(100, 200),
                keyboardSideMargin = parsed.keyboardSideMargin.takeIf(Float::isFinite)?.coerceIn(0f, 20f) ?: 0f,
                keyboardBorderGlow = parsed.keyboardBorderGlow.coerceIn(0, 100),
                keyboardTopMargin = parsed.keyboardTopMargin.takeIf(Float::isFinite)?.coerceIn(0f, 20f) ?: 0f,
                keyboardBottomMargin = parsed.keyboardBottomMargin.takeIf(Float::isFinite)?.coerceIn(0f, 20f) ?: 0f,
                candidateHighlightRadius = parsed.candidateHighlightRadius.takeIf(Float::isFinite)?.coerceIn(-1f, 32f) ?: -1f,
                candidateHighlightColor = parsed.candidateHighlightColor.trim(),
            )
            cacheKey = raw to background
        }
        return cached
    }
    fun isCustomMode() = com.osfans.trime.data.keymap.CustomKeyboards.mode == com.osfans.trime.data.keymap.CustomKeyboards.MODE_CUSTOM

    /**
     * 整块键盘面板的四角圆角。
     *
     * 和按键圆角不同，它不是「某一页」的属性：裁的是键盘外框，换到英文页、数字页
     * 还是同一块面板。但 [current] 只认当前显示的那一页，而用户往往是在主页面
     * （「某某键盘 · 外观」）里调这个值——两页不是同一个 id，于是读回来永远是空，
     * 表现出来就是「设置了但键盘没变」。
     *
     * 所以这里按「主页面 → 当前整套的活动页 → 正在显示的那页」的顺序找，第一个
     * 真正设过四角的就用它。
     */
    fun keyboardCorners(customMode: Boolean = isCustomMode(), id: String? = null): List<Float> {
        if (!customMode) return current(false).keyboardCorners
        val page = id ?: com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId
        val sources = buildList {
            page?.takeIf { it.isNotBlank() }?.let { runCatching { com.osfans.trime.data.keymap.CustomKeyboards.setRoot(it.removePrefix(com.osfans.trime.data.keymap.KeyboardSets.EDIT_PREFIX)) }.getOrNull() }?.let(::add)
            com.osfans.trime.data.keymap.CustomKeyboards.activeId.takeIf { it.isNotBlank() }?.let(::add)
            page?.takeIf { it.isNotBlank() }?.let(::add)
        }
        sources.distinct().forEach { candidate ->
            val corners = runCatching {
                com.osfans.trime.data.keymap.CustomKeyboards.get(candidate)?.surfaceAppearance?.keyboardCorners
            }.getOrNull()
            if (corners != null && corners.size == 4 && corners.any { it > 0f }) return corners
        }
        return current(true, page).keyboardCorners
    }

    /**
     * 按「整套 / 页面 / 面板」作用域列出候选配置：
     * 主页 → 整套当前活动页 → 正在显示的那页。主题键盘只有一份「主题键盘全局外观」。
     */
    private fun keyboardScopedConfigs(customMode: Boolean, id: String?): List<SurfaceAppearanceConfig> {
        if (!customMode) return listOf(current(false))
        val page = id ?: com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId
        val sources = buildList {
            page?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    com.osfans.trime.data.keymap.CustomKeyboards.setRoot(
                        it.removePrefix(com.osfans.trime.data.keymap.KeyboardSets.EDIT_PREFIX)
                    )
                }.getOrNull()
            }?.let(::add)
            com.osfans.trime.data.keymap.CustomKeyboards.runtimeActiveId().takeIf { it.isNotBlank() }?.let(::add)
            page?.takeIf { it.isNotBlank() }?.let(::add)
        }
        val scoped = sources.distinct().mapNotNull { candidate ->
            runCatching { com.osfans.trime.data.keymap.CustomKeyboards.get(candidate)?.surfaceAppearance }.getOrNull()
        }
        // 兜底：把「主题键盘全局外观」接在最后。
        //
        // 内置皮肤是当作自定义键盘注入的，各皮肤只存自己改过的那几项；其余项
        // （描边、留白、候选高亮……）应当从全局设定继承，而不是一律回落到 0。
        // 放在最后是因为各 getter 都取「第一个满足条件的配置」，皮肤自己设了值
        // 就轮不到这条兜底——它只补没设的部分，不会盖掉皮肤自己的外观。
        return scoped + current(false)
    }

    /**
     * 整套作用域查找的结果缓存。
     *
     * 候选栏每按一次键就重刷十几个候选项，每个都要问「高亮圆角 / 高亮颜色 / 描边」，
     * 而这些 getter 原本每次都走全表扫描 + JSON 解析。这里按
     * 「配置版本 + 当前页 + 模式」缓存一次，绘制热路径只剩一次字符串比较。
     *
     * 作用域口径：「主题键盘全局外观」只服务 YAML 主题键盘；内置皮肤是以自定义键盘
     * 注入、各自存了 surfaceAppearance 的，全局设定不能盖掉它们自己的外观。
     */
    private data class ScopedAppearance(
        val sideMargin: Float,
        val topMargin: Float,
        val bottomMargin: Float,
        val highlightRadius: Float?,
        val highlightColor: String?,
        val border: Pair<String, Float>,
        val borderGlow: Int,
        val gradient: PanelGradient?,
        val backdrop: PanelBackdrop?,
        val panelBackground: String?,
    )

    private var scopedStamp = ""
    private var scopedCache = ScopedAppearance(0f, 0f, 0f, null, null, "" to 0f, 60, null, null, null)

    private fun scopedAppearance(customMode: Boolean, id: String?): ScopedAppearance {
        val page = id ?: com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId
        val prefs = AppPrefs.defaultInstance().keyboard
        val stamp = buildString {
            append(com.osfans.trime.data.keymap.CustomKeyboards.version).append('|')
            append(version).append('|')
            append(customMode).append('|')
            append(page).append('|')
            append(prefs.surfaceAppearance.getValue()).append('|')
            append(prefs.customSurfaceAppearance.getValue())
        }
        if (stamp == scopedStamp) return scopedCache
        val result = ScopedAppearance(
            sideMargin = computeSideMargin(customMode, id),
            topMargin = computeTopMargin(customMode, id),
            bottomMargin = computeBottomMargin(customMode, id),
            highlightRadius = computeHighlightRadius(customMode, id),
            highlightColor = computeHighlightColor(customMode, id),
            border = computeBorder(customMode, id),
            borderGlow = computeBorderGlow(customMode, id),
            gradient = computeGradient(customMode, id),
            backdrop = computeBackdrop(customMode, id),
            panelBackground = computePanelBackground(customMode, id),
        )
        scopedCache = result
        scopedStamp = stamp
        return result
    }

    /** 整块键盘的描边：颜色（`#AARRGGBB`）与宽度（dp）。 */
    fun keyboardBorder(customMode: Boolean = isCustomMode(), id: String? = null): Pair<String, Float> =
        scopedAppearance(customMode, id).border

    /** 整块面板的渐变背景；null = 未启用，纯色/透明/图片背景照常生效。 */
    fun panelGradient(customMode: Boolean = isCustomMode(), id: String? = null): PanelGradient? =
        scopedAppearance(customMode, id).gradient

    /** 整块面板的真背景模糊背板；null = 未启用。 */
    fun panelBackdrop(customMode: Boolean = isCustomMode(), id: String? = null): PanelBackdrop? =
        scopedAppearance(customMode, id).backdrop

    /** 面板背景（主题键盘专用）；null = 未设置，跟随主题 keyboard_background。 */
    fun panelBackground(customMode: Boolean = isCustomMode(), id: String? = null): String? =
        scopedAppearance(customMode, id).panelBackground

    /** 描边高光强度（0–100）。 */
    fun keyboardBorderGlow(customMode: Boolean = isCustomMode(), id: String? = null): Int =
        scopedAppearance(customMode, id).borderGlow

    /** 键盘整体左右留白（dp，0–20）。 */
    fun keyboardSideMargin(customMode: Boolean = isCustomMode(), id: String? = null): Float =
        scopedAppearance(customMode, id).sideMargin

    /** 键盘整体上留白（dp，0–20）。 */
    fun keyboardTopMargin(customMode: Boolean = isCustomMode(), id: String? = null): Float =
        scopedAppearance(customMode, id).topMargin

    /** 键盘整体下留白（dp，0–20）。 */
    fun keyboardBottomMargin(customMode: Boolean = isCustomMode(), id: String? = null): Float =
        scopedAppearance(customMode, id).bottomMargin

    /** 候选高亮圆角（dp）。未设置（<0）时返回 null，由调用方跟随主题。 */
    fun candidateHighlightRadius(customMode: Boolean = isCustomMode(), id: String? = null): Float? =
        scopedAppearance(customMode, id).highlightRadius

    /** 候选高亮背景色（#AARRGGBB）。未设置时返回 null，由调用方跟随主题。 */
    fun candidateHighlightColor(customMode: Boolean = isCustomMode(), id: String? = null): String? =
        scopedAppearance(customMode, id).highlightColor

    /** 状态栏是否整排显示（不滑动）。 */
    fun toolbarFitAll(customMode: Boolean = isCustomMode(), id: String? = null): Boolean =
        keyboardScopedConfigs(customMode, id).any { it.toolbarFitAll }

    /** 主题键盘（YAML）的工具栏上下留白（dp）。自定义键盘用布局自带的 toolbarTopPadDp/BottomPadDp。 */
    fun themeToolbarPads(): Pair<Int, Int> {
        val cfg = current(false)
        return cfg.toolbarTopPadDp.coerceIn(0, 60) to cfg.toolbarBottomPadDp.coerceIn(0, 60)
    }

    /** 主题键盘（YAML）的工具栏图标统一大小（dp，0=跟随主题）。 */
    fun themeToolbarIconSize(): Int = current(false).toolbarIconSizeDp.coerceIn(0, 96)

    private fun computeGradient(customMode: Boolean, id: String?): PanelGradient? =
        keyboardScopedConfigs(customMode, id).firstOrNull { it.gradient.enabled }?.gradient?.sanitized()

    private fun computeBackdrop(customMode: Boolean, id: String?): PanelBackdrop? =
        keyboardScopedConfigs(customMode, id).firstOrNull { it.backdrop.enabled }?.backdrop?.sanitized()

    /** 面板背景只服务主题键盘；自定义键盘用自带的整套键盘背景，这里一律返回 null。 */
    private fun computePanelBackground(customMode: Boolean, id: String?): String? =
        if (customMode) null else
            keyboardScopedConfigs(customMode, id).firstOrNull { it.panelBackground.isNotBlank() }?.panelBackground

    private fun computeBorder(customMode: Boolean, id: String?): Pair<String, Float> =
        keyboardScopedConfigs(customMode, id)
            .firstOrNull { it.keyboardBorderColor.isNotBlank() && it.keyboardBorderWidth > 0f }
            ?.let { it.keyboardBorderColor to it.keyboardBorderWidth }
            ?: ("" to 0f)

    private fun computeBorderGlow(customMode: Boolean, id: String?): Int =
        keyboardScopedConfigs(customMode, id)
            .firstOrNull { it.keyboardBorderColor.isNotBlank() && it.keyboardBorderWidth > 0f }
            ?.keyboardBorderGlow ?: 60

    private fun computeSideMargin(customMode: Boolean, id: String?): Float =
        keyboardScopedConfigs(customMode, id).firstOrNull { it.keyboardSideMargin > 0f }?.keyboardSideMargin ?: 0f

    private fun computeTopMargin(customMode: Boolean, id: String?): Float =
        keyboardScopedConfigs(customMode, id).firstOrNull { it.keyboardTopMargin > 0f }?.keyboardTopMargin ?: 0f

    private fun computeBottomMargin(customMode: Boolean, id: String?): Float =
        keyboardScopedConfigs(customMode, id).firstOrNull { it.keyboardBottomMargin > 0f }?.keyboardBottomMargin ?: 0f

    private fun computeHighlightRadius(customMode: Boolean, id: String?): Float? =
        keyboardScopedConfigs(customMode, id).firstOrNull { it.candidateHighlightRadius >= 0f }?.candidateHighlightRadius

    private fun computeHighlightColor(customMode: Boolean, id: String?): String? =
        keyboardScopedConfigs(customMode, id).firstOrNull { it.candidateHighlightColor.isNotBlank() }?.candidateHighlightColor

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
