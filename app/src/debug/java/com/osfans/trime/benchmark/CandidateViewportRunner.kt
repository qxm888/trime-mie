/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.popup.PopupCandidatesLayout
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.composition.PopupPosition
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow

class CandidateViewportRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun member(value: Any, name: String): Any? {
        var c: Class<*>? = value.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) { }
            try { return c.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) { }
            c = c.superclass
        }
        error("Missing $name")
    }
    private fun set(value: Any, name: String, item: Any) { value.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(value, item) }
    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun await(label: String, action: () -> Boolean) {
        repeat(160) { var yes = false; main { yes = action() }; if (yes) return; SystemClock.sleep(60) }
        error(label)
    }
    override fun onStart() {
        val result = Bundle()
        val activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = AppPrefs.defaultInstance()
        try {
            main {
                prefs.keyboard.floatingKeyboard.setValue(false)
                prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.hideInputBar.setValue(false)
                prefs.candidates.mode.setValue(PopupCandidatesMode.ALWAYS_SHOW)
            }
            await("IME missing") { KeyboardWindow.instance != null }
            waitForIdleSync(); SystemClock.sleep(500)
            for (rotation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                main { activity.requestedOrientation = rotation }
                val landscape = rotation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                await("rotation did not apply") { (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) == landscape }
                await("IME did not resume after rotation") {
                    val service = KeyboardWindow.instance?.let { member(it, "service") } as? TrimeInputMethodService
                    val view = service?.let { member(it, "inputView") } as? com.osfans.trime.ime.core.InputView
                    view != null && view.isShown && view.height > 0 && view.keyboardView.height > 0
                }
                waitForIdleSync(); SystemClock.sleep(500)
                for (layout in PopupCandidatesLayout.entries) {
                    main { prefs.candidates.layout.setValue(layout); prefs.candidates.limitLines.setValue(true); prefs.candidates.maxLines.setValue(2) }
                    waitForIdleSync(); SystemClock.sleep(250)
                    val service = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
                    main {
                        val iv = member(service, "inputView") as com.osfans.trime.ime.core.InputView
                        val kb = iv.keyboardView
                        val win = service.window.window!!
                        val origin = IntArray(2).also { win.decorView.getLocationOnScreen(it) }
                        sendStatus(0, Bundle().apply { putString("ime_geometry", "landscape=$landscape decor=${win.decorView.width}x${win.decorView.height} at=${origin.toList()} attrHeight=${win.attributes.height} input=${iv.width}x${iv.height} card=${kb.width}x${kb.height} top=${kb.top} scale=${kb.scaleY} floating=${iv.isFloatingKeyboardEnabled} screen=${iv.resources.displayMetrics.widthPixels}x${iv.resources.displayMetrics.heightPixels}") })
                    }
                    lateinit var popup: CandidatesView
                    lateinit var list: RecyclerView
                    main {
                        service.refreshCandidatesGeometry()
                        popup = member(service, "candidatesView") as CandidatesView
                        popup.handleMessages = false
                        set(popup, "composition", CompositionProto("test"))
                        set(popup, "candidates", Candidates.Paged(candidates = Array(120) { CandidateProto("候选$it", "横屏长注释回归测试。".repeat(40), (it + 1).toString()) }))
                        popup.javaClass.getDeclaredMethod("updateUi").apply { isAccessible = true }.invoke(popup)
                        list = member(member(popup, "candidatesUi")!!, "root") as RecyclerView
                    }
                    var attempts = 0
                    await("popup/list did not lay out") {
                        attempts++
                        if (attempts == 159) sendStatus(0, Bundle().apply { putString("geometry", "popup=${popup.visibility}/${popup.isShown} ${popup.width}x${popup.height} list=${list.width}x${list.height} children=${list.childCount} parent=${(member(popup, "parentSize") as FloatArray).toList()} layout=$layout landscape=$landscape") })
                        popup.isShown && popup.width > 0 && list.height > 0 && list.childCount > 0 && !popup.isLayoutRequested &&
                            (member(member(popup, "touchEventReceiverWindow")!!, "window") as android.widget.PopupWindow).isShowing
                    }
                    main {
                        val bounds = popup.javaClass.getDeclaredMethod("availableViewport").apply { isAccessible = true }.invoke(popup) as RectF
                        check(popup.x >= bounds.left - 1 && popup.y >= bounds.top - 1 && popup.x + popup.width <= bounds.right + 1 && popup.y + popup.height <= bounds.bottom + 1) {
                            "Popup outside viewport: ${popup.x},${popup.y} ${popup.width}x${popup.height} viewport=$bounds landscape=$landscape layout=$layout"
                        }
                        val input = member(service, "inputView") as com.osfans.trime.ime.core.InputView
                        val keyboardLocation = IntArray(2).also { input.keyboardView.getLocationInWindow(it) }
                        val popupLocation = IntArray(2).also { popup.getLocationInWindow(it) }
                        check(popupLocation[1] + popup.height <= keyboardLocation[1] + 1) { "Popup covers docked keys: bottom=${popupLocation[1] + popup.height} keyboardTop=${keyboardLocation[1]}" }
                        check(list.y + list.height <= popup.height - popup.paddingBottom + 1) { "List taller than popup" }
                        check(list.canScrollVertically(1) || list.canScrollVertically(-1) || list.canScrollHorizontally(1) || list.canScrollHorizontally(-1)) { "Long candidates are not scrollable: landscape=$landscape layout=$layout list=${list.width}x${list.height} children=${list.childCount} count=${list.adapter?.itemCount}" }
                        list.scrollToPosition(119)
                    }
                    await("last candidate inaccessible") { list.findViewHolderForAdapterPosition(119) != null }
                    sendStatus(0, Bundle().apply { putString("case", "rotation=$rotation layout=$layout") })
                    // Check invalidation synchronously; real IME geometry can legitimately replace a synthetic obstruction on the next frame.
                    main {
                        val parent = member(popup, "parentSize") as FloatArray
                        val original = parent.copyOf()
                        popup.updateFloatingGeometry(original, RectF(0f, 180f, original[0], original[1].coerceAtLeast(181f)))
                        check(popup.isLayoutRequested) { "Geometry update did not request measurement" }
                        popup.updateFloatingGeometry(original, null)
                        popup.visibility = View.INVISIBLE
                    }
                }
            }
            main {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                prefs.keyboard.floatingKeyboardOffsetY.setValue(-9999)
                prefs.keyboard.floatingKeyboardOffsetX.setValue(0)
                prefs.keyboard.floatingKeyboard.setValue(true)
                prefs.candidates.position.setValue(PopupPosition.FOLLOW)
            }
            await("floating landscape keyboard unavailable") {
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    KeyboardWindow.instance != null && (member(member(KeyboardWindow.instance!!, "service")!!, "inputView") as com.osfans.trime.ime.core.InputView).isFloatingKeyboardEnabled
            }
            waitForIdleSync(); SystemClock.sleep(500)
            val floatingService = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            lateinit var sidePopup: CandidatesView
            main {
                floatingService.refreshCandidatesGeometry()
                sidePopup = member(floatingService, "candidatesView") as CandidatesView
                sidePopup.handleMessages = false
                set(sidePopup, "composition", CompositionProto("test"))
                set(sidePopup, "candidates", Candidates.Paged(candidates = Array(120) { CandidateProto("侧面候选$it", "注释".repeat(40), "$it") }))
                sidePopup.javaClass.getDeclaredMethod("updateUi").apply { isAccessible = true }.invoke(sidePopup)
            }
            await("floating popup unavailable") { sidePopup.isShown && sidePopup.height > 10 }
            main {
                val area = sidePopup.javaClass.getDeclaredMethod("availableViewport").apply { isAccessible = true }.invoke(sidePopup) as RectF
                val obstruction = member(sidePopup, "floatingObstruction") as RectF
                check(!obstruction.isEmpty)
                check(sidePopup.x >= area.left - 1 && sidePopup.y >= area.top - 1 && sidePopup.x + sidePopup.width <= area.right + 1 && sidePopup.y + sidePopup.height <= area.bottom + 1)
                val visible = RectF(sidePopup.x, sidePopup.y, sidePopup.x + sidePopup.width, sidePopup.y + sidePopup.height)
                check(!RectF.intersects(visible, obstruction)) { "Candidate popup covers floating keys" }
            }
            result.putString("floating", "landscape FOLLOW popup remains visible beside a top-aligned floating keyboard without covering keys")
            result.putString("rotation", "portrait/landscape/reverse-landscape/portrait; horizontal and vertical candidate layouts remain bounded")
            result.putString("content", "long annotations scroll through last candidate; reduced free area triggers remeasurement")
            result.putString("result", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("result", "FAIL: " + android.util.Log.getStackTraceString(error)); finish(Activity.RESULT_CANCELED, result)
        } finally { main { activity.finish() } }
    }
}
