/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.core.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.popup.PagedCandidatesUi
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import java.io.File

/** Focused geometry check; the host restores the prior default IME in finally. */
class Mie212PagerRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { action() } catch (t: Throwable) { error = t } }; error?.let { throw it }
    }
    private fun member(value: Any, name: String): Any {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value)!! } catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value)!! } catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("No field: $name")
    }
    private fun await(label: String, predicate: () -> Boolean) {
        repeat(100) {
            var ready = false; main { ready = predicate() }; if (ready) return
            SystemClock.sleep(100)
        }
        error(label)
    }
    override fun onStart() {
        val result = Bundle()
        val bootstrap = startActivitySync(Intent(targetContext, com.osfans.trime.ui.setup.SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = AppPrefs.defaultInstance()
        val oldFloat = prefs.keyboard.floatingKeyboard.getValue()
        val oldAuto = prefs.keyboard.floatingKeyboardAutoLandscape.getValue()
        val oldMode = prefs.candidates.mode.getValue()
        var activity: android.app.Activity? = null
        var popup: CandidatesView? = null
        try {
            main { prefs.keyboard.floatingKeyboard.setValue(false); prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false); prefs.candidates.mode.setValue(PopupCandidatesMode.ALWAYS_SHOW) }
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Debug keyboard missing") { KeyboardWindow.instance != null }
            val service = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            main {
                if (runCatching { member(service, "candidatesView") }.isFailure) {
                    val scope = checkNotNull(com.osfans.trime.data.theme.ColorManager.currentScope())
                    service.javaClass.getDeclaredMethod("replaceCandidateView", com.osfans.trime.data.theme.ThemeScope::class.java)
                        .apply { isAccessible = true }.invoke(service, scope)
                }
            }
            var missing: Throwable? = null
            try { await("Candidate view missing") { runCatching { (member(service, "candidatesView") as CandidatesView).also { popup = it } }.onFailure { missing = it }.isSuccess } }
            catch (t: Throwable) { throw IllegalStateException("Candidate view missing: service=${service.javaClass.name} window=${service.window.window?.decorView?.isShown}", missing ?: t) }
            val window = checkNotNull(popup)
            val ui = member(window, "candidatesUi") as PagedCandidatesUi
            for (withCode in listOf(true, false)) {
                main {
                    window.handleMessages = false
                    window.javaClass.getDeclaredField("composition").apply { isAccessible = true }.set(window, if (withCode) CompositionProto("ni hao") else CompositionProto())
                    window.javaClass.getDeclaredField("candidates").apply { isAccessible = true }.set(window,
                        Candidates.Paged(hasPrevPage = false, hasNextPage = true, candidates = arrayOf(CandidateProto("你好", "", "1"), CandidateProto("拟好", "", "2"), CandidateProto("你号", "", "3"))))
                    window.javaClass.getDeclaredMethod("updateUi").apply { isAccessible = true }.invoke(window)
                    window.requestLayout()
                }
                await("Popup did not settle") { window.isShown && window.width > 0 && ui.root.height > 0 && ui.root.childCount > 0 && !window.isLayoutRequested }
                main {
                    val pager = ui.paginationUi.root
                    val density = window.resources.displayMetrics.density
                    check(kotlin.math.abs(pager.width - 20 * density) <= 1 && kotlin.math.abs(pager.height - 20 * density) <= 1)
                    check(pager.right == window.width - window.paddingRight && pager.top >= window.paddingTop)
                    check(pager.bottom <= ui.root.top + 1) { "Pager overlaps candidate list" }
                    check(ui.root.bottom == window.height - window.paddingBottom) { "Paging footer still reserves space" }
                    check(ui.paginationUi.prevIcon.alpha < ui.paginationUi.nextIcon.alpha)
                    sendStatus(0, Bundle().apply { putString("case", "PASS: code=$withCode pager=${pager.width}x${pager.height} top=${pager.top} listTop=${ui.root.top} listBottom=${ui.root.bottom} popupHeight=${window.height}") })
                }
                if (withCode) {
                    SystemClock.sleep(600)
                    uiAutomation.takeScreenshot()?.let { bmp -> File(targetContext.filesDir, "mie212-pager.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle() }
                }
            }
            result.putString("result", "PASS")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}") }
        finally { main { popup?.handleMessages = true; activity?.finish(); bootstrap.finish(); prefs.keyboard.floatingKeyboard.setValue(oldFloat); prefs.keyboard.floatingKeyboardAutoLandscape.setValue(oldAuto); prefs.candidates.mode.setValue(oldMode) } }
        finish(if (result.getString("result") == "PASS") -1 else 0, result)
    }
}
