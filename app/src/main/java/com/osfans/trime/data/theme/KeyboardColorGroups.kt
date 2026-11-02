/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import android.content.SharedPreferences
import android.content.res.Configuration
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyboardLayoutCatalog
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class KeyboardStyleTarget(val source: String, val id: String, val name: String) {
    init { require(source in KeyboardLayoutCatalog.sources && id.isNotBlank()) }
}

@Serializable
data class KeyboardColorGroup(val id: String = UUID.randomUUID().toString(), val name: String,
                              val day: KeyboardStyleTarget, val night: KeyboardStyleTarget) {
    fun target(dark: Boolean) = if (dark) night else day
}

/** Groups reference complete themes/sets; keyboard data and artwork are never copied. */
object KeyboardColorGroups {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var automaticJob: Job? = null
    private var initialized = false
    private var writing = false
    private var systemNight = false
    private val prefs get() = ThemeManager.prefs
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!writing && key in setOf("keyboard_color_groups", "keyboard_color_group_active", ThemePrefs.FOLLOW_SYSTEM_DAY_NIGHT)) schedule()
    }

    fun list(): List<KeyboardColorGroup> = runCatching {
        json.decodeFromString<List<KeyboardColorGroup>>(prefs.keyboardColorGroups.getValue())
    }.onFailure { timber.log.Timber.w(it, "Could not read keyboard color groups") }.getOrDefault(emptyList())
    fun activeId() = prefs.activeKeyboardColorGroup.getValue()
    fun active(): KeyboardColorGroup? = list().firstOrNull { it.id == activeId() }
    private fun reportedNight() = android.content.res.Resources.getSystem().configuration.let {
        it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private fun effectiveNight(reported: Boolean): Boolean {
        val mode = runCatching { (com.osfans.trime.util.appContext.getSystemService(android.content.Context.UI_MODE_SERVICE)
            as android.app.UiModeManager).nightMode }.getOrDefault(android.app.UiModeManager.MODE_NIGHT_AUTO)
        return KeyboardNightPolicy.resolve(mode, reported)
    }
    fun isNight() = effectiveNight(if (initialized) systemNight else reportedNight()) && prefs.followSystemDayNight.getValue()
    fun init(configuration: Configuration) {
        systemNight = effectiveNight(configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        if (!initialized) { initialized = true; prefs.selectedTheme.sharedPreferences.registerOnSharedPreferenceChangeListener(listener) }
        schedule()
    }
    fun onNightModeChanged(dark: Boolean) {
        val effective = effectiveNight(dark)
        if (systemNight == effective) return
        systemNight = effective
        if (initialized && prefs.followSystemDayNight.getValue()) schedule()
    }
    fun save(group: KeyboardColorGroup) {
        require(group.name.isNotBlank()) { "请输入配色组名称" }
        val values = list().toMutableList()
        require(values.none { it.id != group.id && it.name == group.name }) { "配色组名称已存在" }
        val at = values.indexOfFirst { it.id == group.id }
        if (at < 0) values.add(group) else values[at] = group
        prefs.keyboardColorGroups.setValue(json.encodeToString(values))
    }
    fun deactivate() { automaticJob?.cancel(); write { prefs.activeKeyboardColorGroup.setValue("") } }
    fun delete(id: String) {
        automaticJob?.cancel()
        write { if (activeId() == id) prefs.activeKeyboardColorGroup.setValue("")
            prefs.keyboardColorGroups.setValue(json.encodeToString(list().filterNot { it.id == id })) }
        if (activeId().isNotEmpty()) schedule()
    }
    private inline fun write(block: () -> Unit) { writing = true; try { block() } finally { writing = false } }
    private fun schedule() {
        automaticJob?.cancel()
        if (com.osfans.trime.util.BootStorage.locked(com.osfans.trime.util.appContext)) return
        val group = active() ?: return
        com.osfans.trime.util.DiagnosticLog.event("theme", "color_group_request", "follow=${prefs.followSystemDayNight.getValue()} night=${isNight()}")
        automaticJob = scope.launch {
            try {
                ThemeManager.awaitSelection()
                if (activeId() == group.id) apply(group.target(isNight()), preservePage = true)
            } catch (cancelled: CancellationException) {
                com.osfans.trime.util.DiagnosticLog.event("theme", "color_group_cancelled")
                throw cancelled
            }
            catch (error: Exception) {
                timber.log.Timber.w(error, "Automatic keyboard color group switch failed; keeping current keyboard")
                com.osfans.trime.util.DiagnosticLog.event("theme", "color_group_failed", "type=${error.javaClass.simpleName}")
            }
        }
    }
    suspend fun activate(id: String) = withContext(Dispatchers.Main.immediate) {
        val group = list().firstOrNull { it.id == id } ?: error("配色组已删除")
        automaticJob?.cancel()
        apply(group.target(isNight()), preservePage = true)
        currentCoroutineContext().ensureActive()
        write { prefs.activeKeyboardColorGroup.setValue(group.id) }
    }
    /** Selecting a layout explicitly exits group mode; filtering the picker does not. */
    suspend fun selectLayout(target: KeyboardStyleTarget) = withContext(Dispatchers.Main.immediate) {
        val previous = activeId()
        automaticJob?.cancel()
        write { prefs.activeKeyboardColorGroup.setValue("") }
        try { apply(target, preservePage = false) }
        catch (error: Throwable) { write { if (activeId().isEmpty()) prefs.activeKeyboardColorGroup.setValue(previous) }; throw error }
    }
    private suspend fun apply(target: KeyboardStyleTarget, preservePage: Boolean) {
        if (target.source == KeyboardLayoutCatalog.THEME) {
            ThemeManager.selectTheme(target.id, layoutMode = CustomKeyboards.MODE_THEME, requireExact = true)
        } else {
            CustomKeyboards.awaitBuiltinLayouts()
            val root = CustomKeyboards.get(target.id) ?: error("${target.name} 已删除，请编辑配色组")
            val oldWindow = com.osfans.trime.ime.keyboard.KeyboardWindow.instance
            val navigation = if (preservePage && CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) oldWindow?.captureRuntimeState() else null
            fun oldRole(id: String?) = id?.takeIf { it.isNotBlank() }?.let { runCatching { CustomKeyboards.pageRole(it) }.getOrNull() }
            val mainRole = oldRole(navigation?.asciiPair?.first) ?: oldRole(navigation?.main)
            val previousRole = oldRole(navigation?.last)
            val originRole = oldRole(navigation?.asciiPair?.first)
            val currentId = CustomKeyboards.resolveId(com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId)
            val role = if (preservePage && CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) CustomKeyboards.pageRole(currentId) else ""
            val page = if (role.isNotBlank()) CustomKeyboards.setPageIds(target.id).firstOrNull { CustomKeyboards.pageRole(it) == role } else null
            val selected = page ?: root.setConfig?.defaultPage?.takeIf { CustomKeyboards.get(it) != null } ?: target.id
            ThemeManager.selectTheme(prefs.selectedTheme.getValue(), layoutMode = CustomKeyboards.MODE_CUSTOM, customLayoutId = selected, requireExact = true)
            currentCoroutineContext().ensureActive()
            if (navigation != null) {
                val ids = CustomKeyboards.setPageIds(target.id)
                fun matching(value: String?) = value?.let { role -> ids.firstOrNull { CustomKeyboards.pageRole(it) == role } }
                com.osfans.trime.ime.keyboard.KeyboardWindow.instance?.restoreFamilyNavigation(
                    matching(mainRole), matching(previousRole), if (role == "en") matching(originRole) else null)
            }
        }
        CustomKeyboards.pickerSource = target.source
        com.osfans.trime.util.DiagnosticLog.event("theme", "color_target_applied", "source=${target.source} night=${isNight()}")
    }
}
