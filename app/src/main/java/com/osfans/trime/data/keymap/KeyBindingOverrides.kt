/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.keymap

import android.content.Context
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
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

/**
 * Per-key behavior overrides for the visual key editor: an overlay stored in
 * app-private storage, applied on top of the theme when a [com.osfans.trime.ime.keyboard.Keyboard]
 * is built — the theme files themselves are never modified.
 *
 * Identity of a binding: keyboard id (a key of `theme.presetKeyboards`, the same
 * id passed to `KeyboardWindow.attachKeyboard`) + the key's index in the resolved
 * `TextKeyboard.keys` list + a behavior field name ("click", "longClick",
 * "swipeUp", …, matching [TextKeyboard.TextKey]).
 *
 * A stored token of "" explicitly unbinds the behavior; removing the entry
 * restores the theme default.
 */
object KeyBindingOverrides {
    private const val EXPORT_FORMAT = "trime-key-bindings"

    /** Behaviors exposed by the visual editor, in display order. */
    val EDITABLE_BEHAVIORS = listOf(
        "click" to BehaviorMeta("点按", "Tap"),
        "longClick" to BehaviorMeta("长按", "Long press"),
        "swipeUp" to BehaviorMeta("上滑", "Swipe up"),
        "swipeDown" to BehaviorMeta("下滑", "Swipe down"),
        "swipeLeft" to BehaviorMeta("左滑", "Swipe left"),
        "swipeRight" to BehaviorMeta("右滑", "Swipe right"),
        "doubleClick" to BehaviorMeta("双击", "Double tap"),
    )

    data class BehaviorMeta(val labelZh: String, val labelEn: String)

    @Serializable
    private data class Store(
        var version: Long = 1,
        /** keyboard id -> key index -> behavior -> token */
        val keyboards: MutableMap<String, MutableMap<Int, MutableMap<String, String>>> = mutableMapOf(),
    )

