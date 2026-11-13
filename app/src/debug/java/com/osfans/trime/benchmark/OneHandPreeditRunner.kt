/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlin.math.abs

/** Run only in a fresh, disposable debug package. It uses its own editor and app-specific data. */
class OneHandPreeditRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }

    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { action() } catch (t: Throwable) { error = t } }
        error?.let { throw it }
    }

    private fun member(value: Any, name: String): Any {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value)!! }
            catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() })
                .apply { isAccessible = true }.invoke(value)!! }
            catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing member $name")
    }

    private fun await(message: String, condition: () -> Boolean) {
        repeat(150) {
            var ready = false
            main { ready = condition() }
            if (ready) return
            SystemClock.sleep(80)
        }
        error(message)
    }

    override fun onStart() {
        waitForIdleSync()
        val result = Bundle()
        var activity: OneHandPreeditActivity? = null
        try {
            val prefs = AppPrefs.defaultInstance()
            main {
                prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
                prefs.keyboard.floatingKeyboard.setValue(false)
                prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0)
                prefs.keyboard.oneHandWidth.setValue(80)
                prefs.keyboard.floatingKeyboardHeight.setValue(80)
                prefs.keyboard.hideInputBar.setValue(false)
                prefs.general.inputStatsEnabled.setValue(false)
            }
            activity = startActivitySync(Intent(targetContext, OneHandPreeditActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OneHandPreeditActivity
            await("IME unavailable") { KeyboardWindow.instance != null }
            val service = member(checkNotNull(KeyboardWindow.instance), "service") as TrimeInputMethodService
            fun input() = member(service, "inputView") as InputView
            for (mode in listOf(0, 1, 2)) {
                main { prefs.keyboard.oneHandMode.setValue(mode) }
                await("Keyboard mode $mode unavailable") {
                    input().keyboardView.isShown && input().keyboardView.width > 0 &&
                        input().isOneHandMode == (mode != 0)
                }
                waitForIdleSync(); SystemClock.sleep(500)
                val preedit = member(input(), "preedit") as PreeditDelegate
                var initialTop = 0
                var initialEditorBottom = 0
                for ((index, code) in listOf("", "s", "ywpykmmzr".repeat(6), "").withIndex()) {
                    main {
                        preedit.onCompositionUpdate(CompositionProto(
                            length = code.length, cursorPos = code.length, preedit = code,
                        ))
                    }
                    waitForIdleSync(); SystemClock.sleep(500)
                    main {
                        val insets = InputMethodService.Insets()
                        service.onComputeInsets(insets)
                        val editorBounds = Rect()
                        check(activity.input.getGlobalVisibleRect(editorBounds))
                        if (index == 0) {
                            initialTop = insets.contentTopInsets
                            initialEditorBottom = editorBounds.bottom
                        }
                        check(abs(insets.contentTopInsets - initialTop) <= 1) {
                            "Mode $mode preedit changed app inset: $initialTop -> ${insets.contentTopInsets}"
                        }
                        check(insets.visibleTopInsets == insets.contentTopInsets)
                        check(abs(editorBounds.bottom - initialEditorBottom) <= 1) {
                            "Mode $mode moved editor: $initialEditorBottom -> ${editorBounds.bottom}"
                        }
                        if (mode != 0) {
                            val panel = Rect()
                            check(input().getFloatingKeyboardPanelBoundsInWindow(panel))
                            check(insets.contentTopInsets == panel.top)
                            if (code.isNotEmpty()) {
                                val at = IntArray(2)
                                preedit.ui.root.getLocationInWindow(at)
                                check(preedit.ui.root.isShown && preedit.ui.root.height > 0)
                                check(at[1] < panel.top) { "Preedit did not overlay above panel" }
                                check(insets.touchableRegion.contains(at[0] + 2, at[1] + 2)) {
                                    "Overlay lost its touch region"
                                }
                            }
                        }
                        sendStatus(0, Bundle().apply {
                            putString("case", "mode=$mode codeLength=${code.length} appInset=${insets.contentTopInsets} editorBottom=${editorBounds.bottom}")
                        })
                    }
                }
            }
            result.putString("result", "PASS: normal, left and right; empty/short/long/cleared preedit; app inset and editor fixed; overlay touchable")
        } catch (t: Throwable) {
            result.putString("result", "FAIL: ${t.message}")
            result.putString("trace", t.stackTraceToString())
        } finally { activity?.let { main { it.finish() } } }
        finish(if (result.getString("result")!!.startsWith("PASS")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}

class OneHandPreeditActivity : Activity() {
    lateinit var input: EditText
        private set

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        input = EditText(this).apply { hint = "单手编码覆盖测试"; setSingleLine(true) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(input, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        })
        input.requestFocus()
        input.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }
}
