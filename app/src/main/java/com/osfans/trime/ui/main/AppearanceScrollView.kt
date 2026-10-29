/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.widget.ScrollView

/** Wrap short pages; keep long parameter groups inside the available dialog height. */
internal class AppearanceScrollView(context: Context, private val maximumDp: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit=(maximumDp*resources.displayMetrics.density).toInt()
        val available=if(MeasureSpec.getMode(heightMeasureSpec)==MeasureSpec.UNSPECIFIED) limit else minOf(limit,MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST))
    }
}
