/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView

/** Repaint previews after changing global appearance without moving the editing position. */
fun Activity.refreshAppearancePreview(refresh: () -> Unit) {
    val positions = mutableListOf<Triple<ScrollView, Int, Int>>()
    fun visit(view: View) {
        if (view is ScrollView) positions.add(Triple(view, view.scrollX, view.scrollY))
        if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
    }
    visit(window.decorView)
    refresh()
    positions.forEach { (view, x, y) -> view.post { view.scrollTo(x, y) } }
}
