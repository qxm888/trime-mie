/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

/** Insert at a boundary, never replace a destination key. Returns the key's new index. */
object KeyReorder {
    fun move(rows: MutableList<CustomKeyboards.CustomRow>, fromRow: Int, fromKey: Int, toRow: Int, boundary: Int): Int? {
        val source = rows.getOrNull(fromRow)?.keys ?: return null
        val target = rows.getOrNull(toRow)?.keys ?: return null
        if (fromKey !in source.indices || boundary !in 0..target.size) return null
        val destination = boundary - if (fromRow == toRow && boundary > fromKey) 1 else 0
        if (fromRow == toRow && destination == fromKey) return destination
        val key = source.removeAt(fromKey)
        target.add(destination,key)
        return destination
    }
}
