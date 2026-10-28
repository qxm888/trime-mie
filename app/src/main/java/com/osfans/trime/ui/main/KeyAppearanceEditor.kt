/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.CheckBox
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.KeyAppearance
import com.osfans.trime.data.keymap.KeyAppearance.Style as TextKey
import kotlinx.serialization.json.*

/** Accordion sections keep uncommon controls out of the initial dialog. */
private class SettingsDrawers(private val root: LinearLayout) {
    private val sections = mutableListOf<Pair<android.widget.TextView, LinearLayout>>()
    fun pruneEmpty() { sections.forEach { (header, content) -> if (content.childCount == 0) (header.parent as android.view.View).visibility = android.view.View.GONE } }
    fun section(title: String): LinearLayout {
        val context = root.context
        val header = android.widget.TextView(context).apply {
            text = "▸ $title"; textSize = 16f
            minHeight = (48 * resources.displayMetrics.density).toInt()
            val d = resources.displayMetrics.density
            setPadding((20*d).toInt(), (16*d).toInt(), (20*d).toInt(), (16*d).toInt())
            setTextColor(androidx.core.content.ContextCompat.getColor(context, com.osfans.trime.R.color.framework_text_primary))
            isFocusable = true
        }
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        header.setOnClickListener {
            val opening = content.visibility != android.view.View.VISIBLE
            sections.forEach { (h, body) -> body.visibility = android.view.View.GONE; h.text = "▸ ${h.tag}" }
            content.visibility = if (opening) android.view.View.VISIBLE else android.view.View.GONE
            header.text = "${if (opening) "▾" else "▸"} $title"
        }
        header.tag = title
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22 * resources.displayMetrics.density
                setColor(androidx.core.content.ContextCompat.getColor(context, com.osfans.trime.R.color.settings_surface))
            }
            addView(header); addView(content)
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { val gap = (8 * context.resources.displayMetrics.density).toInt(); setMargins(gap, gap/2, gap, gap/2) })
        sections += header to content
        return content
    }
}

/** One editor for keyboard defaults and individual key overrides. */
object KeyAppearanceEditor {
    fun show(context: Context, title: String, current: TextKey, fields: Set<String> = KeyAppearance.fields.keys, save: (TextKey) -> Unit) {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputs = linkedMapOf<String, () -> String>()
        val values = KeyAppearance.values(current)
        val drawers = SettingsDrawers(body)
        val groups = mapOf(
            "text" to drawers.section("文字、符号与字号"),
            "shape" to drawers.section("圆角与边框"),
            "normal" to drawers.section("常态颜色与背景"),
            "pressed" to drawers.section("按下颜色与背景"),
            "offset" to drawers.section("位置偏移"),
            "action" to drawers.section("弹出备选与手势绑定"),
        )
        KeyAppearance.fields.filterKeys { it in fields }.forEach { (name, label) ->
            val group = when {
                name == "popup" || name == "sendBindings" -> "action"
                name.contains("Offset") -> "offset"
                name.startsWith("hilited") -> "pressed"
                name.endsWith("Color") -> "normal"
                name == "roundCorner" || name == "keyBorder" -> "shape"
                else -> "text"
            }
            val body = groups.getValue(group)
            val value = values.getValue(name)
            if (name == "sendBindings") {
                val input = com.google.android.material.textfield.MaterialAutoCompleteTextView(context).apply {
                    inputType = android.text.InputType.TYPE_NULL
                    setSimpleItems(arrayOf("继承", "启用", "禁用"))
                    setText(when (value.jsonPrimitive.booleanOrNull) { true -> "启用"; false -> "禁用"; null -> "继承" }, false)
                    setOnClickListener { showDropDown() }
                }
                body.addView(TextInputLayout(context).apply { hint = label; setPadding(24, 4, 24, 4); addView(input) })
                inputs[name] = { when (input.text.toString()) { "启用" -> "true"; "禁用" -> "false"; else -> "" } }
            } else if (value is JsonPrimitive && value.booleanOrNull != null) {
                val field = CheckBox(context).apply { text = label; isChecked = value.boolean }
                inputs[name] = { field.isChecked.toString() }
                body.addView(field)
            } else {
                val input = TextInputEditText(context).apply {
                    setText(if (value is JsonArray) value.joinToString("\n") { it.jsonPrimitive.content } else value.jsonPrimitive.content)
                    if (name != "popup") setSingleLine(true)
                }
                body.addView(TextInputLayout(context).apply {
                    hint = label
                    setPadding(24, 4, 24, 4)
                    addView(input)
                })
                if (name.endsWith("Color")) {
                    body.addView(com.google.android.material.button.MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = if (name.endsWith("BackColor")) "预设颜色 · 透明 · 图片" else "选择预设颜色"
                        setOnClickListener { AppearancePicker.show(context, label, input.text.toString(), name.endsWith("BackColor")) { input.setText(it) } }
                    })
                }
                inputs[name] = { input.text?.toString().orEmpty() }
            }
        }
        drawers.pruneEmpty()
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("恢复继承") { _, _ -> save(TextKey()) }
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { KeyAppearance.parse(KeyAppearance.textValues(current) + inputs.mapValues { it.value() }) }.onSuccess {
                    save(it)
                    dialog.dismiss()
                }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
}

