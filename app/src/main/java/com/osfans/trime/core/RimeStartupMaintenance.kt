/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core

import java.io.File

/**
 * Decides whether a normal startup needs to run librime maintenance.
 *
 * librime's own detect_modifications also compares the data directory's mtime.
 * On Android storage that mtime is refreshed by media-provider scans and by
 * userdb/log renames, so it reports a change on every start; the old automatic
 * deployment therefore ran on every IME switch. Only source YAML timestamps are
 * compared here: creating or editing a file moves the newest timestamp past the
 * last build time, while renaming or deleting one leaves it unchanged. The
 * deployment itself still runs asynchronously, so typing is not blocked.
 */
object RimeStartupMaintenance {
    internal const val NO_BUILD_TIME = 0L
    private const val STAGING_CONFIG = "build/default.yaml"

    fun needsMaintenance(
        sharedDataDir: File,
        userDataDir: File,
    ): Boolean {
        // A missing staging normally needs a rebuild, but a fresh install has no
        // schemas to deploy and keeps the explicit first-run deployment flow.
        if (!File(userDataDir, STAGING_CONFIG).isFile) {
            return hasSchemaSource(sharedDataDir, userDataDir)
        }
        return newestSourceTime(sharedDataDir, userDataDir) > lastBuildTime(userDataDir)
    }

    /** A user data dir without schemas cannot be meaningfully redeployed. */
    internal fun hasSchemaSource(
        sharedDataDir: File,
        userDataDir: File,
    ): Boolean =
        sequenceOf(sharedDataDir, userDataDir).any { dir ->
            dir
                .listFiles()
                ?.any { it.isFile && it.name.endsWith(".schema.yaml", ignoreCase = true) } == true
        }

    fun lastBuildTime(userDataDir: File): Long =
        parseLastBuildTime(File(userDataDir, "user.yaml"))

    fun newestSourceTime(
        sharedDataDir: File,
        userDataDir: File,
    ): Long = maxOf(newestTopLevelYamlTime(sharedDataDir), newestTopLevelYamlTime(userDataDir))

    /** "var/last_build_time" written by librime after a successful workspace update. */
    internal fun parseLastBuildTime(userYaml: File): Long {
        if (!userYaml.isFile) return NO_BUILD_TIME
        return runCatching {
            Regex("(?m)^\\s*last_build_time:\\s*(\\d+)")
                .find(userYaml.readText())
                ?.groupValues
                ?.get(1)
                ?.toLong()
        }.getOrNull() ?: NO_BUILD_TIME
    }

    /**
     * Source files live next to user.yaml, so only the top level is scanned,
     * matching librime's own change detection. State files written by the
     * engine (user.yaml, installation.yaml) are not deployment inputs.
     */
    internal fun newestTopLevelYamlTime(dir: File): Long =
        dir
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".yaml", ignoreCase = true) }
            ?.filterNot { it.name == "user.yaml" || it.name == "installation.yaml" }
            ?.maxOfOrNull { it.lastModified() / 1000 } ?: 0L
}
