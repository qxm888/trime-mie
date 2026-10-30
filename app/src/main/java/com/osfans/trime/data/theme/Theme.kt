/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.data.theme.model.Preedit
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.model.Window
import com.osfans.trime.ime.keyboard.KeyAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** 主题和样式配置  */
@Serializable
data class Theme(
    val name: String,
    val style: GeneralStyle = GeneralStyle(),
    val preedit: Preedit = Preedit(),
    val window: Window = Window(),
    val liquidKeyboard: LiquidKeyboard = LiquidKeyboard(),
    val presetKeys: Map<String, PresetKey> = emptyMap(),
    val presetKeyboards: Map<String, TextKeyboard> = emptyMap(),
    val presetColorSchemes: PresetColorSchemes = emptyMap(),
    val fallbackColors: Map<String, String> = emptyMap(),
    val toolBar: ToolBar = ToolBar(),
    @Transient val isCustom: Boolean = false,
    @Transient val customizationVersion: Long = 0,
) {
    val fonts by lazy { ThemeFonts(this) }

    @Transient
    private val actionCache = lazy {
        mutableMapOf<KeyActionToken, KeyAction>()
    }

    fun resolveAction(token: KeyActionToken) = actionCache.value.getOrPut(token) {
        KeyAction(token, BUILTIN_ACTIONS + presetKeys + ((token as? KeyActionToken.Plain)?.token?.let { value ->
            com.osfans.trime.data.keymap.BindingActions.resolve(value)?.let { mapOf(value to it) }
        } ?: emptyMap()))
    }

    fun resolveAction(tokenString: String) = resolveAction(KeyActionToken.Plain(tokenString))

    companion object {
        // 内置功能键使用 __mie_ 前缀：主题 YAML 里写旧参数（switch_* / floating_keyboard /
        // one_hand_mode）不再绑定这些功能——执行端只认 __mie_* 命令，旧 token 一律落空。
        val BUILTIN_ACTIONS: Map<String, PresetKey> = mapOf(
            "__mie_menu" to PresetKey(label = "功能菜单", send = "FUNCTION", command = "menu_keyboard"),
            "__mie_hide_symbols" to PresetKey(label = "符号显示开关", send = "FUNCTION", command = "switch_hide_key_symbol"),
            "__mie_hide_hints" to PresetKey(label = "注解显示开关", send = "FUNCTION", command = "switch_hide_key_hint"),
            "__mie_hide_bar" to PresetKey(label = "隐藏工具栏", send = "FUNCTION", command = "__mie_toggle_hide_bar"),
            "__mie_candidates_window" to PresetKey(label = "候选窗口切换", send = "FUNCTION", command = "__mie_toggle_candidates_window"),
            "__mie_floating_keyboard" to PresetKey(label = "悬浮键盘切换", send = "FUNCTION", command = "__mie_toggle_floating_keyboard"),
            "__mie_one_hand" to PresetKey(label = "单手模式", send = "FUNCTION", command = "__mie_toggle_one_hand"),
            "__mie_floating_ball" to PresetKey(label = "悬浮球开关", send = "FUNCTION", command = "__mie_toggle_floating_ball"),
            "__mie_input_stats_switch" to PresetKey(label = "统计记录开关", send = "FUNCTION", command = "__mie_toggle_input_stats"),
            "__mie_key_sound" to PresetKey(label = "按键声音开关", send = "FUNCTION", command = "__mie_toggle_sound"),
            "__mie_key_vibrate" to PresetKey(label = "按键振动开关", send = "FUNCTION", command = "__mie_toggle_vibrate"),
        )

        /**
         * Top-level keys a theme may declare: the sections reads plus
         * the metadata librime and the theme picker use. The theme linter
         * reports anything else, since the runtime ignores it.
         */
        internal val TOP_LEVEL_KEYS: Set<String> =
            setOf(
                "config_version",
                "name",
                "author",
                "description",
                "version",
                "style",
                "preedit",
                "window",
                "liquid_keyboard",
                "tool_bar",
                "preset_keys",
                "preset_keyboards",
                "preset_color_schemes",
                "fallback_colors",
            )
    }
}
