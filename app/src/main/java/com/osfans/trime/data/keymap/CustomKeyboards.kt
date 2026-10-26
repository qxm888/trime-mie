/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.keymap

import android.content.Context
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.PresetKey
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
import kotlinx.serialization.json.jsonObject
import timber.log.Timber
import java.io.File
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText
import java.util.UUID

/**
 * Two exclusive layout modes:
 *  - [MODE_THEME]: keyboards come from the theme file only (default; the
 *    per-key overlay from [KeyBindingOverrides] still applies on top).
 *  - [MODE_CUSTOM]: keyboards come from the user-defined layouts only; the
 *    theme's preset keyboards are not used for layout.
 *
 * The two never mix, so custom layouts cannot conflict with theme presets.
 */
object CustomKeyboards {
    const val ID_PREFIX = "custom_"
    const val MODE_THEME = "theme"
    const val MODE_CUSTOM = "custom"

    @Serializable
    data class CustomKey(
        val label: String = "",
        val width: Float = 10f,
        /** behavior field name ("click", "longClick", …) -> token; "" = unbound */
        val bindings: MutableMap<String, String> = mutableMapOf(),
        /** Legacy read compatibility; normalized copies use the exclusive background field. */
        val bgColor: String = "",
        val bgImage: String = "",
        val background: CustomBackground? = null,
        val skinId: String = "",
        val skin: com.osfans.trime.data.baiduskin.ImportedKeyStyle? = null,
    ) {
        fun resolvedBackground(): CustomBackground = (background ?: CustomBackground.fromLegacy(bgColor, bgImage, CustomBackground.KEY_COLOR)).normalized()
    }

    @Serializable
    data class CustomRow(
        val keys: MutableList<CustomKey> = mutableListOf(),
        var height: Int = 60,
    )

    @Serializable
    data class CustomKeyboard(
        var name: String = "",
        /** overall keyboard skin, drawn under every key */
        var bgColor: String = "",
        var bgImage: String = "",
        /** Dedicated exact toolbar list; empty means no toolbar buttons. */
        var toolbar: MutableList<ToolbarOverrides.Entry> = StandaloneKeyboardTheme.defaultToolbar(),
        val rows: MutableList<CustomRow> = mutableListOf(),
        var background: CustomBackground? = null,
        var toolbarBackground: CustomBackground? = null,
        var rowSpacing: Int = 4,
        var importedSkin: com.osfans.trime.data.baiduskin.ImportedSkin? = null,
        /** BDS coordinates define key positions, not the physical keyboard height. */
        var importedHeightPercent: Int = 100,
    ) {
        fun importedKeyboardHeight(context: Context): Int {
            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val baseDp = if (landscape) 180 else 240
            val natural = (baseDp * importedHeightPercent.coerceIn(70, 150) / 100f * context.resources.displayMetrics.density).toInt()
            // Keep room for the editor and candidate bar on short landscape displays.
            return if (landscape) minOf(natural, (context.resources.displayMetrics.heightPixels * 0.7f).toInt()) else natural
        }

        fun backgroundFor(context: Context): CustomBackground = if (background?.type == CustomBackground.Type.VIDEO) resolvedBackground() else importedSkin?.layout(context)?.background
            ?.let { CustomBackground(CustomBackground.Type.IMAGE, image = it) } ?: resolvedBackground()
        private fun hasToolbarOverride(): Boolean = toolbarBackground?.let { background ->
            importedSkin == null || background.type != CustomBackground.Type.IMAGE ||
                importedSkin!!.variants.values.none { it.toolbarBackground == background.image }
        } ?: false

        fun toolbarBackgroundFor(context: Context): CustomBackground? {
            if (hasToolbarOverride()) return toolbarBackground
            return importedSkin?.layout(context)?.toolbarBackground?.takeIf { it.isNotEmpty() }
                ?.let { CustomBackground(CustomBackground.Type.IMAGE, image = it) } ?: toolbarBackground
        }

        fun toolbarTextColor(): String? =
            if (importedSkin != null && !hasToolbarOverride()) null else toolbarBackground?.textColor()

        fun maxRowSpacing(): Int = minOf(32, (rows.minOfOrNull { it.height.coerceIn(24, 160) } ?: 60) / 2)

        fun resolvedBackground(): CustomBackground = (background ?: CustomBackground.fromLegacy(bgColor, bgImage, CustomBackground.KEYBOARD_COLOR)).normalized(CustomBackground.KEYBOARD_COLOR)
    }

