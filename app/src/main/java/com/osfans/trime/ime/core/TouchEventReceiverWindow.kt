/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow

/** Touch proxy for the visible part of an IME overlay, never its transparent outer area. */
class TouchEventReceiverWindow(private val contentView: View) {
    private val localBounds = Rect()
    private val window = PopupWindow(object : View(contentView.context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val translated = MotionEvent.obtain(event)
            return try {
                translated.offsetLocation(localBounds.left.toFloat(), localBounds.top.toFloat())
                contentView.dispatchTouchEvent(translated)
            } finally { translated.recycle() }
        }
    }).apply { animationStyle = 0 }
    private val location = IntArray(2)
    private var lastBounds: Rect? = null

    fun show(bounds: Rect = Rect(0, 0, contentView.width, contentView.height)) {
        if (!contentView.isShown || bounds.isEmpty || !contentView.isAttachedToWindow) { dismiss(); return }
        localBounds.set(bounds)
        contentView.getLocationInWindow(location)
        showAt(location[0] + bounds.left, location[1] + bounds.top, bounds.width(), bounds.height())
    }

    fun showAt(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) { dismiss(); return }
        val bounds = Rect(x, y, x + w, y + h)
        if (window.isShowing && bounds == lastBounds) return
        lastBounds = bounds
        if (window.isShowing) window.update(x, y, w, h)
        else {
            window.width = w; window.height = h
            window.showAtLocation(contentView, Gravity.TOP or Gravity.START, x, y)
        }
    }

    fun dismiss() { lastBounds = null; if (window.isShowing) window.dismiss() }
}
