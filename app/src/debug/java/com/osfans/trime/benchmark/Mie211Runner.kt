/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Instrumentation
import android.os.Bundle
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.appearance.*
import kotlinx.serialization.json.Json

/** Regression checks for mode isolation, toolbar actions/styles, and layout export round trips. */
class Mie211Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun tap(text: String) {
        repeat(80) {
            val nodes = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty()
            val target = nodes.firstOrNull { it.text?.toString() == text && it.isVisibleToUser }
            if (target != null) {
                var clickable = target
                while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                if (clickable?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    android.os.SystemClock.sleep(250); return
                }
            }
            android.os.SystemClock.sleep(100)
        }
        error("Missing UI action: $text")
    }
    private fun shot(name: String) {
        uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    override fun onStart() {
        val result = Bundle()
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val bootstrap = startActivitySync(android.content.Intent(targetContext, com.osfans.trime.ui.setup.SetupActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val prefs = AppPrefs.defaultInstance().keyboard
        val old = listOf(prefs.keyBackground.getValue(), prefs.surfaceAppearance.getValue(), prefs.customKeyBackground.getValue(), prefs.customSurfaceAppearance.getValue())
        val oldUnified = prefs.unifiedBackground.getValue() to prefs.customUnifiedBackground.getValue()
        val oldActive = CustomKeyboards.activeId
        var id = ""
        try {
            SurfaceAppearance.save(SurfaceAppearanceConfig(keys = SurfaceStyle(background = "#FF123456", borderWidth = 2f)), false)
            SurfaceAppearance.save(SurfaceAppearanceConfig(keys = SurfaceStyle(background = "#FF654321", borderWidth = 1f)), true)
            check(SurfaceAppearance.current(false).keys.background == "#FF123456")
            check(SurfaceAppearance.current(true).keys.background == "#FF654321")
            SurfaceAppearance.save(SurfaceAppearanceConfig(), true)
            check(SurfaceAppearance.current(false).keys.borderWidth == 2f)
            check(SurfaceAppearance.current(true).keys.background.isEmpty())
            prefs.unifiedBackground.setValue(true); prefs.customUnifiedBackground.setValue(false)
            check(SurfaceAppearance.unifiedBackground(false) && !SurfaceAppearance.unifiedBackground(true))
            sendStatus(0, Bundle().apply { putString("case", "PASS: independent theme/custom appearance and unified backgrounds") })

            val entry = ToolbarOverrides.Entry("__mie_one_hand", icon = "单手", id = "one", width = 60,
                appearance = KeyAppearance.Style(keyBackColor = "#FF112233", hilitedKeyBackColor = "#FF445566", keyBorder = 2, keyBorderColor = "#FFABCDEF", roundCorner = 9f, keyTextColor = "#FFFFFFFF", keyTextSize = 20f),
                bindings = mapOf("longClick" to "clipboard", "swipeUp" to "copy", "doubleClick" to "paste"))
            val bar = ToolbarOverrides.applyForCustomKeyboard(StandaloneKeyboardTheme.base().toolBar, listOf(entry, entry.copy(id = "two", icon = "再来")))
            check(bar.buttons.size == 2)
            check(bar.buttons.first().action == "__mie_one_hand")
            check(bar.buttons.first().longPressAction == "clipboard")
            check(bar.buttons.first().bindings["swipeUp"] == "copy")
            check(bar.buttons.first().foreground.normal == "#FFFFFFFF")
            check(bar.buttons.first().appearance.keyBorder == 2 && bar.buttons.first().size.first() == 60)
            check(Json.decodeFromString(ToolbarOverrides.Entry.serializer(), Json.encodeToString(ToolbarOverrides.Entry.serializer(), entry)) == entry)
            sendStatus(0, Bundle().apply { putString("case", "PASS: modern actions, duplicate functions, bindings, dimensions and appearance survive storage") })

            id = CustomKeyboards.create("Mie211 isolated check")
            CustomKeyboards.mutate(id) { kb ->
                kb.allKeysBackground = "#00000000"
                kb.toolbar = mutableListOf(entry)
                kb.rows.clear()
                kb.rows.add(CustomKeyboards.CustomRow(mutableListOf(CustomKeyboards.CustomKey("A", bindings = mutableMapOf("click" to "a"), background = CustomBackground(color = "#FFFF0000")))))
            }
            val exported = CustomKeyboards.exportYaml(listOf(id))
            val restored = CustomKeyboardYaml.decode(exported).getValue(id)
            check(restored.allKeysBackground == "#00000000")
            check(restored.toolbar.single() == entry)
            check(restored.rows.single().keys.single().resolvedBackground().color == "#FFFF0000")
            CustomKeyboards.mutate(id) { it.allKeysBackground = "" }
            check(CustomKeyboards.get(id)!!.rows.single().keys.single().resolvedBackground().color == "#FFFF0000")
            sendStatus(0, Bundle().apply { putString("case", "PASS: all-key overlay and toolbar round trip preserve original per-key backgrounds") })
            val editor = startActivitySync(android.content.Intent(targetContext, com.osfans.trime.ui.main.ToolbarEditActivity::class.java).putExtra("kbId", id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            android.os.SystemClock.sleep(350)
            runOnMainSync { editor.findViewById<android.view.View>(com.osfans.trime.R.id.toolbar_edit_add).performClick() }
            shot("mie211-add-dialog.png")
            tap("新增自定义按钮…")
            tap("点击 · 未绑定")
            tap("窗口与候选栏")
            tap("单手模式")
            shot("mie211-button-editor.png")
            tap("保存")
            check(CustomKeyboards.get(id)!!.toolbar.size == 2)
            check(CustomKeyboards.get(id)!!.toolbar.last().action == "__mie_one_hand")
            shot("mie211-toolbar-editor.png")
            runOnMainSync { editor.finish() }
            sendStatus(0, Bundle().apply { putString("case", "PASS: real editor add -> action picker -> save creates a modern mode button") })
            result.putString("result", "PASS")
        } catch (t: Throwable) {
            shot("mie211-failure.png")
            val text = uiAutomation.rootInActiveWindow?.let { root ->
                fun collect(node: android.view.accessibility.AccessibilityNodeInfo): String = node.text?.toString().orEmpty() + " | " + (0 until node.childCount).joinToString { index -> node.getChild(index)?.let { collect(it) }.orEmpty() }
                collect(root)
            }
            result.putString("ui", text)
            result.putString("result", "FAIL: ${t.stackTraceToString()}")
        } finally {
            if (id.isNotEmpty()) CustomKeyboards.delete(id)
            CustomKeyboards.activeId = oldActive; CustomKeyboards.flush()
            prefs.keyBackground.setValue(old[0]); prefs.surfaceAppearance.setValue(old[1])
            prefs.customKeyBackground.setValue(old[2]); prefs.customSurfaceAppearance.setValue(old[3])
            prefs.unifiedBackground.setValue(oldUnified.first); prefs.customUnifiedBackground.setValue(oldUnified.second)
        }
        runOnMainSync { bootstrap.finish() }
        finish(if (result.getString("result") == "PASS") -1 else 0, result)
    }
}
