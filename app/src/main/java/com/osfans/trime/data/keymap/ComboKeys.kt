/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.view.KeyEvent
import com.osfans.trime.util.appContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * User-defined hardware key combinations (Ctrl+Enter, Ctrl+Alt+Del, ...).
 *
 * A combo fires its binding action on key-down and consumes the event before the
 * app or Rime ever sees it. Matching requires the pressed modifier set to equal
 * the combo's set exactly, so Ctrl+Enter never fires for Ctrl+Alt+Enter.
 */
object ComboKeys {
    @Serializable
    data class Definition(val id: String, val meta: Int, val keyCode: Int, val action: String, val label: String = "")

    private const val SUPPORTED_META =
        KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs get() = appContext.getSharedPreferences("combo_keys", android.content.Context.MODE_PRIVATE)
    private var cachedRaw: String? = null
    private var cached = emptyList<Definition>()
    val version get() = prefs.getLong("version", 0L)

    @Synchronized
    fun list(): List<Definition> {
        val raw = prefs.getString("definitions", "[]") ?: "[]"
        if (cachedRaw != raw) {
            cached = runCatching { json.decodeFromString<List<Definition>>(raw) }.getOrDefault(emptyList())
            cachedRaw = raw
        }
        return cached
    }

    @Synchronized
    fun save(definitions: List<Definition>) {
        require(definitions.size <= 64) { "组合键过多（上限 64）" }
        require(definitions.map { it.meta to it.keyCode }.distinct().size == definitions.size) { "存在重复的组合键" }
        definitions.forEach {
            require(it.meta != 0 && it.meta and SUPPORTED_META.inv() == 0) { "修饰键需为 Ctrl / Alt / Shift / Meta 的组合" }
            require(it.keyCode != 0) { "组合键缺少主键" }
            require(it.action.isNotBlank() && it.action.length <= 16384) { "动作无效" }
            require(it.label.length <= 40) { "名称过长" }
        }
        check(prefs.edit().putString("definitions", json.encodeToString(definitions)).putLong("version", version + 1).commit()) { "组合键保存失败" }
        cachedRaw = null
    }

    /** Exact-modifier match: the pressed modifier set must equal the combo's set. */
    fun match(metaState: Int, keyCode: Int): Definition? = matchIn(list(), metaState, keyCode)

    fun matchIn(definitions: List<Definition>, metaState: Int, keyCode: Int): Definition? {
        val pressed = metaState and SUPPORTED_META
        return definitions.firstOrNull { it.keyCode == keyCode && it.meta == pressed }
    }

    fun metaLabel(meta: Int): String = buildList {
        if (meta and KeyEvent.META_CTRL_ON != 0) add("Ctrl")
        if (meta and KeyEvent.META_ALT_ON != 0) add("Alt")
        if (meta and KeyEvent.META_SHIFT_ON != 0) add("Shift")
        if (meta and KeyEvent.META_META_ON != 0) add("Meta")
    }.joinToString("+")

    fun describe(definition: Definition): String =
        metaLabel(definition.meta).takeIf { it.isNotEmpty() }?.plus("+").orEmpty() + keyLabel(definition.keyCode)

    fun keyLabel(code: Int): String = KEY_LABELS[code]
        ?: KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')

    private val KEY_LABELS = buildMap {
        put(KeyEvent.KEYCODE_ENTER, "Enter"); put(KeyEvent.KEYCODE_SPACE, "Space"); put(KeyEvent.KEYCODE_TAB, "Tab")
        put(KeyEvent.KEYCODE_DEL, "Backspace"); put(KeyEvent.KEYCODE_FORWARD_DEL, "Delete"); put(KeyEvent.KEYCODE_ESCAPE, "Esc")
        put(KeyEvent.KEYCODE_DPAD_UP, "↑"); put(KeyEvent.KEYCODE_DPAD_DOWN, "↓")
        put(KeyEvent.KEYCODE_DPAD_LEFT, "←"); put(KeyEvent.KEYCODE_DPAD_RIGHT, "→")
        put(KeyEvent.KEYCODE_PAGE_UP, "PageUp"); put(KeyEvent.KEYCODE_PAGE_DOWN, "PageDown")
        put(KeyEvent.KEYCODE_MOVE_HOME, "Home"); put(KeyEvent.KEYCODE_MOVE_END, "End")
        put(KeyEvent.KEYCODE_COMMA, ","); put(KeyEvent.KEYCODE_PERIOD, ".")
        put(KeyEvent.KEYCODE_SLASH, "/"); put(KeyEvent.KEYCODE_SEMICOLON, ";"); put(KeyEvent.KEYCODE_APOSTROPHE, "'")
        put(KeyEvent.KEYCODE_MINUS, "-"); put(KeyEvent.KEYCODE_EQUALS, "=")
        put(KeyEvent.KEYCODE_LEFT_BRACKET, "["); put(KeyEvent.KEYCODE_RIGHT_BRACKET, "]")
        put(KeyEvent.KEYCODE_BACKSLASH, "\\"); put(KeyEvent.KEYCODE_GRAVE, "`")
        for (i in 0..25) put(KeyEvent.KEYCODE_A + i, ('A' + i).toString())
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, i.toString())
        for (i in 1..12) put(KeyEvent.KEYCODE_F1 + i - 1, "F$i")
    }
}
