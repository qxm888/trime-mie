/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.composition

import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.LruCache
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.theme.DirectoryFontFallback
import com.osfans.trime.data.theme.FontOverrides

/** Preserve the selected text font; fall back only for a complete missing/emoji cluster. */
object PreeditFontFallback {
    private data class Key(val primary: Typeface, val text: String, val directory: String, val layouts: Long)
    private data class Face(val value: Typeface?)
    private val cache = LruCache<Key, Face>(512)
    private class FontSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(paint: TextPaint) { paint.typeface = face }
        override fun updateMeasureState(paint: TextPaint) { paint.typeface = face }
    }

    fun apply(text: CharSequence, primary: Typeface, extras: () -> List<Typeface>): CharSequence {
        if (text.isEmpty()) return text
        val normal = TextPaint().apply { typeface = primary }
        val system = TextPaint().apply { typeface = Typeface.DEFAULT }
        val probe = TextPaint()
        val fallbackFaces by lazy {
            (extras() + listOfNotNull(FontOverrides.fromSelection(
                FontOverrides.encode(DirectoryFontFallback.files.map { it.path })))).distinct().filter { it != primary }
        }
        val directory = DirectoryFontFallback.stamp
        val layouts = CustomKeyboards.version
        var result: SpannableStringBuilder? = null
        var start = 0
        while (start < text.length) {
            var end = start + Character.charCount(Character.codePointAt(text, start))
            val first = Character.codePointAt(text, start)
            if (first in 0x1F1E6..0x1F1FF && end < text.length && Character.codePointAt(text, end) in 0x1F1E6..0x1F1FF)
                end += Character.charCount(Character.codePointAt(text, end))
            while (end < text.length) {
                val next = Character.codePointAt(text, end)
                val type = Character.getType(next)
                if (next == 0x200D) {
                    end += Character.charCount(next)
                    if (end < text.length) end += Character.charCount(Character.codePointAt(text, end))
                } else if (next in 0xFE00..0xFE0F || next in 0xE0100..0xE01EF || next in 0x1F3FB..0x1F3FF ||
                    next in 0xE0020..0xE007F || type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) {
                    end += Character.charCount(next)
                } else break
            }
            val cluster = text.subSequence(start, end).toString()
            val key = Key(primary, cluster, directory, layouts)
            val face = synchronized(cache) { cache.get(key) } ?: run {
                var emoji = false; var i = 0
                while (i < cluster.length) {
                    val cp = Character.codePointAt(cluster, i)
                    if (cp in 0x1F000..0x1FAFF || cp == 0xFE0F || cp == 0x20E3) emoji = true
                    i += Character.charCount(cp)
                }
                val selected = when {
                    emoji && (Build.VERSION.SDK_INT < 23 || system.hasGlyph(cluster)) -> Typeface.DEFAULT
                    Build.VERSION.SDK_INT >= 23 && !normal.hasGlyph(cluster) -> fallbackFaces.firstOrNull {
                        probe.typeface = it; probe.hasGlyph(cluster)
                    }
                    else -> null
                }
                Face(selected).also { synchronized(cache) { cache.put(key, it) } }
            }
            if (face.value != null && face.value != primary) {
                if (result == null) result = SpannableStringBuilder(text)
                result.setSpan(FontSpan(face.value), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            start = end
        }
        return result ?: text
    }
}
