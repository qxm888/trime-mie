/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

/** Explicit system settings take precedence over an older configuration callback. */
internal object KeyboardNightPolicy {
    fun resolve(mode: Int, reported: Boolean): Boolean = when (mode) {
        android.app.UiModeManager.MODE_NIGHT_YES -> true
        android.app.UiModeManager.MODE_NIGHT_NO -> false
        else -> reported
    }
}
