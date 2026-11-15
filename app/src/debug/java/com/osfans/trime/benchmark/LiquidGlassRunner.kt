/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.KeyboardColorGroups
import com.osfans.trime.data.theme.KeyboardStyleTarget
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.runBlocking
import java.io.File

/** Installed only as a disposable debug package. Uses an app-owned editor and synthetic candidates. */
class LiquidGlassRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun member(value: Any, name: String): Any? {
        var type: Class<*>? = value.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true }.get(value) } catch (_: NoSuchFieldException) { }
            try { return type.getDeclaredMethod("get" + name.replaceFirstChar { it.uppercase() }).apply { isAccessible = true }.invoke(value) } catch (_: NoSuchMethodException) { }
            type = type.superclass
        }
        error("Missing $name")
    }
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(label: String, predicate: () -> Boolean) {
        repeat(250) { if (ui(predicate)) return; SystemClock.sleep(40) }
        error(label)
    }
    private fun shot(name: String) {
        SystemClock.sleep(500)
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.cacheDir, "liquid-glass-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    override fun onStart() = runBlocking {
        val result = Bundle()
        var activity: Activity? = null
        try {
            check(targetContext.packageName.endsWith(".debug.sentences"))
            await("Application preferences did not initialize") { runCatching { AppPrefs.defaultInstance() }.isSuccess }
            val prefs = AppPrefs.defaultInstance()
            CustomKeyboards.awaitBuiltinLayouts()
            ui { prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE) }
            CustomKeyboards.init(targetContext); CustomKeyboards.awaitBuiltinLayouts()
            val root = "custom_liquid_glass"
            // Reinstalls retain this disposable package's earlier design iterations.
            CustomKeyboards.resetBuiltinSet(root)
            File(CustomKeyAssets.root(targetContext), "内置材质/精雕一辑/液态玻璃/背景.png").delete()
            BuiltinPremiumAssets.install(targetContext)
            check(KeyboardLayoutCatalog.roots(targetContext, KeyboardLayoutCatalog.BUILTIN).any { it.id == root && it.name == "液态玻璃" })
            check(KeyboardSets.family(root, CustomKeyboards.builtinTemplates()).size == 15)
            ui {
                KeyboardColorGroups.deactivate()
                prefs.general.inputStatsEnabled.setValue(false)
                prefs.general.sentenceLearningEnabled.setValue(false)
                prefs.keyboard.floatingKeyboard.setValue(false)
                prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0)
                prefs.keyboard.hideInputBar.setValue(false)
                prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED)
                prefs.candidates.floatingBall.setValue(false)
                KeyboardModeBinding.save(true, KeyboardModeBinding())
                KeyboardModeBinding.save(false, KeyboardModeBinding())
            }
            KeyboardColorGroups.selectLayout(KeyboardStyleTarget("builtin", root, "液态玻璃"))
            activity = startActivitySync(Intent(targetContext, CandidateViewportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui { activity!!.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            await("IME did not start") { KeyboardWindow.instance != null }
            fun ime() = member(KeyboardWindow.instance!!, "service") as TrimeInputMethodService
            fun input() = member(ime(), "inputView") as InputView
            fun active() = CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
            fun nav(id: String) {
                ui { KeyboardWindow.instance!!.switchKeyboardFromAction(id) }
                await("Navigation failed: $id") { active() == id && KeyboardWindow.currentKeyboardOrNull?.keyboardId == id && input().isShown }
                SystemClock.sleep(250)
            }
            nav(root)
            await("Background did not load") {
                val cfg = com.osfans.trime.ime.appearance.SurfaceAppearance.current(true)
                cfg.keyBlur == 0 && cfg.backdrop.systemBlur && cfg.backdrop.fallbackImageOpacity == 100 && views(input()).filterIsInstance<KeyView>().count { it.isShown } >= 30
            }
            result.putString("system_blur", ui { com.osfans.trime.ime.appearance.SystemBackdrop.lastStatus })
            result.putString("glass_fallback", ui { com.osfans.trime.ime.appearance.SystemBackdrop.selfBlurStatus })
            shot("26")
            val key = ui { views(input()).filterIsInstance<KeyView>().first { it.isShown } }
            ui { key.setPressedState(true) }; shot("pressed"); ui { key.setPressedState(false) }
            lateinit var popup: CandidatesView
            ui {
                prefs.candidates.mode.setValue(PopupCandidatesMode.ALWAYS_SHOW)
                ime().refreshCandidatesGeometry()
                popup = member(ime(), "candidatesView") as CandidatesView
                popup.handleMessages = false
                popup.javaClass.getDeclaredField("composition").apply { isAccessible = true }.set(popup, CompositionProto("ni"))
                popup.javaClass.getDeclaredField("candidates").apply { isAccessible = true }.set(popup,
                    Candidates.Paged(candidates = arrayOf(CandidateProto("你好", "", "1"), CandidateProto("霁月", "", "2"), CandidateProto("清风", "", "3"))))
                popup.javaClass.getDeclaredMethod("updateUi").apply { isAccessible = true }.invoke(popup)
            }
            await("Floating candidates hidden") { popup.isShown && popup.width > 0 && popup.height > 0 }
            shot("candidates")
            ui { popup.handleMessages = true; popup.visibility = View.INVISIBLE; prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED) }
            nav(root + "_num"); shot("numbers")
            nav(root + "_symbols"); shot("symbols")
            nav(root + "_9"); shot("9")
            nav(root + "_en"); shot("english")
            ui { popup.handleMessages = true; popup.visibility = View.INVISIBLE; prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED); prefs.keyboard.oneHandMode.setValue(1) }
            await("One-hand layout not applied") { input().keyboardView.width < targetContext.resources.displayMetrics.widthPixels * .95f }
            shot("onehand")
            result.putString("result", "PASS_STOCK_PICKER_15_PAGES_NAVIGATION_CANDIDATES_AND_ONEHAND")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}") }
        finally { ui { activity?.finish() } }
        finish(if (result.getString("result")?.startsWith("PASS") == true) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
