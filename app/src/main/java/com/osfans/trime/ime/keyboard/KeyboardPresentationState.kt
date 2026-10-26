/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

/**
 * Main-thread confined presentation state. Views and actions always read the same surface.
 * Owners are compared by identity: callbacks from an old input view/dialog cannot replace or
 * clear a newer session. No Android dependencies, persistence, or geometry policy live here.
 */
internal class KeyboardPresentationState<K : Any, V : Any> {
    data class Surface<K, V>(val owner: Any, val keyboard: K, val view: V)
    private var session: Any? = null
    private var docked: Surface<K, V>? = null
    private var floating: Surface<K, V>? = null

    val visible: Surface<K, V>? get() = floating ?: docked

    fun visibleFor(owner: Any): Surface<K, V>? = visible.takeIf { session === owner }

    fun begin(owner: Any) {
        if (session === owner) return
        session = owner
        docked = null
        floating = null
    }

    fun attachDocked(owner: Any, keyboard: K, view: V): Boolean {
        if (session !== owner) return false
        docked = Surface(owner, keyboard, view)
        return true
    }

    fun attachFloating(sessionOwner: Any, owner: Any, keyboard: K, view: V): Boolean {
        if (session !== sessionOwner || docked == null) return false
        floating = Surface(owner, keyboard, view)
        return true
    }

    fun detachFloating(owner: Any) {
        if (floating?.owner === owner) floating = null
    }

    fun end(owner: Any): Boolean {
        if (session !== owner) return false
        session = null
        docked = null
        floating = null
        return true
    }
}
