/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.ColorKey
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.ToolBar

/** The fully custom mode has no dependency on a YAML theme or its action aliases. */
object StandaloneKeyboardTheme {
    fun base(): Theme {
        val colors = ColorKey.entries.associate { key ->
            key.key to when {
                key.key.contains("text") || key.key.contains("symbol") || key.key.contains("label") -> "#FF111827"
                key.key.contains("border") || key.key.contains("separator") || key.key.contains("shadow") -> "#00000000"
                key.key == "root_background" || key.key == "keyboard_background" -> "#00000000"
                key.key.contains("hilited") -> "#FFDCE4ED"
                else -> "#FFF1F3F5"
            }
        }
        val presets = mapOf(
            "VoiceInput" to PresetKey(label = "内置语音", send = "VOICE_ASSIST"),
            "BibiVoice" to PresetKey(label = "内置语音", send = "VOICE_ASSIST"),
            "Space" to PresetKey(send = "space"),
            "space" to PresetKey(send = "space"),
            "BackSpace" to PresetKey(label = "⌫", send = "BackSpace", repeatable = true),
            "Return" to PresetKey(label = "↵", send = "Return"),
            "Shift_L" to PresetKey(label = "⇧", send = "Shift_L", shiftLock = "ascii_long"),
            "Mode_switch" to PresetKey(toggle = "ascii_mode", send = "SWITCH_CHARSET", states = listOf("中", "EN")),
            "Keyboard_switch" to PresetKey(label = "键盘", send = "Eisu_toggle", select = ".next"),
            "Keyboard_default" to PresetKey(label = "返回", send = "Eisu_toggle", select = ".default"),
            "paste" to PresetKey(label = "粘贴", send = "Control+v"),
            "copy" to PresetKey(label = "复制", send = "Control+c"),
            "cut" to PresetKey(label = "剪切", send = "Control+x"),
            "select_all" to PresetKey(label = "全选", send = "Control+a"),
            "clipboard" to PresetKey(label = "剪贴板", command = "clipboard_window"),
            "input_stats" to PresetKey(label = "统计", command = "input_stats"),
            "__mie_floating_keyboard" to PresetKey(label = "悬浮", command = "__mie_toggle_floating_keyboard"),
            "Hide" to PresetKey(label = "收起", send = "BACK"),
        )
        fun button(action: String, label: String) = ToolBar.Button(
            action = action, foreground = ToolBar.Button.Foreground(style = label),
        )
        return Theme(
            name = "自定义键盘",
            isCustom = true,
            style = GeneralStyle(
                keyboardHeight = 240, keyboardHeightLand = 180, keyHeight = 60, keyWidth = 10f,
                horizontalGap = 4, verticalGap = 4, roundCorner = 6f,
                keyTextSize = 22f, keyLongTextSize = 14f, symbolTextSize = 11f,
                candidateTextSize = 18f, candidateViewHeight = 32, commentHeight = 12,
            ),
            presetKeys = presets,
            presetColorSchemes = mapOf("default" to colors),
            toolBar = ToolBar(
                buttonSpacing = 8,
                buttons = listOf(
                    button("Mode_switch", "中/EN"), button("paste", "粘贴"),
                    button("clipboard", "剪贴板"), button("Keyboard_switch", "键盘"),
                    button("copy", "复制"), button("cut", "剪切"), button("select_all", "全选"),
                    button("input_stats", "统计"), button("Hide", "收起"),
                ),
            ),
        )
    }

    fun defaultToolbar(): MutableList<ToolbarOverrides.Entry> = mutableListOf(
        ToolbarOverrides.Entry("Mode_switch"), ToolbarOverrides.Entry("paste"),
    )
}
