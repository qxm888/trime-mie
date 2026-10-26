/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.keymap

import android.content.Context
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText

/**
 * Toolbar editor overrides (候选栏工具栏): reorder / enable / hide the theme's
 * toolbar buttons. Stored in app-private storage; the theme file is untouched.
 *
 * A stored entry references a theme button by its `action` string (unique per
 * button in practice). Applying:
 *  - no customization → theme order as-is;
 *  - a configured list, including empty → exact enabled entries in stored order.
 * Custom keyboard lists never read this theme-mode store.
 */
object ToolbarOverrides {
    /** 历史悬浮动作ID，仅用于识别和过滤旧工具栏配置；当前入口在功能面板。 */
    const val BUILTIN_FLOATING = "__floating"
    const val BUILTIN_FLOATING_LABEL = "悬浮"

    // 旧参数与新的 __mie_* 内部名都从工具栏过滤：主题/旧配置里出现这些按钮一律不显示、
    // 不可添加（旧参数在按键执行端也已失效，见 CommonKeyboardActionListener）。
    private val MODE_ACTIONS = setOf(
        BUILTIN_FLOATING, "floating_keyboard", "switch_floating_keyboard", "one_hand_mode", "switch_one_hand_mode",
        "__mie_floating_keyboard", "__mie_toggle_floating_keyboard",
        "__mie_one_hand", "__mie_toggle_one_hand",
        "__mie_hide_bar", "__mie_toggle_hide_bar",
        "__mie_candidates_window", "__mie_toggle_candidates_window",
    )

    fun isModeAction(action: String): Boolean = action in MODE_ACTIONS

    private fun withoutModeButtons(bar: ToolBar, theme: Theme): ToolBar = bar.copy(
        primaryButton = bar.primaryButton?.takeUnless { isModeAction(it.action) || isModeAction(theme.presetKeys[it.action]?.command.orEmpty()) },
        buttons = bar.buttons.filterNot { isModeAction(it.action) || isModeAction(theme.presetKeys[it.action]?.command.orEmpty()) },
    )

    private val BUILTIN_NAMES = mapOf(BUILTIN_FLOATING to "悬浮键盘")

    @Serializable
    data class Entry(
        val action: String,
        val enabled: Boolean = true,
        /** 自定义图标/文字（emoji 或任意字符）；空 = 用主题自带的前景样式 */
        val icon: String = "",
    )

    @Serializable
    private data class Store(
        var version: Long = 1,
        var configured: Boolean = false,
        val buttons: MutableList<Entry> = mutableListOf(),
    )

    private val lock = Any()
    private var store = Store()
    private var appContext: Context? = null

    @Volatile
    private var dirty = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Bumped on every change so the bar can detect staleness. */
    val version: Long
        get() = synchronized(lock) { store.version }

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            runCatching {
                val file = storeFile()
                file.readAtomicText()?.let { contents ->
                    store = json.decodeFromString(Store.serializer(), contents)
                    if (store.buttons.isNotEmpty()) store.configured = true
                }
            }.onFailure { Timber.w(it, "ToolbarOverrides: load failed") }
        }
    }

    fun shutdown() {
        flush()
        ioScope.cancel()
    }

    fun flush(): Boolean = synchronized(lock) {
        if (!dirty || appContext == null) return@synchronized true
        runCatching {
            storeFile().writeAtomicText(json.encodeToString(store))
            dirty = false
        }.onFailure { Timber.w(it, "ToolbarOverrides: save failed") }.isSuccess
    }

    private fun storeFile(): File = File(appContext!!.filesDir, "toolbar_overrides.json")

    /** Current entries in display order (action + enabled). */
    fun entries(): List<Entry> = synchronized(lock) { store.buttons.toList() }

    fun setEntries(entries: List<Entry>) {
        synchronized(lock) {
            store.configured = true
            store.buttons.clear()
            store.buttons.addAll(entries.distinctBy { it.action })
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    fun hasCustomization(): Boolean = synchronized(lock) { store.configured }

    fun resetAll() {
        synchronized(lock) {
            store.buttons.clear()
            store.configured = false
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    /**
     * Applies the overrides to the theme's toolbar. Buttons are matched by
     * `action`; disabled and removed entries stay absent.
     */
    fun apply(toolBar: ToolBar): ToolBar {
        val (configured, stored) = synchronized(lock) { store.configured to store.buttons.toList() }
        return if (configured) applyEntries(toolBar, stored, allowCustomActions = true) else toolBar
    }

    /**
     * 专属工具栏严格采用本列表；空就是空，绝不继承另一方案。
     */
    fun applyForCustomKeyboard(
        toolBar: ToolBar,
        customToolbar: List<Entry>,
    ): ToolBar {
        return applyEntries(toolBar, customToolbar.map { it.copy() }, allowCustomActions = true)
    }

    private fun applyEntries(
        toolBar: ToolBar,
        stored: List<Entry>,
        allowCustomActions: Boolean = false,
    ): ToolBar {
        val byAction = toolBar.buttons.associateBy { it.action }
        val seen = mutableSetOf<String>()
        val result = mutableListOf<ToolBar.Button>()
        stored.forEach { entry ->
            if (isModeAction(entry.action)) return@forEach
            if (!seen.add(entry.action)) return@forEach
            when {
                // 内置功能按钮不在主题里，按位置直接插入
                entry.action == BUILTIN_FLOATING -> {
                    seen += entry.action
                    if (entry.enabled) result += builtinFloatingButton(entry.icon)
                }

                else ->
                    (byAction[entry.action] ?: if (allowCustomActions) ToolBar.Button(
                        action = entry.action, foreground = ToolBar.Button.Foreground(style = entry.icon),
                    ) else null)?.let { btn ->
                        seen += entry.action
                        if (entry.enabled) {
                            result +=
                                if (entry.icon.isNotEmpty()) {
                                    btn.copy(foreground = btn.foreground.copy(style = entry.icon))
                                } else {
                                    btn
                                }
                        }
                    }
            }
        }
        return toolBar.copy(buttons = result)
    }

    /** Mode-aware selection: fully custom layouts never consult the theme overlay store. */
    fun forKeyboard(theme: Theme, runtimeId: String = "default"): ToolBar =
        withoutModeButtons(if (theme.isCustom) {
            val custom = CustomKeyboards.get(CustomKeyboards.resolveId(runtimeId))
            val bar = applyForCustomKeyboard(theme.toolBar, custom?.toolbar ?: StandaloneKeyboardTheme.defaultToolbar())
            val textColor = custom?.toolbarTextColor()
            if (textColor == null) bar else bar.copy(buttons = bar.buttons.map { button ->
                button.copy(foreground = button.foreground.copy(normal = textColor, highlight = textColor))
            })
        } else {
            apply(theme.toolBar)
        }, theme)

    private fun builtinFloatingButton(icon: String?): ToolBar.Button =
        ToolBar.Button(
            action = BUILTIN_FLOATING,
            // 默认"悬浮"文字；Entry.icon 配置后与普通按钮一致的解析规则
            // （文字/emoji 直接显示，ic@ 走图标字体，图片文件名走本地图片）
            foreground = ToolBar.Button.Foreground(style = icon?.takeIf { it.isNotEmpty() } ?: BUILTIN_FLOATING_LABEL),
        )

    /** 编辑器显示用名称：内置按钮有固定名，主题按钮回退到 action。 */
    fun builtinName(action: String): String? = BUILTIN_NAMES[action]

    private fun scheduleSaveLocked() {
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(500)
            flush()
        }
    }
}
