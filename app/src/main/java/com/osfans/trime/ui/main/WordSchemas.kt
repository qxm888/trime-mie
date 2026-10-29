/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.stats.InputStats
import kotlinx.coroutines.withTimeoutOrNull

/** Reuse an existing engine session; opening phrase management never starts deployment. */
internal object WordSchemas {
    data class Snapshot(val current: String, val names: Map<String,String>)
    suspend fun load(): Snapshot {
        val names=InputStats.schemaList().toMap().toMutableMap()
        CandidatePreferences.createdWords().forEach { names.putIfAbsent(it.schema,it.schema) }
        var current=InputStats.activeSchemaId.takeUnless { it=="_" }.orEmpty()
        runCatching {
            val session=RimeDaemon.getFirstSessionOrNull()
            if(session!=null) withTimeoutOrNull(1500) {
                session.runOnReady {
                    availableSchemata().forEach { names[it.id]=it.name }
                    selectedSchemaId().takeIf { it.isNotBlank() }?.let { current=it }
                }
            }
        }
        if(current.isNotBlank()) names.putIfAbsent(current,current)
        return Snapshot(current,names)
    }
}
