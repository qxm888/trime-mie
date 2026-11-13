/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.stats

/** Keeps uncommitted code keys with their composition, including codes left by auto-pop. */
internal class CompositionCodeCounter {
    var rawInput: String = ""
        private set
    var pendingKeys: Long = 0
        private set

    data class Commit(val rawLength: Int, val codeLength: Int, val keyTouches: Long)

    fun reset() {
        rawInput = ""
        pendingKeys = 0
    }

    fun processed(after: String, committed: Boolean) {
        val input = after.takeUnless { it.startsWith("/") }.orEmpty()
        // commit() has already split the keys between the committed prefix and the remainder.
        if (!committed && input != rawInput && input.isNotEmpty()) {
            val beforeLen = rawInput.length
            val afterLen = input.length
            if (afterLen > beforeLen || afterLen == 1 &&
                (beforeLen == 1 || beforeLen > 2 || beforeLen == 2 && input[0] != rawInput[0])) {
                pendingKeys++
            }
        }
        rawInput = input
        if (input.isEmpty()) pendingKeys = 0
    }

    /**
     * [inputKey] means this event adds a code character, rather than selecting a candidate.
     * That character may finish a full-code commit OR remain in the next composition.
     * A manual selection (including a candidate tap) contributes one confirmation key.
     */
    fun commit(before: String?, after: String, inputKey: Boolean): Commit {
        val source = before ?: rawInput
        val added = if (inputKey) 1 else 0
        val consumed = (source.length + added - after.length).coerceAtLeast(0)
        val availableKeys = pendingKeys + added
        val carriedKeys = minOf(after.length.toLong(), availableKeys)
        val result = Commit(
            rawLength = consumed,
            codeLength = consumed + if (!inputKey && consumed > 0) 1 else 0,
            keyTouches = availableKeys - carriedKeys,
        )
        rawInput = after.takeUnless { it.startsWith("/") }.orEmpty()
        pendingKeys = if (rawInput.isEmpty()) 0 else carriedKeys
        return result
    }
}
