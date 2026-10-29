/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.stats.InputStats
import kotlinx.coroutines.withTimeoutOrNull

/** Reuse an existing engine session; opening phrase management never starts deployment. */
internal object WordSchemas {
    data class Snapshot(val current: String, val names: Map<String,String>)
    suspend fun load(words:List<CandidatePreferences.CreatedWord> = CandidatePreferences.createdWords()): Snapshot {
        val names=InputStats.schemaList().toMap().toMutableMap()
        words.forEach { names.putIfAbsent(it.schema,it.schema) }
        var current=InputStats.activeSchemaId.takeUnless { it=="_" }.orEmpty()
        try {
            val session=RimeDaemon.getFirstSessionOrNull()
            if(session!=null) withTimeoutOrNull(1500) {
                session.runOnReady {
                    availableSchemata().forEach { names[it.id]=it.name }
                    selectedSchemaId().takeIf { it.isNotBlank() }?.let { current=it }
                }
            }
        } catch(error:kotlinx.coroutines.CancellationException) { throw error }
        catch(error:Exception) {
            com.osfans.trime.util.DiagnosticLog.event("words","schema_names_unavailable","type=${error.javaClass.simpleName}")
        }
        if(current.isNotBlank()) names.putIfAbsent(current,current)
        return Snapshot(current,names.toMap())
    }
}