    @Serializable
    private data class Store(
        var version: Long = 1,
        var mode: String = MODE_THEME,
        var activeId: String = "",
        val keyboards: MutableMap<String, CustomKeyboard> = mutableMapOf(),
    )

    private val lock = Any()
    private var store = Store()
    private var appContext: Context? = null

    @Volatile
    private var dirty = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Bumped on every change so cached keyboards can detect staleness. */
    val version: Long
        get() = synchronized(lock) { store.version }

    /** Current layout mode: [MODE_THEME] or [MODE_CUSTOM]. */
    val mode: String
        get() = synchronized(lock) { store.mode }

    /**
     * The custom keyboard used at runtime in [MODE_CUSTOM]: aliased as
     * "default" at merge time so system fallbacks (number fields, ".default"
     * evaluation, keyboard cycling start) land here. Empty = first keyboard.
     */
    var activeId: String
        get() = synchronized(lock) { store.activeId }
        set(value) {
            synchronized(lock) {
                if (store.activeId == value) return
                store.activeId = value
                store.version++
                dirty = true
                scheduleSaveLocked()
            }
        }

    fun isActive(id: String): Boolean = synchronized(lock) { effectiveActiveId() == id }

    /**
     * 运行时键盘 id → 真实自定义键盘 id 的统一映射。
     * mergeInto 会把当前使用的键盘额外别名为 "default"，皮肤/按键覆盖/缓存
     * 判断都必须经此解析，否则别名为 default 时查不到配置。
     */
    fun resolveId(runtimeId: String): String =
        synchronized(lock) {
            if (store.mode != MODE_CUSTOM) return@synchronized runtimeId
            when {
                store.keyboards.containsKey(runtimeId) -> runtimeId

                (runtimeId == "default" || runtimeId.isEmpty()) ->
                    store.activeId.takeIf { store.keyboards.containsKey(it) }
                        ?: store.keyboards.keys.firstOrNull()
                        ?: runtimeId

                else -> runtimeId
            }
        }

    /** 该运行时 id 是否（解析后）对应一个自定义键盘。 */
    fun isCustom(runtimeId: String): Boolean =
        synchronized(lock) { store.mode == MODE_CUSTOM && store.keyboards.containsKey(resolveId(runtimeId)) }

