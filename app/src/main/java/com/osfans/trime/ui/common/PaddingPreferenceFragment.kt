/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.os.Bundle
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.preference.PreferenceFragmentCompat
import com.osfans.trime.util.applyNavBarInsetsBottomPadding

/**
 * A fragment template that apply navigation bar window insets bottom padding
 *
 * Taken from fcitx5-android project.
 * Source: https://github.com/fcitx5-android/fcitx5-android/blob/dedfc18/app/src/main/java/org/fcitx/fcitx5/android/ui/common/PaddingPreferenceFragment.kt
 */
abstract class PaddingPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        setDivider(null)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        listView.apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.framework_background))
            setPadding(dp(16), 0, dp(16), paddingBottom)
            clipToPadding = false
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ContextCompat.getColor(context, R.color.settings_surface)
                }
                fun isCategory(parent: RecyclerView, view: View): Boolean {
                    val position = parent.getChildAdapterPosition(view)
                    return position == RecyclerView.NO_POSITION ||
                        view.tag == "trime_preference_category"
                }
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.bottom = dp(if (isCategory(parent, view)) 4 else 8)
                }
                override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                    for (index in 0 until parent.childCount) {
                        val child = parent.getChildAt(index)
                        if (!isCategory(parent, child)) {
                            canvas.drawRoundRect(child.left.toFloat(), child.top + child.translationY,
                                child.right.toFloat(), child.bottom + child.translationY,
                                dp(20).toFloat(), dp(20).toFloat(), paint)
                        }
                    }
                }
            })
        }
        listView.applyNavBarInsetsBottomPadding()
    }
}
