/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import com.osfans.trime.R

/** A picker category is not a third runtime mode. Stock layouts use the custom engine. */
object KeyboardLayoutCatalog {
    const val THEME = "theme"
    const val CUSTOM = "custom"
    const val BUILTIN = "builtin"
    val sources = setOf(THEME, CUSTOM, BUILTIN)
    @Volatile private var stockIds: Set<String>? = null

    fun builtinIds(context: Context): Set<String> = stockIds ?: synchronized(this) {
        stockIds ?: buildSet {
            // Stock YAML is generated with top-level preset IDs indented by two spaces.
            // Read identifiers only; avoid decoding every key merely to open the picker.
            val pattern = Regex("^  (custom_[A-Za-z0-9_-]+):\\s*$")
            for (resource in listOf(R.raw.mie_builtin_keyboards, R.raw.mie_builtin_extra, R.raw.mie_builtin_effects)) {
                context.resources.openRawResource(resource).bufferedReader().useLines { lines ->
                    lines.forEach { line -> pattern.matchEntire(line)?.groupValues?.get(1)?.let(::add) }
                }
            }
        }.also { stockIds = it }
    }

    suspend fun roots(context: Context, source: String): List<CustomKeyboards.Entry> {
        CustomKeyboards.awaitBuiltinLayouts()
        val builtin = builtinIds(context)
        return CustomKeyboards.list().filter { it.secondaryOf.isBlank() && (it.id in builtin) == (source == BUILTIN) }
            .sortedWith(compareBy<CustomKeyboards.Entry> { it.name }.thenBy { it.id })
    }
}
