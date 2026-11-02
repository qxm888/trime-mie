/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import androidx.lifecycle.lifecycleScope
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyboardModeBinding
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Mode-local selection with a persistent return point; queued requests cannot overwrite a newer one. */
internal class KeyboardModeBindingController(private val service: TrimeInputMethodService, private val session: RimeSession) {
    @Serializable private data class Snapshot(val schema: String, val theme: String, val mode: String, val layout: String, val ascii: Boolean)
    private val json = Json { ignoreUnknownKeys = true }
    private val shared get() = AppPrefs.defaultInstance().keyboard.oneHandMode.sharedPreferences
    private val mutex = Mutex()
    private var revision = 0L
    private var signature = ""
    private var previous = runCatching { json.decodeFromString<Snapshot>(shared.getString("keyboard_mode_return", "").orEmpty()) }.getOrNull()

    fun request(mode: String) {
        val binding = when(mode) { "oneHand" -> KeyboardModeBinding.read(true); "floating" -> KeyboardModeBinding.read(false); else -> KeyboardModeBinding() }
        val nextSignature = mode + json.encodeToString(binding)
        if(signature == nextSignature) return
        signature = nextSignature
        val request = ++revision
        com.osfans.trime.util.DiagnosticLog.event("keyboard", "mode_binding_request", "mode=$mode bound=${binding.configured} return=${previous != null} currentMode=${CustomKeyboards.mode} layout=${KeyboardWindow.lastAttachedId}")
        if(previous == null && binding.configured) {
            val status = session.run { statusCached }
            val layout = KeyboardWindow.lastAttachedId
            previous = Snapshot(status.schemaId, ThemeManager.prefs.selectedTheme.getValue(), CustomKeyboards.mode,
                if(CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) CustomKeyboards.resolveId(layout).ifBlank { CustomKeyboards.activeId } else layout,
                status.isAsciiMode)
            shared.edit().putString("keyboard_mode_return", json.encodeToString(previous!!)).apply()
            com.osfans.trime.util.DiagnosticLog.event("keyboard", "mode_binding_captured", "mode=${previous!!.mode} layout=${previous!!.layout}")
        }
        if(!binding.configured && previous == null) return
        service.lifecycleScope.launch {
            mutex.withLock {
                if(request != revision) return@withLock
                val baseline = previous ?: return@withLock
                try {
                    withTimeout(12000) {
                        if(!binding.configured) {
                            restore(baseline)
                            if(request == revision) { previous = null; shared.edit().remove("keyboard_mode_return").apply() }
                        } else {
                            // Each mode starts from the same return point, including its unbound fields.
                            val targetSchema = binding.schemaId.ifBlank { baseline.schema }
                            selectSchema(targetSchema)
                            if(request != revision) return@withTimeout
                            val targetMode = binding.layoutMode.ifBlank { baseline.mode }
                            val targetLayout = binding.layoutId.ifBlank { baseline.layout }
                            selectLayout(binding.themeId.ifBlank { baseline.theme }, targetMode, targetLayout)
                        }
                    }
                } catch(error: CancellationException) {
                    if(error !is TimeoutCancellationException) throw error
                    fail(baseline, "模式绑定切换超时", request)
                } catch(error: Exception) { fail(baseline, error.message ?: "模式绑定切换失败", request) }
            }
        }
    }

    private suspend fun selectSchema(id: String) {
        if(id.isBlank()) return
        session.runOnReady {
            if(selectedSchemaId() != id) {
                require(selectedSchemata().any { it.id == id }) { "绑定的方案未启用，请手动部署后重新选择" }
                check(selectSchema(id)) { "无法切换绑定方案" }
            }
        }
    }
    private suspend fun selectLayout(theme: String, mode: String, id: String) {
        if(mode == CustomKeyboards.MODE_CUSTOM) require(CustomKeyboards.get(id) != null) { "绑定的键盘布局已删除" }
        val selected = ThemeManager.selectTheme(theme, layoutMode = mode, customLayoutId = id.takeIf { mode == CustomKeyboards.MODE_CUSTOM })
        require(theme.isBlank() || selected == theme) { "绑定的主题已不可用，请重新选择" }
        require(id.isBlank() || id.startsWith('.') || ThemeManager.activeThemeOrNull()?.presetKeyboards?.containsKey(id) == true) {
            "绑定的键盘布局已不可用，请重新选择"
        }
        KeyboardWindow.instance?.switchKeyboardFromAction(id.ifBlank { ".default" })
    }
    private suspend fun restore(snapshot: Snapshot) {
        selectSchema(snapshot.schema)
        selectLayout(snapshot.theme, snapshot.mode, snapshot.layout)
        session.runOnReady { setRuntimeOption("ascii_mode", snapshot.ascii) }
    }
    private suspend fun fail(baseline: Snapshot, message: String, request: Long) {
        val restored = withTimeoutOrNull(8000) { runCatching { restore(baseline) }.isSuccess } == true
        if(restored && request == revision) { previous = null; shared.edit().remove("keyboard_mode_return").apply() }
        com.osfans.trime.util.DiagnosticLog.event("keyboard", "mode_binding_failed", "restored=$restored")
        android.widget.Toast.makeText(service, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
