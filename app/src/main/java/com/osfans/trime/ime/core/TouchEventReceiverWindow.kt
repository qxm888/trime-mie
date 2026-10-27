/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow

class TouchEventReceiverWindow(
    private val contentView: View,
) {
    private val ctx = contentView.context

    private val window =
        PopupWindow(
            object : View(ctx) {
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean = contentView.dispatchTouchEvent(event)
            },
        ).apply {
            // disable animation
            animationStyle = 0
        }

    private var isWindowShowing = false

    private val cachedLocation = intArrayOf(0, 0)
    private var lastBounds: android.graphics.Rect? = null

    fun showAt(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val bounds = android.graphics.Rect(x, y, x + w, y + h)
        if (window.isShowing && bounds == lastBounds) return
        lastBounds = bounds
        isWindowShowing = true
        if (window.isShowing) {
            window.update(x, y, w, h)
        } else {
            window.width = w
            window.height = h
            window.showAtLocation(contentView, Gravity.TOP or Gravity.START, x, y)
        }
    }

    fun show() {
        val (x, y) = cachedLocation.also { contentView.getLocationInWindow(it) }
        val width = contentView.width
        val height = contentView.height
        showAt(x, y, width, height)
    }

    fun dismiss() {
        lastBounds = null
        if (isWindowShowing) {
            isWindowShowing = false
            window.dismiss()
        }
    }
}
