/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.core.data.DataManager
import com.osfans.trime.util.get
import com.osfans.trime.util.string
import timber.log.Timber
import java.io.File

object ThemeFilesManager {
    fun listThemes(dir: File): MutableList<ThemeItem> {
        val files = dir.listFiles { _, name -> name.endsWith("trime.yaml") } ?: return mutableListOf()
        val stamp = sourceStamp()
        return files
            .sortedByDescending { it.lastModified() }
            .map { file ->
                val configId = file.nameWithoutExtension
                val name = readSourceName(configId, file, stamp)
                    ?: readDeployedName(configId)
                    ?: configId.removeSuffix(".trime")
                ThemeItem(configId, name)
            }.toMutableList()
    }

    /** Metadata only: invalidate cached names/themes when source or build files change. */
    fun sourceStamp(): String = sequenceOf(DataManager.sharedDataDir, DataManager.userDataDir)
        .flatMap { root -> sequenceOf(root, File(root, "build")) }
        .flatMap { dir -> dir.listFiles { _, name -> name.endsWith(".yaml") }.orEmpty().asSequence() }
        .sortedBy { it.path }
        .joinToString(";") { "${it.path}:${it.lastModified()}:${it.length()}" }

    private val names = androidx.collection.LruCache<String, Pair<String, String?>>(128)

    /** Read the header scalar, not the entire theme and every included keyboard. */
    private fun readSourceName(configId: String, file: File, stamp: String): String? {
        val headerName = runCatching {
            file.bufferedReader().useLines { lines ->
                lines.take(256).firstOrNull { it.startsWith("name:") }
            }?.let { ThemeYaml.parser.parseToYamlNode(it)["name"]?.string }
        }.getOrNull()
        if (headerName != null) return headerName
        val contentStamp = stamp + ThemeSourceState.fingerprint(configId)
        synchronized(names) { names[file.path]?.takeIf { it.first == contentStamp }?.let { return it.second } }
        val name = runCatching {
            ThemeLoader.loadSourceNode(configId, file)?.get("name")?.string
        }.getOrElse { e ->
            Timber.w("Failed to read theme name ${file.name}: ${e.message}")
            null
        }
        synchronized(names) { names.put(file.path, (stamp + ThemeSourceState.fingerprint(configId)) to name) }
        return name
    }

    /** Reads the theme name from the deployed artifact, when it exists. */
    private fun readDeployedName(configId: String): String? {
        val file = File(DataManager.resolveDeployedResourcePath(configId))
        if (!file.isFile) return null
        return runCatching { ThemeYaml.parser.parseToYamlNode(file.readText())["name"]?.string }
            .getOrElse { e ->
                Timber.w("Failed to read deployed theme ${file.absolutePath}: ${e.message}")
                null
            }
    }
}
