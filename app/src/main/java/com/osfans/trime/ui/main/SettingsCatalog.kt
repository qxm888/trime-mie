/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import com.osfans.trime.R

/** Navigation and search share this directory. Preference keys always retain their original owner. */
object SettingsCatalog {
    data class Page(val id: String, val title: String, val summary: String, val parent: String = "", val icon: Int = R.drawable.ic_baseline_tune_24, val keys: List<String> = emptyList(), val collapsed: Boolean = false)
    val pages = listOf(
        Page("input", "输入与候选", "方案、词典、输入行为与候选", icon = R.drawable.ic_baseline_list_alt_24),
        Page("keyboard", "键盘", "布局、工具栏、单手、反馈与手势", icon = R.drawable.ic_baseline_keyboard_24),
        Page("appearance", "外观", "主题、背景、样式与字体", icon = R.drawable.ic_baseline_color_lens_24),
        Page("tools", "便捷功能", "语音输入、剪贴板与输入统计", icon = R.drawable.ic_clipboard_24),
        Page("data", "数据与备份", "存储位置、同步与导入导出", icon = R.drawable.ic_baseline_snippet_folder_24),
        Page("advanced", "高级与关于", "系统兼容、诊断、开发者与版本信息", icon = R.drawable.ic_baseline_more_horiz_24),
        Page("schema", "输入方案", "选择方案与部署配置", "input"),
        Page("dictionary", "用户词典", "管理与同步用户词典", "input"),
        Page("behavior", "输入行为", "行内编码、切换提示与自动填充", "input", keys = listOf("inline_preedit_mode", "ascii_switch_tips", "inline_suggestions", "use_soft_cursor")),
        Page("horizontal", "横向候选栏", "候选排列方式、竖屏与横屏行数", "input", keys = listOf("horizontal_candidate_mode", "max_span_count", "max_span_count_landscape")),
        Page("candidates", "悬浮候选窗", "窗口布局、位置、行数与悬浮球", "input"),
        Page("layout", "布局管理与按键编辑", "自定义键盘、当前布局默认外观与单键覆盖", "keyboard"),
        Page("toolbar", "工具栏", "编辑工具栏与状态栏显示", "keyboard", keys = listOf("hide_input_bar")),
        Page("floating", "单手与悬浮键盘", "单手宽度、左右手与横屏自动悬浮", "keyboard", keys = listOf("one_hand_width", "one_hand_side", "floating_keyboard", "floating_keyboard_auto_landscape")),
        Page("floating_detail", "悬浮键盘高级参数", "尺寸、保持比例、编码偏移与圆角", "floating", keys = listOf("floating_keyboard_width", "floating_keyboard_height", "floating_keyboard_keep_ratio", "floating_preedit_offset_x", "floating_preedit_offset_y", "floating_keyboard_corner_radius"), collapsed = true),
        Page("landscape", "横屏布局", "横屏模式与空格分割比例", "keyboard", keys = listOf("keyboard_landscape_mode", "keyboard_split_space")),
        Page("display", "键盘显示", "按键符号、提示与边缘触摸区域", "keyboard", keys = listOf("hide_key_symbol", "hide_key_hint", "expand_keypress_area")),
        Page("feedback", "按键反馈", "声音、振动、播报与按下气泡", "keyboard", keys = listOf("sound_on_keypress", "sound_volume", "custom_sound_effect_enabled", "custom_sound_effect_name", "vibrate_on_key_press", "vibrate_on_key_release", "vibrate_on_key_repeat", "vibration_duration", "vibration_amplitude", "speak_on_keypress", "speak_on_commit", "show_key_popup")),
        Page("gestures", "手势与长按", "滑动距离、速度、长按、重复与双击", "keyboard", keys = listOf("key_swipe_travel", "key_swipe_velocity", "key_long_press_timeout", "key_repeat_interval", "key_double_tap_timeout", "key_slide_step_size")),
        Page("hardware", "实体键盘快捷键", "Ctrl 与 Shift 组合键行为", "keyboard", collapsed = true),
        Page("theme", "主题与配色", "键盘主题、配色与跟随昼夜", "appearance"),
        Page("background", "主题视频背景", "选择视频；背景共用开关在各自全局外观中", "appearance", keys = listOf("keyboard_video_background")),
        Page("surface", "键盘与工具栏样式", "主题和每个自定义布局分别保存外观", "appearance"),
        Page("fonts", "字体", "按键、候选栏与工具栏字体", "appearance"),
        Page("app_theme", "应用外观", "仅设置应用的浅色 / 深色模式", "appearance", keys = listOf("ui_mode")),
        Page("voice", "语音输入", "在线识别、离线模型与系统语音服务", "tools", keys = listOf("preferred_voice_input")),
        Page("clipboard", "剪贴板", "监听、建议、清理与粘贴规则", "tools"),
        Page("stats", "输入统计", "输入字数与使用情况", "tools"),
        Page("profile", "数据位置与同步", "存储位置、后台同步与配置维护", "data"),
        Page("personal_data", "统计与候选备份", "跨设备恢复统计、候选调序与词典草稿", "data"),
        Page("transfer", "导入导出", "备份与恢复输入法数据", "data"),
        Page("compatibility", "系统兼容", "应用图标与系统手势区域", "advanced", keys = listOf("show_app_icon", "ignore_system_gesture_insets")),
        Page("diagnostics", "诊断与日志", "主题诊断与配置问题排查", "advanced"),
        Page("developer", "开发者", "开发与调试工具", "advanced"),
        Page("about", "关于与开源许可", "Trime Mie V2.1.4 · 版本与许可证", "advanced"),
    )
    fun page(id: String) = pages.first { it.id == id }
    fun path(page: Page): String = if (page.parent.isEmpty()) page.title else path(page(page.parent)) + " › " + page.title
}
