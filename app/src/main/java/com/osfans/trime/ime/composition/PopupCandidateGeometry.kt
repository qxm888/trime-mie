/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.composition

internal fun popupCandidateHeightLimit(screenHeight: Int, availableHeight: Int, edgeSpacing: Int): Int =
    minOf((screenHeight * .45f).toInt(), availableHeight - edgeSpacing).coerceAtLeast(1)

/** Applies to every placement mode, including FOLLOW and anchor-above after rotation. */
internal fun popupCandidatePosition(requested: Float, size: Float, available: Float, spacing: Float): Float {
    val margin = minOf(spacing.coerceAtLeast(0f), ((available - size) / 2f).coerceAtLeast(0f))
    return requested.coerceIn(margin, (available - size - margin).coerceAtLeast(margin))
}

internal data class CandidateArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = (right - left).coerceAtLeast(0f)
    val height get() = (bottom - top).coerceAtLeast(0f)
}

/** Prefer above; when the card is near the top, use the space beside or below it. */
internal fun popupCandidateArea(base: CandidateArea, obstacle: CandidateArea?, minWidth: Float, minHeight: Float): CandidateArea {
    if (obstacle == null || obstacle.right <= base.left || obstacle.left >= base.right || obstacle.bottom <= base.top || obstacle.top >= base.bottom) return base
    val above = base.copy(bottom = obstacle.top.coerceIn(base.top, base.bottom))
    if (above.height >= minHeight) return above
    val choices = listOf(above,
        base.copy(top = obstacle.bottom.coerceIn(base.top, base.bottom)),
        base.copy(right = obstacle.left.coerceIn(base.left, base.right)),
        base.copy(left = obstacle.right.coerceIn(base.left, base.right)))
    val viable = choices.filter { it.width >= minWidth && it.height >= minHeight }
    return (viable.ifEmpty { choices }).maxBy { it.width * it.height }
}
