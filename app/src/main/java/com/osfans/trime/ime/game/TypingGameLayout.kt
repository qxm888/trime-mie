/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

/** Screen coordinates, independent of the movable mini companion and the app's editor. */
internal object TypingGameLayout {
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }
    fun area(width: Int, height: Int, leftInset: Int, topInset: Int, rightInset: Int,
        bottomInset: Int, keyboardTop: Int): Area? {
        val left = leftInset.coerceAtLeast(0)
        val top = topInset.coerceAtLeast(0)
        val right = width - rightInset.coerceAtLeast(0)
        val bottom = minOf(keyboardTop, height - bottomInset.coerceAtLeast(0))
        return if (right > left && bottom > top) Area(left, top, right, bottom) else null
    }
    /** A taller field adds reaction time rather than accelerating the same timed drop. */
    fun fallSpeed(level: Int, travelPx: Float, density: Float): Float {
        val stage = level.coerceIn(1, 50)
        val minimumSeconds = (10f - (stage - 1) * .1f).coerceAtLeast(5f)
        val pace = (22f + (stage - 1) * .45f) * density.coerceAtLeast(.1f)
        return minOf(pace, travelPx.coerceAtLeast(1f) / minimumSeconds)
    }
}
