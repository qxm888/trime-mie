/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import com.osfans.trime.R

/**
 * A translucent, non-touchable media subwindow BELOW the IME surface. Its public
 * Window blur samples the app beneath, while the IME's keys and text stay sharp.
 * The subwindow is limited to [host]'s bounds; it never blurs the whole display.
 * No screen capture, overlay permission, hidden API or background frame loop.
 */
internal class PublicWindowBackdrop(
    private val host: View,
    private val parentWindow: () -> Window?,
    private val onAvailabilityChanged: () -> Unit,
) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private var radius = 0
    private var corner = 0f
    private var wanted = false
    private var observing = false
    private var dialog: Dialog? = null
    private var owner: IBinder? = null
    private var failedOwner: IBinder? = null
    private val bounds = Rect()
    private val rootOnScreen = IntArray(2)
    private val lastBounds = Rect()
    private var lastCorner = -1f
    private var lastRadius = -1
    var active: Boolean = false
        private set
    var status: String = "not_requested"
        private set

    init { host.addOnAttachStateChangeListener(this) }

    fun configure(blurRadiusPx: Int, cornerRadiusPx: Float): Boolean {
        wanted = true
        radius = blurRadiusPx.coerceIn(0, 150)
        corner = cornerRadiusPx.coerceAtLeast(0f)
        observe()
        update()
        return active
    }

    fun clear() {
        wanted = false
        failedOwner = null
        stopObserving()
        dismiss("not_requested", notify = false)
    }

    private fun observe() {
        if (!observing && host.isAttachedToWindow && wanted) {
            host.viewTreeObserver.addOnPreDrawListener(this)
            observing = true
        }
    }

    private fun stopObserving() {
        if (observing) {
            if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(this)
            observing = false
        }
    }

    override fun onViewAttachedToWindow(v: View) { failedOwner = null; observe() }
    override fun onViewDetachedFromWindow(v: View) { stopObserving(); failedOwner = null; dismiss("detached", notify = false) }
    override fun onPreDraw(): Boolean { update(); return true }

    private fun state(value: Boolean, reason: String) {
        status = reason
        if (active == value) return
        active = value
        host.post { onAvailabilityChanged() }
    }

    private fun dismiss(reason: String, notify: Boolean = true) {
        dialog?.let {
            if (Build.VERSION.SDK_INT >= 31) runCatching { it.window?.setBackgroundBlurRadius(0) }
            runCatching { it.dismiss() }
        }
        dialog = null; owner = null; lastBounds.setEmpty(); lastRadius = -1; lastCorner = -1f
        if (notify) state(false, reason) else { active = false; status = reason }
    }

    private fun update() {
        if (!wanted) return
        if (Build.VERSION.SDK_INT < 31) { dismiss("api_below_31"); return }
        if (!host.isAttachedToWindow || !host.isShown || host.windowVisibility != View.VISIBLE ||
            !host.getGlobalVisibleRect(bounds) || bounds.isEmpty || radius == 0) {
            dismiss("hidden"); return
        }
        // getGlobalVisibleRect uses the root View's coordinate space, not screen
        // coordinates. IME windows can start below the status bar or beside a
        // landscape navigation bar; omitting this offset shifts the blur surface.
        host.rootView.getLocationOnScreen(rootOnScreen)
        bounds.offset(rootOnScreen[0], rootOnScreen[1])
        val wm = host.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm?.isCrossWindowBlurEnabled != true) { dismiss("system_blur=off"); return }
        val token = parentWindow()?.decorView?.windowToken ?: run { dismiss("no_parent_window"); return }
        if (owner != null && owner !== token) dismiss("parent_changed")
        if (failedOwner === token) return
        val scale = minOf(bounds.width().toFloat() / host.width.coerceAtLeast(1), bounds.height().toFloat() / host.height.coerceAtLeast(1))
        val scaledCorner = corner * scale
        try {
            var changed = !active
            if (dialog == null) {
                val created = Dialog(host.context, R.style.ImeDynamicGlassLayer)
                created.setContentView(FrameLayout(created.context))
                created.setCancelable(false)
                val window = checkNotNull(created.window)
                window.setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = scaledCorner })
                window.attributes = window.attributes.apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA
                    this.token = token
                    gravity = Gravity.TOP or Gravity.LEFT
                    x = bounds.left; y = bounds.top; width = bounds.width(); height = bounds.height()
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    format = PixelFormat.TRANSLUCENT
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = "Trime dynamic glass"
                }
                // Assign before show so an exception can still release the created window.
                dialog = created
                created.show()
                owner = token
            }
            val window = checkNotNull(dialog?.window)
            if (lastBounds != bounds) {
                changed = true
                window.attributes = window.attributes.apply { x = bounds.left; y = bounds.top; width = bounds.width(); height = bounds.height() }
                lastBounds.set(bounds)
            }
            if (lastCorner != scaledCorner) {
                changed = true
                (window.decorView.background as? GradientDrawable)?.cornerRadius = scaledCorner
                lastCorner = scaledCorner
            }
            if (lastRadius != radius) { window.setBackgroundBlurRadius(radius); lastRadius = radius; changed = true }
            if (changed) state(true, "public_window radius=$radius bounds=${bounds.toShortString()}")
        } catch (error: Exception) {
            dismiss("public_window_failed:${error.javaClass.simpleName}")
            failedOwner = token // Do not retry a rejected window on every frame.
        }
    }
}
