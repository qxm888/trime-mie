/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import com.osfans.trime.core.data.DataManager
import java.io.File

/** Directory metadata is scanned at theme refresh, never per glyph/draw. */
object DirectoryFontFallback {
    @Volatile var files: List<File> = emptyList(); private set
    @Volatile var stamp: String = ""; private set
    @Synchronized fun refresh() {
        val folder = File(DataManager.userDataDir, "fonts")
        val next = folder.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf", "ttc", "otc") }
            .sortedBy { it.name.lowercase() }
        val signature = next.joinToString(";") { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }
        if (signature != stamp) {
            files = next; stamp = signature
            FontOverrides.invalidate(); ThemeFonts.invalidateCaches()
        }
    }
}
