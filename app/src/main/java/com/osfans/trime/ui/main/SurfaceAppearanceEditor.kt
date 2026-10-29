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
    fun show(context: Context, customMode: Boolean = SurfaceAppearance.isCustomMode(), layoutId: String? = null, initialEffects: KeyEffects? = null, onEffects: ((KeyEffects) -> Unit)? = null, changed: () -> Unit = {}): androidx.appcompat.app.AlertDialog {
        var draft = SurfaceAppearance.current(customMode, layoutId).let { if(initialEffects == null) it else it.copy(effects=initialEffects) }
        var unified = SurfaceAppearance.unifiedBackground(customMode, layoutId)
        fun save(config: SurfaceAppearanceConfig) {
            if(onEffects != null) { onEffects(config.effects); return }
            SurfaceAppearance.save(config, customMode, layoutId)
            val prefs = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard
            if (customMode && layoutId != null) {
                com.osfans.trime.data.keymap.CustomKeyboards.mutate(layoutId) { it.unifiedBackground = unified }
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
            note(shadow, "深色主题把颜色改成低透明度亮色、偏移归零，就是霓虹发光。阴影按按键尺寸缓存，不会每帧重算模糊。")

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
            note(material, "渐变叠在背景图/背景色之上；高光和颗粒适合玻璃、磨砂风格。")

            if(includeFeedback) {
            val press = subSection(parent, "按压反馈")
            slider(press, "按下缩放", e.pressScale, 0f, 10f, 0.5f, "%") { update(e.copy(pressScale = it)) }
            slider(press, "按下下沉", e.pressSink, 0f, 8f, 0.5f, " dp") { update(e.copy(pressSink = it)) }
            slider(press, "按下变暗", e.pressDarken.toFloat(), 0f, 60f, 1f, "%") { update(e.copy(pressDarken = it.toInt())) }
            slider(press, "动画时长", e.animationMs.toFloat(), 0f, 300f, 10f, " ms") { update(e.copy(animationMs = it.toInt())) }
            toggle(press, "按下波纹", e.ripple) { update(e.copy(ripple = it)) }
            backgroundButton(press, "波纹颜色", e.rippleColor, false) { update(e.copy(rippleColor = it)) }
            note(press, "缩放和下沉作用于整个按键（含文字）；波纹从触点向外扩散。")

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
        val shape = drawer("键盘整体 · 四角圆角")
        val corners = draft.keyboardCorners.takeIf { it.size == 4 }?.toMutableList() ?: MutableList(4) { 0f }
        val cornerControls = column().apply { visibility = if (draft.keyboardCorners.isEmpty()) View.GONE else View.VISIBLE }
        toggle(shape, "自定义键盘四角", draft.keyboardCorners.isNotEmpty()) {
            draft = draft.copy(keyboardCorners = if (it) corners.toList() else emptyList())
            cornerControls.visibility = if (it) View.VISIBLE else View.GONE
        }
        shape.addView(cornerControls)
        listOf("左上角", "右上角", "右下角", "左下角").forEachIndexed { index, title ->
            slider(cornerControls, title, corners[index], 0f, 64f, 1f, " dp") {
                corners[index] = it; draft = draft.copy(keyboardCorners = corners.toList())
            }
        }
        destination=keyPage
        val keyStyles=drawer("按键 · 背景、边框与模糊")
        fun renderKeyStyles() { keyStyles.removeAllViews(); styleControls(keyStyles,draft.keys) { draft=draft.copy(keys=it) } }
        renderKeyStyles()
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
        note(effects, "先选材质，再展开下方参数微调。预览随修改更新，点击应用后保存。模糊取自键盘背景，文字始终清晰。")
        renderEffects()
        destination=toolbarPage
        val toolbar = drawer("状态栏图标 · 独立或跟随按键")
        val toolbarControls = column()
        toggle(toolbar, "跟随按键外观", draft.linkToolbar) { draft = draft.copy(linkToolbar = it); toolbarControls.visibility = if (it) View.GONE else View.VISIBLE }
        note(toolbar, "开启后，状态栏图标共用按键的背景、边框、圆角和模糊设置；关闭后恢复独立设置。")
        toolbarControls.visibility = if (draft.linkToolbar) View.GONE else View.VISIBLE
        toolbar.addView(toolbarControls)
        styleControls(toolbarControls, draft.toolbar) { draft = draft.copy(toolbar = it) }
        effectsControls(subSection(toolbarControls,"按钮特效 · 阴影、材质与按压"),draft.toolbarEffects) { draft=draft.copy(toolbarEffects=it) }
        val bar = drawer("整条状态栏 · 背景与模糊")
        toggle(bar, "状态栏与键盘共用背景", unified) { unified = it }
        toggle(bar, "共用背景时叠加原工具栏背景", draft.retainToolbarBackground) { draft=draft.copy(retainToolbarBackground=it) }
        styleControls(bar,draft.effectiveBar) { draft=draft.copy(bar=it,barBackground="",barBlur=false) }
        note(bar, "图片和视频背景会透过磨砂层显示。需要一整块背景时，可同时开启“状态栏与键盘共用背景”。")
        effectsControls(subSection(bar,"整条工具栏特效"),draft.barEffects,includeFeedback=false) { draft=draft.copy(barEffects=it) }
        destination=overallPage
        val glass = drawer("磨砂效果 · 强度与叠加")
        slider(glass, "模糊强度", draft.blurRadius.toFloat(), 2f, 32f, 1f, " dp") { draft = draft.copy(blurRadius = it.toInt()) }
        slider(glass, "背景叠加强度", draft.glassOpacity.toFloat(), 0f, 100f, 1f, "%") { draft = draft.copy(glassOpacity = it.toInt()) }
        note(glass, "较低叠加强度更通透，较高时更接近原背景。设置对按键、状态栏图标和整条状态栏的模糊同时生效。")
        val root=column().apply {
            setPadding(dp(12),dp(4),dp(12),0)
            addView(preview,LinearLayout.LayoutParams(-1,dp(136)))
            addView(TextView(context).apply { text="按住预览查看反馈 · 预览背景仅为示例"; textSize=12f; setPadding(dp(4),dp(8),0,dp(8)) })
            val tabs=com.google.android.material.button.MaterialButtonToggleGroup(context).apply { isSingleSelection=true; isSelectionRequired=true }
            listOf("按键","工具栏","整体").forEachIndexed { index,title ->
                tabs.addView(MaterialButton(context,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id=View.generateViewId(); text=title; isAllCaps=false; setOnClickListener { pages.forEachIndexed { i,v -> v.visibility=if(i==index) View.VISIBLE else View.GONE } }
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