    /** Switches the layout mode; bumps the version so cached keyboards rebuild. */
    fun setMode(value: String) {
        require(value == MODE_THEME || value == MODE_CUSTOM)
        synchronized(lock) {
            if (value == MODE_CUSTOM) ensureStarterLocked()
            if (store.mode == value) return
            store.mode = value
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    fun init(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            runCatching {
                val file = storeFile()
                file.readAtomicText()?.let { contents ->
                    store = json.decodeFromString(Store.serializer(), contents)
                    store.keyboards.replaceAll { _, keyboard -> normalizedCopy(keyboard) }
                    if (store.mode !in setOf(MODE_THEME, MODE_CUSTOM)) store.mode = MODE_THEME
                    if (store.mode == MODE_CUSTOM) ensureStarterLocked()
                    store.version++
                    dirty = true
                    scheduleSaveLocked()
                }
            }.onFailure { Timber.w(it, "CustomKeyboards: load failed, starting fresh") }
        }
    }

    fun shutdown() {
        flush()
        ioScope.cancel()
    }

    /** False means the current in-memory version was not persisted. */
    fun flush(): Boolean = synchronized(lock) {
        if (!dirty || appContext == null) return@synchronized true
        runCatching {
            storeFile().writeAtomicText(json.encodeToString(store))
            dirty = false
        }.onFailure { Timber.w(it, "CustomKeyboards: save failed") }.isSuccess
    }

    private fun storeFile(): File = File(appContext!!.filesDir, "custom_keyboards.json")

    data class Entry(
        val id: String,
        val name: String,
        val rowCount: Int,
        val keyCount: Int,
    )

    fun list(): List<Entry> =
        synchronized(lock) {
            store.keyboards.map { (id, kb) ->
                Entry(id, kb.name, kb.rows.size, kb.rows.sumOf { it.keys.size })
            }.sortedBy { it.id }
        }

    fun get(id: String): CustomKeyboard? = synchronized(lock) { store.keyboards[id]?.let(::normalizedCopy) }

    private fun effectiveActiveId(): String = store.activeId.takeIf { it in store.keyboards } ?: store.keyboards.keys.firstOrNull().orEmpty()

    private fun normalizedCopy(kb: CustomKeyboard): CustomKeyboard = kb.copy(
        bgColor = "", bgImage = "", background = kb.resolvedBackground(),
        toolbar = kb.toolbar.toMutableList(),
        toolbarBackground = kb.toolbarBackground?.normalized(CustomBackground.KEYBOARD_COLOR),
        rowSpacing = kb.rowSpacing.coerceIn(0, kb.maxRowSpacing()),
        importedHeightPercent = kb.importedHeightPercent.coerceIn(70, 150),
        rows = kb.rows.ifEmpty { listOf(CustomRow()) }.map { row ->
            CustomRow(row.keys.map { key -> key.copy(
                width = key.width.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(100f) ?: 10f,
                bindings = key.bindings.toMutableMap(), bgColor = "", bgImage = "", background = key.resolvedBackground(),
            ) }.toMutableList(), height = row.height.coerceIn(24, 160))
        }.toMutableList(),
    )

    private fun starter(): CustomKeyboard {
        fun key(label: String, width: Float = 10f, token: String = label) = CustomKey(label, width, mutableMapOf("click" to token))
        return CustomKeyboard(name = "自定义键盘", rows = mutableListOf(
            CustomRow("qwertyuiop".map { key(it.toString()) }.toMutableList()),
            CustomRow("asdfghjkl".map { key(it.toString(), 100f / 9) }.toMutableList()),
            CustomRow((listOf(key("⇧", 15f, "Shift_L")) + "zxcvbnm".map { key(it.toString()) } + key("⌫", 15f, "BackSpace")).toMutableList()),
            CustomRow(mutableListOf(key("中/EN", 20f, "Mode_switch"), key("空格", 60f, "space"), key("↵", 20f, "Return"))),
        ))
    }

    private fun ensureStarterLocked() {
        if (store.keyboards.isNotEmpty()) return
        val id = ID_PREFIX + UUID.randomUUID()
        store.keyboards[id] = normalizedCopy(starter())
        store.activeId = id
        store.version++
        dirty = true
        scheduleSaveLocked()
    }

    /** Returns the new keyboard's id. */
    fun create(name: String): String {
        val id = ID_PREFIX + UUID.randomUUID()
        synchronized(lock) {
            store.keyboards[id] = normalizedCopy(CustomKeyboard(name = name.ifEmpty { "自定义键盘" }, rows = mutableListOf(CustomRow())))
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
        return id
    }

    fun rename(
        id: String,
        name: String,
    ) {
        synchronized(lock) {
            store.keyboards[id]?.let {
                it.name = name
                store.version++
                dirty = true
                scheduleSaveLocked()
            }
        }
    }

    fun delete(id: String) {
        synchronized(lock) {
            store.keyboards.remove(id)
            if (store.activeId == id) store.activeId = ""
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    /** Commit a completed BDS conversion in one store mutation. */
    fun addImported(keyboards: Map<String, CustomKeyboard>) {
        require(keyboards.isNotEmpty() && keyboards.keys.all { it.startsWith(ID_PREFIX) })
        synchronized(lock) {
            require(keyboards.keys.none { it in store.keyboards })
            val copies = keyboards.mapValues { normalizedCopy(it.value) }
            val previousVersion = store.version
            val previousDirty = dirty
            store.keyboards.putAll(copies)
            store.version++
            dirty = true
            if (!flush()) {
                copies.keys.forEach { store.keyboards.remove(it) }
                store.version = previousVersion
                dirty = previousDirty
                error("导入布局保存失败，请检查剩余存储空间")
            }
        }
    }

    /** Mutate one keyboard under the lock; returns false when it doesn't exist. */
    fun mutate(
        id: String,
        block: (CustomKeyboard) -> Unit,
    ): Boolean =
        synchronized(lock) {
            val kb = store.keyboards[id]?.let(::normalizedCopy) ?: return false
            block(kb)
            store.keyboards[id] = normalizedCopy(kb)
            store.version++
            dirty = true
            scheduleSaveLocked()
            true
        }

    // ----------------------------------------------------------------
    // runtime merge
    // ----------------------------------------------------------------

    /**
     * Layout source selection:
     *  - [MODE_THEME]: the theme is returned untouched (its presets drive layout).
     *  - [MODE_CUSTOM]: the theme's presets are **replaced** by the user-defined
     *    keyboards; the first one is also aliased as "default" so runtime
     *    fallbacks (number input, ".default" evaluation, …) keep working.
     *    With no custom keyboards defined the theme is returned as-is.
     */
    fun mergeInto(theme: Theme): Theme = if (mode == MODE_CUSTOM) standaloneTheme() else theme

    /** Creates the complete runtime configuration without loading a theme file. */
    fun standaloneTheme(): Theme = synchronized(lock) {
        ensureStarterLocked()
        val first = effectiveActiveId()
        val merged = linkedMapOf<String, TextKeyboard>()
        store.keyboards.entries.sortedBy { if (it.key == first) 0 else 1 }.forEach { (id, kb) ->
            merged[id] = kb.toTextKeyboard()
        }
        merged["default"] = merged.getValue(first)
        val base = StandaloneKeyboardTheme.base()
        val switches = store.keyboards.map { (id, kb) ->
            "Keyboard_$id" to PresetKey(label = kb.name, send = "Eisu_toggle", select = id)
        }.toMap()
        val imported = store.keyboards[first]?.importedSkin
        val dark = appContext?.resources?.configuration?.uiMode?.and(android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val text = if (imported != null && dark) "#FFF3F3F3" else "#FF202020"
        val colors = if (imported == null) base.presetColorSchemes else base.presetColorSchemes.mapValues { (_, scheme) ->
            scheme + listOf("candidate_text_color", "comment_text_color", "text_color", "label_color", "hilited_candidate_text_color").associateWith { text }
        }
        base.copy(presetKeyboards = merged, presetKeys = base.presetKeys + switches, presetColorSchemes = colors, customizationVersion = store.version)
    }

    /** Flat runtime index includes row-end and empty-row spacers. */
    fun customKeyAt(id: String, flatIndex: Int): CustomKey? = get(id)?.generatedKeys()?.getOrNull(flatIndex)?.first

    private fun token(value: String?): KeyActionToken? =
        value?.let { KeyActionToken.Plain(it) }

    fun CustomKey.toTextKey(keyboardBackground: CustomBackground): TextKeyboard.TextKey =
        TextKeyboard.TextKey(
            label = label,
            allowDefaultVoice = !bindings.containsKey("longClick"),
            width = if (width > 0f) width else 10f,
            keyBackColor = if (resolvedBackground().type == CustomBackground.Type.COLOR) resolvedBackground().color else "#00000000",
            hilitedKeyBackColor = if (resolvedBackground().type == CustomBackground.Type.COLOR) resolvedBackground().color else "#00000000",
            keyTextColor = resolvedBackground().textColor(keyboardBackground),
            hilitedKeyTextColor = resolvedBackground().textColor(keyboardBackground),
            click = bindings["click"]?.let { token(it) },
            longClick = bindings["longClick"]?.let { token(it) },
            swipeUp = bindings["swipeUp"]?.let { token(it) },
            swipeDown = bindings["swipeDown"]?.let { token(it) },
            swipeLeft = bindings["swipeLeft"]?.let { token(it) },
            swipeRight = bindings["swipeRight"]?.let { token(it) },
            doubleClick = bindings["doubleClick"]?.let { token(it) },
            lazyDoubleClick = bindings["lazyDoubleClick"]?.let { token(it) },
            composing = bindings["composing"]?.let { token(it) },
            hasMenu = bindings["hasMenu"]?.let { token(it) },
            paging = bindings["paging"]?.let { token(it) },
            combo = bindings["combo"]?.let { token(it) },
            ascii = bindings["ascii"]?.let { token(it) },
        )

    internal fun CustomKeyboard.generatedKeys(): List<Pair<CustomKey?, TextKeyboard.TextKey>> = buildList {
        rows.forEach { row ->
            val total = row.keys.sumOf { (it.width.takeIf { w -> w.isFinite() && w > 0 } ?: 10f).toDouble() }.toFloat()
            val scale = if (total > 100f) 100f / total else 1f
            row.keys.forEachIndexed { index, key ->
                val width = (key.width.takeIf { it.isFinite() && it > 0 } ?: 10f) * scale
                add(key to key.toTextKey(resolvedBackground()).copy(width = width, height = row.height.coerceIn(24, 160).toFloat(), newRow = index == 0))
            }
            val rest = 100f - total * scale
            if (rest > 0.01f || row.keys.isEmpty()) {
                add(null to TextKeyboard.TextKey(width = if (row.keys.isEmpty()) 100f else rest, height = row.height.coerceIn(24, 160).toFloat(), newRow = row.keys.isEmpty()))
            }
        }
    }

    fun CustomKeyboard.toTextKeyboard(): TextKeyboard = TextKeyboard(
        name = name, width = 10f, height = 60f, columns = -1,
        verticalGap = rowSpacing.coerceIn(0, maxRowSpacing()),
        keyboardHeight = rows.sumOf { it.height.coerceIn(24, 160) }.coerceAtLeast(24),
        keyboardHeightLand = rows.sumOf { it.height.coerceIn(24, 160) }.coerceAtLeast(24),
        asciiMode = if (importedSkin?.page == "en_26" || importedSkin?.page == "num_9") 1 else 0, resetAsciiMode = importedSkin != null, keys = generatedKeys().map { it.second },
    )

    /** Test helper: clears all keyboards (keeps the mode). */
    fun resetAllForTest() {
        synchronized(lock) {
            store.keyboards.clear()
            store.version++
        }
    }

    private fun scheduleSaveLocked() {
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(500)
            flush()
        }
    }

    // ----------------------------------------------------------------
    // 导出 / 导入（SAF 选文件，JSON 备份，不触碰主题文件）
    // ----------------------------------------------------------------

    /** Wrapper written to / read from user-picked files. */
    @Serializable
    private data class ExportFile(
        val format: String = EXPORT_FORMAT,
        val formatVersion: Int = 1,
        val exportedAt: Long = 0,
        val keyboards: MutableMap<String, CustomKeyboard> = mutableMapOf(),
    )

    /** Serializes all custom keyboards to a self-describing JSON document. */
    fun exportJson(now: Long = System.currentTimeMillis()): String =
        synchronized(lock) {
            json.encodeToString(
                ExportFile.serializer(),
                ExportFile(exportedAt = now, keyboards = store.keyboards),
            )
        }

    /** Snapshot only the explicit selection before opening the system file picker. */
    fun exportYaml(ids: List<String>): String {
        val selected = synchronized(lock) {
            require(ids.isNotEmpty() && ids.all { it in store.keyboards }) { "请选择现有键盘" }
            ids.distinct().associateWith { normalizedCopy(store.keyboards.getValue(it)) }
        }
        return CustomKeyboardYaml.encode(selected)
    }

    /** YAML is the user-facing format; existing JSON backups stay readable. */
    fun importDocument(text: String, replace: Boolean) {
        val trimmed = text.removePrefix("\uFEFF").trimStart()
        if (trimmed.startsWith('{')) importJson(trimmed, replace)
        else applyImportedLayouts(CustomKeyboardYaml.decode(trimmed), replace)
    }

    /**
     * Imports keyboards from a previously exported document. The layout mode
     * is deliberately not imported — importing layouts must not switch modes.
     *
     * @param replace true: imported keyboards fully replace current ones;
     *   false: same-id keyboards are overwritten, others kept
     * @throws IllegalArgumentException on an unrecognized file
     */
    fun importJson(
        text: String,
        replace: Boolean,
    ) {
        val file =
            runCatching { json.decodeFromString(ExportFile.serializer(), text) }.getOrElse {
                throw IllegalArgumentException("not a Trime custom keyboard backup", it)
            }
        require(file.format == EXPORT_FORMAT && file.formatVersion == 1) { "unsupported keyboard backup format" }
        require("keyboards" in json.parseToJsonElement(text).jsonObject) { "missing keyboard layouts" }
        require(file.keyboards.keys.all { it.startsWith(ID_PREFIX) }) { "invalid custom keyboard id" }
        applyImportedLayouts(file.keyboards, replace)
    }

    private fun applyImportedLayouts(layouts: Map<String, CustomKeyboard>, replace: Boolean) {
        val imported = layouts.mapValues { normalizedCopy(it.value) }
        synchronized(lock) {
            if (replace) {
                store.keyboards.clear()
            }
            store.keyboards.putAll(imported)
            if (store.activeId !in store.keyboards) store.activeId = store.keyboards.keys.firstOrNull().orEmpty()
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    private const val EXPORT_FORMAT = "trime-custom-keyboards"
}
