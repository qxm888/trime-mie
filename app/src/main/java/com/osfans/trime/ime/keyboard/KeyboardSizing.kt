/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import com.osfans.trime.data.prefs.AppPrefs

internal fun floatingKeyTextScale(floating: Boolean, widthPercent: Int, heightPercent: Int): Float =
    if (floating) minOf(widthPercent, heightPercent).coerceIn(1, 100) / 100f else 1f

internal fun nextOneHandMode(current: Int, side: AppPrefs.Keyboard.OneHandSide): Int = when (side) {
    AppPrefs.Keyboard.OneHandSide.LEFT -> if (current == 1) 0 else 1
    AppPrefs.Keyboard.OneHandSide.RIGHT -> if (current == 2) 0 else 2
    AppPrefs.Keyboard.OneHandSide.BOTH -> (current.coerceIn(0, 2) + 1) % 3
}
