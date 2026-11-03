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
        val deleted = deleted(namespace)
        val byId = ids.zip(input).filter { identity(it.second) !in deleted }.toMap()
        return (saved + ids).distinct().mapNotNull(byId::get)
    }
    fun <T> save(namespace: String, input: List<T>, identity: (T) -> String) {
        prefs.edit().putString(key(namespace), JSONArray(identities(input, identity)).toString()).apply()
    }
    private fun deletedKey(namespace: String) = key(namespace) + "_deleted"
    private fun deleted(namespace: String): Set<String> = prefs.getStringSet(deletedKey(namespace),emptySet()).orEmpty().toSet()
    fun hasDeleted(namespace: String) = deleted(namespace).isNotEmpty()
    /** Deletion is scoped to a source/category and survives catalog or theme updates. */
    fun delete(namespace: String, identity: String) {
        prefs.edit().putStringSet(deletedKey(namespace),deleted(namespace)+identity).apply()
    }
    fun restoreDeleted(namespace: String) { prefs.edit().remove(deletedKey(namespace)).apply() }
    fun reset(namespace: String) { prefs.edit().remove(key(namespace)).apply() }
}
