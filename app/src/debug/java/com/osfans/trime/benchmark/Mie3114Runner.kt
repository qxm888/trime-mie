/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.osfans.trime.R
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

/** Device regression with an app-owned blank editor. Host restores IME and private state. */
class Mie3114Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(action: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { action() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
    }
    private fun member(value: Any, name: String): Any? {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value) }
            catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) }
            catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing member $name")
    }
    private fun service() = member(checkNotNull(KeyboardWindow.instance), "service") as TrimeInputMethodService
    private fun input() = member(service(), "inputView") as InputView
    private fun await(label: String, predicate: () -> Boolean) {
        repeat(200) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            SystemClock.sleep(60)
        }
        error(label)
    }
    private fun pause() { waitForIdleSync(); SystemClock.sleep(350) }
    private fun pass(label: String) = sendStatus(0, Bundle().apply { putString("case", "PASS: $label") })
    private fun shot(name: String) {
        pause()
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.cacheDir, "mie3114-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    override fun onStart() = runBlocking {
        waitForIdleSync()
        val result = Bundle()
        val prefs = AppPrefs.defaultInstance()
        val p = prefs.keyboard
        val shared = p.floatingKeyboard.sharedPreferences
        val saved = shared.all.toMap()
        val originalMode = CustomKeyboards.mode
        val originalId = CustomKeyboards.activeId
        val originalTheme = ThemeManager.prefs.selectedTheme.getValue()
        val originalLayouts = CustomKeyboards.exportJson()
        var activity: Activity? = null
        var originalAscii: Boolean? = null
        var inputFixture: SyntheticInputSchema? = null
        val statsEnabled = com.osfans.trime.data.stats.InputStats.isEnabled()
        val keys = listOf(p.landscapeSizeEnabled, p.landscapeWidth, p.landscapeHeight, p.landscapePosition,
            p.floatingKeyboard, p.floatingKeyboardAutoLandscape, p.floatingKeyboardWidth, p.floatingKeyboardHeight,
            p.floatingKeyboardOffsetX, p.floatingKeyboardOffsetY, p.oneHandMode, p.floatingKeyboardKeepRatio,
            p.floatingToolbarExpanded, p.hideInputBar, prefs.general.inputStatsEnabled)
        try {
            // Users may have removed stock pages; provision fixtures and restore
            // the original exported layouts in finally, as for other mutations.
            main { CustomKeyboards.restoreBuiltins(reset = false) }
            await("frost family not installed") { CustomKeyboards.get("custom_frost_7_2") != null }
            main {
                prefs.general.inputStatsEnabled.setValue(false)
                com.osfans.trime.data.stats.InputStats.setEnabled(false)
                p.oneHandMode.setValue(0); p.floatingKeyboardAutoLandscape.setValue(false)
                p.floatingKeyboard.setValue(false); p.hideInputBar.setValue(false)
                p.floatingToolbarExpanded.setValue(true)
                p.landscapeSizeEnabled.setValue(false)
                CustomKeyboards.mutate("custom_frost") { it.typography = emptyMap() }
                CustomKeyboards.mutate("custom_frost_en") { it.typography = emptyMap() }
                CustomKeyboards.activeId = "custom_frost"
            }
            ThemeManager.selectTheme(originalTheme, layoutMode = CustomKeyboards.MODE_CUSTOM)
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val screen = activity!!
            main { screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("portrait IME unavailable") { KeyboardWindow.instance != null && input().isShown && input().keyboardView.width > 0 }
            pause()
            originalAscii = (member(service(), "rime") as com.osfans.trime.daemon.RimeSession).run { statusCached.isAsciiMode }
            main {
                check(ThemeManager.activeTheme.fonts.key == Typeface.DEFAULT) { "Default global font is not system font" }
                check(LayoutTypography.typeface("key", true) == Typeface.DEFAULT)
                CustomKeyboards.mutate("custom_frost") { it.typography = mapOf(LayoutTypography.GLOBAL to FontSpec("system:monospace")) }
            }
            ThemeManager.refreshActiveTheme(); pause()
            main { check(ThemeManager.activeTheme.fonts.key == FontOverrides.fromSelection("system:monospace")) }
            main { CustomKeyboards.mutate("custom_frost") { it.typography = it.typography + ("key" to FontSpec("system:serif")) } }
            ThemeManager.refreshActiveTheme(); pause()
            main {
                check(ThemeManager.activeTheme.fonts.key == FontOverrides.fromSelection("system:serif"))
                check(ThemeManager.activeTheme.fonts.symbol == FontOverrides.fromSelection("system:monospace"))
                check(CustomKeyboards.globalFont("custom_frost_en") == "system:monospace")
                val exported = CustomKeyboardYaml.decode(CustomKeyboards.exportYaml(listOf("custom_frost")))
                check(exported.getValue("custom_frost").typography[LayoutTypography.GLOBAL]?.font == "system:monospace")
                CustomKeyboards.mutate("custom_frost") { it.typography = emptyMap() }
            }
            ThemeManager.refreshActiveTheme(); pause()
            pass("system default font, per-role override, secondary global inheritance, YAML round trip")
            await("shared blur has no loaded bitmap") {
                val blur = input().keyboardView.getTag(R.id.keyboard_backdrop_blur)
                val frame = blur?.let { member(it, "front") } as? Bitmap
                frame != null && frame.getPixel(frame.width / 2, frame.height / 2) ushr 24 > 200
            }
            main {
                val cfg = SurfaceAppearance.current(true)
                check(cfg.keys.blur && cfg.effectiveToolbar.blur && cfg.effectiveBar.blur)
                check(SurfaceAppearance.unifiedBackground(true))
                check(File(CustomKeyAssets.resolve(targetContext, "backgrounds/frost.png")!!).length() > 1000)
            }
            shot("frost-portrait")
            pass("frost image loaded into real shared backdrop; key/toolbar/bar blur + unified background")
            var portraitWidth = 0; var portraitHeight = 0
            main { portraitWidth = input().keyboardView.width; portraitHeight = KeyboardWindow.currentKeyboard.keyboardHeight }
            main {
                p.landscapeSizeEnabled.setValue(true); p.landscapeWidth.setValue(70)
                p.landscapeHeight.setValue(40); p.landscapePosition.setValue(50)
            }
            pause()
            main {
                check(input().keyboardView.width == portraitWidth && KeyboardWindow.currentKeyboard.keyboardHeight == portraitHeight) { "landscape prefs changed portrait geometry" }
                screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await("landscape rotation missing") { screen.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && input().resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && input().isShown }
            pause()
            val themeId = originalTheme.ifBlank { "tongwenfeng.trime" }
            for (custom in listOf(true, false)) {
                if (custom) main { CustomKeyboards.activeId = "custom_frost" }
                ThemeManager.selectTheme(themeId, layoutMode = if (custom) CustomKeyboards.MODE_CUSTOM else CustomKeyboards.MODE_THEME)
                pause()
                for (floating in listOf(false, true)) {
                    main { p.floatingKeyboard.setValue(floating) }
                    pause()
                    for ((width, height, position) in listOf(Triple(40, 20, 0), Triple(70, 40, 50), Triple(100, 70, 100))) {
                        main { p.landscapeWidth.setValue(width); p.landscapeHeight.setValue(height); p.landscapePosition.setValue(position) }
                        await("geometry did not settle custom=$custom floating=$floating width=$width") {
                            val iv = input(); val kb = KeyboardWindow.currentKeyboard
                            val requestedHeight = (iv.resources.displayMetrics.heightPixels * height / 100f).toInt().coerceAtLeast((56 * iv.resources.displayMetrics.density).toInt())
                            val limit = KeyboardWindow.instance!!.heightLimitPx
                            val expectedHeight = if (limit > 0) minOf(requestedHeight, limit) else requestedHeight
                            iv.isFloatingKeyboardEnabled == floating && abs(kb.keyboardHeight - expectedHeight) <= 2 &&
                                abs(iv.keyboardView.width - iv.width * width / 100f) <= iv.width * .012f &&
                                abs(iv.keyboardView.scaleX - 1f) < .02f && abs(iv.keyboardView.scaleY - 1f) < .02f
                        }
                        pause()
                        main {
                            val iv = input(); val card = iv.keyboardView
                            check(card.width > 0 && card.height > 0)
                            val board = (member(iv, "windowManager") as com.osfans.trime.ime.window.BoardWindowManager).view
                            val bar = member(member(iv, "inputBar")!!, "view") as View
                            val bottom = member(iv, "bottomPaddingSpace") as View
                            if (floating) {
                                check(card.y >= 0 && card.y + card.height <= iv.height + 1) { "Floating card outside viewport: y=${card.y} height=${card.height} root=${iv.height}" }
                            } else {
                                check(board.top >= bar.bottom - 1 && board.bottom <= bottom.top + 1) {
                                    "Docked keys overlap toolbar/bottom: board=${board.top}..${board.bottom} barBottom=${bar.bottom} bottomTop=${bottom.top} card=${card.height} requested=$height"
                                }
                            }
                            check(KeyboardWindow.currentKeyboard.keys.all { it.x >= 0 && it.y >= 0 && it.x + it.width <= KeyboardWindow.currentKeyboard.minWidth + 2 && it.y + it.height <= KeyboardWindow.currentKeyboard.height + 2 }) { "key geometry outside keyboard" }
                            val margin = if (floating && width < 100) 8 * iv.resources.displayMetrics.density else 0f
                            val expectedX = margin + (iv.width - card.width - 2 * margin).coerceAtLeast(0f) * position / 100f
                            check(abs(card.x - expectedX) < iv.resources.displayMetrics.density * 3) { "position mismatch x=${card.x} expected=$expectedX custom=$custom floating=$floating" }
                            sendStatus(0, Bundle().apply { putString("geometry", "custom=$custom floating=$floating width=$width height=$height position=$position root=${iv.width}x${iv.height} card=${card.width}x${card.height} keyHeight=${KeyboardWindow.currentKeyboard.keyboardHeight} x=${card.x} scale=${card.scaleX}/${card.scaleY}") })
                        }
                        if (width == 100) shot("max-${if(custom) "custom" else "theme"}-${if(floating) "floating" else "docked"}")
                        if (width == 70) shot("${if(custom) "custom" else "theme"}-${if(floating) "floating" else "docked"}-landscape")
                    }
                    pass("${if(custom) "custom" else "theme"} ${if(floating) "floating" else "docked"}: 40/70/100% width, 20/40/70% key height, left/center/right")
                }
            }
            main {
                CustomKeyboards.activeId = "custom_frost"
                p.floatingKeyboard.setValue(true); p.landscapeWidth.setValue(70); p.landscapeHeight.setValue(40); p.landscapePosition.setValue(50)
            }
            ThemeManager.selectTheme(themeId, layoutMode = CustomKeyboards.MODE_CUSTOM); pause()
            val portraitFloatingWidth = p.floatingKeyboardWidth.getValue()
            val portraitFloatingHeight = p.floatingKeyboardHeight.getValue()
            main {
                val handle = member(input(), "rightResizeHandle") as com.osfans.trime.ime.core.FloatingResizeHandleView
                val point = handle.gripPoint()
                val time = SystemClock.uptimeMillis()
                for ((action, x, y, dt) in listOf(
                    listOf(MotionEvent.ACTION_DOWN, 0, 0, 0), listOf(MotionEvent.ACTION_MOVE, 40, 15, 40), listOf(MotionEvent.ACTION_MOVE, 120, 45, 70), listOf(MotionEvent.ACTION_UP, 120, 45, 100))) {
                    val e = MotionEvent.obtain(time, time + dt, action, point[0] + x, point[1] + y, 0)
                    handle.dispatchTouchEvent(e); e.recycle()
                }
            }
            await("landscape handle did not save own width") { p.landscapeWidth.getValue() != 70 && KeyboardWindow.currentKeyboard.bakedFloatingWidthPercent == p.landscapeWidth.getValue() && abs(input().keyboardView.scaleX - 1f) < .02f }
            main { check(p.floatingKeyboardWidth.getValue() == portraitFloatingWidth && p.floatingKeyboardHeight.getValue() == portraitFloatingHeight) }
            pass("actual corner-handle gesture saves landscape size and preserves portrait floating size")
            main { p.floatingKeyboard.setValue(false); screen.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await("portrait return failed") { screen.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && input().resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && !input().isFloatingKeyboardEnabled }
            pause()
            main { check(input().keyboardView.width == portraitWidth && KeyboardWindow.currentKeyboard.keyboardHeight == portraitHeight) }
            // Exercise the real touch path into this app-owned editor.
            inputFixture = SyntheticInputSchema.create(member(service(), "rime") as com.osfans.trime.daemon.RimeSession)
            pause()
            service().postRimeJob { clearComposition(); setRuntimeOption("ascii_mode", true) }.join(); pause()
            val edit = member(screen, "input") as EditText
            var tapX = 0f; var tapY = 0f
            main {
                edit.setText("")
                check(service().currentInputConnection != null)
                val key = KeyboardWindow.currentKeyboard.keys.first { it.getLabel().equals("a", true) }
                val host = member(input(), "windowManager") as com.osfans.trime.ime.window.BoardWindowManager
                val origin = IntArray(2).also { host.view.getLocationOnScreen(it) }
                tapX = origin[0] + key.x + key.width / 2f
                tapY = origin[1] + key.y + key.height / 2f
            }
            // The IME has a dedicated touch receiver window. Inject through Android's
            // real screen input dispatcher rather than bypassing that window with View.dispatchTouchEvent.
            val down = SystemClock.uptimeMillis()
            for ((action, delay) in listOf(MotionEvent.ACTION_DOWN to 0L, MotionEvent.ACTION_UP to 60L)) {
                if (delay > 0) SystemClock.sleep(delay)
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tapX, tapY, 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                sendPointerSync(event); event.recycle()
            }
            await("real key did not type into test editor") { edit.text.toString().equals("a", true) }
            pass("portrait dimensions restored; real touch key commits to app-owned editor")
            result.putString("result", "PASS")
        } catch (e: Throwable) {
            result.putString("result", "FAIL: ${e.message}"); result.putString("trace", e.stackTraceToString())
            runCatching { shot("failure") }
        } finally {
            inputFixture?.restore()
            runCatching { service().postRimeJob { clearComposition(); originalAscii?.let { setRuntimeOption("ascii_mode", it) } }.join() }
            main {
                CustomKeyboards.importJson(originalLayouts, true)
                CustomKeyboards.activeId = originalId
                val editor = shared.edit()
                keys.forEach { pref -> when(val old = saved[pref.key]) {
                    is Boolean -> editor.putBoolean(pref.key, old)
                    is Int -> editor.putInt(pref.key, old)
                    is String -> editor.putString(pref.key, old)
                    else -> editor.remove(pref.key)
                } }
                editor.commit()
                com.osfans.trime.data.stats.InputStats.setEnabled(statsEnabled)
            }
            runCatching { ThemeManager.selectTheme(originalTheme, layoutMode = originalMode) }
            CustomKeyboards.flush()
            activity?.let { main { it.finish() } }
        }
        finish(if(result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
