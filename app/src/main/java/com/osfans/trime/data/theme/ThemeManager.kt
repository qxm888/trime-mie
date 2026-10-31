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
    /**
     * 主题源文件已改动、但编译产物尚未重建（引擎当时不可用）。
     * 输入法进程在键盘再次弹出时补一次重编译，保证用户改完主题后总能看到效果。
     */
    @Volatile
    var pendingThemeRebuild: Boolean = false

    private val customizationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var customizationRefreshJob: Job? = null

    /**
     * 兜底：个别进程状态下 librime 的 deploy_config_file 会直接返回失败，
     * 而完整维护总能重编译主题。这里后台跑一次维护，完成后重建视图。
     */
    private fun scheduleMaintenanceFallback(session: com.osfans.trime.daemon.RimeSession) {
        customizationScope.launch {
            runCatching {
                kotlinx.coroutines.withTimeout(180_000L) {
                    session.runOnReady { deploy() }
                }
            }.onSuccess {
                com.osfans.trime.util.DiagnosticLog.event("theme", "deploy_maintenance", "ok=true")
                pendingThemeRebuild = false
                runCatching { refreshActiveTheme() }
            }.onFailure {
                Timber.w(it, "Theme maintenance fallback failed")
                com.osfans.trime.util.DiagnosticLog.event("theme", "deploy_maintenance", "ok=false")
            }
        }
    }

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

    private fun getThemeById(id: String): ResolvedTheme {
        if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) {
            return ResolvedTheme(id, CustomKeyboards.standaloneTheme().let { it.copy(customizationVersion = it.customizationVersion + com.osfans.trime.data.keymap.NamedFunctions.version + FontOverrides.version + com.osfans.trime.ime.voice.VoiceSettings.version) }, null)
        }
        when (val result = ThemeLoader.loadTheme(id)) {
            is ThemeLoader.ThemeLoadResult.Success -> return resolvedWithCustomKeyboards(id, result.theme, result.findings)
            is ThemeLoader.ThemeLoadResult.Failure -> Timber.w(result.error)
        }

        if (id != DEFAULT_THEME_ID) {
            when (val result = ThemeLoader.loadTheme(DEFAULT_THEME_ID)) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to default theme '$DEFAULT_THEME_ID'")
                    return resolvedWithCustomKeyboards(DEFAULT_THEME_ID, result.theme, result.findings)
                }

                is ThemeLoader.ThemeLoadResult.Failure -> Timber.w(result.error)
            }
        }

        var lastFailure: ThemeLoader.ThemeLoadError? = null
        for (fallbackId in getAllThemes().map { it.configId }.distinct()) {
            when (val result = ThemeLoader.loadTheme(fallbackId)) {
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
    ): ResolvedTheme = ResolvedTheme(configId, theme.copy(presetKeys = theme.presetKeys + mapOf("VoiceInput" to com.osfans.trime.data.theme.model.PresetKey(label = "内置语音", send = "VOICE_ASSIST"), "BibiVoice" to com.osfans.trime.data.theme.model.PresetKey(label = "内置语音", send = "VOICE_ASSIST")),
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
        ensureActiveTheme()
        ColorManager.init(configuration)
    }

    /**
     * Reloads the active theme — needed after custom keyboards or the layout
     * mode changed, so the injected presets and the UI tree are rebuilt.
     */
    suspend fun refreshActiveTheme(): String = selectTheme(prefs.selectedTheme.getValue())

    /**
     * Switches to theme [configId], falling back when it is unavailable.
     * Loading runs on [Dispatchers.IO]; state changes and listener callbacks run on the main thread.
     * @param recompileTheme rebuild librime's compiled artifact first. Needed when the user
     *   edits theme YAML by hand: such themes can only be read back from the artifact, and
     *   librime skips a rebuild when a file edit preserves the source mtime.
     * @return the config id actually in effect; differs from [configId] when a fallback was used.
     */
    private val resourceRevision = java.util.concurrent.atomic.AtomicLong()
    private val themeRequestRevision = java.util.concurrent.atomic.AtomicLong()
    suspend fun selectTheme(configId: String, recompileTheme: Boolean = false): String {
        val request = themeRequestRevision.incrementAndGet()
        val resolvedTheme = withContext(Dispatchers.IO) {
            FontOverrides.invalidate()
            if (recompileTheme && configId.isNotBlank() && CustomKeyboards.mode != CustomKeyboards.MODE_CUSTOM) {
                // 先标记源文件已修改：不依赖引擎是否就绪，之后任何一次部署都会重编译。
                ThemeLoader.touchThemeSource(configId)
                pendingThemeRebuild = true
                val session = com.osfans.trime.daemon.RimeDaemon.getFirstSessionOrNull()
                com.osfans.trime.util.DiagnosticLog.event("theme", "deploy_check", "id=$configId hasSession=${session != null}")
                if (session != null) {
                    val deployed = runCatching {
                        kotlinx.coroutines.withTimeout(30_000L) {
                            session.runOnReady { deployThemeConfig(configId, force = true) }
                        }
                    }.onSuccess { ok ->
                        com.osfans.trime.util.DiagnosticLog.event("theme", "deploy_done", "id=$configId ok=$ok")
                    }.onFailure {
                        Timber.w(it, "Theme deploy check failed for '$configId'")
                        com.osfans.trime.util.DiagnosticLog.event("theme", "deploy_failed", "id=$configId type=${it.javaClass.simpleName}")
                    }.getOrDefault(false)
                    if (deployed) pendingThemeRebuild = false
                    else scheduleMaintenanceFallback(session)
                }
            }
            getThemeById(configId).let { resolved -> resolved.copy(theme = resolved.theme.copy(
                customizationVersion = resolved.theme.customizationVersion + resourceRevision.incrementAndGet())) }
                .also { it.theme.fonts.preload() }
        }
        return withContext(Dispatchers.Main.immediate) {
            // A slower earlier load must not overwrite the newest theme/layout selection.
            if (request != themeRequestRevision.get()) return@withContext prefs.selectedTheme.getValue()
            BitmapAssets.invalidate()
            applyTheme(resolvedTheme)
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
