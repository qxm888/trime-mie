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
        suspend fun create(session: RimeSession): SyntheticInputSchema {
            val id = "mie_audit_${java.util.UUID.randomUUID().toString().replace("-", "") }"
            val files = listOf(File(DataManager.userDataDir, "$id.schema.yaml"), File(DataManager.stagingDir, "$id.schema.yaml"))
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
                  translators: [echo_translator]
                speller:
                  alphabet: abcdefghijklmnopqrstuvwxyz
            """.trimIndent()
            try {
                files.forEach { it.parentFile?.mkdirs(); it.writeText(source) }
                check(session.runOnReady { selectSchema(id) }) { "Cannot select synthetic input schema" }
                return fixture
            } catch (error: Throwable) { fixture.restore(); throw error }
        }
    }
}
