/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import androidx.annotation.RequiresApi
import com.osfans.trime.core.data.DataManager
import timber.log.Timber
import java.io.File

class ThemeFonts(private val theme: Theme) {
    private val style = theme.style
    private val fontNames = mapOf(
        "hanb" to style.hanbFont.toList(),
        "latin" to style.latinFont.toList(),
        "candidate" to style.candidateFont.toList(),
        "comment" to style.commentFont.toList(),
        "key" to style.keyFont.toList(),
        "label" to style.labelFont.toList(),
        "popup" to style.popupFont.toList(),
        "symbol" to style.symbolFont.toList(),
        "text" to style.textFont.toList(),
        "toolbar" to theme.toolBar.buttonFont.toList(),
    )

    private data class FileState(
        val path: String,
        val lastModified: Long,
        val size: Long,
    )

    private data class TypefaceKey(val files: List<FileState>)

    private val fontDir get() = File(DataManager.userDataDir, "fonts")

    // Resolve metadata once per immutable theme, not once per draw/bind. Font file
    // bytes are not Typeface heap usage: large Han fonts previously evicted themselves.
    private val resolved = mutableMapOf<List<String>, Typeface>()
    companion object {
        private val fontFamilyCache = object : androidx.collection.LruCache<FileState, FontFamily>(24) {}
        private val typefaceCache = object : androidx.collection.LruCache<TypefaceKey, Typeface>(24) {}
    }

    val hanb: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("hanb", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("hanb")) ?: getTypeface(fontNames.getValue("hanb"))

    val latin: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("latin", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("latin")) ?: getTypeface(fontNames.getValue("latin"))

    val candidate: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("candidate", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("candidate")) ?: getTypeface(fontNames.getValue("candidate"))

    val comment: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("comment", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("comment")) ?: getTypeface(fontNames.getValue("comment"))

    val key: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("key", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("key")) ?: getTypeface(fontNames.getValue("key"))

    val label: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("label", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("label")) ?: getTypeface(fontNames.getValue("label"))

    val popup: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("popup", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("popup")) ?: getTypeface(fontNames.getValue("popup"))

    val symbol: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("symbol", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("symbol")) ?: getTypeface(fontNames.getValue("symbol"))

    val hint: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("hint", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("hint")) ?: symbol

    val text: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("text", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("text")) ?: getTypeface(fontNames.getValue("text"))

    val toolbar: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("toolbar", theme.isCustom) ?: (if (theme.isCustom) null else FontOverrides.typeface("toolbar")) ?: getTypeface(fontNames.getValue("toolbar"))

    private fun makeFileStateList(names: List<String>): List<FileState> = names.asSequence()
        .map { File(fontDir, it) }
        .filter { it.isFile }
        .map { FileState(it.path, it.lastModified(), it.length()) }
        .toList()

    private fun resolveKey(fonts: List<String>): TypefaceKey {
        val existing = makeFileStateList(fonts)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TypefaceKey(existing)
        } else {
            TypefaceKey(existing.take(1))
        }
    }

    @Synchronized private fun getTypeface(fonts: List<String>): Typeface = resolved.getOrPut(fonts) {
        val primary = resolveKey(fonts)
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && primary.files.isNotEmpty())
            TypefaceKey(makeFileStateList(style.latinFont.toList() + fonts + style.hanbFont.toList()).distinct()) else primary
        synchronized(typefaceCache) {
        val typeface = typefaceCache[key]
        if (typeface == null) {
            Timber.d("getTypeface: key=$key")
            val newTypeface = runCatching { if (key.files.isEmpty()) {
                Typeface.DEFAULT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fontFamilies = key.files.map { getFontFamily(it) }
                Typeface.CustomFallbackBuilder(fontFamilies.first())
                    .apply {
                        for (i in 1 until fontFamilies.size) {
                            addCustomFallback(fontFamilies[i])
                        }
                        setSystemFallback("sans-serif")
                    }
                    .build()
            } else {
                Typeface.createFromFile(key.files.first().path)
            } }.getOrElse { Timber.w(it, "Theme font unavailable; using system font"); Typeface.DEFAULT }
            typefaceCache.put(key, newTypeface)
            newTypeface
        } else {
            typeface
        }
        }
    }

    fun preload() {
        runCatching { listOf(key, candidate, comment, label, popup, symbol, hint, text, toolbar) }
            .onFailure { Timber.w(it, "Font preload failed") }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun getFontFamily(state: FileState): FontFamily {
        val family = fontFamilyCache[state]
        return if (family == null) {
            val newFamily = FontFamily.Builder(
                Font.Builder(File(state.path)).build(),
            ).build()
            fontFamilyCache.put(state, newFamily)
            newFamily
        } else {
            family
        }
    }
}
