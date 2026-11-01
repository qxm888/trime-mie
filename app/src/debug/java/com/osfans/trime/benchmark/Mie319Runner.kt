/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import androidx.preference.PreferenceManager
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.*
import com.osfans.trime.ime.bar.InputBarVisibility
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Unique generated themes only. Never switches IME, touches real themes, or deploys schemata. */
class Mie319Runner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync { try { block() } catch (e: Throwable) { error = e } }
        error?.let { throw it }
    }
    private fun pass(name: String) = sendStatus(0, Bundle().apply { putString("case", "PASS: $name") })
    override fun onStart() = runBlocking {
        main { check(AppPrefs.defaultInstance() != null) }
        val result = Bundle()
        val marker = "mie319_${java.util.UUID.randomUUID()}"
        val id = "$marker.trime"
        val owner = "$marker-session"
        val prefs = AppPrefs.defaultInstance()
        val shared = PreferenceManager.getDefaultSharedPreferences(targetContext)
        val original = shared.all.toMap()
        val mode = CustomKeyboards.mode
        val selected = ThemeManager.prefs.selectedTheme.getValue()
        val generated = mutableListOf<File>()
        var sessionStarted = false
        var collection: Job? = null
        val deploys = AtomicInteger()
        fun fixture(resource: String) = File(DataManager.userDataDir, "$resource.yaml").also { check(!it.exists()); generated += it }
        fun deployed(resource: String) = File(DataManager.stagingDir, "$resource.yaml").also { check(!it.exists()); generated += it }
        fun simple(size: Int = 21, name: String = "Alpha") = """
            name: $name
            config_version: '0.1'
            style:
              color_scheme: default
              key_text_size: $size
            preset_color_schemes:
              default: {name: Default, back_color: '#FFEEEEEE', text_color: '#FF333333'}
            preset_keyboards:
              default:
                keys: [{click: a}]
        """.trimIndent() + "\n"
        fun success(resource: String) = ThemeLoader.loadTheme(resource).let { check(it is ThemeLoader.ThemeLoadResult.Success) { it }; it }
        fun replaceSameMetadata(file: File, value: String) {
            val stamp = file.lastModified(); val length = file.length()
            file.writeText(value); check(file.length() == length); check(file.setLastModified(stamp))
        }
        try {
            val alive = com.osfans.trime.data.sync.ClipboardSync.javaClass.getDeclaredField("serviceAlive").apply { isAccessible = true }
            check(!alive.getBoolean(com.osfans.trime.data.sync.ClipboardSync)) { "Test IME service must be inactive" }
            check(RimeDaemon.getFirstSessionOrNull() == null) { "Test requires no active debug engine clients" }
            val source = fixture(id); val artifact = deployed(id)
            source.writeText(simple())
            main { CustomKeyboards.setMode(CustomKeyboards.MODE_THEME) }
            ThemeManager.selectTheme(id)
            check(ThemeManager.activeTheme.style.keyTextSize == 21f)
            replaceSameMetadata(source, simple(31))
            ThemeManager.selectTheme(id)
            check(ThemeManager.activeTheme.style.keyTextSize == 31f)
            check(!artifact.exists())
            check(ThemeFilesManager.listThemes(DataManager.userDataDir).first { it.configId == id }.name == "Alpha")
            replaceSameMetadata(source, simple(31, "Bravo"))
            check(ThemeFilesManager.listThemes(DataManager.userDataDir).first { it.configId == id }.name == "Bravo")
            pass("source + parse cache + picker name refresh with identical size/mtime; no native artifact")
            val includeId = "${marker}_include"
            val include = fixture(includeId).apply { writeText(simple(41)) }
            source.writeText("__include: $includeId:/\n")
            ThemeManager.selectTheme(id); check(ThemeManager.activeTheme.style.keyTextSize == 41f)
            replaceSameMetadata(include, simple(51))
            ThemeManager.selectTheme(id); check(ThemeManager.activeTheme.style.keyTextSize == 51f)
            val patch = fixture("${includeId}.custom").apply { writeText("patch:\n  name: Delta\n") }
            ThemeManager.selectTheme(id); check(ThemeManager.activeTheme.name == "Delta")
            replaceSameMetadata(patch, "patch:\n  name: Gamma\n")
            ThemeManager.selectTheme(id); check(ThemeManager.activeTheme.name == "Gamma")
            pass("included source and optional custom patch edits refresh, including preserved metadata")
            source.writeText(simple(21) + "__patch:\n  style/key_text_size: 22\n")
            check(ThemeLoader.loadFromSource(id) == null) // nested patch requires native DSL
            val session = RimeDaemon.createSession(owner); sessionStarted = true
            collection = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                session.runOnReady { messageFlow.collect { if (it is RimeMessage.DeployMessage && !it.isUserDataSync) deploys.incrementAndGet() } }
            }
            val originalBytes = source.readBytes(); val originalTime = source.lastModified()
            check(withTimeout(30_000) { session.runOnReady { deployThemeConfig(id, true) } })
            check(artifact.isFile && success(id).fromCompiled && success(id).theme.style.keyTextSize == 22f)
            check(source.readBytes().contentEquals(originalBytes) && source.lastModified() == originalTime)
            check(!ThemeSourceState.needsCompile(id))
            val oldArtifact = artifact.readBytes(); val oldTime = artifact.lastModified()
            check(session.runOnReady { deployThemeConfig(id) })
            check(artifact.readBytes().contentEquals(oldArtifact) && artifact.lastModified() == oldTime)
            replaceSameMetadata(source, source.readText().replace("size: 22", "size: 32"))
            check(ThemeSourceState.needsCompile(id))
            ThemeManager.selectTheme(id)
            check(ThemeManager.activeTheme.style.keyTextSize == 32f && !ThemeSourceState.needsCompile(id))
            check(source.lastModified() == originalTime)
            pass("native config rebuild handles identical metadata; unchanged complex config skipped; select refreshes changed DSL without touching source")
            val usable = artifact.readBytes()
            source.writeText("style: [broken\n")
            check(!session.runOnReady { deployThemeConfig(id, true) })
            check(artifact.readBytes().contentEquals(usable))
            check(runCatching { ThemeManager.selectTheme(id) }.isFailure)
            check(ThemeManager.activeTheme.style.keyTextSize == 32f)
            check(deploys.get() == 0) { "Unexpected full deployment notification" }
            pass("invalid source rejected; previous generated artifact + displayed theme retained; zero full-deployment callbacks")
            collection?.cancelAndJoin(); collection = null
            main {
                prefs.keyboard.hideInputBar.setValue(false)
                prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED)
                prefs.candidates.floatingBall.setValue(true)
                check(!prefs.candidates.floatingBall.getValue())
                prefs.keyboard.hideInputBar.setValue(true)
                check(!prefs.candidates.floatingBall.getValue())
                prefs.candidates.mode.setValue(PopupCandidatesMode.ALWAYS_SHOW)
                check(prefs.candidates.floatingBall.getValue())
                prefs.candidates.floatingBall.setValue(false)
                check(!prefs.candidates.floatingBall.getValue())
                prefs.keyboard.hideInputBar.setValue(false); prefs.keyboard.hideInputBar.setValue(true)
                check(prefs.candidates.floatingBall.getValue())
                prefs.candidates.mode.setValue(PopupCandidatesMode.DISABLED)
                check(!prefs.candidates.floatingBall.getValue())
                check(InputBarVisibility.hidden(true, false) && !InputBarVisibility.hidden(true, true))
            }
            pass("candidate-bar ball prohibited; floating hidden auto-enables; manual off allowed; floating keyboard forces toolbar without changing preference")
            val font = File("/system/fonts/Roboto-Regular.ttf").takeIf { it.isFile }
                ?: File("/system/fonts").listFiles()!!.first { it.extension == "ttf" }
            val chain = FontOverrides.encode(listOf(font.path, "system:sans-serif"))
            check(FontOverrides.decode(chain) == listOf(font.path, "system:sans-serif"))
            check(FontOverrides.fromSelection(chain) != null && FontOverrides.fromSelection(chain) === FontOverrides.fromSelection(chain))
            check(FontOverrides.decode(font.path) == listOf(font.path))
            val all = CustomKeyboards.list()
            for (color in listOf("mint", "pink", "blue", "orange", "purple")) for (variant in 1..2) {
                val entry = all.single { it.id == "custom_${color}_7_$variant" }
                val layout = CustomKeyboards.get(entry.id)!!
                check(layout.secondaryOf == "custom_$color" && layout.rows.size == 5)
                val letters = layout.rows.flatMap { it.keys }.map { it.label.lowercase() }.filter { it.matches(Regex("[a-z]")) }.toSet()
                check(letters.size == 26) { "${entry.id}: $letters" }
            }
            pass("ordered multi-font + legacy single-font loading/caching; all ten seven-column pages contain 26 letters and correct parents")
            result.putString("result", "PASS")
        } catch (e: Throwable) { result.putString("result", "FAIL: ${e.message}"); result.putString("trace", e.stackTraceToString()) }
        finally {
            collection?.cancelAndJoin()
            main {
                CustomKeyboards.setMode(mode)
                ThemeManager.prefs.selectedTheme.setValue(selected)
                val keys = listOf(AppPrefs.Candidates.MODE, "candidate_floating_ball", AppPrefs.Keyboard.HIDE_INPUT_BAR)
                val edit = shared.edit()
                keys.forEach { key -> when (val v = original[key]) {
                    is String -> edit.putString(key, v)
                    is Boolean -> edit.putBoolean(key, v)
                    else -> edit.remove(key)
                } }
                edit.commit()
            }
            if (sessionStarted) RimeDaemon.destroySession(owner)
            generated.forEach { it.delete() }
            DataManager.stagingDir.listFiles().orEmpty().filter { it.name.startsWith(".$id.") }.forEach { it.delete() }
            // Clear only regenerable bookkeeping entries for our unique fixture directory/id.
            targetContext.getSharedPreferences("theme_source_state", 0).let { state ->
                val old = state.all; val ours = old.filterValues { it is String && it.contains(marker) }.keys
                val edit = state.edit()
                ours.forEach { key -> edit.remove(key); edit.remove("compiled." + key.removePrefix("sources.")) }; edit.commit()
            }
        }
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
