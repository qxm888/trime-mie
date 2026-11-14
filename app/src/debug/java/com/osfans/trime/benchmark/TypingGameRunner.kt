/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.RectF
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.osfans.trime.ime.core.InputView
import kotlin.math.abs
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.game.FallingWord
import com.osfans.trime.ime.game.TypingGame
import com.osfans.trime.ime.game.TypingGameActivity
import com.osfans.trime.ime.game.TypingGameEffects
import com.osfans.trime.ime.game.TypingGameSound
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Disposable debug app only: synthetic targets and progress, never a real pet save. */
class TypingGameRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun field(value: Any, name: String): java.lang.reflect.Field = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun member(value: Any, name: String): Any = runCatching { field(value, name).get(value)!! }.getOrElse {
        value.javaClass.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value)!!
    }
    private fun status(text: String) = sendStatus(0, Bundle().apply { putString("case", text) })
    private fun await(label: String, condition: () -> Boolean) {
        repeat(240) { if (ui(condition)) return; SystemClock.sleep(30) }; error(label)
    }
    private fun touch(view: View, x: Float, y: Float) = ui {
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
    private fun game() = member(TypingGame, "gameView") as TypingGame.GameView
    private fun menu(tag: String) {
        waitForIdleSync()
        val view = ui { game() }
        val point = ui {
            @Suppress("UNCHECKED_CAST")
            val entries = member(view, "menuRects") as List<Pair<RectF, String>>
            val rect = entries.first { it.second == tag }.first
            val scale = member(view, "menuScale") as Float
            floatArrayOf((member(view, "menuLeft") as Float) + rect.centerX() * scale,
                (member(view, "menuTop") as Float) + rect.centerY() * scale)
        }
        touch(view, point[0], point[1]); waitForIdleSync()
    }
    private fun hud(name: String) {
        val view = ui { game() }; val rect = ui { member(view, name) as RectF }
        touch(view, rect.centerX(), rect.centerY()); waitForIdleSync()
    }
    private fun seed(view: TypingGame.GameView) = ui {
        @Suppress("UNCHECKED_CAST")
        val targets = member(view, "items") as MutableList<FallingWord>
        targets.clear()
        val target = FallingWord("的", view.width * .7f, view.height * .36f, 0f, view.resources.displayMetrics.density * 24)
        targets.add(target)
        target
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(300) // Wait for the popup buffer to reach the compositor.
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.filesDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    override fun onStart() {
        val output = Bundle()
        var activity: Activity? = null
        var originalPets: PetHouse.State? = null
        try {
            waitForIdleSync()
            ui { AppPrefs.defaultInstance().profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE) }
            val own = startActivitySync(Intent(targetContext, SentenceLearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SentenceLearningActivity
            activity = own
            await("IME unavailable") { KeyboardWindow.instance != null }
            val ime = ui { member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService }
            await("Editor unavailable") { ime.currentInputConnection != null && ime.isInputViewShown }
            val prefs = targetContext.getSharedPreferences("typing_game", 0)
            @Suppress("UNCHECKED_CAST")
            val pets = member(PetHouse, "mutable") as MutableStateFlow<PetHouse.State>
            originalPets = pets.value
            ui { pets.value = PetHouse.State(started = true, selected = "sheep", pets = listOf(PetHouse.Pet("sheep"))) }
            fun input() = member(ime, "inputView") as InputView
            var lastGeometry = ""
            fun fullScreen(): Boolean {
                val view = game()
                if (!view.isAttachedToWindow || view.width == 0) return false
                val keyboard = Rect()
                if (!ime.typingGameKeyboardBoundsOnScreen(keyboard)) return false
                val at = IntArray(2); view.getLocationOnScreen(at)
                val insets = ViewCompat.getRootWindowInsets(ime.window.window!!.decorView)?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val displayFrame = Rect(); ime.window.window!!.decorView.getWindowVisibleDisplayFrame(displayFrame)
                val statusTop = maxOf(insets?.top ?: 0, displayFrame.top)
                val editorAt=IntArray(2)
                (member(TypingGame,"host") as TypingGameActivity).input.getLocationOnScreen(editorAt)
                val fieldBottom=minOf(keyboard.top,editorAt[1])
                val geometry = "popup=${at[0]},${at[1]},${view.width},${view.height} keyboard=$keyboard insets=$insets visibleFrame=$displayFrame"
                if (lastGeometry != geometry) { lastGeometry = geometry; status("GAME_GEOMETRY $geometry") }
                return abs(at[1] - statusTop) <= 2 &&
                    abs(at[1] + view.height - fieldBottom) <= 2 &&
                    abs(at[0] - (insets?.left ?: 0)) <= 2 &&
                    abs(view.width - (view.resources.displayMetrics.widthPixels - (insets?.left ?: 0) - (insets?.right ?: 0))) <= 2
            }
            fun open() {
                ui {
                    val rect = Rect()
                    val valid = ime.typingGameKeyboardBoundsOnScreen(rect)
                    val root = ime.window.window!!.decorView
                    val at = IntArray(2); input().keyboardView.getLocationOnScreen(at)
                    status("BEFORE_OPEN valid=$valid bounds=$rect keyboard=${at.toList()} shown=${input().keyboardView.isShown} root=${root.width}x${root.height} metrics=${root.resources.displayMetrics} insets=${ViewCompat.getRootWindowInsets(root)}")
                    TypingGame.attach(ime); TypingGame.start()
                }
                await("Game menu missing") { (field(TypingGame, "gameView").get(TypingGame) as? View)?.let { it.isAttachedToWindow && it.width > 0 } == true }
                await("Game does not fill keyboard-to-status-bar space") { fullScreen() }
                waitForIdleSync()
            }
            for (mode in listOf(0, 1, 2)) {
                ui { TypingGame.stop(); AppPrefs.defaultInstance().keyboard.oneHandMode.setValue(mode) }
                await("Keyboard mode unavailable") { input().isOneHandMode == (mode != 0) && input().keyboardView.isShown }
                SystemClock.sleep(500); open()
                screenshot("game-full-mode-$mode")
                status("PASS_FULLSCREEN mode=$mode actual popup fills area above the visible game editor and leaves keyboard uncovered")
            }
            ui { TypingGame.stop(); AppPrefs.defaultInstance().keyboard.oneHandMode.setValue(0) }
            await("Docked mode unavailable") { !input().isOneHandMode && input().keyboardView.isShown }
            SystemClock.sleep(500)
            ui { prefs.edit().clear().putBoolean("soundEnabled", false).commit() }
            open(); screenshot("game-menu-initial")
            check(ui { member(game(), "startLevel") } == 1)
            check(ui { (member(game(), "menuRects") as List<*>).none { (it as Pair<*, *>).second == "level:2" } })
            menu("start")
            hud("pauseRect")
            val resizedView = ui { game() }
            val movingTarget = seed(resizedView)
            val effectsBefore = ui { member(resizedView, "effects") as TypingGameEffects }
            val remaining = ui { movingTarget.speed = 100f; (effectsBefore.floorY - movingTarget.y) / movingTarget.speed }
            val oldHeight = ui { resizedView.height }
            val keyboardView = ui { input().keyboardView }
            val oldTranslation = ui { keyboardView.translationY }
            try {
                ui { keyboardView.translationY = oldTranslation - own.resources.displayMetrics.density * 100; keyboardView.requestLayout() }
                await("Popup did not follow changed keyboard geometry") { fullScreen() && resizedView.height != oldHeight }
                ui {
                    check(abs((effectsBefore.floorY - movingTarget.y) / movingTarget.speed - remaining) < .05f)
                    check(member(resizedView, "state").toString() == "PAUSED" && member(resizedView, "score") == 0)
                }
                status("PASS_RESIZE keyboard movement updates field without resetting score, pause or remaining time")
            } finally {
                ui { keyboardView.translationY = oldTranslation; keyboardView.requestLayout() }
                await("Popup did not restore geometry") { fullScreen() && resizedView.height == oldHeight }
            }
            hud("pauseRect")
            repeat(10) { index ->
                val view = ui { game() }; seed(view)
                ui { TypingGame.onCommit("的") }; waitForIdleSync()
                check(ui { member(view, "score") } == index) { "Score awarded before projectile impact" }
                await("Projectile missed its reserved target") { member(view, "score") == index + 1 }
            }
            check(prefs.getInt("unlocked", 0) == 2)
            ui { TypingGame.stop() }; open()
            menu("level:2"); menu("start")
            check(ui { member(game(), "level") } == 2)
            ui { TypingGame.saveRecord(2, 10); TypingGame.stop() }; open()
            check(ui { (member(game(), "menuRects") as List<*>).none { (it as Pair<*, *>).second == "level:3" } })
            status("PASS_UNLOCK first clear unlocks two; reopen/select two; replay/loss does not unlock three")
            ui { TypingGame.stop(); prefs.edit().putInt("unlocked", 1).putInt("bestLevel", 37).remove("selectedLevel").commit() }
            open(); screenshot("game-menu-legacy")
            check(ui { member(game(), "startLevel") } == 37)
            menu("level:37"); menu("start")
            check(ui { member(game(), "level") } == 37)
            status("PASS_LEGACY old highest reached level restored; selectable beyond level ten")
            for (id in listOf("sheep", "xiaomie", "jige")) {
                ui {
                    TypingGame.stop(); prefs.edit().putInt("bestLevel", 1).putInt("unlocked", 1).putInt("selectedLevel", 1).putBoolean("soundEnabled", false).commit()
                    pets.value = PetHouse.State(started = true, selected = id, pets = listOf(PetHouse.Pet(id)))
                }
                open(); menu("start")
                val view = ui { game() }
                val effects = ui { member(view, "effects") as TypingGameEffects }
                val sounds = ui { member(view, "sounds") as TypingGameSound }
                check(effects.heartAttacks == (id == "xiaomie"))
                check(effects.chickAttacks == (id == "jige"))
                seed(view); ui { TypingGame.onCommit("的") }; waitForIdleSync()
                SystemClock.sleep(110); screenshot("game-attack-$id")
                hud("pauseRect")
                val frozen = ui { member(effects, "clock") as Long }
                SystemClock.sleep(450)
                check(ui { member(effects, "clock") } == frozen) { "Paused projectiles advanced" }
                hud("pauseRect")
                await("Attack did not destroy word") { member(view, "score") == 1 }
                check(sounds.played == 0) { "Muted game played sound" }
                hud("soundRect")
                check(prefs.getBoolean("soundEnabled", false))
                SystemClock.sleep(600)
                seed(view); ui { TypingGame.onCommit("的") }
                await("Sound-enabled hit failed") { member(view, "score") == 2 }
                check(sounds.played >= 2) { "Launch/impact sound did not play after load" }
                hud("soundRect")
                val played = sounds.played
                seed(view); ui { TypingGame.onCommit("的") }
                await("Muted hit failed") { member(view, "score") == 3 }
                check(sounds.played == played)
                ui { TypingGame.stop() }; open()
                check(!(ui { member(game(), "sounds") as TypingGameSound }).enabled)
                check(ui { field(sounds, "pool").get(sounds) == null })
                status("PASS_ATTACK pet=$id correct projectile; pause freeze; impact score; sounds launch/hit; mute persists; pool released")
            }
            output.putString("result", "PASS")
        } catch (error: Throwable) {
            output.putString("result", "FAIL"); output.putString("failure", android.util.Log.getStackTraceString(error))
        } finally {
            ui { TypingGame.stop(); activity?.finish() }
            originalPets?.let {
                @Suppress("UNCHECKED_CAST")
                val pets = member(PetHouse, "mutable") as MutableStateFlow<PetHouse.State>
                ui { pets.value = it }
            }
        }
        finish(if (output.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }
}
