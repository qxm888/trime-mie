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
import kotlinx.serialization.json.*
import kotlinx.serialization.builtins.serializer
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
 * 上/下留白模式下的「候选/图标内容行高」（dp）。
 *
 * 旧实现在这里叠了主题默认的状态栏整高
 * （candidateViewHeight + commentHeight ≈ 46dp），设 5+5 时总高变成 56dp，
 * 比不设置还宽。但也不能拍脑袋写 28：候选项里的文字 + 圆形高亮背景是按
 * `theme.style.candidateViewHeight`（34dp）量高的，28dp 会把紫色圆的下半截
 * 直接裁掉、顶上还挤出一条空白。这里取字母高度 + 2dp 余量，
 * 保证圆形候选完整显示；图标行也用同一个值，不会溢出内容区。
 */
const val TOOLBAR_ICON_ROW_DP = 36

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
    private const val BUILTIN_VERSION = 18
    private var builtinBindings: BuiltinBindingDefaults? = null

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
        val keyId: String = "",
        val identity: String = "",
        val setRole: String = "normal",
        val setStyleOverrides: JsonObject? = null,
        val setBindingOverrides: Set<String>? = null,
        val setBaseBindings: Map<String, String>? = null,
        val primaryBindingOverrides: Set<String> = emptySet(),
        val primaryBaseBindings: Map<String, String>? = null,
        val primaryBasePopup: List<String>? = null,
        val primaryBaseSendBindings: Boolean? = null,

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
        var setConfig: KeyboardSetConfig? = null,
        var setOverrides: JsonObject? = null,
        var setPageRole: String = "",
        var setKeyStyles: Map<String, KeyAppearance.Style> = emptyMap(),
        var setBindings: Map<String, Map<String, String>> = emptyMap(),
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
        var hiddenMenuItems: Set<String> = emptySet(),
        /** Overlay for every key in this layout; empty restores individual backgrounds. */
        var allKeysBackground: String = "",
        var rowSpacing: Int = 4,
        /** 行左右边距：键盘整体收进屏幕两侧的留白，0–48 dp。 */
        var rowMargin: Int = 0,
        var keyboardHeightDp: Int = 0,
        var landscapeHeightDp: Int = 0,
        var toolbarHeightDp: Int = 0,
        /** 工具栏图标上方留白（dp，0–60）。与下方留白一起：总高=上+下+图标行高，图标不再居中。 */
        var toolbarTopPadDp: Int = 0,
        /** 工具栏图标下方留白（dp，0–60）。 */
        var toolbarBottomPadDp: Int = 0,
        /** 工具栏图标统一大小（dp，0=跟随主题按钮自带的 iconSize/fontSize，8–96）。整套全局。 */
        var toolbarIconSizeDp: Int = 0,
        /** 非空 = 某主键盘的次级键盘（跳转用），不出现在主列表。 */
        var secondaryOf: String = "",
        /**
         * 非空 = 系统进入暗色模式时整套换成这套键盘（布局、按键、外观一起换）。
         * 亮色仍用本套；只跟一层，替身自己的 [nightId] 不再生效。
         */
        var nightId: String = "",
        var surfaceAppearance: com.osfans.trime.ime.appearance.SurfaceAppearanceConfig? = null,
        var unifiedBackground: Boolean? = null,
        var typography: Map<String, FontSpec> = emptyMap(),
        var floatingCandidates: com.osfans.trime.ime.appearance.FloatingCandidateStyle? = null,
        var keyAppearance: KeyAppearance.Style = KeyAppearance.Style(),
        var options: KeyboardOptions = KeyboardOptions(),
        var importedSkin: com.osfans.trime.data.baiduskin.ImportedSkin? = null,
        /** BDS coordinates define key positions, not the physical keyboard height. */
        var importedHeightPercent: Int = 100,
        /** Explicit layout name chosen in the editor, independent of its navigation role. */
        var layoutDisplayName: String = "",
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
        fun effectiveToolbarHeightDp(): Int {
            val legacy = StandaloneKeyboardTheme.base().style.run { candidateViewHeight + commentHeight }
            val top = toolbarTopPadDp.coerceIn(0, 60)
            val bottom = toolbarBottomPadDp.coerceIn(0, 60)
            // 上/下留白任一设置过：总高 = 上 + 下 + 紧凑图标行高，图标贴着上高度排，
            // 不再叠加主题默认整高，也不读旧的整高覆盖值。
            if (top > 0 || bottom > 0) return top + bottom + TOOLBAR_ICON_ROW_DP
            return if (toolbarHeightDp > 0) toolbarHeightDp else legacy
        }

        /** 上/下留白是否生效（决定渲染端是否按留白排图标）。 */
        fun toolbarPadsEnabled(): Boolean = toolbarTopPadDp.coerceIn(0, 60) > 0 || toolbarBottomPadDp.coerceIn(0, 60) > 0

        fun effectivePortraitHeightDp(): Int = if (keyboardHeightDp > 0) keyboardHeightDp
            else if (importedSkin != null) 240 * importedHeightPercent / 100
            else rows.sumOf { it.height.coerceIn(1, 160) }.coerceAtLeast(1) * options.heightPercent / 100
        fun effectiveLandscapeHeightDp(): Int = if (landscapeHeightDp > 0) landscapeHeightDp
            else if (importedSkin != null) 180 * importedHeightPercent / 100
            else rows.sumOf { it.height.coerceIn(1, 160) }.coerceAtLeast(1) * options.landscapeHeightPercent / 100

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

        /**
         * 行间距上限只受绝对上限约束，不再跟「最小行高的一半」绑定。
         * 间距是键内缩：间距越大按键越扁，行数越多越是挤压，
         * 这是用户自己想调出来的效果，不该由程序替他限制。
         */
        fun maxRowSpacing(): Int = 32

        fun resolvedBackground(): CustomBackground = (background ?: CustomBackground.fromLegacy(bgColor, bgImage, CustomBackground.KEYBOARD_COLOR)).normalized(CustomBackground.KEYBOARD_COLOR)
    }

    @Serializable
    private data class Store(
        var version: Long = 1,
        var mode: String = MODE_THEME,
        var activeId: String = "",
        var pickerSource: String = "",
        val keyboards: MutableMap<String, CustomKeyboard> = mutableMapOf(),
        val sharedProfiles: MutableMap<String, KeyboardSharedProfile> = mutableMapOf(),
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
    private var layoutStorage: File? = null

    @Volatile
    private var dirty = false

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var builtinExtrasJob: Job? = null

    // 只写非默认值：内置五色 300 套键盘的存档从 13.5MB 降到 2MB 级，启动解析更快。
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

    /** Bumped on every change so cached keyboards can detect staleness. */
    val version: Long
        get() = synchronized(lock) { store.version }

    /** Current layout mode: [MODE_THEME] or [MODE_CUSTOM]. */
    val mode: String
        get() = synchronized(lock) { store.mode }

    /** Last browsed category; separate from the two runtime loading modes. */
    var pickerSource: String
        get() = synchronized(lock) {
            store.pickerSource.takeIf { it in KeyboardLayoutCatalog.sources }
                ?: if (store.mode == MODE_THEME) KeyboardLayoutCatalog.THEME
                else if (appContext?.let { store.activeId in KeyboardLayoutCatalog.builtinIds(it) } == true)
                    KeyboardLayoutCatalog.BUILTIN else KeyboardLayoutCatalog.CUSTOM
        }
        set(value) {
            require(value in KeyboardLayoutCatalog.sources)
            synchronized(lock) {
                if (store.pickerSource == value) return
                store.pickerSource = value; dirty = true; scheduleSaveLocked()
            }
        }

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
     *
     * 这里**不再按 store.mode 提前返回**：只要键盘库里存在就直接命中。
     * 模式与主题快照短暂不同步时（切换布局来源、配置刚刚重载），
     * 早退会让所有 id 都解析不到 —— 键盘背景被置空 → 整块键盘变全透明，
     * 顶栏工具栏同时塌掉。多解析一步不会有副作用，调用方各自还有 isCustom 守卫。
     */
    fun resolveId(runtimeId: String): String =
        synchronized(lock) {
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
            layoutStorage = KeyboardLayoutStorage.resolve(appContext!!)
            // 记下启动时的日夜状态：否则「启动就在暗色，随后切亮色」会因为没有基准值
            // 被判成「没变化」，整套键盘不重建，一直停在夜间那套上。
            lastSyncedNight = systemNight()
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
            // Upgrade only app-owned icon references; old exports remain readable via aliases.
            store.keyboards.replaceAll { _, keyboard -> BuiltinToolbarAssets.upgrade(keyboard) }
            dirty = true; scheduleSaveLocked()
            // Install the versioned stock icon pack and remove its obsolete assets.
            runCatching { ensureBuiltinIcons(context) }.onFailure { Timber.w(it, "CustomKeyboards: builtin icons failed") }
            // Main templates may have been saved before an interrupted background
            // upgrade finished. Retry extras independently on the next launch.
            seedBuiltinExtrasInBackground(context)

        }
    }

    private fun decodeBuiltin(context: android.content.Context, resId: Int): Map<String, CustomKeyboard>? =
        runCatching {
            val current = resId in setOf(com.osfans.trime.R.raw.mie_builtin_keyboards, com.osfans.trime.R.raw.mie_builtin_extra, com.osfans.trime.R.raw.mie_builtin_effects)
            val bindings = if (current) builtinBindings ?: BuiltinBindingDefaults(context.resources.openRawResource(com.osfans.trime.R.raw.mie_builtin_bindings)
                .bufferedReader().use { it.readText() }).also { builtinBindings = it } else null
            context.resources.openRawResource(resId)
                .bufferedReader().use { CustomKeyboardYaml.decode(it.readText()) }.mapValues { (id, kb) -> bindings?.template(id, kb) ?: kb }
        }.onFailure { Timber.w(it, "CustomKeyboards: builtin asset %d decode failed", resId) }.getOrNull()

    /** 扩展内置键盘（表情/符号分类页）放后台补，避免首次启动主线程卡住。 */
    private fun seedBuiltinExtrasInBackground(context: android.content.Context) {
        if (synchronized(lock) { builtinExtrasJob?.isActive == true }) return
        builtinExtrasJob = ioScope.launch {
            // Repair missing stock textures off the UI thread even when layout migration is current.
            val installed = try { BuiltinPremiumAssets.install(context) }
            catch (error: Exception) {
                Timber.w(error, "CustomKeyboards: premium material installation failed; retaining current layouts")
                return@launch
            }
            if (synchronized(lock) { store.builtinExtrasVersion >= BUILTIN_VERSION }) {
                if (installed) com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
                return@launch
            }
            val extras = (decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_extra) ?: return@launch) +
                (decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_effects) ?: return@launch)
            val hasLegacy = synchronized(lock) { store.keyboards.keys.any { "_cat_" in it } }
            val legacy = if (hasLegacy) decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_legacy_symbols).orEmpty() else emptyMap()
            synchronized(lock) {
                if (store.builtinExtrasVersion >= BUILTIN_VERSION) return@synchronized
                // Drop only untouched stock symbol pages. User-edited pages stay available.
                legacy.filterKeys { "_cat_" in it }.forEach { (id, template) ->
                    if (store.keyboards[id] == normalizedCopy(template)) {
                        store.keyboards.remove(id)
                        if (store.activeId == id) store.activeId = id.substringBefore("_cat_")
                    }
                }
                var added = 0
                val sevenGeometry = preserveSevenGeometry(extras)
                extras.forEach { (id, keyboard) ->
                    val existing = store.keyboards[id]
                    if (existing == null) {
                        store.keyboards[id] = normalizedCopy(keyboard)
                        added++
                    } else store.keyboards[id] = builtinBindings?.migrate(id, upgradeSymbols(existing, keyboard, store.keyboards.keys, sevenGeometry[id]))
                        ?: upgradeSymbols(existing, keyboard, store.keyboards.keys, sevenGeometry[id])
                }
                store.builtinExtrasVersion = BUILTIN_VERSION
                store.version++
                dirty = true
                scheduleSaveLocked()
                Timber.i("CustomKeyboards: added %d builtin extras", added)
            }
            com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
        }
    }

    /** Picker waits off the main thread so first launch also lists every stock family. */
    suspend fun awaitBuiltinLayouts() { synchronized(lock) { builtinExtrasJob }?.join() }

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
     * V7 增加七列页面，V8 添加表情入口，V9 补七列功能键外观，保留用户编辑。
     * 较早版本沿用其原有整套模板升级规则。
     */
    private fun refreshBuiltinKeyboardsLocked() {
        if (store.builtinVersion >= BUILTIN_VERSION) return
        val context = appContext ?: return
        val layouts = decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_keyboards)
        if (layouts.isNullOrEmpty()) return
        // 只在已经内置过这套键盘的库里刷新，不把模板强塞进用户自建的列表。
        if (store.keyboards.keys.none { it in layouts.keys }) return
        val oldSeven = if (store.builtinVersion in 6..8)
            decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_seven_legacy).orEmpty() else emptyMap()
        val sevenGeometry = preserveSevenGeometry(layouts)
        layouts.forEach { (id, keyboard) ->
            val existing = store.keyboards[id]
            if (store.builtinVersion >= 6 && existing != null) {
                // V7 adds pages and chooser entries. Keep each existing layout's
                // fonts, toolbar, appearance and user-edited keys.
                val kept = if (store.builtinVersion < 7 && id.endsWith("_layouts")) existing.copy(rows = normalizedCopy(keyboard).rows) else existing
                val updated = upgradeSymbols(kept, keyboard, store.keyboards.keys, sevenGeometry[id])
                oldSeven[id]?.let { legacy ->
                    // Compare whole keys: custom bindings, labels and appearances
                    // prevent replacement. Existing order and layout settings stay.
                    val replacements = normalizedCopy(legacy).rows.flatMap { it.keys }
                        .zip(normalizedCopy(keyboard).rows.flatMap { it.keys })
                        .filter { (before, after) -> before != after }.toMap()
                    updated.rows.forEach { row -> row.keys.indices.forEach { index ->
                        replacements[row.keys[index]]?.let { replacement ->
                            row.keys[index] = replacement.copy(bindings = replacement.bindings.toMutableMap())
                        }
                    } }
                }
                store.keyboards[id] = builtinBindings?.migrate(id, updated) ?: updated
            } else store.keyboards[id] = normalizedCopy(keyboard)
        }
        store.builtinVersion = BUILTIN_VERSION
        store.version++
        dirty = true
        scheduleSaveLocked()
        seedBuiltinExtrasInBackground(context)
    }

    /** Insert a matching icon between floating/language without resetting edited keys/styles. */
    private val legacyChoosers by lazy {
        appContext?.let { decodeBuiltin(it, com.osfans.trime.R.raw.mie_builtin_chooser_legacy) }.orEmpty()
    }

    private fun preserveSevenGeometry(templates: Map<String, CustomKeyboard>): Map<String, JsonObject> =
        templates.filterKeys { it.endsWith("_7_1") || it.endsWith("_7_2") }.mapNotNull { (id, _) ->
            KeyboardSets.resolve(id, store.keyboards, store.sharedProfiles)?.let { id to KeyboardSets.values(it, "geometry") }
        }.toMap()

    internal fun upgradeSymbols(existing: CustomKeyboard, template: CustomKeyboard, existingIds: Set<String>, preservedGeometry: JsonObject? = null): CustomKeyboard {
        val updated = normalizedCopy(existing)
        updated.rows.forEach { row -> row.keys.replaceAll(BuiltinRDoubleTapDefaults::upgrade) }
        updated.setBindings = BuiltinRDoubleTapDefaults.rules(updated.setBindings)
        updated.setConfig = updated.setConfig?.let { it.copy(defaults = BuiltinRDoubleTapDefaults.settings(it.defaults)) }
        updated.setOverrides = updated.setOverrides?.let(BuiltinRDoubleTapDefaults::settings)
        // Add only newly introduced annotation bounds. Never replace a user's explicit values.
        val insetDefaults = KeyAppearance.values(template.keyAppearance).filter { (field, value) ->
            field in setOf("symbolInsetX", "symbolInsetY", "hintInsetX", "hintInsetY") && value != JsonNull
        }
        if (insetDefaults.isNotEmpty()) {
            fun fill(style: JsonObject) = JsonObject(style + insetDefaults.filterKeys { style[it] == null || style[it] == JsonNull })
            updated.keyAppearance = json.decodeFromJsonElement(KeyAppearance.Style.serializer(), fill(KeyAppearance.values(updated.keyAppearance)))
            fun fillSettings(values: JsonObject): JsonObject {
                val style = values["keyAppearance"] as? JsonObject ?: return values
                return JsonObject(values + ("keyAppearance" to fill(style)))
            }
            updated.setConfig = updated.setConfig?.let { it.copy(defaults = fillSettings(it.defaults)) }
            updated.setOverrides = updated.setOverrides?.let(::fillSettings)
        }
        updated.rows.forEach { row -> row.keys.replaceAll(BuiltinPunctuationDefaults::upgrade) }
        if (updated.options.asciiKeyboard.isBlank() && template.options.asciiKeyboard in existingIds) {
            updated.options = updated.options.copy(asciiKeyboard = template.options.asciiKeyboard)
        }
        if (template.keyboardHeightDp in setOf(251,260)) {
            val targetHeight = template.keyboardHeightDp
            val targetGap = if(targetHeight == 251) 8 else 10
            val previousHeights = if(targetHeight == 251) setOf(0,260) else setOf(0)
            val previousGaps = if(targetHeight == 251) setOf(4,5,10) else setOf(4,5)
            if (updated.keyboardHeightDp in previousHeights) updated.keyboardHeightDp = targetHeight
            if (updated.rowSpacing in previousGaps) updated.rowSpacing = targetGap
            // Adopted sets read defaults/overrides instead of raw page fields.
            fun migrate(values: JsonObject) = JsonObject(values.toMutableMap().apply {
                if ((get("keyboardHeightDp") as? JsonPrimitive)?.intOrNull in previousHeights) put("keyboardHeightDp", JsonPrimitive(targetHeight))
                if ((get("rowSpacing") as? JsonPrimitive)?.intOrNull in previousGaps) put("rowSpacing", JsonPrimitive(targetGap))
            })
            updated.setConfig = updated.setConfig?.let { it.copy(defaults = migrate(it.defaults)) }
            updated.setOverrides = updated.setOverrides?.let(::migrate)
        } else if (preservedGeometry != null && updated.setOverrides != null) {
            // A seven-column page must not inherit the newly changed main-page dimensions.
            updated.setOverrides = KeyboardSets.overlay(updated.setOverrides!!, preservedGeometry)
        }
        if (template.name.endsWith("9键")) {
            updated.rows.lastOrNull()?.let { row ->
                if (row.keys.map { it.width } == listOf(16f, 12f, 47f, 12f, 13f) && row.keys.last().label == "确定") {
                    row.keys[2] = row.keys[2].copy(width = 45f)
                    row.keys[4] = row.keys[4].copy(width = 15f)
                }
            }
        }
        if (template.rows.firstOrNull()?.keys?.firstOrNull()?.label == "选择键盘主题") {
            val legacy = legacyChoosers[template.secondaryOf + "_themes"]?.let(::normalizedCopy)
            if (legacy?.rows == existing.rows) {
                updated.rows.clear(); updated.rows.addAll(normalizedCopy(template).rows)
            } else {
                // Edited chooser pages retain their keys/order; append only the four new themes.
                val actions = updated.rows.flatMap { it.keys }.map { it.bindings["click"] }.toSet()
                val added = template.rows.flatMap { it.keys }.filter {
                    it.bindings["click"] in setOf("Keyboard_custom_porcelain", "Keyboard_custom_obsidian", "Keyboard_custom_champagne", "Keyboard_custom_frost") &&
                        it.bindings["click"] !in actions
                }
                val position = updated.rows.indexOfLast { row -> row.keys.any { it.label == "返回" } }.takeIf { it >= 0 } ?: updated.rows.size
                added.chunked(2).forEachIndexed { offset, keys ->
                    updated.rows.add(position + offset, CustomRow(keys.map { it.copy(width = 50f, bindings = it.bindings.toMutableMap()) }.toMutableList(), height = 52))
                }
            }
        }
        val emoji = template.toolbar.firstOrNull { it.icon.endsWith("tb_emoji.png") }
        if (emoji != null && updated.toolbar.none { it.icon.endsWith("tb_emoji.png") || BindingActions.resolve(it.action)?.command == "__mie_symbols" }) {
            val lang = updated.toolbar.indexOfFirst { it.icon.endsWith("tb_lang.png") }
            val floating = updated.toolbar.indexOfFirst { it.action == "__mie_floating_keyboard" }
            if (lang >= 0 && floating >= 0) updated.toolbar.add(lang + 1, emoji)
        }
        val category = Regex("Keyboard_custom_(?:mint|pink|blue|orange|purple)_(cat_(\\w+?)(?:_p\\d+)?|symbols(?:_cn|_math|_more)?)")
        updated.rows.forEach { row -> row.keys.forEach { key ->
            // Old stock return buttons hard-code the 26-key family root. Keep explicit
            // 26/9/14/18 choices intact and migrate only a labelled return action.
            if (key.label == "返回" && updated.secondaryOf.isNotBlank() &&
                key.bindings["click"] == "Keyboard_${updated.secondaryOf}") {
                key.bindings["click"] = "Keyboard_default"
            }
            key.bindings.toMap().forEach { (behavior, token) ->
                category.matchEntire(token)?.let { match ->
                    // Retained legacy category pages may contain user edits. Stock pages
                    // removed later are handled by the missing-target navigation fallback.
                    if (match.groupValues[1].startsWith("cat_") && token.removePrefix("Keyboard_") in existingIds) return@let
                    val id = match.groupValues[2].ifEmpty {
                        mapOf("symbols" to "english", "symbols_cn" to "chinese", "symbols_math" to "math", "symbols_more" to "common").getValue(match.groupValues[1])
                    }
                    key.bindings[behavior] = BindingActions.command("__mie_symbols", id, "表情与符号")
                }
            }
        } }
        // The hub/symbol pages use the same conservative surface upgrade as
        // core layouts. Preserve any effect that differs from the old defaults.
        updated.surfaceAppearance?.let { current ->
            val stock = com.osfans.trime.ime.appearance.KeyEffects(pressDarken = 6)
            template.surfaceAppearance?.let { next ->
                updated.surfaceAppearance = current.copy(
                    effects = if (current.effects == stock) next.effects else current.effects,
                    toolbarEffects = if (current.toolbarEffects == stock) next.toolbarEffects else current.toolbarEffects,
                )
            }
        }
        return BuiltinToolbarAssets.upgrade(updated)
    }

    /** 内置键盘的图标解压到素材目录（.mie/键盘背景/icons），已存在的不覆盖。 */
    private fun ensureBuiltinIcons(context: Context) {
        val backdrop = File(com.osfans.trime.core.data.MieUserDirectory.assetFolder(context, "custom_kb_bg"), "backgrounds/frost.png")
        if (!backdrop.isFile) {
            backdrop.parentFile?.mkdirs()
            context.resources.openRawResource(com.osfans.trime.R.raw.mie_frost_background).use { input ->
                val atomic = android.util.AtomicFile(backdrop)
                val output = atomic.startWrite()
                try {
                    input.copyTo(output)
                    atomic.finishWrite(output)
                } catch (error: Exception) {
                    atomic.failWrite(output)
                    throw error
                }
            }
        }
        BuiltinToolbarAssets.install(context)
    }

    fun shutdown() {
        flush()
        ioScope.cancel()
    }

    /** False means the current in-memory version was not persisted. */
    fun flush(): Boolean = synchronized(lock) {
        if (!dirty || appContext == null) return@synchronized true
        runCatching {
            val payload = json.encodeToString(store)
            layoutStorage = KeyboardLayoutStorage.save(appContext!!, payload)
            dirty = false
        }.onFailure { Timber.w(it, "CustomKeyboards: save failed") }.isSuccess
    }

    private fun storeFile(): File = layoutStorage ?: KeyboardLayoutStorage.resolve(appContext!!).also { layoutStorage = it }

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

    private var resolvedVersion = -1L
    private val resolvedCache = mutableMapOf<String, CustomKeyboard>()
    private fun resolvedLocked(id: String): CustomKeyboard? {
        if (resolvedVersion != store.version) { resolvedCache.clear(); resolvedVersion = store.version }
        return resolvedCache[id] ?: KeyboardSets.resolve(id, store.keyboards, store.sharedProfiles)?.also { resolvedCache[id] = it }
    }
    fun get(id: String): CustomKeyboard? = synchronized(lock) {
        (if (id.startsWith(KeyboardSets.EDIT_PREFIX)) KeyboardSets.editor(id.removePrefix(KeyboardSets.EDIT_PREFIX), store.keyboards, store.sharedProfiles)
        else resolvedLocked(id))?.let(::normalizedCopy)
    }
    fun setPageIds(root: String): List<String> = synchronized(lock) {
        val members = KeyboardSets.family(root, store.keyboards)
        (store.keyboards[root]?.setConfig?.order.orEmpty() + members).distinct().filter { it in members }
    }
    fun pageRole(id: String): String = synchronized(lock) {
        val kb = store.keyboards[id] ?: return@synchronized ""
        val root = runCatching { KeyboardSets.rootOf(id, store.keyboards) }.getOrNull() ?: return@synchronized ""
        KeyboardSets.pageRole(id, root, kb)
    }
    internal fun rawLayouts(): Map<String, CustomKeyboard> = synchronized(lock) { store.keyboards.mapValues { normalizedCopy(it.value) } }
    fun sharedProfiles(): List<KeyboardSharedProfile> = synchronized(lock) { store.sharedProfiles.values.toList() }
    fun setRoot(id: String): String = synchronized(lock) { KeyboardSets.rootOf(id, store.keyboards) }

    /**
     * 整套（全局）的工具栏上/下留白（dp，0–60）。
     *
     * 工具栏留白是「外观」，不是「某一页」的属性：中文页、英文页、符号页共用同一块
     * 顶栏，改一处处处生效。以前渲染端读 [KeyboardWindow.lastAttachedId] 那一页自己的
     * `toolbarTopPadDp/BottomPadDp`，切中英文时中文页设了留白、英文页没设，按钮尺寸
     * 就在 36dp ↔ 46dp 之间来回跳（图标忽大忽小）。这里统一读整套根键盘，切页不再变。
     */
    fun setToolbarPads(runtimeId: String): Pair<Int, Int> {
        // 预设页（.ascii / number / email 等）不在自定义键盘库里，`resolveId` 会原样
        // 返回它们，`setRoot` 直接抛「键盘已删除」，于是留白读成 0 → 按钮跳回 defaultButtonSize。
        // 这里先回退到当前活动键盘，再取整套根，保证数字/密码/英文页与中文页读同一份留白。
        val id = resolveId(runtimeId)
        val resolved = if (get(id) != null) id else resolveId("default")
        val root = runCatching { setRoot(resolved) }.getOrNull() ?: resolved
        val kb = get(root)
        val top = kb?.toolbarTopPadDp?.coerceIn(0, 60) ?: 0
        val bottom = kb?.toolbarBottomPadDp?.coerceIn(0, 60) ?: 0
        return top to bottom
    }

    /**
     * 整套（全局）的排版规格（字号/字体/颜色），读整套根键盘。
     *
     * 「工具栏」这类外观是整套共享的：中文页、英文页、符号页共用同一块顶栏，其图标
     * 字号、文字颜色都该一致。以前 [LayoutTypography] 读 [KeyboardWindow.lastAttachedId]
     * 那一页自己的 `typography[role]`，切中英文时中文页和英文页字号不同，工具栏图标
     * 就忽大忽小。这里统一读整套根键盘，切页不再变。
     */
    fun setTypography(runtimeId: String, role: String): FontSpec? {
        val id = resolveId(runtimeId)
        val resolved = if (get(id) != null) id else resolveId("default")
        val root = runCatching { setRoot(resolved) }.getOrNull() ?: resolved
        return get(root)?.typography?.get(role)
    }

    /**
     * 整套（全局）的工具栏图标统一大小（dp，0=跟随主题按钮自带的尺寸，8–96）。
     *
     * 图标大小与留白、字号一样是整套外观：改一处处处生效。读整套根键盘，
     * 预设页（数字/密码/英文页）回退到活动键盘后再取根，与 [setToolbarPads] 一致。
     */
    fun setToolbarIconSize(runtimeId: String): Int {
        val id = resolveId(runtimeId)
        val resolved = if (get(id) != null) id else resolveId("default")
        val root = runCatching { setRoot(resolved) }.getOrNull() ?: resolved
        return get(root)?.toolbarIconSizeDp?.coerceIn(0, 96) ?: 0
    }

    @Serializable private data class SetUndo(val before: String, val afterFingerprint: String)
    private fun setFingerprint(pages: Map<String, CustomKeyboard>, profiles: Map<String, KeyboardSharedProfile>): String {
        val snapshot = kotlinx.serialization.json.buildJsonObject {
            put("pages", json.encodeToJsonElement(kotlinx.serialization.builtins.MapSerializer(String.serializer(), CustomKeyboard.serializer()), pages.toSortedMap()))
            put("profiles", json.encodeToJsonElement(kotlinx.serialization.builtins.MapSerializer(String.serializer(), KeyboardSharedProfile.serializer()), profiles.toSortedMap()))
        }.toString()
        return java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    /** Snapshot plus single synchronous store commit; any error restores the complete old state. */
    internal fun editSets(block: (MutableMap<String, CustomKeyboard>, MutableMap<String, KeyboardSharedProfile>) -> Unit) = synchronized(lock) {
        val previous = json.encodeToString(store)
        val oldDirty = dirty
        try {
            val pages = store.keyboards.mapValues { normalizedCopy(it.value) }.toMutableMap()
            val profiles = store.sharedProfiles.toMutableMap()
            block(pages, profiles)
            require(pages.values.all { it.secondaryOf.isEmpty() || it.secondaryOf in pages }) { "次级键盘所属整套不存在" }
            pages.keys.forEach { id ->
                KeyboardSets.rootOf(id, pages)
                // Validate typed inherited configuration before committing an imported/shared change.
                normalizedCopy(KeyboardSets.resolve(id, pages, profiles) ?: error("键盘已删除"))
            }
            File(storeFile().parentFile, "整套操作撤销.json").writeAtomicText(json.encodeToString(SetUndo(previous, setFingerprint(pages, profiles))))
            store.keyboards.clear(); store.keyboards.putAll(pages)
            store.sharedProfiles.clear(); store.sharedProfiles.putAll(profiles)
            if (store.activeId !in pages) store.activeId = pages.keys.firstOrNull().orEmpty()
            store.version++; dirty = true
            check(flush()) { "整套设置保存失败，已恢复原数据" }
        } catch (error: Exception) {
            store = json.decodeFromString(Store.serializer(), previous); dirty = oldDirty
            resolvedVersion = -1L
            throw error
        }
    }
    fun undoSetOperation() = synchronized(lock) {
        val file = File(storeFile().parentFile, "整套操作撤销.json")
        val text = file.readAtomicText() ?: error("没有可撤销的整套操作")
        val undo = json.decodeFromString(SetUndo.serializer(), text)
        require(undo.afterFingerprint == setFingerprint(store.keyboards, store.sharedProfiles)) { "整套操作后又修改过键盘，无法直接撤销；当前配置已保留" }
        val restored = json.decodeFromString(Store.serializer(), undo.before)
        val previous = store
        store = restored.copy(version = store.version + 1); dirty = true
        if (!flush()) { store = previous; dirty = true; error("撤销保存失败") }
        file.delete(); resolvedVersion = -1L
    }
    internal fun portableLayouts(ids: List<String>): Map<String, CustomKeyboard> = synchronized(lock) {
        ids.associateWith { id ->
            val kb = get(id) ?: error("键盘已删除")
            kb.copy(setPageRole = kb.setPageRole.ifBlank { KeyboardSets.pageRole(id, KeyboardSets.rootOf(id, store.keyboards), kb) },
                setConfig = kb.setConfig?.let { config -> config.copy(defaults = KeyboardSets.defaults(config, store.sharedProfiles), profiles = emptyMap()) })
        }
    }

    /** Read only typography metadata; parent fallback never copies full key arrays. */
    fun globalFont(id: String): String = synchronized(lock) {
        var current = resolveId(id)
        val visited = mutableSetOf<String>()
        while (current.isNotBlank() && visited.add(current)) {
            val keyboard = resolvedLocked(current) ?: break
            keyboard.typography[LayoutTypography.GLOBAL]?.font?.takeIf { it.isNotBlank() }?.let { return@synchronized it }
            current = keyboard.secondaryOf
        }
        ""
    }

    /** 系统当前是否处于暗色模式。直接读配置，不缓存：切换后调用方会重建键盘。 */
    private fun systemNight(): Boolean =
        appContext?.resources?.configuration?.uiMode?.and(android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * 当前是否暗色：优先用系统广播过来的值，没收到过就现读配置。
     *
     * 广播里带的是「新」配置，而 [appContext] 的 resources 有可能还没跟着更新，
     * 收到通知那一刻以广播为准，其余时刻以配置为准。
     */
    private fun isNightNow(): Boolean = lastSyncedNight ?: systemNight()

    /**
     * 这套键盘在暗色模式下指定的替身，没有则返回空串。
     *
     * 只认一层：替身自己再指定 [CustomKeyboard.nightId] 也不会继续跳，
     * 既避免 A→B→A 来回循环，也让「夜间那套」始终是用户明确选的那套。
     */
    fun nightKeyboardOf(id: String): String = synchronized(lock) {
        store.keyboards[id]?.nightId.orEmpty()
            .takeIf { it.isNotBlank() && it != id && it in store.keyboards }.orEmpty()
    }

    /**
     * 设定（或清除，[target] 传空）暗色模式替身，返回是否落盘成功。
     *
     * 直接改 store 里那一条，不经过 [mutate]：nightId 不是布局字段，走布局改写那条链
     * 只会白白重算一遍按键身份，出问题还不容易看出来。保存失败时回滚并保留 dirty，
     * 让调用方能把「没存住」提示给用户。
     */
    fun setNightTarget(
        id: String,
        target: String,
    ): Boolean = synchronized(lock) {
        val before = store.keyboards[id] ?: return@synchronized false
        val next = target.takeIf { it.isNotBlank() && it != id && it in store.keyboards }.orEmpty()
        if (before.nightId == next) return@synchronized true
        store.keyboards[id] = before.copy(nightId = next)
        store.version++
        dirty = true
        if (flush()) {
            true
        } else {
            store.keyboards[id] = before
            store.version++
            dirty = true
            false
        }
    }

    /** 当前真正要渲染的整套键盘：暗色时优先用替身。 */
    private fun effectiveActiveId(): String {
        val base = store.activeId.takeIf { it in store.keyboards } ?: store.keyboards.keys.firstOrNull().orEmpty()
        if (!isNightNow()) return base
        // activeId 可能指向某套里的某一页（布局），而替身是按整套（root）存的：先归到 root，
        // 否则「切到暗色不换」——页 id 上永远读不到 nightId。
        val root = runCatching { KeyboardSets.rootOf(base, store.keyboards) }.getOrNull().orEmpty()
        return nightKeyboardOf(root.ifEmpty { base }).ifEmpty { base }
    }

    /**
     * 运行时该挂载哪一页：暗色且配了替身时是替身那套，否则就是用户选的那套。
     *
     * [activeId] 记的是「用户选了哪套」，不随日夜变；键盘窗口起首挂载必须用这个，
     * 否则新建窗口时按 `.default` 会按 [activeId] 回到日间那套，替身等于白配。
     */
    fun runtimeActiveId(): String = synchronized(lock) { effectiveActiveId() }

    /**
     * 此刻是否正被「夜间替身」接管（暗色 + 日间那套配了替身）。
     *
     * 为 true 时运行时切页不能写回 [activeId]：那是「日间用哪套」的记录，
     * 一旦写成夜间那套的页 id，切回亮色就再也回不到原来那套了。
     */
    fun isNightShadowed(): Boolean = synchronized(lock) { nightTargetOfActiveLocked().isNotEmpty() }

    /** 暗色替身目标；不在暗色、没配替身或 id 不合法时为空串。 */
    private fun nightTargetOfActiveLocked(): String {
        if (!isNightNow()) return ""
        val base = store.activeId.takeIf { it in store.keyboards } ?: return ""
        val root = runCatching { KeyboardSets.rootOf(base, store.keyboards) }.getOrNull() ?: return ""
        return nightKeyboardOf(root)
    }

    /**
     * 系统日夜变化后调用：返回 true 表示需要重建整套键盘。
     *
     * 幂等：日夜值没真的变化时返回 false，所以应用层和输入法服务层可以都调一次。
     * 切到暗色看有没有替身；切回亮色只要配过替身就必须重建（要回到日间那套）。
     */
    fun syncNightMode(night: Boolean = systemNight()): Boolean = synchronized(lock) {
        val previous = lastSyncedNight
        lastSyncedNight = night
        if (previous == null || previous == night) return@synchronized false
        val base = store.activeId.takeIf { it in store.keyboards } ?: return@synchronized false
        val root = runCatching { KeyboardSets.rootOf(base, store.keyboards) }.getOrNull() ?: return@synchronized false
        nightKeyboardOf(root).isNotEmpty()
    }

    /** 上一次同步时的日夜状态；null = 还没同步过（首帧按 theme 构建，不需要重建）。 */
    private var lastSyncedNight: Boolean? = null

    private fun normalizedCopy(kb: CustomKeyboard): CustomKeyboard = kb.copy(
        options = kb.options.validated(),
        importedActions = kb.importedActions.toMap(),
        surfaceAppearance=kb.surfaceAppearance?.sanitized(),
        floatingCandidates=kb.floatingCandidates?.sanitized(),
        typography=kb.typography.filterKeys { it in com.osfans.trime.data.theme.FontOverrides.roles || it in setOf("latin", "hanb") || it == LayoutTypography.GLOBAL }.mapValues { (role,spec) -> spec.copy(
            size=if(role != LayoutTypography.GLOBAL && spec.size.isFinite()) spec.size.coerceIn(0f,200f) else 0f,
            color=spec.color.trim().takeIf { runCatching { android.graphics.Color.parseColor(it) }.isSuccess } ?: "",
        ) },
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
            ) }.toMutableList(), height = row.height.coerceIn(1, 160), alignment = ButtonAlignment.normalized(row.alignment))
        }.toMutableList().onEachIndexed { index, row ->
            row.margin = kb.rows.getOrNull(index)?.margin?.coerceIn(0, 48)
        },
    )

    private fun starter(): CustomKeyboard {
        fun key(label: String, width: Float = 10f, token: String = label) = CustomKey(label = label, width = width, bindings = mutableMapOf("click" to token))
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
                it.layoutDisplayName = name
                store.version++
                dirty = true
                scheduleSaveLocked()
            }
        }
        com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
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
            val ids = KeyboardSets.family(id, store.keyboards).toSet()
            ids.forEach { key -> store.keyboards.remove(key)?.let(removed::add) }
            store.keyboards.values.forEach { kb -> kb.setConfig?.let { config ->
                kb.setConfig = config.copy(order = config.order.filterNot { it in ids }, defaultPage = config.defaultPage.takeUnless { it in ids }.orEmpty())
            } }
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
        val survivors = synchronized(lock) { json.encodeToString(store) }
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
            if (survivors.contains(path) || !owned(path)) return
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
            val scopeRoot = id.takeIf { it.startsWith(KeyboardSets.EDIT_PREFIX) }?.removePrefix(KeyboardSets.EDIT_PREFIX)
            if (scopeRoot != null) {
                val before = get(id) ?: return false
                val after = normalizedCopy(before); block(after)
                val root = store.keyboards[scopeRoot] ?: return false
                val config = root.setConfig ?: return false
                val patch = KeyboardSets.difference(KeyboardSets.values(before), KeyboardSets.values(after))
                // Editing a set always makes the changed groups local; shared profiles have their own explicit save action.
                val changedGroups = KeyboardSets.fields.filterValues { fields -> patch.keys.any { it in fields } }.keys
                val base = KeyboardSets.defaults(config, store.sharedProfiles)
                // 圆角/边框一条参数两处存储，改到它就把整套两份写成同一个数，否则页面按另一份旧值渲染。
                val shape = KeyboardSets.pushedShape(patch, after)
                var nextDefaults = KeyboardSets.overlay(base, patch)
                if (shape.isNotEmpty()) nextDefaults = KeyboardSets.syncShape(nextDefaults, shape)
                root.setConfig = config.copy(defaults = nextDefaults, profiles = config.profiles - changedGroups)
                // 整套只是一块「批量推送」面板：这次改到的字段，从每一页的独立设置里清掉，
                // 页面才会真的用上新值。只清改动过的那几个字段，页面其它自定义保留。
                if (patch.isNotEmpty()) {
                    val pushPatch = if (shape.isEmpty()) patch else KeyboardSets.overlay(patch, KeyboardSets.shapeProbe(shape))
                    val appearanceFields = (
                        (patch["keyAppearance"] as? JsonObject)?.let {
                            // difference 可能把整组包成 __mie_replace，真正改到的字段在里面。
                            if ("__mie_replace" in it) (it["__mie_replace"] as? JsonObject)?.keys?.toSet() else it.keys
                        }.orEmpty() + KeyboardSets.keyShapeFields(shape)).toSet()
                    KeyboardSets.family(scopeRoot, store.keyboards).forEach { pageId ->
                        val page = store.keyboards[pageId] ?: return@forEach
                        // null = 这页从没跟随过整套：先把现值固化成独立设置，否则清完等于把它重置成整套的值。
                        val overrides = page.setOverrides
                            ?: JsonObject(KeyboardSets.values(page).filter { (name, value) -> value != base[name] })
                        // 整套动的按键外观也要覆盖到单键，否则改过的键会一直用自己那份旧值。
                        store.keyboards[pageId] = KeyboardSetOperations.pushAppearance(
                            page.copy(setOverrides = KeyboardSets.prune(overrides, pushPatch)), appearanceFields)
                    }
                }
            } else {
                val raw = store.keyboards[id]?.let(::normalizedCopy) ?: return false
                val before = get(id) ?: return false
                val after = normalizedCopy(before); block(after)
                store.keyboards[id] = normalizedCopy(KeyboardSets.captureMutation(raw, before, after))
            }
            store.version++
            dirty = true
            scheduleSaveLocked()
            true
        }

    /** Complete-family binding changes are validated/prepared before this single commit. */
    internal fun replaceBindingLayouts(expectedVersion: Long, replacements: Map<String, CustomKeyboard>) {
        synchronized(lock) {
            require(store.version == expectedVersion) { "键盘已变化，请重新选择来源" }
            require(replacements.keys.all { it in store.keyboards }) { "目标键盘已删除" }
            val original = replacements.keys.associateWith { store.keyboards.getValue(it) }
            val prepared = replacements.mapValues { (id, value) -> normalizedCopy(KeyboardSets.captureMutation(store.keyboards.getValue(id), get(id)!!, value)) }
            store.keyboards.putAll(prepared); store.version++; dirty = true
            if (!flush()) {
                store.keyboards.putAll(original); store.version++; dirty = true
                scheduleSaveLocked()
                error("保存失败，请检查数据目录；内存中的原绑定已保留")
            }
        }
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
    fun standaloneTheme(selectedId: String? = null): Theme = synchronized(lock) {
        ensureStarterLocked()
        val first = selectedId?.also { require(store.keyboards.containsKey(it)) { "键盘已删除，请重新选择" } } ?: effectiveActiveId()
        val merged = linkedMapOf<String, TextKeyboard>()
        store.keyboards.entries.sortedBy { if (it.key == first) 0 else 1 }.forEach { (id, kb) ->
            merged[id] = (resolvedLocked(id) ?: kb).toTextKeyboard(id)
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
        val active = resolvedLocked(first) ?: store.keyboards.values.firstOrNull()
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
        base.copy(presetKeyboards = merged, presetKeys = base.presetKeys + store.keyboards.keys.flatMap { resolvedLocked(it)?.importedActions?.entries.orEmpty() }.associate { it.key to it.value } + switches, presetColorSchemes = colors, customizationVersion = store.version)
    }

    /** 当前自定义键盘的配色（背景/文字/回车强调色），供菜单与候选栏美化使用。 */
    data class Palette(val background: String, val text: String, val accent: String)

    fun activePalette(): Palette? {
        if (store.mode != MODE_CUSTOM) return null
        val kb = synchronized(lock) { resolvedLocked(effectiveActiveId()) } ?: return null
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

    /**
     * 单键是否自己设过这项形状。整套/页面推下来的值存在 [setStyleOverrides] 里，
     * 只有那里带了这个字段（且不是 -1 继承）才算这个键自己的选择。
     */
    private fun CustomKey.ownShape(field: String, inherited: Float): Boolean {
        // 有差量表时它才是这个键的真实来源：resolve 已经把页面值烘进 appearance 了。
        val overrides = setStyleOverrides ?: return inherited >= 0f
        val explicit = overrides[field]?.let { (it as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() } ?: return false
        return explicit >= 0f
    }

    fun CustomKey.toTextKey(keyboardBackground: CustomBackground, defaults: KeyAppearance.Style = KeyAppearance.Style()): TextKeyboard.TextKey =
        KeyAppearance.merge(defaults, appearance).toTextKey().let { style -> style.copy(
            label = label,
            allowDefaultVoice = !bindings.containsKey("longClick"),
            width = if (width > 0f) width else 10f,
            ownRoundCorner = ownShape("roundCorner", appearance.roundCorner),
            ownKeyBorder = ownShape("keyBorder", appearance.keyBorder.toFloat()),
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
                add(key to text.copy(width = width, height = row.height.coerceIn(1, 160).toFloat(), newRow = index == 0,
                    rowMargin = row.margin ?: rowMargin))
            }
        }
    }

    internal fun effectiveAsciiTarget(id: String, keyboard: CustomKeyboard, pages: Map<String, CustomKeyboard>): String {
        keyboard.options.asciiKeyboard.takeIf { it.isNotBlank() && it in pages }?.let { return it }
        if(id.isBlank()) return ""
        val root = runCatching { KeyboardSets.rootOf(id, pages) }.getOrNull() ?: return ""
        if(!KeyboardSets.pageRole(id, root, keyboard).startsWith("zh_")) return ""
        return KeyboardSets.family(root, pages).firstOrNull { KeyboardSets.pageRole(it, root, pages.getValue(it)) == "en" }.orEmpty()
    }

    fun CustomKeyboard.toTextKeyboard(layoutId: String = ""): TextKeyboard = TextKeyboard(
        name = name, width = 10f, height = 60f, columns = -1,
        // 按键没自己设形状时回落到这里：本页统一值。键自己的值仍由 per-key 覆盖。
        roundCorner = keyAppearance.roundCorner,
        keyBorder = keyAppearance.keyBorder,
        verticalGap = rowSpacing.coerceIn(0, maxRowSpacing()),
        horizontalGap = options.horizontalGap,
        // keyboard_height is the key area only; the toolbar strip sits above it inside the
        // same surface, so an explicit total keyboard height leaves the toolbar its share.
        keyboardHeight = if (keyboardHeightDp > 0) (keyboardHeightDp - effectiveToolbarHeightDp()).coerceAtLeast(24)
            else rows.sumOf { it.height.coerceIn(1, 160) }.coerceAtLeast(1) * options.heightPercent / 100,
        keyboardHeightLand = if (landscapeHeightDp > 0) (landscapeHeightDp - effectiveToolbarHeightDp()).coerceAtLeast(24)
            else rows.sumOf { it.height.coerceIn(1, 160) }.coerceAtLeast(1) * options.landscapeHeightPercent / 100,
        asciiKeyboard = effectiveAsciiTarget(layoutId, this, store.keyboards),
        asciiMode = if (importedSkin != null) { if (importedSkin?.page == "en_26" || importedSkin?.page == "num_9") 1 else 0 } else options.asciiMode,
        resetAsciiMode = importedSkin != null || options.resetAsciiMode,
        lock = options.lock,
        landscapeSplitPercent = options.landscapeSplitPercent,
        labelTransform = if (options.uppercaseLabels) TextKeyboard.LabelTransform.UPPERCASE else TextKeyboard.LabelTransform.NONE,
        symbolAlign = options.symbolAlignment,
        hintAlign = options.effectiveHintAlignment,
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
        val sharedProfiles: Map<String, KeyboardSharedProfile> = emptyMap(),
    )

    /** Serializes all custom keyboards to a self-describing JSON document. */
    fun exportJson(now: Long = System.currentTimeMillis(), functions: List<NamedFunctions.Definition> = NamedFunctions.list()): String {
        return synchronized(lock) {
            json.encodeToString(
                ExportFile.serializer(),
                ExportFile(functions=functions, exportedAt = now, keyboards = store.keyboards, sharedProfiles = store.sharedProfiles),
            )
        }
    }

    /** A primary selection includes its complete descendant tree exactly once. */
    fun familyIds(primaries: Collection<String>): List<String> = synchronized(lock) {
        val selected = primaries.filter { it in store.keyboards }.toMutableSet()
        do {
            val before = selected.size
            store.keyboards.forEach { (id, keyboard) -> if (keyboard.secondaryOf in selected) selected.add(id) }
        } while (selected.size != before)
        store.keyboards.keys.filter { it in selected }
    }

    /** Restore missing stock pages, or reset only known built-in ids. User-created ids survive. */
    fun restoreBuiltins(reset: Boolean): Int {
        val context = appContext ?: error("键盘尚未初始化")
        BuiltinPremiumAssets.install(context)
        val templates = decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_keyboards).orEmpty() +
            decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_extra).orEmpty() +
            decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_effects).orEmpty()
        check(templates.isNotEmpty()) { "内置键盘资源读取失败" }
        synchronized(lock) {
            var count = 0
            templates.forEach { (id, keyboard) ->
                if (reset || id !in store.keyboards) { store.keyboards[id] = normalizedCopy(keyboard); count++ }
            }
            if (store.activeId !in store.keyboards) store.activeId = BUILTIN_DEFAULT_ID
            store.builtinVersion = BUILTIN_VERSION; store.builtinExtrasVersion = BUILTIN_VERSION
            store.builtinsOffered = true; store.version++; dirty = true
            scheduleSaveLocked(); ensureBuiltinIcons(context)
            return count
        }
    }

    internal fun builtinTemplates(): Map<String, CustomKeyboard> {
        val context = appContext ?: error("键盘尚未初始化")
        return decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_keyboards).orEmpty() +
            decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_extra).orEmpty() +
            decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_effects).orEmpty()
    }
    fun resetBuiltinSet(root: String) {
        val context = appContext ?: error("键盘尚未初始化")
        BuiltinPremiumAssets.install(context)
        val templates = decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_keyboards).orEmpty() +
            decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_extra).orEmpty() +
            decodeBuiltin(context, com.osfans.trime.R.raw.mie_builtin_effects).orEmpty()
        require(root in templates && templates.getValue(root).secondaryOf.isBlank()) { "不是内置整套" }
        val ids = KeyboardSets.family(root, templates)
        editSets { pages, profiles ->
            ids.forEach { pages[it] = normalizedCopy(templates.getValue(it)) }
            KeyboardSetOperations.adoptBuiltinData(root, pages, profiles, templates)
        }
    }

    /** Snapshot only the explicit selection before opening the system file picker. */
    fun exportYaml(ids: List<String>): String {
        val selected = synchronized(lock) {
            require(ids.isNotEmpty() && ids.all { it in store.keyboards }) { "请选择现有键盘" }
            portableLayouts(ids.distinct())
        }
        // Traditional YAML is an effective standalone snapshot. The ZIP format retains live inheritance and resources.
        return CustomKeyboardYaml.encode(selected.mapValues { (_, kb) -> kb.copy(setConfig = null, setOverrides = null,
            rows = kb.rows.map { row -> row.copy(keys = row.keys.map { it.copy(setStyleOverrides = null, setBindingOverrides = null) }.toMutableList()) }.toMutableList()) })
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
        val detached = file.keyboards.mapValues { (_, kb) -> kb.copy(setConfig = kb.setConfig?.let { config ->
            config.copy(defaults = KeyboardSets.defaults(config, file.sharedProfiles), profiles = emptyMap())
        }) }
        applyImportedLayouts(detached, replace)
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
