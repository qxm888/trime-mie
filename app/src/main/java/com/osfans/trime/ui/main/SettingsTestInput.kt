/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.osfans.trime.R

/** Keep the original top-bar test action available on every settings page. */
internal class SettingsTestInput(private val activity: AppCompatActivity) {
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private var dialog: BottomSheetDialog? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { attachAction(content) }

    init {
        attachAction(content)
        // Editors can rebuild the toolbar without recreating the activity.
        content.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }

    private fun attachAction(view: View): Boolean {
        if (view is Toolbar) {
            if (view.menu.findItem(R.id.settings_test_input_entry) == null) {
                view.menu.add(Menu.NONE, R.id.settings_test_input_entry, 0, R.string.test_input).apply {
                    icon = ContextCompat.getDrawable(activity, R.drawable.ic_baseline_keyboard_24)?.mutate()?.apply {
                        setTint(ContextCompat.getColor(activity, R.color.framework_text_primary))
                    }
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    setOnMenuItemClickListener { show(); true }
                }
            }
            return true
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (attachAction(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun show() {
        if (dialog?.isShowing == true || activity.isFinishing) return
        val panel = TestInputPanel(activity).apply { isVisible = false }
        val sheet = BottomSheetDialog(activity)
        dialog = sheet
        sheet.setContentView(panel)
        sheet.behavior.apply {
            skipCollapsed = true
            isDraggable = false
        }
        sheet.setOnShowListener {
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
            sheet.window?.let { window ->
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                panel.show(window)
            }
        }
        panel.onDismiss = { sheet.dismiss() }
        sheet.setOnDismissListener {
            panel.onDismiss = null
            panel.dismiss()
            if (dialog === sheet) dialog = null
        }
        sheet.show()
    }

    private fun dismiss() { dialog?.dismiss() }

    private fun dispose() {
        dismiss()
        if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
    }

    companion object {
        fun callbacks() = object : Application.ActivityLifecycleCallbacks {
            private val hosts = mutableMapOf<Activity, SettingsTestInput>()

            override fun onActivityStarted(activity: Activity) {
                if (activity is AppCompatActivity &&
                    activity.javaClass.name.startsWith("com.osfans.trime.ui.main.") &&
                    !activity.isFinishing && activity !in hosts
                ) hosts[activity] = SettingsTestInput(activity)
            }

            override fun onActivityStopped(activity: Activity) { hosts[activity]?.dismiss() }
            override fun onActivityDestroyed(activity: Activity) { hosts.remove(activity)?.dispose() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        }
    }
}