/** Per-keyboard geometry and navigation, with validation before the store changes. */
object KeyboardOptionsEditor {
    fun show(context: Context, current: com.osfans.trime.data.keymap.KeyboardOptions, save: (com.osfans.trime.data.keymap.KeyboardOptions) -> Unit) {
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val drawers = SettingsDrawers(form)
        val geometry = drawers.section("尺寸与键距")
        val behavior = drawers.section("中英模式与锁定")
        val navigation = drawers.section("关联键盘与标签")
        var section = geometry
        fun input(label: String, value: Any): TextInputEditText {
            val view = TextInputEditText(context).apply { setSingleLine(true); setText(value.toString()) }
            section.addView(TextInputLayout(context).apply { hint = label; setPadding(24, 4, 24, 4); addView(view) })
            return view
        }
        fun toggle(label: String, value: Boolean): CheckBox = CheckBox(context).apply { text = label; isChecked = value; section.addView(this) }
        val gap = input("横向键距 dp（0–32）", current.horizontalGap)
        val height = input("竖屏高度 %（50–200）", current.heightPercent)
        val landHeight = input("横屏高度 %（50–200）", current.landscapeHeightPercent)
        val split = input("横屏分割 %（0–100）", current.landscapeSplitPercent)
        section = behavior
        val ascii = toggle("初始英文模式", current.asciiMode == 1)
        val reset = toggle("每次切入重置中英模式", current.resetAsciiMode)
        val lock = toggle("锁定为主键盘", current.lock)
        section = navigation
        val uppercase = toggle("字母标签大写", current.uppercaseLabels)
        val asciiTarget = input("英文模式键盘 ID（留空保持当前）", current.asciiKeyboard)
        val landscapeTarget = input("横屏键盘 ID（留空保持当前）", current.landscapeKeyboard)
        val dialog = MaterialAlertDialogBuilder(context).setTitle("布局与切换行为")
            .setView(ScrollView(context).apply { addView(form) }).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("恢复默认") { _, _ -> save(com.osfans.trime.data.keymap.KeyboardOptions()) }
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    current.copy(horizontalGap = gap.text.toString().toInt(), heightPercent = height.text.toString().toInt(),
                        landscapeHeightPercent = landHeight.text.toString().toInt(), landscapeSplitPercent = split.text.toString().toInt(),
                        asciiMode = if (ascii.isChecked) 1 else 0, resetAsciiMode = reset.isChecked, lock = lock.isChecked,
                        uppercaseLabels = uppercase.isChecked, asciiKeyboard = asciiTarget.text.toString().trim(), landscapeKeyboard = landscapeTarget.text.toString().trim()).validated()
                }.onSuccess { save(it); dialog.dismiss() }
                    .onFailure { Toast.makeText(context, it.message ?: "请输入有效数字", Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
}
