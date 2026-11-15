/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

/** A page may inherit only from its own family, even while another family is active. */
internal fun appearanceScopeIds(
    page: String, active: String, resolve: (String) -> String, rootOf: (String) -> String?,
): List<String> {
    val requested = resolve(page)
    val selected = resolve(active)
    val root = rootOf(requested)
    val activeRoot = rootOf(selected)
    return if (root != null) listOfNotNull(root, selected.takeIf { activeRoot == root }, requested).distinct()
        else listOfNotNull(activeRoot, selected.takeIf { activeRoot != null }).distinct()
}
