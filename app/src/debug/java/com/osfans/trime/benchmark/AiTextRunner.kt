/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import com.osfans.trime.core.data.DataStorageMode
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.ai.*
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/** Disposable debug package only. In-process HTTP interceptor, dummy credentials, synthetic editor. */
class AiTextRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private fun <T> ui(action: () -> T): T {
        var result: Result<T>? = null
        runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
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
        error("Missing $name")
    }
    private fun await(label: String, predicate: () -> Boolean) {
        repeat(200) { if (ui(predicate)) return; SystemClock.sleep(40) }
        error(label)
    }
    private fun status(text: String) = sendStatus(0, Bundle().apply { putString("case", text) })
    override fun onStart() = runBlocking {
        var activity: AiTextFixtureActivity? = null
        val result = Bundle()
        val delay = AtomicInteger(30)
        val calls = AtomicInteger()
        val fail = AtomicInteger()
        val clientField = TextHttp.javaClass.getDeclaredField("client\$delegate").apply { isAccessible = true }
        val previousClient = clientField.get(TextHttp)
        try {
            waitForIdleSync()
            check(targetContext.packageName.endsWith(".debug.sentences")) { "Disposable debug package required" }
            // Every run starts with empty speech settings in this disposable package.
            // Reinstall intentionally retains data; apply() from a terminated prior process may not flush.
            check(com.osfans.trime.util.storageContext(targetContext).getSharedPreferences("builtin_asr", 0).edit().clear().commit())
            val prefs = AppPrefs.defaultInstance()
            ui {
                prefs.profile.dataStorageMode.setValue(DataStorageMode.APP_STORAGE)
                prefs.general.sentenceLearningEnabled.setValue(false)
                prefs.general.inputStatsEnabled.setValue(false)
                prefs.keyboard.floatingKeyboard.setValue(false)
                prefs.keyboard.floatingKeyboardAutoLandscape.setValue(false)
                prefs.keyboard.oneHandMode.setValue(0)
                AiTextSettings.init(targetContext)
            }
            check(!AiTextSettings.flag("voice_polish") && !AiTextSettings.flag("voice_translate"))
            status("PASS_DEFAULT_AI_AND_TRANSLATION_OFF")
            val fakeClient = OkHttpClient.Builder().addInterceptor { chain ->
                calls.incrementAndGet()
                val request = chain.request()
                check(request.header("Authorization") == "Bearer dummy-key")
                val buf = okio.Buffer(); request.body!!.writeTo(buf)
                val body = JSONObject(buf.readUtf8())
                val prompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
                val input = body.getJSONArray("messages").getJSONObject(1).getString("content")
                Thread.sleep(delay.get().toLong())
                val output = if (prompt.contains("翻译为")) "Hello translated" else "润色结果"
                check(input.isNotBlank())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (fail.get() == 0) 200 else 500).message("OK")
                    .body(JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", "stop")
                        .put("message", JSONObject().put("content", output)))).toString().toResponseBody("application/json".toMediaType())).build()
            }.build()
            clientField.set(TextHttp, lazyOf(fakeClient))
            ui {
                AiTextSettings.providerId = "custom"
                AiTextSettings.set("custom.api_key", "dummy-key")
                AiTextSettings.set("custom.endpoint", "https://mock.invalid/v1")
                AiTextSettings.set("custom.model", "fixture-model")
                AiTextSettings.set("translation_vendor", "ai")
                AiTextSettings.set("target", "en")
            }
            val own = startActivitySync(Intent(targetContext, AiTextFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AiTextFixtureActivity
            activity = own
            await("keyboard unavailable") { KeyboardWindow.instance != null }
            val ime = member(checkNotNull(KeyboardWindow.instance), "service") as TrimeInputMethodService
            await("editor unavailable") { ime.isInputViewShown && ime.currentInputConnection != null }
            fun dialog() = member(ime, "showingDialog") as? AlertDialog
            fun content() = own.first.text.toString()
            fun reset(text: String = "原始文字", selected: Boolean = true) {
                ui { ime.cancelVoiceIfActive(); own.first.requestFocus(); own.first.setText(text); own.first.setSelection(if (selected) 0 else text.length, text.length) }
                waitForIdleSync(); SystemClock.sleep(160)
            }
            reset()
            ui { ime.showAiText(false) }
            try { await("manual polish preview unavailable") { dialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.text == "替换选中文字" } }
            catch (e: Throwable) {
                val controller = (member(ime, "aiTextDelegate") as Lazy<*>).value!!
                status("DEBUG calls=${calls.get()} controllerDialog=${member(controller, "dialog")} job=${member(controller, "job")} revision=${ime.textSelectionRevision} input=${ui { own.first.selectionStart to own.first.selectionEnd }}")
                throw e
            }
            check(ui { content() } == "原始文字")
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.filesDir, "ai-polish-preview.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            ui { dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("manual polish did not replace selection") { content() == "润色结果" }
            status("PASS_MANUAL_POLISH_PREVIEW_AND_CONFIRM")
            reset()
            ui { ime.showAiText(true) }
            await("manual translation preview unavailable") { dialog()?.getButton(AlertDialog.BUTTON_POSITIVE)?.text == "替换选中文字" }
            ui { dialog()!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick() }
            await("translation not committed") { content() == "Hello translated" }
            status("PASS_MANUAL_TRANSLATION")
            delay.set(800)
            reset()
            ui { ime.showAiText(false) }
            SystemClock.sleep(100)
            ui { dialog()!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick() }
            SystemClock.sleep(1000)
            check(ui { content() } == "原始文字")
            status("PASS_CANCEL_PRESERVES_ORIGINAL")
            reset()
            ui { ime.showAiText(false) }
            SystemClock.sleep(100)
            ui { own.first.setSelection(2) }
            SystemClock.sleep(1000)
            check(ui { content() } == "原始文字" && dialog() == null)
            status("PASS_CURSOR_CHANGE_REJECTS_LATE_RESULT")
            reset()
            ui { ime.showAiText(false) }
            SystemClock.sleep(100)
            ui { own.second.requestFocus(); own.second.setText("第二输入框"); own.second.setSelection(5) }
            SystemClock.sleep(1000)
            check(ui { content() } == "原始文字" && own.second.text.toString() == "第二输入框")
            status("PASS_EDITOR_SWITCH_NO_STALE_WRITE")
            delay.set(30)
            reset("", false)
            ui { AiTextSettings.setFlag("voice_polish", true); ime.voiceInput.start(mock = true); ime.voiceInput.release() }
            await("automatic voice polish failed") { content() == "润色结果" }
            status("PASS_VOICE_AUTOMATIC_POLISH")
            reset("选中文字", true)
            ui { ime.voiceInput.start(mock = true) }
            val session = member(ime.voiceInput, "current")!!
            val listener = member(member(session, "engine")!!, "listener") as com.osfans.trime.ime.voice.builtin.AsrListener
            ui { listener.onPartial("临时语音") }
            await("voice preview missing") { content() == "临时语音" }
            ui { ime.voiceInput.release() }
            await("voice preview not replaced with polished final") { content() == "润色结果" }
            status("PASS_VOICE_PARTIAL_PREVIEW_REPLACED_ONCE")
            reset("选中文字", true)
            ui { ime.voiceInput.start(mock = true) }
            val cancelSession = member(ime.voiceInput, "current")!!
            val cancelListener = member(member(cancelSession, "engine")!!, "listener") as com.osfans.trime.ime.voice.builtin.AsrListener
            ui { cancelListener.onPartial("临时语音") }
            await("cancel voice preview missing") { content() == "临时语音" }
            ui { ime.voiceInput.cancel() }
            await("voice cancel did not restore original selection") { content() == "选中文字" }
            status("PASS_VOICE_PARTIAL_CANCEL_RESTORES_SELECTION")
            reset("", false)
            ui { AiTextSettings.setFlag("voice_translate", true); ime.voiceInput.start(mock = true); ime.voiceInput.release() }
            await("automatic voice translation failed") { content() == "Hello translated" }
            status("PASS_VOICE_POLISH_THEN_TRANSLATION")
            fail.set(1)
            reset("", false)
            ui { ime.voiceInput.start(mock = true); ime.voiceInput.release() }
            await("failed transformations lost raw transcript") { content() == "内置语音测试" }
            status("PASS_VOICE_HTTP_FAILURE_FALLBACK_RAW_TEXT")
            fail.set(0)
            delay.set(800)
            reset("原文", false)
            ui { ime.voiceInput.start(mock = true); ime.voiceInput.release() }
            SystemClock.sleep(260)
            ui { ime.cancelVoiceIfActive() }
            SystemClock.sleep(1000)
            check(ui { content() } == "原文")
            status("PASS_VOICE_PROCESSING_CANCEL_NO_COMMIT")
            // Real native Foxlet runtime initialization; no model download or network requests.
            if (!OfflineTranslationService.supported()) error("Unsupported test runtime")
            val installed = OfflineTranslationService.installed(targetContext)
            check(installed.isEmpty())
            status("PASS_FOXLET_NATIVE_INITIALIZATION_AND_EMPTY_CATALOG")
            // Verify new keys participate in existing secret exclusion, without saving plaintext keys.
            val backup = com.osfans.trime.data.backup.FullSettingsBackup
            val snapshot = backup.javaClass.getDeclaredMethod("preferenceSnapshot", android.content.Context::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val redacted = snapshot.invoke(backup, targetContext, false).toString()
            check(!redacted.contains("dummy-key"))
            status("PASS_AI_KEY_EXCLUDED_FROM_DEFAULT_BACKUP")
            val settings = startActivitySync(Intent(targetContext, com.osfans.trime.ui.main.AiTextSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.filesDir, "ai-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            ui { settings.finish() }
            status("PASS_SETTINGS_PAGE_RENDER")
            result.putInt("mock_requests", calls.get())
            result.putString("result", "PASS")
        } catch (t: Throwable) { result.putString("result", "FAIL: ${t.stackTraceToString()}")
        } finally {
            clientField.set(TextHttp, previousClient)
            ui { AiTextSettings.setFlag("voice_polish", false); AiTextSettings.setFlag("voice_translate", false); activity?.finish() }
        }
        finish(if (result.getString("result") == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}

class AiTextFixtureActivity : Activity() {
    lateinit var first: EditText
    lateinit var second: EditText
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        first = EditText(this).apply { hint = "合成测试文字"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        second = EditText(this).apply { hint = "第二个合成输入框"; inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(first, LinearLayout.LayoutParams(-1, 160)); root.addView(second, LinearLayout.LayoutParams(-1, 160))
        setContentView(root)
        first.requestFocus()
        first.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(first, InputMethodManager.SHOW_IMPLICIT) }, 400)
    }
}