    /** Wrapper written to / read from user-picked files (SAF backup). */
    @Serializable
    private data class ExportFile(
        val format: String = EXPORT_FORMAT,
        val formatVersion: Int = 1,
        val exportedAt: Long = 0,
        val data: Store = Store(),
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

    fun init(context: Context) {
        synchronized(lock) {
            appContext = com.osfans.trime.util.storageContext(context)
            runCatching {
                val file = storeFile()
                file.readAtomicText()?.let { contents ->
                    store = json.decodeFromString(Store.serializer(), contents)
                }
            }.onFailure { Timber.w(it, "KeyBindingOverrides: load failed, starting fresh") }
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
        }.onFailure { Timber.w(it, "KeyBindingOverrides: save failed") }.isSuccess
    }

    private fun storeFile(): File = File(appContext!!.filesDir, "key_binding_overrides.json")

    /** All overrides of one keyboard: index -> (behavior -> token). */
    fun bindingsFor(keyboard: String): Map<Int, Map<String, String>> =
        synchronized(lock) { store.keyboards[keyboard]?.mapValues { it.value.toMap() } ?: emptyMap() }

    /** Current override token for one behavior, or null when the theme default applies. */
    fun binding(
        keyboard: String,
        index: Int,
        behavior: String,
    ): String? = synchronized(lock) { store.keyboards[keyboard]?.get(index)?.get(behavior) }

    /**
     * Set an override. `token == null` removes the override (theme default);
     * `token == ""` explicitly unbinds the behavior.
     */
    fun setBinding(
        keyboard: String,
        index: Int,
        behavior: String,
        token: String?,
    ) {
        synchronized(lock) {
            if (token == null) {
                store.keyboards[keyboard]?.get(index)?.remove(behavior)
                store.keyboards[keyboard]?.get(index)?.takeIf { it.isEmpty() }?.let {
                    store.keyboards[keyboard]?.remove(index)
                }
                store.keyboards[keyboard]?.takeIf { it.isNullOrEmpty() }?.let {
                    store.keyboards.remove(keyboard)
                }
            } else {
                store.keyboards.getOrPut(keyboard) { mutableMapOf() }
                    .getOrPut(index) { mutableMapOf() }[behavior] = token
            }
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    fun clearKey(
        keyboard: String,
        index: Int,
    ) {
        synchronized(lock) {
            store.keyboards[keyboard]?.remove(index)
            store.keyboards[keyboard]?.takeIf { it.isNullOrEmpty() }?.let {
                store.keyboards.remove(keyboard)
            }
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    fun resetAll() {
        synchronized(lock) {
            store.keyboards.clear()
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }

    /**
     * Returns a copy of [key] with the overrides for `keyboard`/`index` applied.
     * Called at the exact point where a theme key definition becomes a runtime key.
     */
    fun applyTo(
        keyboard: String,
        index: Int,
        key: TextKeyboard.TextKey,
    ): TextKeyboard.TextKey {
        val map = synchronized(lock) { store.keyboards[keyboard]?.get(index)?.toMap() } ?: return key
        var result = key
        for ((behavior, token) in map) {
            val t: KeyActionToken? = if (token.isEmpty()) null else KeyActionToken.Plain(token)
            result =
                when (behavior) {
                    "click" -> result.copy(click = t)
                    "doubleClick" -> result.copy(doubleClick = t)
                    "lazyDoubleClick" -> result.copy(lazyDoubleClick = t)
                    "longClick" -> result.copy(longClick = t, allowDefaultVoice = false)
                    "swipeUp" -> result.copy(swipeUp = t)
                    "swipeDown" -> result.copy(swipeDown = t)
                    "swipeLeft" -> result.copy(swipeLeft = t)
                    "swipeRight" -> result.copy(swipeRight = t)
                    "composing" -> result.copy(composing = t)
                    "hasMenu" -> result.copy(hasMenu = t)
                    "paging" -> result.copy(paging = t)
                    "combo" -> result.copy(combo = t)
                    "ascii" -> result.copy(ascii = t)
                    else -> result
                }
        }
        return result
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

    /** Total number of customized bindings, for the "nothing to export" hint. */
    fun count(): Int =
        synchronized(lock) { store.keyboards.values.sumOf { k -> k.values.sumOf { it.size } } }

    /** Serializes all overrides to a self-describing JSON document. */
    fun exportJson(now: Long = System.currentTimeMillis()): String =
        synchronized(lock) {
            json.encodeToString(ExportFile.serializer(), ExportFile(exportedAt = now, data = store))
        }

    /**
     * Imports bindings from a previously exported document.
     *
     * @param replace true: the imported set fully replaces current bindings;
     *   false: bindings merge, the imported token wins per (keyboard, index, behavior)
     * @throws IllegalArgumentException on an unrecognized file
     */
    fun importJson(
        text: String,
        replace: Boolean,
    ) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw IllegalArgumentException("not a Trime key binding backup", it)
        }
        val file = if ("format" in root) {
            json.decodeFromString(ExportFile.serializer(), text).also {
                require(it.format == EXPORT_FORMAT && it.formatVersion == 1) { "unsupported key binding backup" }
            }
        } else {
            require("keyboards" in root && "version" in root) { "not a Trime key binding backup" }
            ExportFile(data = json.decodeFromString(Store.serializer(), text))
        }
        synchronized(lock) {
            if (replace) {
                store.keyboards.clear()
            }
            file.data.keyboards.forEach { (kb, keys) ->
                keys.forEach { (idx, behaviors) ->
                    behaviors.forEach { (behavior, token) ->
                        store.keyboards.getOrPut(kb) { mutableMapOf() }
                            .getOrPut(idx) { mutableMapOf() }[behavior] = token
                    }
                }
            }
            store.version++
            dirty = true
            scheduleSaveLocked()
        }
    }
}
