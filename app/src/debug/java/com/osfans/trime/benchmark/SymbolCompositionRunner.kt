/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.InlinePreeditMode
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.symbol.BuiltinSymbolsWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.runBlocking

/** Uses only the app-owned blank editor; host backs up/restores the test package. */
class SymbolCompositionRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch (e: Throwable) { failure = e } }
        failure?.let { throw it }
    }
    private fun member(value: Any, name: String): Any? {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing member $name")
    }
    private fun await(label: String, condition: () -> Boolean) {
        repeat(200) { var ready = false; main { ready = condition() }; if (ready) return; SystemClock.sleep(60) }
        error(label)
    }
    override fun onStart() = runBlocking {
        waitForIdleSync()
        val result = Bundle()
        var activity: Activity? = null
        var service: TrimeInputMethodService? = null
        var ascii: Boolean? = null
        var fixture: SyntheticInputSchema? = null
        try {
            val prefs = com.osfans.trime.data.prefs.AppPrefs.defaultInstance()
            main {
                prefs.keyboard.floatingKeyboard.setValue(false)
                prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.hideInputBar.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0)
                prefs.general.inputStatsEnabled.setValue(false)
                com.osfans.trime.data.stats.InputStats.setEnabled(false)
                com.osfans.trime.data.keymap.CustomKeyboards.activeId = "custom_pink"
            }
            com.osfans.trime.data.theme.ThemeManager.selectTheme(
                com.osfans.trime.data.theme.ThemeManager.prefs.selectedTheme.getValue(),
                layoutMode = com.osfans.trime.data.keymap.CustomKeyboards.MODE_CUSTOM,
            )
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("IME unavailable") { KeyboardWindow.instance != null }
            val ime = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            service = ime
            val input = member(ime, "inputView") as InputView
            val session = member(ime, "rime") as RimeSession
            await("IME editor not ready") { input.isShown && ime.currentInputConnection != null && ime.isInputViewShown }
            waitForIdleSync(); SystemClock.sleep(400)
            fixture = SyntheticInputSchema.create(session)
            waitForIdleSync(); SystemClock.sleep(250)
            val editor = member(activity!!, "input") as EditText
            ascii = session.run { statusCached.isAsciiMode }
            val manager = member(input, "windowManager") as BoardWindowManager
            for ((longPress, inline) in listOf(
                false to InlinePreeditMode.DISABLE, true to InlinePreeditMode.DISABLE,
                false to InlinePreeditMode.COMPOSING_TEXT, true to InlinePreeditMode.COMPOSING_TEXT,
            )) {
                main { prefs.general.inlinePreeditMode.setValue(inline); manager.attachWindow(KeyboardWindow); editor.setText("") }
                waitForIdleSync(); SystemClock.sleep(250)
                ime.postRimeJob { clearComposition(); setRuntimeOption("ascii_mode", false) }.join()
                val handled = mutableListOf<Boolean>()
                for (key in "nihao") {
                    session.runOnReady { handled += processKey(key.code) }
                    waitForIdleSync(); SystemClock.sleep(80)
                }
                sendStatus(0, Bundle().apply { putString("input", "handled=$handled disabled=${session.run { statusCached.isDisabled }} rawLength=${session.runOnReady { getRawInput() }.length}") })
                check(session.runOnReady { getRawInput() }.isNotEmpty()) { "Fixture schema does not compose nihao; ascii=${session.run { statusCached.isAsciiMode }}" }
                // Inline preedit can be disabled by the user's preference. The
                // synthetic engine state above is the composition precondition.
                val panel = BuiltinSymbolsWindow(input.di, "english")
                main { manager.attachWindow(panel) }
                lateinit var grid: RecyclerView
                await("symbol cells unavailable") {
                    grid = member(panel, "grid") as RecyclerView
                    grid.findViewHolderForAdapterPosition(0) != null
                }
                var literal = ""
                main {
                    val cell = grid.findViewHolderForAdapterPosition(0)!!.itemView
                    literal = cell.contentDescription.toString()
                    if (longPress) {
                        cell.performLongClick()
                        (member(ime, "showingDialog") as android.app.AlertDialog).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                    } else cell.performClick()
                }
                ime.postRimeJob { }.join()
                waitForIdleSync(); SystemClock.sleep(200)
                check(session.runOnReady { getRawInput() }.isEmpty()) { "Rime composition survives symbol commit (longPress=$longPress)" }
                main { check(editor.text.toString() == "nihao$literal") { "Pending composition lost or literal reordered" } }
                sendStatus(0, Bundle().apply { putString("case", "PASS: pending composition preserved before literal; Rime cleared; longPress=$longPress inline=$inline") })
            }
            main { editor.setText("") }
            waitForIdleSync(); SystemClock.sleep(200)
            val literal = "{Return}👩🏽‍💻"
            ime.postRimeJob { commitLiteralText(literal) }.join()
            await("literal interpreted as key sequence or split") { editor.text.toString() == literal }
            sendStatus(0, Bundle().apply { putString("case", "PASS: braces and multi-codepoint emoji committed literally") })
            result.putString("result", "PASS")
        } catch (e: Throwable) {
            result.putString("result", "FAIL: ${e.message}"); result.putString("trace", e.stackTraceToString())
        } finally {
            service?.postRimeJob { clearComposition(); ascii?.let { setRuntimeOption("ascii_mode", it) } }?.join()
            fixture?.restore()
            activity?.let { main { it.finish() } }
        }
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
