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
    /** 内置五色键盘中默认使用的方案：樱花粉。 */
    const val BUILTIN_DEFAULT_ID = "custom_pink"

    /** 内置五色键盘的版本；改过内置 YAML 后 +1，升级时自动刷新内置 id。 */
    private const val BUILTIN_VERSION = 6

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
        /** Native key appearance; gestures continue to come from bindings. */
        val appearance: KeyAppearance.Style = KeyAppearance.Style(),
    ) {
        fun resolvedBackground(): CustomBackground = (background ?: CustomBackground.fromLegacy(bgColor, bgImage, CustomBackground.KEY_COLOR)).normalized()
    }

    @Serializable
    data class CustomRow(
        val keys: MutableList<CustomKey> = mutableListOf(),
        var height: Int = 60,
        var alignment: String = "left",
        /** 本行行边距：null 跟随键盘级行边距，0–48 dp。 */
        var margin: Int? = null,
    )

    @Serializable
    data class CustomKeyboard(
        var name: String = "",
        /** Namespaced macro dependencies copied from a theme. */
        var importedActions: Map<String, PresetKey> = emptyMap(),
        /** overall keyboard skin, drawn under every key */
        var bgColor: String = "",
        var bgImage: String = "",
        /** Dedicated exact toolbar list; empty means no toolbar buttons. */
        var toolbar: MutableList<ToolbarOverrides.Entry> = StandaloneKeyboardTheme.defaultToolbar(),
        val rows: MutableList<CustomRow> = mutableListOf(),
        var background: CustomBackground? = null,
        var toolbarBackground: CustomBackground? = null,
        var toolbarAlignment: String = "legacy",
        /** Overlay for every key in this layout; empty restores individual backgrounds. */
        var allKeysBackground: String = "",
        var rowSpacing: Int = 4,
        /** 行左右边距：键盘整体收进屏幕两侧的留白，0–48 dp。 */
        var rowMargin: Int = 0,
        var keyboardHeightDp: Int = 0,
        var landscapeHeightDp: Int = 0,
        var toolbarHeightDp: Int = 0,
        /** 非空 = 某主键盘的次级键盘（跳转用），不出现在主列表。 */
        var secondaryOf: String = "",
        var surfaceAppearance: com.osfans.trime.ime.appearance.SurfaceAppearanceConfig? = null,
        var unifiedBackground: Boolean? = null,
        var typography: Map<String, FontSpec> = emptyMap(),
        var floatingCandidates: com.osfans.trime.ime.appearance.FloatingCandidateStyle? = null,
        var keyAppearance: KeyAppearance.Style = KeyAppearance.Style(),
        var options: KeyboardOptions = KeyboardOptions(),
        var importedSkin: com.osfans.trime.data.baiduskin.ImportedSkin? = null,
        /** BDS coordinates define key positions, not the physical keyboard height. */
        var importedHeightPercent: Int = 100,
    ) {
        fun importedKeyboardHeight(context: Context): Int {
            val landscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val density = context.resources.displayMetrics.density
            val heightPx = if (landscape && landscapeHeightDp > 0) (landscapeHeightDp * density).toInt()
                else if (!landscape && keyboardHeightDp > 0) (keyboardHeightDp * density).toInt()
                else (if (landscape) 180 else 240) * importedHeightPercent.coerceIn(70, 150) / 100 * density.toInt()
            // Keep room for the editor and candidate bar on short landscape displays.
            return if (landscape) minOf(heightPx, (context.resources.displayMetrics.heightPixels * 0.7f).toInt()) else heightPx
        }

        /** The height the keyboard actually renders at, in dp, honoring the dp override first. */
        fun effectiveToolbarHeightDp(): Int =
            if (toolbarHeightDp > 0) toolbarHeightDp
            else StandaloneKeyboardTheme.base().style.run { candidateViewHeight + commentHeight }

        fun effectivePortraitHeightDp(): Int = if (keyboardHeightDp > 0) keyboardHeightDp
            else if (importedSkin != null) 240 * importedHeightPercent / 100
            else rows.sumOf { it.height.coerceIn(24, 160) }.coerceAtLeast(24) * options.heightPercent / 100
        fun effectiveLandscapeHeightDp(): Int = if (landscapeHeightDp > 0) landscapeHeightDp
            else if (importedSkin != null) 180 * importedHeightPercent / 100
            else rows.sumOf { it.height.coerceIn(24, 160) }.coerceAtLeast(24) * options.landscapeHeightPercent / 100

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
        /** 内置五色键盘的版本：升级时只刷新内置 id，用户自建键盘不动。 */
        var builtinVersion: Int = 0,
        /** 是否已经把内置键盘提供给这个库（升级老用户也会补一次）。 */
        var builtinsOffered: Boolean = false,
        /** 扩展内置键盘（表情/符号分类页）已补齐的版本。 */
        var builtinExtrasVersion: Int = 0,
    )

    private val lock = Any()
    private var store = Store()
    private var appContext: Context? = null

    @Volatile
    private var dirty = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    // 只写非默认值：内置五色 300 套键盘的存档从 13.5MB 降到 2MB 级，启动解析更快。
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

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
            appContext = com.osfans.trime.util.storageContext(context)
            runCatching {
                val file = storeFile()
                val contents = file.readAtomicText()
                if (contents == null) {
                    // 全新安装：内置五色键盘（默认樱花粉），并切到自定义布局模式。
                    seedBuiltinKeyboardsLocked()
                } else {
                    store = json.decodeFromString(Store.serializer(), contents)
                    store.keyboards.replaceAll { _, keyboard -> normalizedCopy(keyboard) }
                    if (store.mode !in setOf(MODE_THEME, MODE_CUSTOM)) store.mode = MODE_THEME
                    if (store.mode == MODE_CUSTOM) ensureStarterLocked()
                    store.version++
                    dirty = true
                    scheduleSaveLocked()
                    offerBuiltinKeyboardsLocked()
                    refreshBuiltinKeyboardsLocked()
                }
            }.onFailure { Timber.w(it, "CustomKeyboards: load failed, starting fresh") }
            // 内置图标每次启动补齐（素材目录可能此时才可用），已存在的不覆盖。
            runCatching { ensureBuiltinIcons(context) }.onFailure { Timber.w(it, "CustomKeyboards: builtin icons failed") }
        }
    }

    private fun decodeBuiltin(context: android.content.Context, resId: Int): Map<String, CustomKeyboard>? =
        runCatching {
            context.resources.openRawResource(resId)
                .bufferedReader().use { CustomKeyboardYaml.decode(it.readText()) }
        }.onFailure { Timber.w(it, "CustomKeyboards: builtin asset %d decode failed", resId) }.getOrNull()

    /** 扩展内置键盘（表情/符号分类页）放后台补，避免首次启动主线程卡住。 */
    private fun seedBuiltinExtrasInBackground(context: android.content.Context) {
        if (synchronized(lock) { store.builtinExtrasVersion >= BUILTIN_VERSION }) return
        ioScope.launch {
            val extras = decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_extra) ?: return@launch
            synchronized(lock) {
                var added = 0
                extras.forEach { (id, keyboard) ->
                    if (!store.keyboards.containsKey(id)) {
                        store.keyboards[id] = normalizedCopy(keyboard)
                        added++
                    }
                }
                store.builtinExtrasVersion = BUILTIN_VERSION
                store.version++
                dirty = true
                scheduleSaveLocked()
                Timber.i("CustomKeyboards: added %d builtin extras", added)
            }
        }
    }

    /** 内置五色键盘：首次安装时写入并默认使用樱花粉。 */
    private fun seedBuiltinKeyboardsLocked() {
        val context = appContext ?: return
        val layouts = decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_keyboards)
        if (layouts.isNullOrEmpty()) return
        layouts.forEach { (id, keyboard) -> store.keyboards[id] = normalizedCopy(keyboard) }
        store.mode = MODE_CUSTOM
        store.activeId = BUILTIN_DEFAULT_ID.takeIf { layouts.containsKey(it) } ?: layouts.keys.first()
        store.builtinVersion = BUILTIN_VERSION
        store.builtinsOffered = true
        store.version++
        dirty = true
        scheduleSaveLocked()
        seedBuiltinExtrasInBackground(context)
    }

    /**
     * 老用户升级时补上内置五色键盘：只插入库里还没有的内置 id，
     * 不覆盖已有键盘、不改当前布局模式和已选键盘。
     */
    private fun offerBuiltinKeyboardsLocked() {
        if (store.builtinsOffered) return
        val context = appContext ?: return
        val layouts = decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_keyboards)
        if (layouts.isNullOrEmpty()) return
        var added = 0
        layouts.forEach { (id, keyboard) ->
            if (!store.keyboards.containsKey(id)) {
                store.keyboards[id] = normalizedCopy(keyboard)
                added++
            }
        }
        store.builtinsOffered = true
        if (store.builtinVersion < BUILTIN_VERSION) store.builtinVersion = BUILTIN_VERSION
        if (added > 0) {
            store.version++
            dirty = true
            scheduleSaveLocked()
            Timber.i("CustomKeyboards: offered %d builtin keyboards", added)
        }
        seedBuiltinExtrasInBackground(context)
    }

    /**
     * 升级时刷新内置五色键盘：只替换内置 id（custom_<色>_*），用户自建键盘保持不变。
     * 内置键盘是模板，用户对模板的改动会在升级时被覆盖。
     */
    private fun refreshBuiltinKeyboardsLocked() {
        if (store.builtinVersion >= BUILTIN_VERSION) return
        val context = appContext ?: return
        val layouts = decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_keyboards)
        if (layouts.isNullOrEmpty()) return
        // 只在已经内置过这套键盘的库里刷新，不把模板强塞进用户自建的列表。
        if (store.keyboards.keys.none { it in layouts.keys }) return
        // 整体刷新：替换已有内置 id，并补齐新版本新增的内置 id（如符号/表情分类页）。
        layouts.forEach { (id, keyboard) -> store.keyboards[id] = normalizedCopy(keyboard) }
        store.builtinVersion = BUILTIN_VERSION
        store.version++
        dirty = true
        scheduleSaveLocked()
        seedBuiltinExtrasInBackground(context)
    }

    /** 内置键盘的图标解压到素材目录（.mie/键盘背景/icons），已存在的不覆盖。 */
    private fun ensureBuiltinIcons(context: Context) {
        val dir = File(com.osfans.trime.core.data.MieUserDirectory.assetFolder(context, "custom_kb_bg"), "icons")
        val marker = File(dir, ".builtin_v1")
        // 标记存在但关键图标缺失（例如早期解压失败）时重新解压一次。
        if (marker.isFile && File(dir, "tb_menu.png").isFile && File(dir, "mint_shift.png").isFile) return
        if (!dir.isDirectory && !dir.mkdirs()) return
        runCatching {
            context.resources.openRawResource(com.osfans.trime.R.raw.mie_builtin_icons).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory) {
                            val name = entry.name.substringAfterLast('/')
                            if (name.isNotEmpty()) {
                                val out = File(dir, name)
                                if (!out.exists()) out.outputStream().use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            marker.createNewFile()
        }.onFailure { Timber.w(it, "CustomKeyboards: could not unpack builtin icons") }
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
        val secondaryOf: String = "",
    )

    fun list(): List<Entry> =
        synchronized(lock) {
            store.keyboards.map { (id, kb) ->
                Entry(id, kb.name, kb.rows.size, kb.rows.sumOf { it.keys.size }, kb.secondaryOf)
            }.sortedBy { it.id }
        }

    fun get(id: String): CustomKeyboard? = synchronized(lock) { store.keyboards[id]?.let(::normalizedCopy) }

    private fun effectiveActiveId(): String = store.activeId.takeIf { it in store.keyboards } ?: store.keyboards.keys.firstOrNull().orEmpty()

    private fun normalizedCopy(kb: CustomKeyboard): CustomKeyboard = kb.copy(
        options = kb.options.validated(),
        importedActions = kb.importedActions.toMap(),
        surfaceAppearance=kb.surfaceAppearance?.sanitized(),
        floatingCandidates=kb.floatingCandidates?.sanitized(),
        typography=kb.typography.filterKeys { it in com.osfans.trime.data.theme.FontOverrides.roles }.mapValues { (_,spec) -> spec.copy(size=if(spec.size.isFinite()) spec.size.coerceIn(0f,200f) else 0f) },
        bgColor = "", bgImage = "", background = kb.resolvedBackground(),
        toolbar = kb.toolbar.toMutableList(),
        toolbarAlignment = kb.toolbarAlignment.takeIf { it == "legacy" || it in ButtonAlignment.choices } ?: "legacy",
        toolbarBackground = kb.toolbarBackground?.normalized(CustomBackground.KEYBOARD_COLOR),
        rowSpacing = kb.rowSpacing.coerceIn(0, kb.maxRowSpacing()),
        rowMargin = kb.rowMargin.coerceIn(0, 48),
        importedHeightPercent = kb.importedHeightPercent.coerceIn(70, 150),
        rows = kb.rows.ifEmpty { listOf(CustomRow()) }.map { row ->
            CustomRow(row.keys.map { key -> key.copy(
                width = key.width.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(100f) ?: 10f,
                bindings = key.bindings.toMutableMap(), bgColor = "", bgImage = "", background = key.resolvedBackground(),
            ) }.toMutableList(), height = row.height.coerceIn(24, 160), alignment = ButtonAlignment.normalized(row.alignment))
        }.toMutableList().onEachIndexed { index, row ->
            row.margin = kb.rows.getOrNull(index)?.margin?.coerceIn(0, 48)
        },
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
    fun create(name: String): String = createLocked(name, "")

    private fun createLocked(name: String, secondaryOf: String): String {
        val id = ID_PREFIX + UUID.randomUUID()
        synchronized(lock) {
            store.keyboards[id] = normalizedCopy(CustomKeyboard(name = name.ifEmpty { "自定义键盘" }, rows = mutableListOf(CustomRow()), secondaryOf = secondaryOf))
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

    fun createSecondary(name: String, primaryId: String): String = synchronized(lock) {
        require(store.keyboards[primaryId] != null) { "主键盘不存在" }
        val id = createLocked(name, secondaryOf = primaryId)
        id
    }

    fun listSecondaries(primaryId: String): List<Entry> = list().filter { it.secondaryOf == primaryId }

    fun delete(id: String) {
        val removed = mutableListOf<CustomKeyboard>()
        synchronized(lock) {
            store.keyboards.remove(id)?.let(removed::add)
            // 次级键盘属于整套键盘：主键盘删除时一并删除。
            store.keyboards.keys.filter { store.keyboards[it]?.secondaryOf == id }.forEach { key ->
                store.keyboards.remove(key)?.let(removed::add)
            }
            if (store.activeId == id) store.activeId = ""
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
        // 键盘删了，它导入的素材（背景图 / 视频 / 百度皮肤目录 / 按键图片 / 工具栏图片）同步删除。
        removed.forEach(::deleteOwnedFiles)
    }

    /** 收集键盘模型里引用的、位于咩版自有素材目录内的文件/目录并删除。 */
    private fun deleteOwnedFiles(kb: CustomKeyboard) {
        val context = appContext ?: return
        val ownedRoots = buildList {
            runCatching { add(com.osfans.trime.core.data.MieUserDirectory.assetFolder(context, "custom_kb_bg").canonicalPath) }
            runCatching { add(com.osfans.trime.core.data.MieUserDirectory.assetFolder(context, "baidu_skins").canonicalPath) }
            add(File(context.filesDir, "custom_kb_bg").canonicalPath)
            add(File(context.filesDir, "baidu_skins").canonicalPath)
        }.distinct()
        fun owned(path: String): Boolean {
            if (path.isBlank() || !path.startsWith("/")) return false
            val canonical = runCatching { File(path).canonicalPath }.getOrNull() ?: return false
            return ownedRoots.any { canonical.startsWith(it + File.separator) }
        }
        fun deletePath(path: String) {
            if (!owned(path)) return
            val file = File(path)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
            com.osfans.trime.data.theme.BitmapAssets.invalidate()
        }
        kb.resolvedBackground().takeIf { it.type != CustomBackground.Type.COLOR && it.type != CustomBackground.Type.TRANSPARENT }?.image?.let(::deletePath)
        kb.toolbarBackground?.takeIf { it.type == CustomBackground.Type.IMAGE }?.image?.let(::deletePath)
        kb.allKeysBackground.takeIf { it.startsWith("/") }?.let(::deletePath)
        kb.importedSkin?.let { skin ->
            // 百度皮肤整目录：任一素材路径的公共祖先目录。
            val paths = skin.variants.values.flatMap { layout ->
                listOf(layout.background, layout.toolbarBackground, layout.preview) +
                    layout.keys.flatMap { key -> key.style.layers.flatMap { listOfNotNull(it.normal?.file, it.pressed?.file) } }
            }.filter { it.startsWith("/") }
            val dirs = paths.map { File(it).parent }.filter { owned(it) }.map { File(it).canonicalPath }.distinct()
            dirs.forEach { dir ->
                val dirFile = File(dir)
                if (!dirFile.isDirectory) return@forEach
                val parent = dirFile.parentFile
                if (parent != null && parent.isDirectory && parent.listFiles()?.size == 1) deletePath(parent.absolutePath) else deletePath(dir)
            }
        }
        kb.rows.forEach { row -> row.keys.forEach { key ->
            key.resolvedBackground().takeIf { it.type == CustomBackground.Type.IMAGE }?.image?.let(::deletePath)
            key.skin?.layers?.forEach { layer -> listOfNotNull(layer.normal?.file, layer.pressed?.file).forEach(::deletePath) }
        } }
        kb.toolbar.forEach { entry -> entry.icon.takeIf { it.startsWith("/") }?.let(::deletePath) }
        kb.surfaceAppearance?.let { config ->
            listOf(config.keys.background, config.toolbar.background, config.bar.background, config.floatingBackground).forEach { value ->
                value.takeIf { it.startsWith("/") }?.let(::deletePath)
            }
        }
        kb.floatingCandidates?.let { style ->
            listOf(style.panel.background, style.ball.background).forEach { value ->
                value.takeIf { it.startsWith("/") }?.let(::deletePath)
            }
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
        // 候选栏跟随当前键盘配色：底色用键盘背景、文字用键面文字色、选中项用回车强调色。
        val active = store.keyboards[effectiveActiveId()] ?: store.keyboards.values.firstOrNull()
        val paletteBackground = active?.resolvedBackground()?.takeIf {
            it.type == com.osfans.trime.data.keymap.CustomBackground.Type.COLOR
        }?.color
        val paletteText = active?.keyAppearance?.keyTextColor?.takeIf { it.isNotBlank() }
        val paletteAccent = active?.rows?.asSequence()
            ?.flatMap { it.keys.asSequence() }
            ?.firstOrNull { it.bindings["click"] == "Return" }
            ?.resolvedBackground()?.takeIf {
                it.type == com.osfans.trime.data.keymap.CustomBackground.Type.COLOR
            }?.color
        val candidateColors = buildMap {
            paletteBackground?.let { put("text_back_color", it) }
            paletteText?.let {
                listOf("candidate_text_color", "comment_text_color", "text_color", "label_color").forEach { key -> put(key, it) }
            }
            paletteAccent?.let { put("hilited_candidate_back_color", it) }
            put("hilited_candidate_text_color", "#FFFFFFFF")
            put("candidate_separator_color", "#00000000")
        }
        val colors = if (imported == null) {
            base.presetColorSchemes.mapValues { (_, scheme) -> scheme + candidateColors }
        } else {
            base.presetColorSchemes.mapValues { (_, scheme) ->
                scheme + listOf("candidate_text_color", "comment_text_color", "text_color", "label_color", "hilited_candidate_text_color").associateWith { text }
            }
        }
        base.copy(presetKeyboards = merged, presetKeys = base.presetKeys + store.keyboards.values.flatMap { it.importedActions.entries }.associate { it.key to it.value } + switches, presetColorSchemes = colors, customizationVersion = store.version)
    }

    /** 当前自定义键盘的配色（背景/文字/回车强调色），供菜单与候选栏美化使用。 */
    data class Palette(val background: String, val text: String, val accent: String)

    fun activePalette(): Palette? {
        if (store.mode != MODE_CUSTOM) return null
        val kb = synchronized(lock) { store.keyboards[effectiveActiveId()] } ?: return null
        val background = kb.resolvedBackground()
            .takeIf { it.type == CustomBackground.Type.COLOR }?.color.orEmpty()
        val text = kb.keyAppearance.keyTextColor.takeIf { it.isNotBlank() }.orEmpty()
        val accent = kb.rows.asSequence()
            .flatMap { it.keys.asSequence() }
            .firstOrNull { it.bindings["click"] == "Return" }
            ?.resolvedBackground()?.takeIf { it.type == CustomBackground.Type.COLOR }?.color.orEmpty()
        if (background.isEmpty() && text.isEmpty() && accent.isEmpty()) return null
        return Palette(background, text, accent)
    }

    /** Flat runtime index includes row-end and empty-row spacers. */
    fun customKeyAt(id: String, flatIndex: Int): CustomKey? = get(id)?.generatedKeys()?.getOrNull(flatIndex)?.first

    private fun token(value: String?): KeyActionToken? =
        value?.let { KeyActionToken.Plain(it) }

    fun CustomKey.toTextKey(keyboardBackground: CustomBackground, defaults: KeyAppearance.Style = KeyAppearance.Style()): TextKeyboard.TextKey =
        KeyAppearance.merge(defaults, appearance).toTextKey().let { style -> style.copy(
            label = label,
            allowDefaultVoice = !bindings.containsKey("longClick"),
            width = if (width > 0f) width else 10f,
            keyBackColor = style.keyBackColor.ifEmpty { if (resolvedBackground().type == CustomBackground.Type.COLOR) resolvedBackground().color else "#00000000" },
            hilitedKeyBackColor = style.hilitedKeyBackColor.ifEmpty { KeyAppearance.pressedColor(style.keyBackColor.ifEmpty { resolvedBackground().color }) },
            keyTextColor = style.keyTextColor.ifEmpty { resolvedBackground().textColor(keyboardBackground) },
            hilitedKeyTextColor = style.hilitedKeyTextColor.ifEmpty { resolvedBackground().textColor(keyboardBackground) },
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
        ) }

    internal fun CustomKeyboard.generatedKeys(): List<Pair<CustomKey?, TextKeyboard.TextKey>> = buildList {
        rows.forEach { row ->
            ButtonAlignment.slots(row).forEachIndexed { index, (key, width) ->
                val text = key?.toTextKey(resolvedBackground(), keyAppearance) ?: TextKeyboard.TextKey()
                add(key to text.copy(width = width, height = row.height.coerceIn(24, 160).toFloat(), newRow = index == 0,
                    rowMargin = row.margin ?: rowMargin))
            }
        }
    }

    fun CustomKeyboard.toTextKeyboard(): TextKeyboard = TextKeyboard(
        name = name, width = 10f, height = 60f, columns = -1,
        verticalGap = rowSpacing.coerceIn(0, maxRowSpacing()),
        horizontalGap = options.horizontalGap,
        // keyboard_height is the key area only; the toolbar strip sits above it inside the
        // same surface, so an explicit total keyboard height leaves the toolbar its share.
        keyboardHeight = if (keyboardHeightDp > 0) (keyboardHeightDp - effectiveToolbarHeightDp()).coerceAtLeast(24)
            else rows.sumOf { it.height.coerceIn(24, 160) }.coerceAtLeast(24) * options.heightPercent / 100,
        keyboardHeightLand = if (landscapeHeightDp > 0) (landscapeHeightDp - effectiveToolbarHeightDp()).coerceAtLeast(24)
            else rows.sumOf { it.height.coerceIn(24, 160) }.coerceAtLeast(24) * options.landscapeHeightPercent / 100,
        asciiKeyboard = options.asciiKeyboard.let { it.takeIf { id -> id.isNotEmpty() && store.keyboards.containsKey(id) }.orEmpty() },
        asciiMode = if (importedSkin != null) { if (importedSkin?.page == "en_26" || importedSkin?.page == "num_9") 1 else 0 } else options.asciiMode,
        resetAsciiMode = importedSkin != null || options.resetAsciiMode,
        lock = options.lock,
        landscapeSplitPercent = options.landscapeSplitPercent,
        labelTransform = if (options.uppercaseLabels) TextKeyboard.LabelTransform.UPPERCASE else TextKeyboard.LabelTransform.NONE,
        symbolAlign = options.symbolAlignment,
        keys = generatedKeys().map { it.second },
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
        val functions: List<NamedFunctions.Definition> = emptyList(),
        val format: String = EXPORT_FORMAT,
        val formatVersion: Int = 1,
        val exportedAt: Long = 0,
        val keyboards: MutableMap<String, CustomKeyboard> = mutableMapOf(),
    )

    /** Serializes all custom keyboards to a self-describing JSON document. */
    fun exportJson(now: Long = System.currentTimeMillis()): String {
        val functions=NamedFunctions.list()
        return synchronized(lock) {
            json.encodeToString(
                ExportFile.serializer(),
                ExportFile(functions=functions, exportedAt = now, keyboards = store.keyboards),
            )
        }
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
        else {
            val layouts=CustomKeyboardYaml.decode(trimmed)
            val functions=CustomKeyboardYaml.functions(trimmed)
            if(functions.isNotEmpty()) NamedFunctions.merge(functions)
            applyImportedLayouts(layouts, replace)
        }
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
        if(file.functions.isNotEmpty()) NamedFunctions.merge(file.functions)
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
