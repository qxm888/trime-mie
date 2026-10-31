/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.theme.model.TextKeyboard

/** Pure preset selection policy; window creation, Rime calls and view mutation stay in the host. */
internal class KeyboardNavigation(
    private val presets: Map<String, TextKeyboard>,
    private val custom: Boolean,
) {
    private val ids = presets.keys.filter { !custom || it != "default" }

    data class State(
        val current: String = "",
        val resolvedCurrent: String = current,
        val last: String = "",
        val locked: String = "",
        val ascii: String? = null,
        val currentIsLocked: Boolean = false,
        val schema: String = "",
        val alphabet: String = "",
        val landscape: Boolean = false,
    )

    private fun default(state: State): String {
        if (state.schema in ids) return state.schema
        val match = when {
            state.alphabet.all { it.isLetter() } -> "qwerty"
            state.alphabet.all { it.isLetter() || it in ",./;" } -> "qwerty_"
            state.alphabet.all { it.isLetterOrDigit() } -> "qwerty0"
            else -> "default"
        }
        return if (match in ids) match else "default"
    }

    fun select(request: String, state: State): String {
        val index = ids.indexOf(state.resolvedCurrent)
        val selected = when (request) {
            // 返回主键盘：优先回到最近选中的主键盘（lock=true，如 26/9/14/18 键），否则主题默认。
            ".default" -> state.locked.takeIf { it.isNotEmpty() && it in ids } ?: default(state)
            ".prior" -> ids.getOrNull(index - 1) ?: if (custom) ids.lastOrNull().orEmpty() else state.current
            ".next" -> ids.getOrNull(index + 1) ?: if (custom) ids.firstOrNull().orEmpty() else state.current
            ".last" -> state.last
            ".last_lock" -> state.locked
            ".ascii" -> (state.ascii?.takeIf { it.isNotEmpty() } ?: state.locked).let { if (it in ids) it else state.current }
            else -> request.ifEmpty { if (state.currentIsLocked) state.current else state.locked }
        }.ifEmpty { default(state) }
        return if (state.landscape) {
            presets[selected]?.landscapeKeyboard?.takeIf { it.isNotEmpty() && it in ids } ?: selected
        } else selected
    }
}
