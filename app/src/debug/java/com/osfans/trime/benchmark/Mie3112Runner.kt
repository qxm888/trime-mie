/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.*
import java.io.File

/** Exercises the release-only crash path in an independent, minified APK. */
class Mie3112Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    override fun onStart() {
        // Instrumentation.onCreate precedes Application.onCreate; don't race its initialization.
        waitForIdleSync()
        runBlocking {
            val output = Bundle()
            val originalMode = CustomKeyboards.mode
            val originalId = ThemeManager.prefs.selectedTheme.getValue()
            val marker = "mie3112_${java.util.UUID.randomUUID()}.trime"
            val source = File(DataManager.userDataDir, "$marker.yaml")
            var sessionStarted = false
            try {
                check(RimeDaemon.getFirstSessionOrNull() == null) { "Independent IME must be inactive" }
                source.writeText("""
                    name: Release regression
                    config_version: '0.1'
                    style: {key_text_size: 21, color_scheme: default}
                    preset_color_schemes:
                      default: {name: Default, back_color: '#FFEEEEEE', text_color: '#FF333333'}
                    preset_keyboards:
                      default:
                        keys: [{click: a}]
                    __patch:
                      style/key_text_size: 32
                """.trimIndent())
                val session = RimeDaemon.createSession(marker); sessionStarted = true
                withTimeout(30_000) {
                    check(session.runOnReady { javaClass.name == "com.osfans.trime.core.Rime" })
                    check(session.runOnReady { deployThemeConfig(marker, true) })
                }
                ThemeManager.selectTheme(marker, layoutMode = CustomKeyboards.MODE_THEME)
                check(!ThemeManager.activeTheme.isCustom && ThemeManager.activeTheme.style.keyTextSize == 32f)
                sendStatus(0, Bundle().apply { putString("case", "PASS: optimized direct Rime API + native DSL refresh + theme mode") })
                val realTheme = "同文夜澜听雨风_咩版.trime"
                if (File(DataManager.userDataDir, "$realTheme.yaml").isFile) {
                    ThemeManager.selectTheme(realTheme, layoutMode = CustomKeyboards.MODE_THEME)
                    check(!ThemeManager.activeTheme.isCustom)
                    ThemeManager.selectTheme(realTheme, layoutMode = CustomKeyboards.MODE_CUSTOM)
                    check(ThemeManager.activeTheme.isCustom)
                    ThemeManager.selectTheme(realTheme, layoutMode = CustomKeyboards.MODE_THEME)
                    check(!ThemeManager.activeTheme.isCustom)
                    sendStatus(0, Bundle().apply { putString("case", "PASS: actual old Mie theme -> custom -> old theme, optimized APK") })
                }
                output.putString("result", "PASS")
            } catch (error: Throwable) {
                output.putString("result", "FAIL: ${error.message}")
                output.putString("trace", error.stackTraceToString())
            } finally {
                runCatching { ThemeManager.selectTheme(originalId, layoutMode = originalMode) }
                if (sessionStarted) RimeDaemon.destroySession(marker)
                source.delete()
                DataManager.stagingDir.listFiles().orEmpty().filter { it.name.contains(marker) }.forEach { it.delete() }
            }
            finish(if (output.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
        }
    }
}
