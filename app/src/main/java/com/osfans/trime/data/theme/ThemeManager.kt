/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.appContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object ThemeManager {
    private val customizationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var customizationRefreshJob: Job? = null
    private val selectionGate = ThemeSelectionGate()

    /** Survives editor Activity destruction and coalesces rapid mode/edit changes. */
    fun requestCustomizationRefresh() {
        customizationRefreshJob?.cancel()
        customizationRefreshJob = customizationScope.launch {
            try { refreshActiveTheme() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { Timber.e(error, "Could not refresh keyboard customization") }
        }
    }

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    fun getAllThemes(): List<ThemeItem> {
        val sharedThemes = ThemeFilesManager.listThemes(DataManager.sharedDataDir)
        val userThemes = ThemeFilesManager.listThemes(DataManager.userDataDir)
        // 同一 configId 在内置共享目录与用户目录各有一份时只列一次，内置版本优先；
        // 旧的 tongwenfeng（預設）主题已移除，不再展示。
        return (sharedThemes + userThemes)
            .filterNot { it.configId == DEPRECATED_THEME_ID }
            .distinctBy { it.configId }
    }

    private lateinit var _activeTheme: Theme

    fun activeThemeOrNull(): Theme? = if (::_activeTheme.isInitialized) _activeTheme else null

    private var _activeFindings: List<ThemeDiagnostics.Finding>? = null

    /**
     * What static checks found in the active theme, or null when it was read
     * from its deployed artifact and never checked. Serves the diagnostics
     * screen: the findings belong to the load that produced the active theme.
     */
    val activeFindings: List<ThemeDiagnostics.Finding>?
        get() {
            ensureActiveTheme()
            return _activeFindings
        }

    private fun ensureActiveTheme() {
        if (!::_activeTheme.isInitialized) {
            _activeTheme = evaluateActiveTheme()
        }
    }

    var activeTheme: Theme
        get() {
            ensureActiveTheme()
            return _activeTheme
        }
        private set(value) {
            if (::_activeTheme.isInitialized && _activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.defaultInstance().registerProvider(::ThemePrefs)

    private const val DEFAULT_THEME_ID = "tongwenfeng.trime"
    private const val DEPRECATED_THEME_ID = "trime"

    private data class ResolvedTheme(
        val configId: String,
        val theme: Theme,
        val findings: List<ThemeDiagnostics.Finding>?,
    )

    private val themeCache = object : androidx.collection.LruCache<String, Pair<String, ThemeLoader.ThemeLoadResult>>(8) {}

    private fun loadTheme(id: String): ThemeLoader.ThemeLoadResult {
        val stamp = ThemeSourceState.fingerprint(id)
        synchronized(themeCache) { themeCache[id]?.takeIf { it.first == stamp }?.let { return it.second } }
        val result = ThemeLoader.loadTheme(id)
        synchronized(themeCache) { themeCache.put(id, ThemeSourceState.fingerprint(id) to result) }
        return result
    }

    /** Source-readable themes need no native work. Complex DSL compiles only
     * this changed config; never escalate a theme selection to full maintenance. */
    private suspend fun refreshChangedConfig(id: String) {
        val loaded = loadTheme(id)
        if (loaded is ThemeLoader.ThemeLoadResult.Success && !loaded.fromCompiled) return
        if (ThemeLoader.themeSourceFile(id) == null || !ThemeSourceState.needsCompile(id)) return
        val owner = "theme-refresh-$id"
        val existing = com.osfans.trime.daemon.RimeDaemon.getFirstSessionOrNull()
        val session = existing ?: com.osfans.trime.daemon.RimeDaemon.createSession(owner)
        try {
            val ok = kotlinx.coroutines.withTimeout(30_000L) {
                session.runOnReady { deployThemeConfig(id, force = true) }
            }
            check(ok) { "主题配置刷新失败，已保留上次可用结果" }
            synchronized(themeCache) { themeCache.remove(id) }
        } finally {
            if (existing == null) com.osfans.trime.daemon.RimeDaemon.destroySession(owner)
        }
    }

    private fun getThemeById(id: String, custom: Boolean = CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM, customLayoutId: String? = null): ResolvedTheme {
        if (custom) {
            return ResolvedTheme(id, CustomKeyboards.standaloneTheme(customLayoutId).let { it.copy(customizationVersion = it.customizationVersion + com.osfans.trime.data.keymap.NamedFunctions.version + FontOverrides.version + com.osfans.trime.ime.voice.VoiceSettings.version) }, null)
        }
        when (val result = loadTheme(id)) {
            is ThemeLoader.ThemeLoadResult.Success -> return resolvedWithCustomKeyboards(id, result.theme, result.findings)
            is ThemeLoader.ThemeLoadResult.Failure -> Timber.w(result.error)
        }

        if (id != DEFAULT_THEME_ID) {
            when (val result = loadTheme(DEFAULT_THEME_ID)) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to default theme '$DEFAULT_THEME_ID'")
                    return resolvedWithCustomKeyboards(DEFAULT_THEME_ID, result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> Timber.w(result.error)
            }
        }

        var lastFailure: ThemeLoader.ThemeLoadError? = null
        for (fallbackId in getAllThemes().map { it.configId }.distinct()) {
            when (val result = loadTheme(fallbackId)) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to available theme '$fallbackId'")
                    return resolvedWithCustomKeyboards(fallbackId, result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> lastFailure = result.error
            }
        }

        Timber.w(lastFailure, "No valid theme available")
        // 不再内置同文风主题：没有可用主题时用独立键盘外观兜底，避免整页设置/键盘崩溃。
        val standalone = com.osfans.trime.data.keymap.StandaloneKeyboardTheme.base()
        return ResolvedTheme("", standalone, null)
    }

    private fun evaluateActiveTheme(): Theme {
        val storedThemeId = prefs.selectedTheme.getValue()
        val selectedThemeId =
            if (storedThemeId == DEPRECATED_THEME_ID) {
                prefs.selectedTheme.setValue(DEFAULT_THEME_ID)
                DEFAULT_THEME_ID
            } else storedThemeId
        val resolvedTheme = getThemeById(selectedThemeId)
        val newTheme = resolvedTheme.theme
        if (resolvedTheme.configId != selectedThemeId) {
            prefs.selectedTheme.setValue(resolvedTheme.configId)
        }
        applyTheme(resolvedTheme)
        return newTheme
    }

    /** Injects the user's custom keyboards into every theme the manager hands out. */
    private fun resolvedWithCustomKeyboards(
        configId: String,
        theme: Theme,
        findings: List<ThemeDiagnostics.Finding>?,
    ): ResolvedTheme = ResolvedTheme(configId, theme.copy(fontConfigId = configId, presetKeys = theme.presetKeys + mapOf("VoiceInput" to com.osfans.trime.data.theme.model.PresetKey(label = "内置语音", send = "VOICE_ASSIST"), "BibiVoice" to com.osfans.trime.data.theme.model.PresetKey(label = "内置语音", send = "VOICE_ASSIST")),
        // A video change must rebuild the keyboard even when its YAML is unchanged.
        customizationVersion = KeyBindingOverrides.version + ToolbarOverrides.version + com.osfans.trime.data.keymap.NamedFunctions.version + FontOverrides.version + com.osfans.trime.ime.voice.VoiceSettings.version + com.osfans.trime.ime.appearance.SurfaceAppearance.version + AppPrefs.defaultInstance().keyboard.videoBackground.getValue().hashCode().toLong()), findings)

    private fun applyTheme(resolvedTheme: ResolvedTheme) {
        val theme = resolvedTheme.theme
        // The findings describe the file this load read; they are not tied to the
        // views, so they are refreshed even when the theme itself is unchanged.
        _activeFindings = resolvedTheme.findings
        // A structurally equal theme suppresses the change notification below, so the
        // UI tree keeps its views and their injected scope. Replace neither the
        // caches nor the scope in that case, or later scheme changes would update
        // the new global scope while existing views still read the old one.
        if (::_activeTheme.isInitialized && _activeTheme == theme) return
        ColorManager.attachTheme(theme)
        activeTheme = theme
    }

    fun init(configuration: Configuration) {
        DirectoryFontFallback.refresh()
        ensureActiveTheme()
        ColorManager.init(configuration)
    }

    /**
     * Reloads the active theme — needed after custom keyboards or the layout
     * mode changed, so the injected presets and the UI tree are rebuilt.
     */
    suspend fun refreshActiveTheme(): String {
        selectionGate.awaitSelection()
        return selectThemeInternal(prefs.selectedTheme.getValue(), forceReload = true)
    }

    /**
     * Switches to theme [configId], falling back when it is unavailable.
     * Loading runs on [Dispatchers.IO]; state changes and listener callbacks run on the main thread.
     * @return the config id actually in effect; differs from [configId] when a fallback was used.
     */
    private val resourceRevision = java.util.concurrent.atomic.AtomicLong()
    private val themeRequestRevision = java.util.concurrent.atomic.AtomicLong()
    suspend fun selectTheme(configId: String, forceReload: Boolean = false, layoutMode: String? = null, customLayoutId: String? = null): String {
        val ticket = selectionGate.begin()
        try { return selectThemeInternal(configId, forceReload, layoutMode, customLayoutId) }
        finally { selectionGate.finish(ticket) }
    }

    private suspend fun selectThemeInternal(configId: String, forceReload: Boolean = false, layoutMode: String? = null, customLayoutId: String? = null): String {
        val mode = layoutMode ?: CustomKeyboards.mode
        require(mode in setOf(CustomKeyboards.MODE_THEME, CustomKeyboards.MODE_CUSTOM))
        val custom = mode == CustomKeyboards.MODE_CUSTOM
        val request = themeRequestRevision.incrementAndGet()
        val resolvedTheme = withContext(Dispatchers.IO) {
            DirectoryFontFallback.refresh()
            if (forceReload) synchronized(themeCache) { themeCache.evictAll() }
            if (!custom && configId.isNotBlank()) refreshChangedConfig(configId)
            getThemeById(configId, custom, customLayoutId).let { resolved -> resolved.copy(theme = resolved.theme.copy(
                customizationVersion = resolved.theme.customizationVersion + DirectoryFontFallback.stamp.hashCode().toLong() +
                    (if (forceReload) resourceRevision.incrementAndGet() else resourceRevision.get()))) }
                .also {
                    it.theme.fonts.preload()
                    if (custom) {
                        CustomKeyboards.get(customLayoutId ?: CustomKeyboards.activeId)?.let { layout ->
                            (layout.typography.values + layout.floatingCandidates?.fonts.orEmpty().values)
                                .map { spec -> spec.font }.distinct().forEach(FontOverrides::fromSelection)
                        }
                    }
                }
        }
        return withContext(Dispatchers.Main.immediate) {
            // A slower earlier load must not overwrite the newest theme/layout selection.
            if (request != themeRequestRevision.get()) return@withContext prefs.selectedTheme.getValue()
            val oldMode = CustomKeyboards.mode
            val oldActive = CustomKeyboards.activeId
            val oldSource = CustomKeyboards.pickerSource
            val oldTheme = activeThemeOrNull()
            val oldFindings = _activeFindings
            try {
                if (custom && customLayoutId != null) CustomKeyboards.activeId = customLayoutId
                CustomKeyboards.setMode(mode)
                BitmapAssets.invalidate()
                applyTheme(resolvedTheme)
            } catch (error: Exception) {
                CustomKeyboards.activeId = oldActive
                CustomKeyboards.setMode(oldMode)
                CustomKeyboards.pickerSource = oldSource
                if (oldTheme != null) {
                    ColorManager.attachTheme(oldTheme); _activeTheme = oldTheme; _activeFindings = oldFindings
                    runCatching { fireChange() }.onFailure { Timber.e(it, "Could not rebuild previous theme") }
                }
                throw error
            }
            prefs.selectedTheme.setValue(resolvedTheme.configId)
            resolvedTheme.configId
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.preferenceDelegates.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }
}
