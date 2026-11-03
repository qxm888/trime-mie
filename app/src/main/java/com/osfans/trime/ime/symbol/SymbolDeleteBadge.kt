/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.symbol

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** A small edit-mode affordance; the entire symbol remains the tap target. */
internal fun symbolDeleteBadge(context:Context) = TextView(context).apply {
    text = "×"
    textSize = 12f
    gravity = Gravity.CENTER
    isClickable = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    visibility = View.GONE
}
