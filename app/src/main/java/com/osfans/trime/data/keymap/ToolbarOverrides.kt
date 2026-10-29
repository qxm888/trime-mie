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

    // Only obsolete aliases are filtered. Modern __mie_* actions are executable
    // and can be assigned to toolbar buttons just like keyboard keys.
    private val MODE_ACTIONS = setOf(
        BUILTIN_FLOATING, "floating_keyboard", "switch_floating_keyboard", "one_hand_mode", "switch_one_hand_mode",
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
        val id: String = "",
        val appearance: KeyAppearance.Style = KeyAppearance.Style(),
        val bindings: Map<String, String> = emptyMap(),
        val width: Int = 0,
        val height: Int = 0,
        val effects: com.osfans.trime.ime.appearance.KeyEffects? = null,
        val primary: Boolean = false,
        /** Frozen native toolbar definition for imported themes. Editor overrides still win. */
        val template: ToolBar.Button? = null,
        val iconSize: Int = 0, // dp; 0 fits available button area
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
            store.buttons.addAll(entries)
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

    fun primaryEntry(button: ToolBar.Button? = null) = Entry(action = button?.action?.ifEmpty { "__mie_menu" } ?: "__mie_menu", icon = button?.foreground?.style?.ifEmpty { "•••" } ?: "•••", primary = true, id = "primary-menu")

    fun editableEntries(bar: ToolBar, entries: List<Entry>): List<Entry> =
        if (entries.any { it.primary || it.id == "primary-hidden" }) entries else listOf(primaryEntry(bar.primaryButton)) + entries

    private fun applyEntries(
        toolBar: ToolBar,
        stored: List<Entry>,
        allowCustomActions: Boolean = false,
    ): ToolBar {
        val byAction = (listOfNotNull(toolBar.primaryButton) + toolBar.buttons).associateBy { it.action }
        var primary: ToolBar.Button? = toolBar.primaryButton
        var primaryVisible = true
        val result = mutableListOf<ToolBar.Button>()
        stored.forEach { entry ->
            if (entry.primary || entry.id == "primary-hidden") primaryVisible = entry.enabled && entry.id != "primary-hidden"
            if (isModeAction(entry.action)) return@forEach
            when {
                // 内置功能按钮不在主题里，按位置直接插入
                entry.action == BUILTIN_FLOATING -> {
                    if (entry.enabled) result += builtinFloatingButton(entry.icon)
                }

                else ->
                    (entry.template ?: byAction[entry.action] ?: if (allowCustomActions) ToolBar.Button(
                        action = entry.action, foreground = ToolBar.Button.Foreground(style = entry.icon),
                    ) else null)?.let { btn ->
                        if (entry.enabled) {
                            val style = entry.appearance
                            val configured = btn.copy(
                                action = entry.action,
                                foreground = btn.foreground.copy(
                                    style = entry.icon.ifEmpty { btn.foreground.style },
                                    optionStyles = if (entry.icon.isEmpty()) btn.foreground.optionStyles else emptyList(),
                                    normal = style.keyTextColor.ifBlank { btn.foreground.normal },
                                    highlight = style.hilitedKeyTextColor.ifBlank { btn.foreground.highlight },
                                    iconSize = entry.iconSize.coerceIn(0, 96),
                                    fontSize = style.keyTextSize.takeIf { it > 0 } ?: btn.foreground.fontSize,
                                ),
                                longPressAction = entry.bindings["longClick"] ?: btn.longPressAction,
                                bindings = entry.bindings,
                                appearance = style, effects = entry.effects?.sanitized(),
                                size = if (entry.width > 0 || entry.height > 0) listOf(entry.width.takeIf { it > 0 } ?: 44, entry.height.takeIf { it > 0 } ?: 44) else btn.size,
                            )
                            if (entry.primary) primary = configured else result += configured
                        }
                    }
            }
        }
        return toolBar.copy(primaryButton = primary, primaryVisible = primaryVisible, buttons = result)
    }

    /** Mode-aware selection: fully custom layouts never consult the theme overlay store. */
    fun forKeyboard(theme: Theme, runtimeId: String = "default"): ToolBar =
        withoutModeButtons(if (theme.isCustom) {
            val custom = CustomKeyboards.get(CustomKeyboards.resolveId(runtimeId))
            val bar = applyForCustomKeyboard(theme.toolBar, custom?.toolbar ?: StandaloneKeyboardTheme.defaultToolbar()).copy(alignment = custom?.toolbarAlignment ?: "legacy")
            val textColor = custom?.toolbarTextColor()
            if (textColor == null) bar else bar.copy(buttons = bar.buttons.map { button ->
                button.copy(foreground = button.foreground.copy(normal = button.appearance.keyTextColor.ifBlank { textColor }, highlight = button.appearance.hilitedKeyTextColor.ifBlank { textColor }))
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
