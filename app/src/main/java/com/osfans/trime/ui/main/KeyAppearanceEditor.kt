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

/** One editor for keyboard defaults and individual key overrides. */
object KeyAppearanceEditor {
    fun show(context: Context, title: String, current: TextKey, save: (TextKey) -> Unit) {
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val inputs = linkedMapOf<String, () -> String>()
        val values = KeyAppearance.values(current)
        KeyAppearance.fields.forEach { (name, label) ->
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
                inputs[name] = { input.text?.toString().orEmpty() }
            }
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("恢复继承") { _, _ -> save(TextKey()) }
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { KeyAppearance.parse(inputs.mapValues { it.value() }) }.onSuccess {
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
        fun input(label: String, value: Any): TextInputEditText {
            val view = TextInputEditText(context).apply { setSingleLine(true); setText(value.toString()) }
            form.addView(TextInputLayout(context).apply { hint = label; setPadding(24, 4, 24, 4); addView(view) })
            return view
        }
        fun toggle(label: String, value: Boolean): CheckBox = CheckBox(context).apply { text = label; isChecked = value; form.addView(this) }
        val gap = input("横向键距 dp（0–32）", current.horizontalGap)
        val height = input("竖屏高度 %（50–200）", current.heightPercent)
        val landHeight = input("横屏高度 %（50–200）", current.landscapeHeightPercent)
        val split = input("横屏分割 %（0–100）", current.landscapeSplitPercent)
        val ascii = toggle("初始英文模式", current.asciiMode == 1)
        val reset = toggle("每次切入重置中英模式", current.resetAsciiMode)
        val lock = toggle("锁定为主键盘", current.lock)
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
