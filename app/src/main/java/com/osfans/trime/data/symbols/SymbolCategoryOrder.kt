/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.symbols

import androidx.preference.PreferenceManager
import com.osfans.trime.util.appContext
import org.json.JSONArray

object SymbolCategoryOrder {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(appContext)
    private const val KEY = "mie_symbol_category_order"
    fun ordered(input: List<BuiltinSymbolCatalog.Category>): List<BuiltinSymbolCatalog.Category> {
        val saved = runCatching {
            val array = JSONArray(prefs.getString(KEY, "[]")); (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
        val byId = input.associateBy { it.id }
        return (saved + input.map { it.id }).distinct().mapNotNull(byId::get)
    }
    fun save(ids: List<String>) { prefs.edit().putString(KEY, JSONArray(ids.distinct()).toString()).apply() }
    fun reset() { prefs.edit().remove(KEY).apply() }
}
