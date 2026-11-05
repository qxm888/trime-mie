/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.view.KeyEvent
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.BindingActions
import com.osfans.trime.data.keymap.ComboKeys
import com.osfans.trime.data.keymap.ComboKeys.Definition

/** Editor for user-defined hardware key combinations (Ctrl+Enter, Ctrl+Alt+Del, ...). */
object ComboKeysEditor {
    private val BASE_KEYS = buildList {
        add("Enter" to KeyEvent.KEYCODE_ENTER); add("Space" to KeyEvent.KEYCODE_SPACE); add("Tab" to KeyEvent.KEYCODE_TAB)
        add("Backspace" to KeyEvent.KEYCODE_DEL); add("Delete" to KeyEvent.KEYCODE_FORWARD_DEL); add("Esc" to KeyEvent.KEYCODE_ESCAPE)
        add("↑" to KeyEvent.KEYCODE_DPAD_UP); add("↓" to KeyEvent.KEYCODE_DPAD_DOWN)
        add("←" to KeyEvent.KEYCODE_DPAD_LEFT); add("→" to KeyEvent.KEYCODE_DPAD_RIGHT)
        add("PageUp" to KeyEvent.KEYCODE_PAGE_UP); add("PageDown" to KeyEvent.KEYCODE_PAGE_DOWN)
        add("Home" to KeyEvent.KEYCODE_MOVE_HOME); add("End" to KeyEvent.KEYCODE_MOVE_END)
        add("," to KeyEvent.KEYCODE_COMMA); add("." to KeyEvent.KEYCODE_PERIOD)
        add("/" to KeyEvent.KEYCODE_SLASH); add(";" to KeyEvent.KEYCODE_SEMICOLON); add("'" to KeyEvent.KEYCODE_APOSTROPHE)
        add("-" to KeyEvent.KEYCODE_MINUS); add("=" to KeyEvent.KEYCODE_EQUALS)
        add("[" to KeyEvent.KEYCODE_LEFT_BRACKET); add("]" to KeyEvent.KEYCODE_RIGHT_BRACKET)
        add("\\" to KeyEvent.KEYCODE_BACKSLASH); add("`" to KeyEvent.KEYCODE_GRAVE)
        for (i in 0..25) add(('A' + i).toString() to KeyEvent.KEYCODE_A + i)
        for (i in 0..9) add(i.toString() to KeyEvent.KEYCODE_0 + i)
        for (i in 1..12) add("F$i" to KeyEvent.KEYCODE_F1 + i - 1)
    }

    fun show(activity: FragmentActivity) {
        val definitions = ComboKeys.list()
        MaterialAlertDialogBuilder(activity).setTitle("自定义组合键")
            .setItems((listOf("＋ 新建组合键") + definitions.map { "${ComboKeys.describe(it)}  →  ${BindingActions.label(it.action)}" }).toTypedArray()) { _, index ->
                edit(activity, definitions.getOrNull(index - 1))
            }.setNegativeButton("关闭", null).show()
    }

    private fun edit(activity: FragmentActivity, original: Definition?) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density + 0.5f).toInt()
        var meta = original?.meta ?: KeyEvent.META_CTRL_ON
        var keyCode = original?.keyCode ?: KeyEvent.KEYCODE_ENTER
        var action = original?.action.orEmpty()

        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 8, 32, 24) }
        val preview = TextView(activity).apply { setPadding(0, dp(8), 0, dp(4)); textSize = 15f }
        fun renderPreview() {
            preview.text = "当前组合：${ComboKeys.metaLabel(meta).ifEmpty { "（未选修饰键）" }} + ${ComboKeys.keyLabel(keyCode)}" +
                (if (action.isEmpty()) "\n动作：未选择" else "\n动作：${BindingActions.label(action)}")
        }
        val boxes = listOf(
            "Ctrl" to KeyEvent.META_CTRL_ON, "Alt" to KeyEvent.META_ALT_ON,
            "Shift" to KeyEvent.META_SHIFT_ON, "Meta / Win" to KeyEvent.META_META_ON,
        ).map { (name, bit) ->
            CheckBox(activity).apply {
                text = name; isChecked = meta and bit != 0
                setOnCheckedChangeListener { _, checked ->
                    meta = if (checked) meta or bit else meta and bit.inv(); renderPreview()
                }
            }
        }
        val modifiersRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        boxes.forEach { modifiersRow.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        body.addView(modifiersRow)
        body.addView(TextView(activity).apply { text = "主键"; setPadding(0, dp(12), 0, dp(4)) })
        val keySpinner = Spinner(activity).apply {
            adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, BASE_KEYS.map { it.first })
            setSelection(BASE_KEYS.indexOfFirst { it.second == keyCode }.coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    keyCode = BASE_KEYS[pos].second; renderPreview()
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
        }
        body.addView(keySpinner)
        val actionButton = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false; text = if (action.isEmpty()) "选择动作" else "动作：${BindingActions.label(action)}"
            setOnClickListener {
                KeyTokenPicker.show(activity, "选择组合键动作", false) { next ->
                    if (next != null) { action = next; text = "动作：${BindingActions.label(action)}"; renderPreview() }
                }
            }
        }
        body.addView(actionButton, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) })
        val label = TextInputEditText(activity).apply { setSingleLine(); setText(original?.label.orEmpty()) }
        body.addView(TextInputLayout(activity).apply { hint = "备注名（可选，仅用于列表显示）"; addView(label) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) })
        body.addView(TextView(activity).apply {
            text = "组合键在应用收到按键之前拦截：触发后应用不会再收到这次按键。至少需要一个修饰键。"
            setPadding(0, dp(12), 0, 0); textSize = 12f; alpha = 0.7f
        })
        renderPreview()
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (original == null) "新建组合键" else "编辑组合键")
            .setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("删除") { _, _ ->
                original ?: return@setNeutralButton
                runCatching { ComboKeys.save(ComboKeys.list().filterNot { it.id == original.id }) }
            }.create()
        // 校验失败时保持对话框打开（覆盖自动关闭），输入内容不丢失。
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val definitions = ComboKeys.list().toMutableList()
                val entry = Definition(
                    id = original?.id ?: java.util.UUID.randomUUID().toString(),
                    meta = meta, keyCode = keyCode, action = action,
                    label = label.text?.toString()?.trim().orEmpty(),
                )
                definitions.removeAll { it.id == entry.id }
                definitions.add(entry)
                runCatching { ComboKeys.save(definitions) }
                    .onSuccess { dialog.dismiss() }
                    .onFailure { android.widget.Toast.makeText(activity, it.message, android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
        dialog.show()
    }
}
