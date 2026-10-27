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
        return sharedThemes + userThemes
    }

    private lateinit var _activeTheme: Theme

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

    private data class ResolvedTheme(
        val configId: String,
        val theme: Theme,
        val findings: List<ThemeDiagnostics.Finding>?,
    )

    private fun getThemeById(id: String): ResolvedTheme {
        if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) {
            return ResolvedTheme(id, CustomKeyboards.standaloneTheme().let { it.copy(customizationVersion = it.customizationVersion + FontOverrides.version + com.osfans.trime.ime.voice.VoiceSettings.version) }, null)
        }
        when (val result = ThemeLoader.loadTheme(id)) {
            is ThemeLoader.ThemeLoadResult.Success -> return resolvedWithCustomKeyboards(id, result.theme, result.findings)
            is ThemeLoader.ThemeLoadResult.Failure -> Timber.w(result.error)
        }

        if (id != "trime") {
            when (val result = ThemeLoader.loadTheme("trime")) {
                is ThemeLoader.ThemeLoadResult.Success -> {
                    Timber.w("Theme '$id' is unavailable, fallback to default theme 'trime'")
                    return resolvedWithCustomKeyboards("trime", result.theme, result.findings)
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
        error("No valid theme available")
    }

    private fun evaluateActiveTheme(): Theme {
        val selectedThemeId = prefs.selectedTheme.getValue()
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
        customizationVersion = KeyBindingOverrides.version + ToolbarOverrides.version + FontOverrides.version + com.osfans.trime.ime.voice.VoiceSettings.version + AppPrefs.defaultInstance().keyboard.videoBackground.getValue().hashCode().toLong()), findings)

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
     * @return the config id actually in effect; differs from [configId] when a fallback was used.
     */
    private val resourceRevision = java.util.concurrent.atomic.AtomicLong()
    private val themeRequestRevision = java.util.concurrent.atomic.AtomicLong()
    suspend fun selectTheme(configId: String): String {
        val request = themeRequestRevision.incrementAndGet()
        val resolvedTheme = withContext(Dispatchers.IO) {
            getThemeById(configId).let { resolved -> resolved.copy(theme = resolved.theme.copy(
                customizationVersion = resolved.theme.customizationVersion + resourceRevision.incrementAndGet())) }
        }
        return withContext(Dispatchers.Main.immediate) {
            // A slower earlier load must not overwrite the newest theme/layout selection.
            if (request != themeRequestRevision.get()) return@withContext prefs.selectedTheme.getValue()
            BitmapAssets.invalidate()
            FontOverrides.invalidate()
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
