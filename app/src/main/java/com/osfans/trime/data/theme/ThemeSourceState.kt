/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import android.content.Context
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.appContext
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** Regenerable state, scoped to a data directory; no reliance on editor mtimes. */
internal object ThemeSourceState {
    private val resources = mutableMapOf<String, List<String>>()
    private val prefs get() = appContext.getSharedPreferences("theme_source_state", Context.MODE_PRIVATE)
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun key(id: String) = digest("${DataManager.userDataDir.canonicalPath}|${DataManager.sharedDataDir.canonicalPath}|$id")
    private fun defaults(id: String) = listOf(id, id.removeSuffix(".schema") + ".custom")
    private fun ids(id: String): List<String> {
        val key = key(id)
        synchronized(resources) { resources[key]?.let { return it } }
        val stored = runCatching {
            val array = JSONArray(prefs.getString("sources.$key", "[]"))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
        return (defaults(id) + stored).distinct()
    }
    fun remember(id: String, sourceIds: Collection<String>) {
        val key = key(id)
        // Keep native-only dependencies until a successful native build replaces them.
        val merged = (ids(id) + sourceIds).distinct().sorted()
        synchronized(resources) { resources[key] = merged }
    }
    fun fingerprint(id: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        for (resource in ids(id).sorted()) {
            val file = ThemeLoader.findSourceFile(resource, listOf(DataManager.userDataDir, DataManager.sharedDataDir))
            sha.update((resource + "\u0000" + (file?.canonicalPath ?: "missing") + "\u0000").toByteArray())
            if (file != null) file.inputStream().buffered().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; sha.update(buffer, 0, count) }
            }
            sha.update(0.toByte())
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    fun needsCompile(id: String): Boolean = !File(DataManager.stagingDir, "$id.yaml").isFile ||
        prefs.getString("compiled.${key(id)}", null) != fingerprint(id)
    fun compiled(id: String, sourceIds: Collection<String>) {
        val source = (defaults(id) + sourceIds).distinct().sorted()
        val key = key(id)
        synchronized(resources) { resources[key] = source }
        check(prefs.edit().putString("sources.$key", JSONArray(source).toString())
            .putString("compiled.$key", fingerprint(id)).commit())
    }
}
