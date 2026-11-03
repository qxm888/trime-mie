/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.symbols

import androidx.preference.PreferenceManager
import com.osfans.trime.util.appContext
import org.json.JSONArray
import java.security.MessageDigest

/** Independent orders per source/category; duplicate entries and newly added symbols survive. */
object SymbolOrder {
    private val prefs get() = PreferenceManager.getDefaultSharedPreferences(appContext)
    private fun key(namespace: String) = "mie_symbol_order_" + MessageDigest.getInstance("SHA-256")
        .digest(namespace.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun <T> identities(input: List<T>, identity: (T) -> String): List<String> {
        val counts = mutableMapOf<String, Int>()
        return input.map { item ->
            val id = identity(item); val occurrence = counts.getOrDefault(id, 0); counts[id] = occurrence + 1
            JSONArray().put(id).put(occurrence).toString()
        }
    }
    fun <T> ordered(namespace: String, input: List<T>, identity: (T) -> String): List<T> {
        val ids = identities(input, identity)
        val saved = runCatching {
            val array = JSONArray(prefs.getString(key(namespace), "[]"))
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
        val byId = ids.zip(input).toMap()
        return (saved + ids).distinct().mapNotNull(byId::get)
    }
    fun <T> save(namespace: String, input: List<T>, identity: (T) -> String) {
        prefs.edit().putString(key(namespace), JSONArray(identities(input, identity)).toString()).apply()
    }
    fun reset(namespace: String) { prefs.edit().remove(key(namespace)).apply() }
}
