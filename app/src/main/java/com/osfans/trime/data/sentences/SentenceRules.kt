/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.sentences

import java.util.Locale

internal object SentenceRules {
    const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
    const val MAX_CHARS = 256
    const val MAX_UTF16 = MAX_CHARS * 2
    const val PENDING_LIMIT = 5000
    const val LEARNED_LIMIT = 2000
    private val terminators = "。！？!?\n\r"
    private val spaces = Regex("[ \\t]+")
    private val privateNumber = Regex("[0-9]{7,}")

    fun isBoundary(cp: Int): Boolean = cp.toChar() in terminators

    fun normalize(text: String): String? {
        if (text.length > MAX_UTF16) return null
        val value = text.trim().trimEnd('。', '！', '？', '!', '?', '.', '；', ';').trim()
            .replace(spaces, " ")
        if (value.codePointCount(0, value.length) !in 4..MAX_CHARS) return null
        if (value.any { it.isISOControl() } || value.none { it.isLetter() }) return null
        if (value.contains('@') || value.contains("://") || privateNumber.containsMatchIn(value)) return null
        return value
    }

    fun lookup(text: String): String = text.lowercase(Locale.ROOT)
    /** Exclusive end of a literal prefix range in SQLite's UTF-8 BINARY ordering. */
    fun prefixEnd(prefix: String): String? {
        var end = prefix.length
        while (end > 0) {
            val cp = prefix.codePointBefore(end)
            val start = end - Character.charCount(cp)
            if (cp < Character.MAX_CODE_POINT) {
                val next = (cp + 1).let { if (it in 0xD800..0xDFFF) 0xE000 else it }
                return prefix.substring(0, start) + String(Character.toChars(next))
            }
            end = start
        }
        return null
    }
    fun recent(previous: List<Long>, now: Long): List<Long> =
        (previous.filter { it > now - WEEK_MS && it <= now } + now).sorted().takeLast(3)

    fun suffix(sentence: String, committed: String, preview: String): String? {
        val prefix = committed + preview
        if (prefix.codePointCount(0, prefix.length) < 2 || !sentence.startsWith(prefix, ignoreCase = true)) return null
        if (prefix.length >= sentence.length) return null
        return sentence.substring(committed.length)
    }
}

/** Only text produced in the current editor is retained; overlong input is ignored to its boundary. */
internal class SentenceAccumulator(private val completed: (String) -> Unit) {
    private val text = StringBuilder()
    private var overflow = false
    val prefix: String get() = if (overflow) "" else text.toString().trimStart()

    fun reset() { text.setLength(0); overflow = false }
    fun finish() {
        if (!overflow) SentenceRules.normalize(text.toString())?.let(completed)
        reset()
    }
    fun backspace() {
        if (overflow || text.isEmpty()) { reset(); return }
        val end = text.length
        text.delete(end - Character.charCount(Character.codePointBefore(text, end)), end)
    }
    fun append(value: String) {
        if (value.length > 2048) { reset(); overflow = true; return }
        var offset = 0
        while (offset < value.length) {
            val cp = value.codePointAt(offset)
            offset += Character.charCount(cp)
            if (SentenceRules.isBoundary(cp)) { finish(); continue }
            if (overflow) continue
            if (text.length + Character.charCount(cp) > SentenceRules.MAX_UTF16) {
                text.setLength(0); overflow = true
            } else text.appendCodePoint(cp)
        }
    }
}
