/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.ime.game

/** Unlocks describe reached levels; starting, losing, or replaying never grants a new level. */
internal object TypingGameProgress {
    const val MAX_LEVEL = 50
    fun restore(stored: Int, legacyBestReached: Int) = maxOf(stored, legacyBestReached).coerceIn(1, MAX_LEVEL)
    fun cleared(unlocked: Int, completed: Int): Int =
        maxOf(unlocked.coerceIn(1, MAX_LEVEL), completed.coerceIn(1, MAX_LEVEL) + 1).coerceAtMost(MAX_LEVEL)
    fun selected(requested: Int, unlocked: Int) = requested.coerceIn(1, unlocked.coerceIn(1, MAX_LEVEL))
}
