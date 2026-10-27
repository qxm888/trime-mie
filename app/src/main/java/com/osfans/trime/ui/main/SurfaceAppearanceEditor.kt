/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import com.osfans.trime.ime.appearance.SurfaceStyle

/** Staged appearance settings. Sections stay folded until requested; Apply commits all changes. */
object SurfaceAppearanceEditor {
    fun show(context: Context, changed: () -> Unit = {}): androidx.appcompat.app.AlertDialog {
        var draft = SurfaceAppearance.current()
        fun dp(n: Int) = (context.resources.displayMetrics.density * n).toInt()
        fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val body = column().apply { setPadding(dp(12), dp(4), dp(12), dp(12)) }
        fun note(parent: LinearLayout, text: String) {
            parent.addView(TextView(context).apply { this.text = text; textSize = 13f; setPadding(dp(8), dp(8), dp(8), dp(12)) })
        }
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
            body.addView(column().apply {
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
                setOnCheckedChangeListener { _, checked -> apply(checked) }
                parent.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        fun slider(parent: LinearLayout, title: String, initial: Float, min: Float, max: Float, step: Float, suffix: String, apply: (Float) -> Unit): Slider {
            val label = TextView(context).apply { text = "$title · ${initial.coerceIn(min, max)}$suffix"; setPadding(0, dp(12), 0, 0) }
            parent.addView(label)
            return Slider(context).apply {
                valueFrom = min; valueTo = max; stepSize = step; value = initial.coerceIn(min, max)
                contentDescription = title
                addOnChangeListener { _, value, _ -> label.text = "$title · $value$suffix"; apply(value) }
                parent.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
            }
        }
        fun backgroundButton(parent: LinearLayout, title: String, initial: String, image: Boolean, apply: (String) -> Unit) {
            var selected = initial
            parent.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                fun describe() = "$title · ${when { selected.isBlank() -> "跟随主题"; selected.startsWith("/") -> "图片"; selected == "#00000000" -> "透明"; else -> selected }}"
                text = describe()
                setOnClickListener { AppearancePicker.show(context, title, selected, image) { selected = it; text = describe(); apply(it) } }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        fun styleControls(parent: LinearLayout, initial: SurfaceStyle, apply: (SurfaceStyle) -> Unit) {
            var style = initial
            fun update(next: SurfaceStyle) { style = next; apply(next) }
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
        note(body, "分别设置按键和状态栏图标，或让它们共用外观。动态模糊只处理背景，文字与图标保持清晰。")
        styleControls(drawer("按键 · 背景、边框与模糊"), draft.keys) { draft = draft.copy(keys = it) }
        val toolbar = drawer("状态栏图标 · 独立或跟随按键")
        val toolbarControls = column()
        toggle(toolbar, "跟随按键外观", draft.linkToolbar) { draft = draft.copy(linkToolbar = it); toolbarControls.visibility = if (it) View.GONE else View.VISIBLE }
        note(toolbar, "开启后，状态栏图标共用按键的背景、边框、圆角和模糊设置；关闭后恢复独立设置。")
        toolbarControls.visibility = if (draft.linkToolbar) View.GONE else View.VISIBLE
        toolbar.addView(toolbarControls)
        styleControls(toolbarControls, draft.toolbar) { draft = draft.copy(toolbar = it) }
        val bar = drawer("整条状态栏 · 背景与模糊")
        backgroundButton(bar, "状态栏背景", draft.barBackground, true) { draft = draft.copy(barBackground = it) }
        toggle(bar, "动态模糊背景", draft.barBlur) { draft = draft.copy(barBlur = it) }
        note(bar, "图片和视频背景会透过磨砂层显示。需要一整块背景时，可同时开启“状态栏与键盘共用背景”。")
        val glass = drawer("磨砂效果 · 强度与叠加")
        slider(glass, "模糊强度", draft.blurRadius.toFloat(), 2f, 32f, 1f, " dp") { draft = draft.copy(blurRadius = it.toInt()) }
        slider(glass, "背景叠加强度", draft.glassOpacity.toFloat(), 0f, 100f, 1f, "%") { draft = draft.copy(glassOpacity = it.toInt()) }
        note(glass, "较低叠加强度更通透，较高时更接近原背景。设置对按键、状态栏图标和整条状态栏的模糊同时生效。")
        return MaterialAlertDialogBuilder(context).setTitle("按键与状态栏外观")
            .setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("恢复默认") { _, _ ->
                MaterialAlertDialogBuilder(context).setTitle("恢复默认外观？").setMessage("清除整体按键与状态栏外观设置，恢复跟随主题。")
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton("恢复") { _, _ -> SurfaceAppearance.save(SurfaceAppearanceConfig()); changed() }.show()
            }
            .setPositiveButton("应用") { _, _ -> SurfaceAppearance.save(draft); changed() }.show()
    }
}
