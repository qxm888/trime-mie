/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.ime.appearance.KeyBorderGradient
import com.osfans.trime.ime.appearance.KeyEffects
import com.osfans.trime.ime.appearance.KeyGradient
import com.osfans.trime.ime.appearance.KeyInnerShadow
import com.osfans.trime.ime.appearance.KeyShadow
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import com.osfans.trime.ime.appearance.SurfaceStyle
import kotlin.math.roundToInt

/** Staged appearance settings. Sections stay folded until requested; Apply commits all changes. */
object SurfaceAppearanceEditor {
    /** 与 SurfaceAppearanceConfig.sanitized() 的上限保持一致。 */
    private const val MAX_KEYBOARD_CORNER_DP = 64f

    /** 打开「自定义键盘四角」时的落值，保证一开就能看见效果。 */
    private const val DEFAULT_KEYBOARD_CORNER_DP = 12f

    fun show(context: Context, customMode: Boolean = SurfaceAppearance.isCustomMode(), layoutId: String? = null, initialEffects: KeyEffects? = null, onEffects: ((KeyEffects) -> Unit)? = null, changed: () -> Unit = {}): androidx.appcompat.app.AlertDialog {
        var draft = SurfaceAppearance.current(customMode, layoutId).let { if(initialEffects == null) it else it.copy(effects=initialEffects) }
        var unified = SurfaceAppearance.unifiedBackground(customMode, layoutId)
        fun save(config: SurfaceAppearanceConfig) {
            if(onEffects != null) { onEffects(config.effects); return }
            SurfaceAppearance.save(config, customMode, layoutId)
            val prefs = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard
            if (customMode && layoutId != null) {
                com.osfans.trime.data.keymap.CustomKeyboards.mutate(layoutId) {
                    it.unifiedBackground = unified
                    // 圆角/边框只有一个参数，这里和「按键默认外观」是两个入口，保存时写到同一个数上。
                    val radius = config.keys.radius
                    val border = config.keys.borderWidth
                    val before = it.keyAppearance
                    if (radius != null || border != null) it.keyAppearance = before.copy(
                        roundCorner = radius ?: before.roundCorner,
                        keyBorder = border?.toInt() ?: before.keyBorder)
                    // 这是本页的统一值：改了就推下去，26 个键（含单独调过的）一起变。
                    // 整套作用域由整套推送统一处理每一页，这里只处理单页。
                    if (!layoutId.startsWith(com.osfans.trime.data.keymap.KeyboardSets.EDIT_PREFIX)) {
                        val touched = buildList {
                            if (radius != null && radius != before.roundCorner) add("roundCorner")
                            if (border != null && border.toInt() != before.keyBorder) add("keyBorder")
                        }
                        if (touched.isNotEmpty()) {
                            val pushed = com.osfans.trime.data.keymap.KeyboardSetOperations.pushAppearance(it, touched)
                            it.rows.clear(); it.rows.addAll(pushed.rows)
                        }
                    }
                }
                com.osfans.trime.data.keymap.CustomKeyboards.flush()
            } else (if (customMode) prefs.customUnifiedBackground else prefs.unifiedBackground).setValue(unified)
            com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
            changed()
        }
        fun dp(n: Int) = (context.resources.displayMetrics.density * n).toInt()
        fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val preview = AppearancePreview(context).apply { config=draft }
        fun refreshPreview() { preview.config=draft }
        val body = column().apply { setPadding(dp(12), dp(4), dp(12), dp(12)) }
        fun note(parent: LinearLayout, text: String) {
            parent.addView(TextView(context).apply { this.text = text; textSize = 13f; setPadding(dp(8), dp(8), dp(8), dp(12)) })
        }
        var destination = body
        fun drawer(title: String): LinearLayout {
            val contents = column().apply { visibility = View.GONE; setPadding(dp(12), 0, dp(12), dp(12)) }
            val header = TextView(context).apply {
                text = "▸ $title"; textSize = 16f; minHeight = dp(52); isFocusable = true
                setPadding(dp(16), dp(16), dp(16), dp(16))
                contentDescription = "$title，已折叠"
                setOnClickListener {
                    val open = contents.visibility != View.VISIBLE
                    contents.visibility = if (open) View.VISIBLE else View.GONE
                    this.text = "${if (open) "▾" else "▸"} $title"
                    contentDescription = "$title，${if (open) "已展开" else "已折叠"}"
                }
            }
            destination.addView(column().apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(androidx.core.content.ContextCompat.getColor(context, com.osfans.trime.R.color.settings_surface))
                }
                addView(header); addView(contents)
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) })
            return contents
        }
        fun toggle(parent: LinearLayout, title: String, initial: Boolean, apply: (Boolean) -> Unit): SwitchMaterial {
            return SwitchMaterial(context).apply {
                text = title; minHeight = dp(48); isChecked = initial
                setOnCheckedChangeListener { _, checked -> apply(checked); refreshPreview() }
                parent.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        fun slider(parent: LinearLayout, title: String, initial: Float, min: Float, max: Float, step: Float, suffix: String, apply: (Float) -> Unit): Slider {
            val start = min + ((initial.coerceIn(min, max) - min) / step).roundToInt() * step
            val label = TextView(context).apply { text = "$title · ${start.coerceIn(min, max)}$suffix"; setPadding(0, dp(12), 0, 0) }
            parent.addView(label)
            return Slider(context).apply {
                valueFrom = min; valueTo = max; stepSize = step; value = start.coerceIn(min, max)
                contentDescription = title
                addOnChangeListener { _, value, _ -> label.text = "$title · $value$suffix"; apply(value); refreshPreview() }
                parent.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        fun backgroundButton(parent: LinearLayout, title: String, initial: String, image: Boolean, apply: (String) -> Unit) {
            var selected = initial
            parent.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                fun describe() = "$title · ${when { selected.isBlank() -> "跟随主题"; selected.startsWith("/") -> "图片"; selected == "#00000000" -> "透明"; else -> selected }}"
                text = describe()
                setOnClickListener { AppearancePicker.show(context, title, selected, image) { selected = it; text = describe(); apply(it); refreshPreview() } }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        fun styleControls(parent: LinearLayout, initial: SurfaceStyle, apply: (SurfaceStyle) -> Unit) {
            var style = initial
            fun update(next: SurfaceStyle) { style = next; apply(next); refreshPreview() }
            backgroundButton(parent, "背景", style.background, true) { update(style.copy(background = it)) }
            val overrideWidth = toggle(parent, "自定义边框粗细", style.borderWidth != null) { update(style.copy(borderWidth = if (it) style.borderWidth ?: 1f else null)) }
            val width = slider(parent, "边框粗细", style.borderWidth ?: 1f, 0f, 4f, .5f, " dp") { if (overrideWidth.isChecked) update(style.copy(borderWidth = it)) }
            width.isEnabled = overrideWidth.isChecked
            overrideWidth.setOnCheckedChangeListener { _, checked -> width.isEnabled = checked; update(style.copy(borderWidth = if (checked) width.value else null)) }
            backgroundButton(parent, "边框颜色", style.borderColor, false) { update(style.copy(borderColor = it)) }
            val overrideRadius = toggle(parent, "自定义圆角", style.radius != null) { update(style.copy(radius = if (it) style.radius ?: 10f else null)) }
            val radius = slider(parent, "圆角", style.radius ?: 10f, 0f, 32f, 1f, " dp") { if (overrideRadius.isChecked) update(style.copy(radius = it)) }
            radius.isEnabled = overrideRadius.isChecked
            overrideRadius.setOnCheckedChangeListener { _, checked -> radius.isEnabled = checked; update(style.copy(radius = if (checked) radius.value else null)) }
            toggle(parent, "动态模糊背景", style.blur) { update(style.copy(blur = it)) }
        }
        fun subSection(parent: LinearLayout, title: String): LinearLayout = SettingsDrawers(parent).section(title)
        fun gradientType(parent: LinearLayout, initial: String, apply: (String) -> Unit) {
            val codes = arrayOf("linear", "radial", "sweep")
            val labels = arrayOf("线性", "径向", "扫描")
            val input = MaterialAutoCompleteTextView(context).apply {
                inputType = InputType.TYPE_NULL
                setSimpleItems(labels)
                setText(labels[codes.indexOf(initial).coerceAtLeast(0)], false)
                setOnItemClickListener { _, _, position, _ -> apply(codes[position]) }
                setOnClickListener { showDropDown() }
            }
            parent.addView(TextInputLayout(context).apply {
                hint = "渐变类型"
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(input)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        fun effectsControls(parent: LinearLayout, initial: KeyEffects, includeFeedback: Boolean = true, apply: (KeyEffects) -> Unit) {
            var e = initial
            fun update(next: KeyEffects) { e = next; apply(next); refreshPreview() }

            val shadow = subSection(parent, "外阴影 · 投影 / 发光")
            toggle(shadow, "开启按键阴影", e.shadow.enabled) { update(e.copy(shadow = e.shadow.copy(enabled = it))) }
            backgroundButton(shadow, "阴影颜色", e.shadow.color, false) { update(e.copy(shadow = e.shadow.copy(color = it))) }
            slider(shadow, "模糊半径", e.shadow.blur, 0f, 32f, 1f, " dp") { update(e.copy(shadow = e.shadow.copy(blur = it))) }
            slider(shadow, "水平偏移", e.shadow.offsetX, -16f, 16f, 1f, " dp") { update(e.copy(shadow = e.shadow.copy(offsetX = it))) }
            slider(shadow, "垂直偏移", e.shadow.offsetY, -16f, 16f, 1f, " dp") { update(e.copy(shadow = e.shadow.copy(offsetY = it))) }
            slider(shadow, "扩散", e.shadow.spread, -8f, 8f, 1f, " dp") { update(e.copy(shadow = e.shadow.copy(spread = it))) }

            val inner = subSection(parent, "内阴影 · 凹陷质感")
            toggle(inner, "开启内阴影", e.innerShadow.enabled) { update(e.copy(innerShadow = e.innerShadow.copy(enabled = it))) }
            backgroundButton(inner, "内阴影颜色", e.innerShadow.color, false) { update(e.copy(innerShadow = e.innerShadow.copy(color = it))) }
            slider(inner, "模糊半径", e.innerShadow.blur, 0f, 24f, 1f, " dp") { update(e.copy(innerShadow = e.innerShadow.copy(blur = it))) }
            slider(inner, "水平偏移", e.innerShadow.offsetX, -12f, 12f, 1f, " dp") { update(e.copy(innerShadow = e.innerShadow.copy(offsetX = it))) }
            slider(inner, "垂直偏移", e.innerShadow.offsetY, -12f, 12f, 1f, " dp") { update(e.copy(innerShadow = e.innerShadow.copy(offsetY = it))) }

            val material = subSection(parent, "渐变与材质")
            toggle(material, "叠加渐变", e.gradient.enabled) { update(e.copy(gradient = e.gradient.copy(enabled = it))) }
            gradientType(material, e.gradient.type) { update(e.copy(gradient = e.gradient.copy(type = it))) }
            backgroundButton(material, "渐变起始色", e.gradient.startColor, false) { update(e.copy(gradient = e.gradient.copy(startColor = it))) }
            backgroundButton(material, "渐变结束色", e.gradient.endColor, false) { update(e.copy(gradient = e.gradient.copy(endColor = it))) }
            slider(material, "渐变角度", e.gradient.angle, 0f, 360f, 15f, "°") { update(e.copy(gradient = e.gradient.copy(angle = it))) }
            slider(material, "渐变不透明度", e.gradient.opacity.toFloat(), 0f, 100f, 1f, "%") { update(e.copy(gradient = e.gradient.copy(opacity = it.toInt()))) }
            toggle(material, "渐变边框", e.borderGradient.enabled) { update(e.copy(borderGradient = e.borderGradient.copy(enabled = it))) }
            backgroundButton(material, "边框起始色", e.borderGradient.startColor, false) { update(e.copy(borderGradient = e.borderGradient.copy(startColor = it))) }
            backgroundButton(material, "边框结束色", e.borderGradient.endColor, false) { update(e.copy(borderGradient = e.borderGradient.copy(endColor = it))) }
            slider(material, "边框角度", e.borderGradient.angle, 0f, 360f, 15f, "°") { update(e.copy(borderGradient = e.borderGradient.copy(angle = it))) }
            slider(material, "边框粗细", e.borderGradient.width, 0.5f, 8f, 0.5f, " dp") { update(e.copy(borderGradient = e.borderGradient.copy(width = it))) }
            toggle(material, "顶部高光 / 玻璃反光", e.gloss) { update(e.copy(gloss = it)) }
            slider(material, "高光强度", e.glossStrength.toFloat(), 0f, 60f, 1f, "%") { update(e.copy(glossStrength = it.toInt())) }
            toggle(material, "磨砂颗粒", e.noise) { update(e.copy(noise = it)) }
            slider(material, "颗粒强度", e.noiseStrength.toFloat(), 0f, 30f, 1f, "%") { update(e.copy(noiseStrength = it.toInt())) }
            toggle(material, "3D 键帽底边", e.edge3d) { update(e.copy(edge3d = it)) }
            slider(material, "底边深度", e.edgeDepth, 0f, 8f, 0.5f, " dp") { update(e.copy(edgeDepth = it)) }
            backgroundButton(material, "底边颜色", e.edgeColor, false) { update(e.copy(edgeColor = it)) }

            if(includeFeedback) {
            val press = subSection(parent, "按压反馈")
            slider(press, "按下缩放", e.pressScale, 0f, 10f, 0.5f, "%") { update(e.copy(pressScale = it)) }
            slider(press, "按下下沉", e.pressSink, 0f, 8f, 0.5f, " dp") { update(e.copy(pressSink = it)) }
            slider(press, "按下变暗", e.pressDarken.toFloat(), 0f, 60f, 1f, "%") { update(e.copy(pressDarken = it.toInt())) }
            slider(press, "动画时长", e.animationMs.toFloat(), 0f, 300f, 10f, " ms") { update(e.copy(animationMs = it.toInt())) }
            toggle(press, "按下波纹", e.ripple) { update(e.copy(ripple = it)) }
            backgroundButton(press, "波纹颜色", e.rippleColor, false) { update(e.copy(rippleColor = it)) }

            val text = subSection(parent, "文字阴影")
            toggle(text, "开启文字阴影", e.textShadow.enabled) { update(e.copy(textShadow = e.textShadow.copy(enabled = it))) }
            backgroundButton(text, "文字阴影颜色", e.textShadow.color, false) { update(e.copy(textShadow = e.textShadow.copy(color = it))) }
            slider(text, "文字阴影模糊", e.textShadow.blur, 0f, 16f, 0.5f, " dp") { update(e.copy(textShadow = e.textShadow.copy(blur = it))) }
            slider(text, "水平偏移", e.textShadow.offsetX, -8f, 8f, 0.5f, " dp") { update(e.copy(textShadow = e.textShadow.copy(offsetX = it))) }
            slider(text, "垂直偏移", e.textShadow.offsetY, -8f, 8f, 0.5f, " dp") { update(e.copy(textShadow = e.textShadow.copy(offsetY = it))) }
            }
        }
        if (onEffects != null) {
            effectsControls(body, draft.effects) { draft=draft.copy(effects=it) }
            val root=column().apply { addView(preview,LinearLayout.LayoutParams(-1,dp(136))); addView(ScrollView(context).apply { addView(body) },LinearLayout.LayoutParams(-1,dp((context.resources.displayMetrics.heightPixels/context.resources.displayMetrics.density-330).toInt().coerceIn(100,380)))) }
            return MaterialAlertDialogBuilder(context).setTitle("按钮独立特效").setView(root)
                .setPositiveButton("应用") { _,_ -> save(draft) }.setNegativeButton("取消",null)
                .setNeutralButton("清空特效") { _,_ -> save(draft.copy(effects=KeyEffects())) }.show()
        }
        val keyPage=column(); val toolbarPage=column(); val overallPage=column()
        val pages=listOf(keyPage,toolbarPage,overallPage)
        pages.forEachIndexed { index, page -> body.addView(page); page.visibility=if(index==0) View.VISIBLE else View.GONE }
        destination=overallPage
        // ── 面板背景（纯色 / 图片 / 透明）────────────────────────────────────────
        // 主题键盘没有「整套键盘背景」入口，全局外观里也缺这一项，想设「全透明 + 真模糊背板」
        // 就找不到地方。这里补上；自定义键盘走自己的整套键盘背景，不显示这一项。
        if (!customMode) {
            val panelBase = drawer("键盘整体 · 背景")
            backgroundButton(panelBase, "背景", draft.panelBackground, true) { value ->
                draft = draft.copy(panelBackground = value)
            }
        }
        // ── 真模糊背板 ───────────────────────────────────────────────────────
        // 和下面的渐变不是一类：渐变是我们自己涂色，这个是让系统在合成时把面板
        // 背后的 App 先糊掉，所以隔着键盘能看见后面但看不清。
        val backdropBox = drawer("键盘整体 · 真模糊背景（背板）")
        var panelBackdrop = draft.backdrop.sanitized()
        fun updateBackdrop(next: com.osfans.trime.ime.appearance.PanelBackdrop) {
            panelBackdrop = next.sanitized()
            draft = draft.copy(backdrop = panelBackdrop)
            refreshPreview()
        }
        // ── 全局背景渐变 ─────────────────────────────────────────────────────
        // 铺满整块面板：工具栏、候选栏、键盘区共用同一条，中间不断开。
        // 它是覆盖层，开着时纯色/透明/图片背景全部让位；关掉才恢复。
        val panelBg = drawer("键盘整体 · 全局背景（渐变）")
        var panelGradient = draft.gradient.sanitized()
        fun updateGradient(next: com.osfans.trime.ime.appearance.PanelGradient) {
            panelGradient = next.sanitized()
            draft = draft.copy(gradient = panelGradient)
            refreshPreview()
        }
        toggle(panelBg, "启用全局渐变（覆盖纯色 / 透明 / 图片背景）", panelGradient.enabled) {
            updateGradient(panelGradient.copy(enabled = it))
        }
        fun colorCell(caption: String, initial: String, fallback: String, apply: (String) -> Unit): MaterialButton {
            var value = initial
            fun label() = if (value.isBlank()) "$caption · 跟随日间" else "$caption · $value"
            return MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                isAllCaps = false
                minHeight = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(14)
                textSize = 12f
                text = label()
                setOnClickListener {
                    AppearancePicker.show(context, caption, value.ifBlank { fallback }, false) {
                        value = it
                        text = label()
                        apply(it)
                        refreshPreview()
                    }
                }
            }
        }
        fun colorPair(
            parent: LinearLayout,
            title: String,
            day: String,
            night: String,
            fallback: String,
            applyDay: (String) -> Unit,
            applyNight: (String) -> Unit,
        ) {
            parent.addView(TextView(context).apply { text = title; textSize = 13f; setPadding(0, dp(10), 0, dp(2)) })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(colorCell("日间", day, fallback, applyDay), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(
                colorCell("夜间", night, day.ifBlank { fallback }, applyNight),
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) },
            )
            parent.addView(row)
        }
        toggle(backdropBox, "启用真模糊背板（覆盖纯色 / 渐变 / 图片背景）", panelBackdrop.enabled) {
            updateBackdrop(panelBackdrop.copy(enabled = it))
        }
        slider(backdropBox, "模糊强度", panelBackdrop.strength.toFloat(), 0f, 100f, 5f, " %") {
            updateBackdrop(panelBackdrop.copy(strength = it.toInt()))
        }
        slider(backdropBox, "图片 / 视频 模糊覆盖度", panelBackdrop.imageAlpha.toFloat(), 0f, 100f, 5f, " %") {
            updateBackdrop(panelBackdrop.copy(imageAlpha = it.toInt()))
        }
        slider(backdropBox, "图片 / 视频 不透明度", panelBackdrop.imageOpacity.toFloat(), 0f, 100f, 5f, " %") {
            updateBackdrop(panelBackdrop.copy(imageOpacity = it.toInt()))
        }
        slider(backdropBox, "模糊后饱和度", draft.blurSaturation.toFloat(), 100f, 200f, 5f, " %") {
            draft = draft.copy(blurSaturation = it.toInt())
        }
        colorPair(panelBg, "渐变起始色", panelGradient.start, panelGradient.startNight,
            com.osfans.trime.ime.appearance.PanelGradient.DEFAULT_START,
            { updateGradient(panelGradient.copy(start = it)) },
            { updateGradient(panelGradient.copy(startNight = it)) })
        colorPair(panelBg, "渐变中间色", panelGradient.center, panelGradient.centerNight,
            com.osfans.trime.ime.appearance.PanelGradient.DEFAULT_CENTER,
            { updateGradient(panelGradient.copy(center = it)) },
            { updateGradient(panelGradient.copy(centerNight = it)) })
        colorPair(panelBg, "渐变结束色", panelGradient.end, panelGradient.endNight,
            com.osfans.trime.ime.appearance.PanelGradient.DEFAULT_END,
            { updateGradient(panelGradient.copy(end = it)) },
            { updateGradient(panelGradient.copy(endNight = it)) })
        run {
            val labels = arrayOf(
                "0 · 上 → 下", "1 · 右上 → 左下", "2 · 右 → 左", "3 · 右下 → 左上",
                "4 · 下 → 上", "5 · 左下 → 右上", "6 · 左 → 右", "7 · 左上 → 右下",
                "8 · 径向", "9 · 扫描",
            )
            val input = MaterialAutoCompleteTextView(context).apply {
                inputType = InputType.TYPE_NULL
                setSimpleItems(labels)
                setText(labels[panelGradient.direction.coerceIn(0, 9)], false)
                setOnItemClickListener { _, _, position, _ -> updateGradient(panelGradient.copy(direction = position)) }
                setOnClickListener { showDropDown() }
            }
            panelBg.addView(TextInputLayout(context).apply {
                hint = "渐变方向"
                setPadding(dp(4), dp(6), dp(4), dp(4))
                addView(input)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        slider(panelBg, "径向半径（仅方向 8）", panelGradient.radius.toFloat(), 10f, 200f, 5f, " %") {
            updateGradient(panelGradient.copy(radius = it.toInt()))
        }
        slider(panelBg, "扫描中心 X（仅方向 9）", panelGradient.sweepX * 100f, 0f, 100f, 5f, " %") {
            updateGradient(panelGradient.copy(sweepX = it / 100f))
        }
        slider(panelBg, "扫描中心 Y（仅方向 9）", panelGradient.sweepY * 100f, 0f, 100f, 5f, " %") {
            updateGradient(panelGradient.copy(sweepY = it / 100f))
        }
        // ────────────────────────────────────────────────────────────────────
        val shape = drawer("键盘整体 · 四角圆角")
        val corners = draft.keyboardCorners.takeIf { it.size == 4 }?.toMutableList() ?: MutableList(4) { 0f }
        // 全 0 的圆角裁出来和直角没有区别，等于没开：历史配置存成 [0,0,0,0] 时
        // 开关要显示成关闭，重新打开才会落一个有值的圆角。
        val cornersActive = draft.keyboardCorners.isNotEmpty() && corners.any { it > 0f }
        val cornerControls = column().apply { visibility = if (cornersActive) View.VISIBLE else View.GONE }
        // 四个角的滑杆都攥在手里：拖动「统一圆角」时一起改写，单角滑杆也跟着回显。
        val cornerSliders = mutableListOf<Slider>()
        var syncingCorners = false
        toggle(shape, "自定义键盘四角", cornersActive) {
            if (it) for (i in 0 until 4) if (corners[i] <= 0f) corners[i] = DEFAULT_KEYBOARD_CORNER_DP
            draft = draft.copy(keyboardCorners = if (it) corners.toList() else emptyList())
            cornerControls.visibility = if (it) View.VISIBLE else View.GONE
        }
        shape.addView(cornerControls)
        slider(cornerControls, "定义键盘圆角", corners.max(), 0f, MAX_KEYBOARD_CORNER_DP, 1f, " dp") { value ->
            if (syncingCorners) return@slider
            for (i in 0 until 4) corners[i] = value
            draft = draft.copy(keyboardCorners = corners.toList())
            syncingCorners = true
            cornerSliders.forEach { if (it.value != value) it.value = value }
            syncingCorners = false
        }
        listOf("左上角", "右上角", "右下角", "左下角").forEachIndexed { index, title ->
            cornerSliders += slider(cornerControls, title, corners[index], 0f, MAX_KEYBOARD_CORNER_DP, 1f, " dp") { value ->
                if (syncingCorners) return@slider
                corners[index] = value; draft = draft.copy(keyboardCorners = corners.toList())
            }
        }
        val applied = SurfaceAppearance.lastAppliedCorners
        note(
            cornerControls,
            "「定义键盘圆角」一次改四个角；下面四个滑杆可以单独调某一个角。圆角把整块键盘面板（含工具栏）裁成圆角，四角之外透出后面的应用。\n" +
                "键盘当前实际使用：" + if (applied.isEmpty()) "未读到（先点「应用」，再回键盘看一眼）" else applied.joinToString(" / ") { "${it.toInt()} dp" },
        )
        destination=keyPage
        val outline = drawer("键盘整体 · 描边")
        var borderColor = draft.keyboardBorderColor
        var borderWidth = draft.keyboardBorderWidth.takeIf { it > 0f } ?: 1f
        fun applyBorder() {
            draft = draft.copy(keyboardBorderColor = borderColor, keyboardBorderWidth = borderWidth)
        }
        backgroundButton(outline, "描边颜色", borderColor, false) { value ->
            borderColor = value
            applyBorder()
        }
        slider(outline, "描边宽度", borderWidth, 0f, 5f, 0.5f, " dp") { value ->
            borderWidth = value
            applyBorder()
        }
        var borderGlow = draft.keyboardBorderGlow.coerceIn(0, 100)
        slider(outline, "亮边强度", borderGlow.toFloat(), 0f, 100f, 5f, " %") { value ->
            borderGlow = value.toInt()
            draft = draft.copy(keyboardBorderGlow = borderGlow)
        }
        val sideMargin = drawer("键盘整体 · 留白（左 / 右 / 上 / 下）")
        var sideMarginValue = draft.keyboardSideMargin.takeIf { it.isFinite() } ?: 0f
        var topMarginValue = draft.keyboardTopMargin.takeIf { it.isFinite() } ?: 0f
        var bottomMarginValue = draft.keyboardBottomMargin.takeIf { it.isFinite() } ?: 0f
        fun applySideMargin() {
            draft = draft.copy(
                keyboardSideMargin = sideMarginValue,
                keyboardTopMargin = topMarginValue,
                keyboardBottomMargin = bottomMarginValue,
            )
        }
        slider(sideMargin, "左右留白", sideMarginValue, 0f, 20f, 1f, " dp") { value ->
            sideMarginValue = value
            applySideMargin()
        }
        slider(sideMargin, "上留白", topMarginValue, 0f, 20f, 1f, " dp") { value ->
            topMarginValue = value
            applySideMargin()
        }
        slider(sideMargin, "下留白", bottomMarginValue, 0f, 20f, 1f, " dp") { value ->
            bottomMarginValue = value
            applySideMargin()
        }
        val candBar = drawer("候选栏 · 高亮背景")
        var candRadius = draft.candidateHighlightRadius.takeIf { it.isFinite() } ?: -1f
        var candColor = draft.candidateHighlightColor
        fun applyCandBar() {
            draft = draft.copy(candidateHighlightRadius = candRadius, candidateHighlightColor = candColor)
        }
        slider(candBar, "高亮圆角", if (candRadius < 0f) 0f else candRadius, 0f, 32f, 1f, " dp") { value ->
            candRadius = value
            applyCandBar()
        }
        backgroundButton(candBar, "高亮背景色", candColor, false) { value ->
            candColor = value
            applyCandBar()
        }
        candBar.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "恢复默认（跟随主题）"
            isAllCaps = false
            setOnClickListener {
                candRadius = -1f; candColor = ""
                applyCandBar()
                android.widget.Toast.makeText(context, "已恢复默认，点「应用」后生效", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        val keyStyles=drawer("按键 · 背景、边框与模糊")
        fun renderKeyStyles() { keyStyles.removeAllViews(); styleControls(keyStyles,draft.keys) { draft=draft.copy(keys=it) } }
        renderKeyStyles()
        slider(keyStyles, "按键模糊强度", draft.keyBlur.toFloat(), 0f, 100f, 5f, " %") { value ->
            draft = draft.copy(keyBlur = value.toInt())
        }
        slider(keyStyles, "按键不透明度", draft.keyOpacity.toFloat(), 0f, 100f, 5f, " %") { value ->
            draft = draft.copy(keyOpacity = value.toInt())
        }
        val effects = drawer("按键特效 · 阴影与材质")
        val effectsBody = column()
        val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderEffects() {
            effectsBody.removeAllViews()
            effectsControls(effectsBody, draft.effects) { draft = draft.copy(effects = it) }
        }
        fun addPreset(label: String, preset: KeyEffects) {
            presetRow.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                setOnClickListener {
                    draft = draft.copy(effects = preset)
                    if(label=="通透" || label=="磨砂") {
                        draft=draft.copy(keys=draft.keys.copy(background=if(label=="通透") "#22FFFFFF" else "#55FFFFFF",blur=true),glassOpacity=if(label=="通透") 20 else 38)
                        renderKeyStyles()
                    }
                    renderEffects(); refreshPreview()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        addPreset("柔光", KeyEffects(
            shadow = KeyShadow(enabled = true, color = "#24000000", blur = 5f, offsetY = 1f),
            innerShadow = KeyInnerShadow(enabled = false, color = "#33000000", blur = 5f, offsetY = 1f),
            gradient = KeyGradient(enabled = true, type = "linear", startColor = "#26FFFFFF", endColor = "#00000000", angle = 90f, opacity = 70),
            borderGradient = KeyBorderGradient(enabled = true, startColor = "#66FFFFFF", endColor = "#00000000", angle = 90f, width = 1f),
            gloss = true,
            glossStrength = 16,
            edge3d = false,
            edgeDepth = 2.5f,
            edgeColor = "#33000000",
            pressScale = 3f,
            pressSink = 1.5f,
            pressDarken = 6,
            ripple = false,
            rippleColor = "#33FFFFFF",
            animationMs = 90,
        ))
        addPreset("通透", KeyEffects(gradient=KeyGradient(enabled=true,startColor="#24FFFFFF",endColor="#08FFFFFF"),borderGradient=KeyBorderGradient(enabled=true,startColor="#70FFFFFF",endColor="#18FFFFFF",width=.5f),gloss=true,glossStrength=8,pressScale=1.5f,animationMs=100))
        addPreset("磨砂", KeyEffects(gradient=KeyGradient(enabled=true,startColor="#30FFFFFF",endColor="#12FFFFFF"),noise=true,noiseStrength=3,borderGradient=KeyBorderGradient(enabled=true,width=.5f),pressScale=1f))
        addPreset("清空", KeyEffects())
        effects.addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(presetRow) }, LinearLayout.LayoutParams(-1, -2))
        effects.addView(effectsBody)
        renderEffects()
        destination=toolbarPage
        val toolbar = drawer("状态栏图标 · 独立或跟随按键")
        val toolbarControls = column()
        toggle(toolbar, "整排显示（不滑动）", draft.toolbarFitAll) { draft = draft.copy(toolbarFitAll = it) }
        toggle(toolbar, "跟随按键外观", draft.linkToolbar) { draft = draft.copy(linkToolbar = it); toolbarControls.visibility = if (it) View.GONE else View.VISIBLE }
        toolbarControls.visibility = if (draft.linkToolbar) View.GONE else View.VISIBLE
        toolbar.addView(toolbarControls)
        styleControls(toolbarControls, draft.toolbar) { draft = draft.copy(toolbar = it) }
        effectsControls(subSection(toolbarControls,"按钮特效 · 阴影、材质与按压"),draft.toolbarEffects) { draft=draft.copy(toolbarEffects=it) }
        val bar = drawer("整条状态栏 · 背景与模糊")
        toggle(bar, "状态栏与键盘共用背景", unified) { unified = it }
        toggle(bar, "共用背景时叠加原工具栏背景", draft.retainToolbarBackground) { draft=draft.copy(retainToolbarBackground=it) }
        styleControls(bar,draft.effectiveBar) { draft=draft.copy(bar=it,barBackground="",barBlur=false) }
        effectsControls(subSection(bar,"整条工具栏特效"),draft.barEffects,includeFeedback=false) { draft=draft.copy(barEffects=it) }
        destination=overallPage
        if (!customMode) {
            val floating = drawer("悬浮窗美化 · 背景与边框")
            backgroundButton(floating, "悬浮窗背景", draft.floatingBackground, true) { draft = draft.copy(floatingBackground = it) }
            val borderControls = column().apply { visibility = if (draft.floatingBorderWidth > 0f) View.VISIBLE else View.GONE }
            val overrideBorder = toggle(floating, "自定义悬浮窗边框", draft.floatingBorderWidth > 0f) {
                draft = draft.copy(floatingBorderWidth = if (it) (draft.floatingBorderWidth.takeIf { w -> w > 0f } ?: 1f) else 0f)
                borderControls.visibility = if (it) View.VISIBLE else View.GONE
            }
            floating.addView(borderControls)
            backgroundButton(borderControls, "悬浮窗边框颜色", draft.floatingBorderColor.ifBlank { "#FF888888" }, false) { draft = draft.copy(floatingBorderColor = it) }
            slider(borderControls, "悬浮窗边框粗细", draft.floatingBorderWidth, 0.5f, 4f, 0.5f, " dp") { draft = draft.copy(floatingBorderWidth = it) }
        }
        val glass = drawer("磨砂效果 · 强度与叠加")
        slider(glass, "模糊强度", draft.blurRadius.toFloat(), 2f, 32f, 1f, " dp") { draft = draft.copy(blurRadius = it.toInt()) }
        slider(glass, "背景叠加强度", draft.glassOpacity.toFloat(), 0f, 100f, 1f, "%") { draft = draft.copy(glassOpacity = it.toInt()) }
        val root=column().apply {
            setPadding(dp(12),dp(4),dp(12),0)
            addView(preview,LinearLayout.LayoutParams(-1,dp(136)))
            addView(TextView(context).apply { text="按住预览查看反馈 · 预览背景仅为示例"; textSize=12f; setPadding(dp(4),dp(8),0,dp(8)) })
            val tabs=com.google.android.material.button.MaterialButtonToggleGroup(context).apply { isSingleSelection=true; isSelectionRequired=true }
            listOf("按键","工具栏","整体").forEachIndexed { index,title ->
                tabs.addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id=View.generateViewId(); text=title; isAllCaps=false
                    // 小屏三等分时"工具栏"会被默认内缩和内边距挤成"工…"：去掉内缩、收紧内边距并允许自适应字号。
                    setInsetTop(0); setInsetBottom(0)
                    minWidth=0; minimumWidth=0
                    setPadding(dp(2),0,dp(2),0); letterSpacing=0f; maxLines=1
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                    setOnClickListener { pages.forEachIndexed { i,v -> v.visibility=if(i==index) View.VISIBLE else View.GONE } }
                },LinearLayout.LayoutParams(0,dp(44),1f))
            }
            tabs.check(tabs.getChildAt(0).id); addView(tabs)
            addView(AppearanceScrollView(context,(context.resources.displayMetrics.heightPixels/context.resources.displayMetrics.density-350).toInt().coerceIn(100,330)).apply { addView(body) },LinearLayout.LayoutParams(-1,-2))
        }
        return MaterialAlertDialogBuilder(context).setTitle(if (customMode) (layoutId?.let { com.osfans.trime.data.keymap.CustomKeyboards.get(it)?.name } ?: "自定义键盘默认") + " · 外观" else "主题键盘 · 外观")
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("恢复默认") { _, _ ->
                MaterialAlertDialogBuilder(context).setTitle("恢复默认外观？").setMessage("清除整体按键与状态栏外观设置，恢复跟随主题。")
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton("恢复") { _, _ -> unified = false; save(SurfaceAppearanceConfig()) }.show()
            }
            .setPositiveButton("应用") { _, _ -> save(draft) }.show()
    }
}
