/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import kotlinx.coroutines.CompletableDeferred

/** Background editor refreshes wait for an explicit selection to commit its new mode. */
internal class ThemeSelectionGate {
    private val lock = Any()
    private var latest: CompletableDeferred<Unit>? = null
    fun begin(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { synchronized(lock) { latest = it } }
    fun finish(ticket: CompletableDeferred<Unit>) {
        synchronized(lock) { if(latest === ticket) latest = null }
        ticket.complete(Unit)
    }
    suspend fun awaitSelection() {
        while(true) (synchronized(lock) { latest } ?: return).await()
    }
}
