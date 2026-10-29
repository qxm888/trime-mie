/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar.ui

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ViewAnimator

/** Draw every visible button's shadow beneath all button surfaces, outside child clips. */
class ToolbarShadowAnimator(context: Context) : ViewAnimator(context) {
    override fun dispatchDraw(canvas: Canvas) {
        drawShadows(this, canvas)
        super.dispatchDraw(canvas)
    }
    private fun drawShadows(parent: ViewGroup, canvas: Canvas) {
        val viewport = if(parent is android.widget.HorizontalScrollView) canvas.save() else -1
        if(viewport>=0) canvas.clipRect(0,0,parent.width,parent.height)
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.visibility != View.VISIBLE || child.alpha == 0f) continue
            val save = canvas.save()
            canvas.translate((child.left-parent.scrollX).toFloat(),(child.top-parent.scrollY).toFloat())
            canvas.concat(child.matrix)
            if (child is ToolButton) child.drawOuterShadow(canvas)
            else if (child is ViewGroup) drawShadows(child,canvas)
            canvas.restoreToCount(save)
        }
        if(viewport>=0) canvas.restoreToCount(viewport)
    }
}
