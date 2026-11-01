/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.symbols

import com.osfans.trime.R
import com.osfans.trime.util.appContext
import org.json.JSONObject

/** One compact shared catalogue; loaded off the UI thread only when the panel opens. */
object BuiltinSymbolCatalog {
    data class Category(val id: String, val name: String, val wide: Boolean, val items: List<String>)
    val categories: List<Category> by lazy {
        val document = appContext.resources.openRawResource(R.raw.mie_symbol_catalog).bufferedReader().use { JSONObject(it.readText()) }
        val list = document.getJSONArray("categories")
        (0 until list.length()).map { i ->
            val item = list.getJSONObject(i); val values = item.getJSONArray("items")
            Category(item.getString("id"), item.getString("name"), item.optBoolean("wide"),
                (0 until values.length()).map(values::getString))
        }
    }
}
