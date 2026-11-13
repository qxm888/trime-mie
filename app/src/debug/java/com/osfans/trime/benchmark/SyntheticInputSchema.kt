/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeSession
import java.io.File

/** Dictionary-free fixture: no deployment of user schemata or user dictionary writes. */
internal class SyntheticInputSchema private constructor(
    private val session: RimeSession,
    private val originalId: String,
    private val originalAscii: Boolean,
    private val files: List<File>,
) {
    suspend fun restore() {
        try {
            session.runOnReady { clearComposition(); selectSchema(originalId); setRuntimeOption("ascii_mode", originalAscii) }
        } finally { files.forEach { it.delete() } }
    }
    companion object {
        suspend fun create(session: RimeSession, candidate: String? = null): SyntheticInputSchema {
            require(candidate == null || (candidate.length in 1..20 && candidate.all { it.isLetter() }))
            val id = "mie_audit_${java.util.UUID.randomUUID().toString().replace("-", "") }"
            val schemaFiles = listOf(File(DataManager.userDataDir, "$id.schema.yaml"), File(DataManager.stagingDir, "$id.schema.yaml"))
            val luaFile = if (candidate != null) File(DataManager.userDataDir, "lua/$id.lua") else null
            val files = schemaFiles + listOfNotNull(luaFile)
            check(files.none { it.exists() })
            val fixture = SyntheticInputSchema(session, session.runOnReady { selectedSchemaId() },
                session.runOnReady { getRuntimeOption("ascii_mode") }, files)
            val source = """
                schema:
                  schema_id: $id
                  name: Audit synthetic input
                  version: '1'
                switches:
                  - {name: ascii_mode, reset: 0}
                engine:
                  processors: [ascii_composer, speller, selector, express_editor]
                  segmentors: [ascii_segmentor, abc_segmentor, fallback_segmentor]
                  translators: [${if (candidate == null) "echo_translator" else "lua_translator@*$id"}]
                speller:
                  alphabet: abcdefghijklmnopqrstuvwxyz
            """.trimIndent()
            try {
                schemaFiles.forEach { it.parentFile?.mkdirs(); it.writeText(source) }
                luaFile?.also { it.parentFile?.mkdirs(); it.writeText("return function(input, seg) yield(Candidate(\"audit\", seg.start, seg._end, \"$candidate\", \"\")) end") }
                check(session.runOnReady { selectSchema(id) }) { "Cannot select synthetic input schema" }
                return fixture
            } catch (error: Throwable) { fixture.restore(); throw error }
        }
    }
}
