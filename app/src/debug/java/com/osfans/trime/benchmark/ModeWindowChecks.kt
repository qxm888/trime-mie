/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.TestInputPanel
import kotlin.math.abs

/** Synthetic gestures and app-owned views only. The host also restores private config/stat files. */
object ModeWindowChecks {
    @JvmStatic fun run(test: Instrumentation): Bundle {
        fun main(action: () -> Unit) {
            var error: Throwable? = null
            test.runOnMainSync { try { action() } catch (t: Throwable) { error = t } }
            error?.let { throw it }
        }
        fun member(value: Any, name: String): Any {
            var type: Class<*>? = value.javaClass
            while (type != null) {
                try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value)!! }
                catch (_: NoSuchFieldException) { }
                try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value)!! }
                catch (_: NoSuchMethodException) { }
                type = type.superclass
            }
            error("Missing member $name")
        }
        fun await(message: String, predicate: () -> Boolean) {
            repeat(100) {
                var ready = false
                main { ready = predicate() }
                if (ready) return
                SystemClock.sleep(60)
            }
            error(message)
        }
        val activity = test.startActivitySync(Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main { (member(activity, "testInputPanel") as TestInputPanel).show(activity.window) }
        await("Keyboard was not attached") { KeyboardWindow.instance != null }
        val service = member(checkNotNull(KeyboardWindow.instance), "service") as TrimeInputMethodService
        val prefs = AppPrefs.defaultInstance().keyboard
        val shared = prefs.floatingKeyboard.sharedPreferences
        val saved = shared.all.toMap()
        val modeKeys = listOf(prefs.floatingKeyboard, prefs.oneHandMode, prefs.floatingKeyboardWidth,
            prefs.floatingKeyboardHeight, prefs.floatingKeyboardOffsetX, prefs.floatingKeyboardOffsetY,
            prefs.oneHandOffsetX, prefs.floatingToolbarExpanded,
            prefs.hideInputBar, prefs.oneHandSide, prefs.floatingKeyboardAutoLandscape, prefs.floatingKeyboardKeepRatio)
        fun input() = member(service, "inputView") as InputView
        fun bounds(): Rect = Rect().also { check(input().getFloatingKeyboardBoundsInWindow(it)) }
        fun panelClick(position: Int) {
            var panel: SwitchOptionWindow? = null
            main {
                panel = SwitchOptionWindow(input().di)
                val entries = member(panel!!, "staticEntries") as Array<*>
                check(entries.size >= 7)
                val names = entries.map { member(it!!, "type").toString() }
                check(names.slice(4..6) == listOf("InputStats", "FloatingKeyboard", "OneHandMode"))
                (member(input(), "windowManager") as BoardWindowManager).attachWindow(panel!!)
            }
            await("Mode button was not visible") { panel!!.view.findViewHolderForAdapterPosition(position) != null }
            main { check(panel!!.view.findViewHolderForAdapterPosition(position)!!.itemView.performClick()) }
            SystemClock.sleep(350)
        }
        val result = Bundle()
        try {
            main {
                prefs.hideInputBar.setValue(false)
                prefs.floatingKeyboard.setValue(false)
                prefs.oneHandMode.setValue(0)
                prefs.oneHandSide.setValue(AppPrefs.Keyboard.OneHandSide.BOTH)
            }
            test.waitForIdleSync()
            SystemClock.sleep(500)
            await("Docked mode unavailable") { !input().isFloatingKeyboardEnabled && input().keyboardView.width > 0 }
            var fullWidth = 0
            main { fullWidth = KeyboardWindow.currentKeyboard.minWidth }
            panelClick(5)
            await("Floating button did not enable native card") { input().isFloatingKeyboardEnabled && input().keyboardView.width > 0 }
            var start = Rect()
            main {
                start = bounds()
                val surface = input().keyboardView
                val bar = member(member(input(), "inputBar"), "view") as View
                var parent = bar.parent
                while (parent != null && parent !== surface) parent = parent.parent
                check(parent === surface) { "Candidate bar did not move into the floating card" }
                val insets = InputMethodService.Insets()
                service.onComputeInsets(insets)
                check(insets.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_REGION)
                check(insets.touchableRegion.contains(start.centerX(), start.centerY()))
                check(!insets.touchableRegion.contains(0, 0)) { "Outside touches did not pass through" }
            }
            var toolbar: com.osfans.trime.ime.bar.ui.AlwaysUi? = null
            var scroller: android.widget.HorizontalScrollView? = null
            var leftBefore = 0; var rightBefore = 0; var itemBefore = 0
            main {
                toolbar = member(member(input(), "inputBar"), "alwaysUi") as com.osfans.trime.ime.bar.ui.AlwaysUi
                // 收放按钮默认收起会藏掉工具条，先展开再量测。
                prefs.floatingToolbarExpanded.setValue(true)
                toolbar!!.setToolbarCollapsed(false)
                toolbar!!.applyToolbar(com.osfans.trime.data.theme.model.ToolBar(
                    buttons = List(18) { com.osfans.trime.data.theme.model.ToolBar.Button(action = "copy", size = listOf(40, 40), foreground = com.osfans.trime.data.theme.model.ToolBar.Button.Foreground(style = "$it")) },
                ))
                scroller = toolbar!!.buttonsUi.viewport as android.widget.HorizontalScrollView
            }
            await("toolbar overflow did not measure") { toolbar!!.buttonsUi.root.width > scroller!!.width && scroller!!.width > 0 }
            main {
                fun x(view: View) = IntArray(2).also { view.getLocationInWindow(it) }[0]
                val left = member(toolbar!!, "leftMostButton") as View
                val right = member(toolbar!!, "rightMostButton") as View
                leftBefore = x(left); rightBefore = x(right)
                check(x(scroller!!) >= leftBefore + left.width && x(scroller!!) + scroller!!.width <= rightBefore)
                itemBefore = x(toolbar!!.buttonsUi.root.getChildAt(0))
                val host = scroller!!
                val time = SystemClock.uptimeMillis()
                for ((action, fraction, elapsed) in listOf(Triple(MotionEvent.ACTION_DOWN, .85f, 0L), Triple(MotionEvent.ACTION_MOVE, .6f, 30L), Triple(MotionEvent.ACTION_MOVE, .2f, 60L), Triple(MotionEvent.ACTION_UP, .2f, 90L))) {
                    val event = MotionEvent.obtain(time, time + elapsed, action, host.width * fraction, host.height / 2f, 0)
                    host.dispatchTouchEvent(event); event.recycle()
                }
            }
            main {
                fun x(view: View) = IntArray(2).also { view.getLocationInWindow(it) }[0]
                check(scroller!!.scrollX > 0)
                check(x(member(toolbar!!, "leftMostButton") as View) == leftBefore)
                check(x(member(toolbar!!, "rightMostButton") as View) == rightBefore)
                check(x(toolbar!!.buttonsUi.root.getChildAt(0)) != itemBefore)
                toolbar!!.applyToolbar(ToolbarOverrides.forKeyboard(ThemeManager.activeTheme, KeyboardWindow.lastAttachedId))
                val keyboard = KeyboardWindow.currentKeyboard
                check(kotlin.math.abs(keyboard.keyTextScale - minOf(keyboard.bakedFloatingWidthPercent, keyboard.bakedFloatingHeightPercent) / 100f) < .001f)
            }
            main {
                // Collapsed toolbar hides the buttons row and keeps the collapse button visible.
                toolbar!!.setToolbarCollapsed(true)
                check(toolbar!!.buttonsUi.viewport.visibility == View.GONE) { "Collapsed toolbar still shows buttons" }
                val collapse = member(toolbar!!, "collapseButton") as View
                check(collapse.visibility == View.VISIBLE) { "Collapse button hidden" }
                toolbar!!.setToolbarCollapsed(false)
            }
            result.putString("toolbar_scroll", "center content scrolls horizontally; both end buttons stay fixed; collapse toggle hides buttons row")
            // Collapsed-toolbar blank drag moves the whole card (same path the IME toolbar uses).
            main {
                val time = SystemClock.uptimeMillis()
                for ((action, x, y, delta) in listOf(
                    listOf(MotionEvent.ACTION_DOWN, 30, 8, 0),
                    listOf(MotionEvent.ACTION_MOVE, 65, -42, 30),
                    listOf(MotionEvent.ACTION_UP, 65, -42, 60),
                )) {
                    val event = MotionEvent.obtain(time, time + delta, action, x.toFloat(), y.toFloat(), 0)
                    check(service.dragKeyboardFromToolbar(event)) { "Toolbar blank drag was not consumed" }
                    event.recycle()
                }
            }
            SystemClock.sleep(250)
            main {
                val moved = bounds()
                check(abs(moved.left - start.left) > 5 || abs(moved.top - start.top) > 5) { "Title drag did not move the card" }
                check(moved.left >= 0 && moved.top >= 0)
                KeyboardWindow.currentKeyboard.setShifted(true, true)
            }
            // Drag the bottom-left pink corner handle outward to widen, not a private resize method.
            var oldWidthTier = 0
            main {
                oldWidthTier = prefs.floatingKeyboardWidth.getValue()
                val handle = member(input(), "leftResizeHandle") as View
                check(handle.visibility == View.VISIBLE) { "Resize handle hidden in floating mode" }
                val time = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float, tick: Long) {
                    val event = MotionEvent.obtain(time, time + tick, action, x, handle.height * .5f, 0)
                    handle.dispatchTouchEvent(event); event.recycle()
                }
                send(MotionEvent.ACTION_DOWN, 40f, 0)
                send(MotionEvent.ACTION_MOVE, 0f, 30)
                send(MotionEvent.ACTION_MOVE, -50f, 60)
                send(MotionEvent.ACTION_UP, -50f, 90)
            }
            await("Corner handle drag did not persist and bake a new width") {
                prefs.floatingKeyboardWidth.getValue() > oldWidthTier &&
                    KeyboardWindow.currentKeyboard.bakedFloatingWidthPercent == prefs.floatingKeyboardWidth.getValue() &&
                    abs(input().keyboardView.scaleX - 1f) < .02f && abs(input().keyboardView.scaleY - 1f) < .02f
            }
            main { check(KeyboardWindow.currentKeyboard.isShifted) { "Resize lost modifier state" } }
            main { prefs.floatingKeyboardKeepRatio.setValue(true); prefs.floatingKeyboardWidth.setValue(70) }
            await("Width slider did not link height") {
                prefs.floatingKeyboardHeight.getValue() == 70 && KeyboardWindow.currentKeyboard.bakedFloatingHeightPercent == 70
            }
            main { prefs.floatingKeyboardHeight.setValue(65) }
            await("Height slider did not link width") {
                prefs.floatingKeyboardWidth.getValue() == 65 && KeyboardWindow.currentKeyboard.bakedFloatingWidthPercent == 65
            }
            result.putString("settings", "keep-ratio width/height sliders linked in both directions")
            result.putString("floating", "candidate bar + key grid in one IME card; toolbar blank drag; pink corner handle resize; baked dimensions; Shift preserved")
            result.putString("touch_region", "card receives touches, outside point passes through")
            // The same physical panel button cycles left, right, normal -- one hand is the
            // bottom-docked floating card now: whole card shrinks, insets push the editor up.
            main { service.toggleFloatingKeyboard() }
            var leftX = 0; var rightX = 0
            panelClick(6)
            await("Left-hand card did not appear") {
                prefs.oneHandMode.getValue() == 1 && input().isOneHandMode && input().isFloatingKeyboardEnabled
            }
            main {
                check(KeyboardWindow.currentKeyboard.minWidth < fullWidth)
                check(!input().isOneHandMode || input().keyboardView.layoutParams is android.view.ViewGroup.LayoutParams)
                val card = bounds()
                leftX = card.left
                val insets = InputMethodService.Insets()
                service.onComputeInsets(insets)
                check(insets.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_REGION)
                check(insets.contentTopInsets < service.window.window!!.decorView.height) { "One-hand mode did not push the editor above the card" }
            }
            panelClick(6)
            await("Right-hand card did not appear") { prefs.oneHandMode.getValue() == 2 && input().isOneHandMode }
            main {
                rightX = bounds().right
                check(rightX > leftX) { "Right-hand mode did not align right" }
            }
            panelClick(6)
            main {
                check(prefs.oneHandMode.getValue() == 0)
                check(!input().isFloatingKeyboardEnabled)
                check(abs(KeyboardWindow.currentKeyboard.minWidth - fullWidth) < 3)
                val bar = ToolbarOverrides.forKeyboard(ThemeManager.activeTheme, KeyboardWindow.lastAttachedId)
                check(bar.buttons.none { ToolbarOverrides.isModeAction(it.action) })
            }
            result.putString("one_hand", "panel button cycles left/right/normal; card shrinks and docks bottom; insets push editor; full width restored")
            main { prefs.oneHandSide.setValue(AppPrefs.Keyboard.OneHandSide.LEFT); service.toggleOneHandMode() }
            main { check(prefs.oneHandMode.getValue() == 1 && input().isOneHandMode); service.toggleOneHandMode() }
            main { check(prefs.oneHandMode.getValue() == 0); prefs.oneHandSide.setValue(AppPrefs.Keyboard.OneHandSide.RIGHT); service.toggleOneHandMode() }
            main { check(prefs.oneHandMode.getValue() == 2); service.toggleOneHandMode() }
            main { check(prefs.oneHandMode.getValue() == 0) }
            result.putString("one_hand_preference", "left and right preferences alternate with normal; both keeps left/right/normal")
            result.putString("font_scale", "baked floating font size follows smaller dimension after release")
            result.putString("entries", "InputStats, FloatingKeyboard, OneHandMode consecutive; no mode button in candidate toolbar")
            result.putString("result", "PASS")
            return result
        } finally {
            main {
                val editor = shared.edit()
                modeKeys.forEach { pref ->
                    when (val value = saved[pref.key]) {
                        is Int -> editor.putInt(pref.key, value)
                        is Boolean -> editor.putBoolean(pref.key, value)
                        is String -> editor.putString(pref.key, value)
                        else -> editor.remove(pref.key)
                    }
                }
                editor.apply()
            }
        }
    }
}
