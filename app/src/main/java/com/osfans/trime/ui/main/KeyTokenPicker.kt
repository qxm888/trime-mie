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
        standalone: Boolean = activity is CustomKeyboardEditActivity,
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
        val categories = categories(activity, standalone)
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
                                hint = "按键名、组合键或已定义的功能名称"
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
                                input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { onPicked(com.osfans.trime.data.keymap.NamedFunctions.normalize(it)) }
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }

                    else -> {
                        val catIdx = ops[which] - OP_CATEGORY
                        val (catTitle, items) = categories[catIdx]
                        showCategory(activity,catTitle,items,onBack={ show(activity,title,hasOverride,standalone,onPicked) }) { chosen ->
                                val selected = chosen.token
                                if (selected.startsWith("__parameter:")) {
                                    var command = selected.removePrefix("__parameter:")
                                    val input = TextInputEditText(activity).apply { hint = if(command=="__custom_command") "命令名，参数（用逗号分隔，可无参数）" else "参数（可留空）"; setSingleLine() }
                                    MaterialAlertDialogBuilder(activity).setTitle(command).setView(input)
                                        .setPositiveButton("绑定") { _, _ -> val raw=input.text.toString()
                                            if(command=="__rime_option") onPicked(com.osfans.trime.data.keymap.BindingActions.token(com.osfans.trime.data.theme.model.PresetKey(send="Mode_switch",toggle=raw.trim(),label=raw.trim())))
                                            else if(command=="__custom_command") { val parts=raw.split(',',limit=2); onPicked(com.osfans.trime.data.keymap.BindingActions.command(parts[0].trim(),parts.getOrNull(1).orEmpty())) }
                                            else onPicked(com.osfans.trime.data.keymap.BindingActions.command(command,raw,label=chosen.display + if(raw.isBlank()) "" else " · ${raw.take(40)}")) }
                                        .setNegativeButton(android.R.string.cancel, null).show()
                                } else onPicked(selected)
                        }
                    }
                }
            }
            .show()
    }

    private fun showCategory(activity: FragmentActivity, title: String, entries: List<Entry>, onBack: () -> Unit, picked: (Entry) -> Unit) {
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(24,8,24,8) }
        val query=TextInputEditText(activity).apply { hint="搜索功能名称"; setSingleLine() }
        body.addView(TextInputLayout(activity).apply { addView(query) })
        val list=android.widget.ListView(activity)
        var visible=entries
        val adapter=android.widget.ArrayAdapter(activity,android.R.layout.simple_list_item_1,entries.map { it.display }.toMutableList())
        list.adapter=adapter
        val height=((activity.resources.displayMetrics.heightPixels/activity.resources.displayMetrics.density-300).toInt().coerceIn(140,400)*activity.resources.displayMetrics.density).toInt()
        body.addView(list,LinearLayout.LayoutParams(-1,height))
        val dialog=MaterialAlertDialogBuilder(activity).setTitle(title).setView(body)
            .setNegativeButton("返回") { _,_ -> onBack() }.setOnCancelListener { onBack() }.create()
        query.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                val text=s.toString().trim()
                visible=entries.filter { it.display.contains(text,true) }
                adapter.clear(); adapter.addAll(visible.map { it.display }); adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        list.setOnItemClickListener { _,_,index,_ -> visible.getOrNull(index)?.let { selected -> dialog.dismiss(); picked(selected) } }
        dialog.show()
    }

    private fun categories(activity: FragmentActivity, standalone: Boolean): List<Pair<String, List<Entry>>> {
        fun e(display: String, token: String = display) = Entry(display, token)
        fun fn(token: String, nameRes: Int) = Entry("$token · ${activity.getString(nameRes)}", token)
        return listOf(
            "自定义功能" to com.osfans.trime.data.keymap.NamedFunctions.list().map { e(it.name, com.osfans.trime.data.keymap.NamedFunctions.PREFIX + it.id) },
            "键盘跳转" to (listOf(".default" to "返回主键盘", ".last" to "返回上个键盘", ".prior" to "上一个键盘", ".next" to "下一个键盘", ".last_lock" to "返回锁定键盘") + CustomKeyboards.list().map { it.id to it.name }).map { (id,name) -> e(name, com.osfans.trime.data.keymap.BindingActions.keyboard(id, name)) },
            "内置语音控制" to listOf(e("开始录音",com.osfans.trime.data.keymap.BindingActions.command("__mie_voice_start",label="开始录音")),e("结束录音并识别",com.osfans.trime.data.keymap.BindingActions.command("__mie_voice_finish",label="结束录音并识别")),e("取消录音",com.osfans.trime.data.keymap.BindingActions.command("__mie_voice_cancel",label="取消录音"))),
            "方案选项与中英切换" to (listOf(e("自定义方案选项…","__parameter:__rime_option")) + listOf("ascii_mode" to "中英切换", "full_shape" to "全角半角", "simplification" to "简繁切换", "ascii_punct" to "中西文标点").map { (option,name) -> e(name,com.osfans.trime.data.keymap.BindingActions.token(com.osfans.trime.data.theme.model.PresetKey(send="Mode_switch",toggle=option,label=name))) }),
            "Rime 按键（全部）" to com.osfans.trime.core.RimeKeyMapping::class.java.fields.filter { it.name.startsWith("RimeKey_") }.sortedBy { it.name }.map { e(it.name.removePrefix("RimeKey_")) },
            "输入法与设置" to listOf(e("设置", "SETTINGS"),e("选择输入方案", "MENU"),e("选择配色", "PROG_RED"),e("切换系统输入法", "LANGUAGE_SWITCH"),e("内置语音输入", "VOICE_ASSIST")),
            "系统按键（全部）" to android.view.KeyEvent::class.java.fields.filter { it.name.startsWith("KEYCODE_") && it.type == Int::class.javaPrimitiveType }.sortedBy { it.name }.map { field -> e(field.name.removePrefix("KEYCODE_"), field.name.removePrefix("KEYCODE_")) },
            "文本与组合输入" to listOf(e("直接上屏文本", "__parameter:commit"), e("日期时间", "__parameter:date"), e("自定义命令 / Intent", "__parameter:__custom_command")),
            "同文命令" to linkedMapOf("menu_keyboard" to "功能菜单", "clipboard_window" to "剪贴板 / 收藏", "liquid_keyboard" to "符号键盘（名称 / 类型）", "set_color_scheme" to "指定配色（配色ID）", "set_theme" to "指定主题（主题名）", "broadcast" to "发送广播（Action）", "commit" to "直接上屏文字", "date" to "格式化日期（格式）", "run" to "运行命令", "apply" to "处理选中文字", "share_text" to "分享文字", "select_candidate" to "选择候选（序号）", "input_stats" to "输入统计").map { (command,name) -> e(name, "__parameter:" + command) },
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
                (com.osfans.trime.data.keymap.StandaloneKeyboardTheme.base().presetKeys +
                    if (standalone) emptyMap() else ThemeManager.activeThemeOrNull()?.presetKeys.orEmpty()).map { (token, action) ->
                        Entry(action.label.ifEmpty { action.states.joinToString("/").ifEmpty { token } } + " · " + token, com.osfans.trime.data.keymap.BindingActions.token(action))
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
