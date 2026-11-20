/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.data.keymap

/** Shared layout choices; widths remain user-defined and only unused space moves. */
object ButtonAlignment {
    val choices = linkedMapOf("left" to "靠左", "center" to "居中", "right" to "靠右", "distributed" to "分散")
    fun normalized(value: String) = value.takeIf { it in choices } ?: "left"
    fun slots(row: CustomKeyboards.CustomRow): List<Pair<CustomKeyboards.CustomKey?, Float>> = buildList {
        val total = row.keys.sumOf { (it.width.takeIf { width -> width.isFinite() && width > 0 } ?: 10f).toDouble() }.toFloat()
        val scale = if (total > 100f) 100f / total else 1f
        val rest = (100f - total * scale).coerceAtLeast(0f)
        if (row.keys.isEmpty()) { add(null to 100f); return@buildList }
        val alignment = normalized(row.alignment)
        fun gap(width: Float) { if (width > 0.001f) add(null to width) }
        if (alignment == "right") gap(rest)
        if (alignment == "center" || alignment == "distributed" && row.keys.size == 1) gap(rest / 2)
        row.keys.forEachIndexed { index, key ->
            add(key to ((key.width.takeIf { it.isFinite() && it > 0 } ?: 10f) * scale))
            if (alignment == "distributed" && row.keys.size > 1 && index < row.keys.lastIndex) gap(rest / (row.keys.size - 1))
        }
        if (alignment == "left") gap(rest)
        if (alignment == "center" || alignment == "distributed" && row.keys.size == 1) gap(rest / 2)
    }
}
