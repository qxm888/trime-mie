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

    private data class FileState(
        val path: String,
        val lastModified: Long,
        val size: Long,
    )

    private data class TypefaceKey(val files: List<FileState>)

    private val fontDir get() = File(DataManager.userDataDir, "fonts")

    private val fontFamilyCache =
        object : androidx.collection.LruCache<FileState, FontFamily>(16) {}

    private val typefaceCache =
        object : androidx.collection.LruCache<TypefaceKey, Typeface>(16 * 1024) {
            override fun sizeOf(key: TypefaceKey, value: Typeface): Int = key.files.sumOf {
                (it.size / 1024).toInt().coerceAtLeast(1)
            }
        }

    val hanb: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("hanb", theme.isCustom) ?: FontOverrides.typeface("hanb").takeUnless { theme.isCustom } ?: getTypeface(style.hanbFont.toList())

    val latin: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("latin", theme.isCustom) ?: FontOverrides.typeface("latin").takeUnless { theme.isCustom } ?: getTypeface(style.latinFont.toList())

    val candidate: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("candidate", theme.isCustom) ?: FontOverrides.typeface("candidate").takeUnless { theme.isCustom } ?: getTypeface(style.candidateFont.toList())

    val comment: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("comment", theme.isCustom) ?: FontOverrides.typeface("comment").takeUnless { theme.isCustom } ?: getTypeface(style.commentFont.toList())

    val key: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("key", theme.isCustom) ?: FontOverrides.typeface("key").takeUnless { theme.isCustom } ?: getTypeface(style.keyFont.toList())

    val label: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("label", theme.isCustom) ?: FontOverrides.typeface("label").takeUnless { theme.isCustom } ?: getTypeface(style.labelFont.toList())

    val popup: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("popup", theme.isCustom) ?: FontOverrides.typeface("popup").takeUnless { theme.isCustom } ?: getTypeface(style.popupFont.toList())

    val symbol: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("symbol", theme.isCustom) ?: FontOverrides.typeface("symbol").takeUnless { theme.isCustom } ?: getTypeface(style.symbolFont.toList())

    val hint: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("hint", theme.isCustom) ?: FontOverrides.typeface("hint").takeUnless { theme.isCustom } ?: symbol

    val text: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("text", theme.isCustom) ?: FontOverrides.typeface("text").takeUnless { theme.isCustom } ?: getTypeface(style.textFont.toList())

    val toolbar: Typeface
        get() = com.osfans.trime.data.keymap.LayoutTypography.typeface("toolbar", theme.isCustom) ?: FontOverrides.typeface("toolbar").takeUnless { theme.isCustom } ?: getTypeface(theme.toolBar.buttonFont.toList())

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

    private fun getTypeface(fonts: List<String>): Typeface {
        val key = resolveKey(fonts)
        val typeface = typefaceCache[key]
        return if (typeface == null) {
            Timber.d("getTypeface: key=$key")
            val newTypeface = if (key.files.isEmpty()) {
                Typeface.DEFAULT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bulk = makeFileStateList(style.latinFont.toList() + fonts + style.hanbFont.toList())
                val fontFamilies = bulk.map { getFontFamily(it) }
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
            }
            typefaceCache.put(key, newTypeface)
            newTypeface
        } else {
            typeface
        }
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
