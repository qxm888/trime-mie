/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.theme.ThemeManager

/**
 * Shared two-step action picker of the key editors:
 * restore / unbind / custom input + six curated categories
 * (letters, digits, symbols, function keys, combos), plus the active scheme
 * action catalog. Fully custom layouts use the independent built-in catalog.
 *
 * The callback receives the chosen token: `null` = restore default,
 * `""` = unbind, anything else = bind that token.
 */
object KeyTokenPicker {
    private const val OP_RESTORE = 0
    private const val OP_UNBIND = 1
    private const val OP_CUSTOM = 2
    private const val OP_CATEGORY = 100

    private data class Entry(val display: String, val token: String)

    fun show(
        activity: FragmentActivity,
        title: String,
        hasOverride: Boolean,
        onPicked: (String?) -> Unit,
    ) {
        val entries = mutableListOf<String>()
        val ops = mutableListOf<Int>()
        if (hasOverride) {
            entries.add(activity.getString(R.string.key_editor_restore_default))
            ops.add(OP_RESTORE)
        }
        entries.add(activity.getString(R.string.key_editor_unbind))
        ops.add(OP_UNBIND)
        entries.add(activity.getString(R.string.key_editor_custom))
        ops.add(OP_CUSTOM)
        val categories = categories(activity)
        categories.forEachIndexed { idx, (catTitle, _) ->
            entries.add(catTitle)
            ops.add(OP_CATEGORY + idx)
        }

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density + 0.5f).toInt()

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(entries.toTypedArray()) { _, which ->
                when (ops[which]) {
                    OP_RESTORE -> onPicked(null)
                    OP_UNBIND -> onPicked("")
                    OP_CUSTOM -> {
                        val layout =
                            TextInputLayout(activity).apply {
                                hint = activity.getString(R.string.key_editor_custom_hint)
                                setPadding(dp(20), dp(4), dp(20), dp(4))
                            }
                        val input =
                            TextInputEditText(layout.context).apply {
                                setSingleLine(true)
                                inputType = InputType.TYPE_CLASS_TEXT
                            }
                        layout.addView(
                            input,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.key_editor_custom)
                            .setView(layout)
                            .setPositiveButton(R.string.ok) { _, _ ->
                                input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(onPicked)
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }

                    else -> {
                        val catIdx = ops[which] - OP_CATEGORY
                        val (catTitle, items) = categories[catIdx]
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(catTitle)
                            .setItems(items.map { it.display }.toTypedArray()) { _, itemIdx ->
                                onPicked(items[itemIdx].token)
                            }
                            .setNegativeButton(R.string.key_editor_back) { _, _ ->
                                show(activity, title, hasOverride, onPicked)
                            }
                            .setOnCancelListener {
                                show(activity, title, hasOverride, onPicked)
                            }
                            .show()
                    }
                }
            }
            .show()
    }

    private fun categories(activity: FragmentActivity): List<Pair<String, List<Entry>>> {
        fun e(display: String, token: String = display) = Entry(display, token)
        fun fn(token: String, nameRes: Int) = Entry("$token · ${activity.getString(nameRes)}", token)
        return listOf(
            "窗口与候选栏" to com.osfans.trime.data.theme.Theme.BUILTIN_ACTIONS.map { (id, action) -> e(action.label, id) },
            activity.getString(R.string.key_editor_cat_lowercase) to ('a'..'z').map { e(it.toString()) },
            activity.getString(R.string.key_editor_cat_uppercase) to ('A'..'Z').map { e(it.toString()) },
            activity.getString(R.string.key_editor_cat_digits) to ('0'..'9').map { e(it.toString()) },
            activity.getString(R.string.key_editor_cat_symbols) to SYMBOLS.map { e(it) },
            activity.getString(R.string.key_editor_cat_function) to
                listOf(
                    e("内置语音（按住说话）", "VoiceInput"),
                    fn("Space", R.string.key_editor_key_space),
                    fn("Return", R.string.key_editor_key_return),
                    fn("BackSpace", R.string.key_editor_key_backspace),
                    fn("Tab", R.string.key_editor_key_tab),
                    fn("Escape", R.string.key_editor_key_esc),
                    fn("Left", R.string.key_editor_key_left),
                    fn("Right", R.string.key_editor_key_right),
                    fn("Up", R.string.key_editor_key_up),
                    fn("Down", R.string.key_editor_key_down),
                    fn("Home", R.string.key_editor_key_home),
                    fn("End", R.string.key_editor_key_end),
                    fn("Page_Up", R.string.key_editor_key_page_up),
                    fn("Page_Down", R.string.key_editor_key_page_down),
                    fn("Delete", R.string.key_editor_key_delete),
                ),
            activity.getString(R.string.key_editor_cat_combo) to
                listOf(
                    fn("Control+a", R.string.key_editor_key_select_all),
                    fn("Control+c", R.string.key_editor_key_copy),
                    fn("Control+v", R.string.key_editor_key_paste),
                    fn("Control+x", R.string.key_editor_key_cut),
                    fn("Control+z", R.string.key_editor_key_undo),
                    fn("Control+Left", R.string.key_editor_key_line_start),
                    fn("Control+Right", R.string.key_editor_key_line_end),
                    fn("Shift+Left", R.string.key_editor_key_select_left),
                    fn("Shift+Right", R.string.key_editor_key_select_right),
                ),
        ) + listOf(
            activity.getString(R.string.key_editor_cat_actions) to
                (if (activity is CustomKeyboardEditActivity) CustomKeyboards.standaloneTheme() else ThemeManager.activeTheme)
                    .presetKeys.map { (token, action) ->
                        Entry(action.label.ifEmpty { action.states.joinToString("/").ifEmpty { token } } + " · " + token, token)
                    },
        )
    }

    /** Behavior display names reused by both editors. */
    fun behaviorLabel(behavior: String): String =
        KeyBindingOverrides.EDITABLE_BEHAVIORS.firstOrNull { it.first == behavior }?.second?.labelZh ?: behavior

    private val SYMBOLS = listOf(
        "，", "。", "、", "；", "：", "？", "！", "…", "—", "·",
        "（", "）", "《", "》", "“", "”", "‘", "’",
        ",", ".", "?", "!", ";", ":", "'", "\"", "@", "#", "$", "%",
        "&", "*", "-", "_", "+", "=", "/", "\\", "|", "~", "<", ">", "[", "]", "{", "}", "^", "`",
    )
}
