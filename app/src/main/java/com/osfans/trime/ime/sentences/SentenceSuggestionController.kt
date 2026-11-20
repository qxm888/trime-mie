/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.ime.sentences

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.TrimeApplication
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.sentences.SentenceAccumulator
import com.osfans.trime.data.sentences.SentenceRepository
import com.osfans.trime.data.sentences.SentenceRules
import com.osfans.trime.data.stats.WordCloudVocabulary
import com.osfans.trime.ime.core.TrimeInputMethodService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** A separate attached window: suggestions never change IME/app resize insets. */
internal class SentenceSuggestionController(private val service: TrimeInputMethodService) {
    private val repository get() = SentenceRepository.get(service)
    private val prefs get() = AppPrefs.defaultInstance().general
    private var allowed = false
    private data class Editor(val packageName: String?, val fieldId: Int, val fieldName: String?, val type: Int, val options: Int)
    private var editor: Editor? = null
    private var inlineCandidate = ""
    private var editorToken = 0L
    private var version = 0L
    private var preview = ""
    private var preedit = ""
    private var dismissed = false
    private var deletingUntil = 0L
    private var editingUntil = 0L
    private var query: Job? = null
    private var popup: PopupWindow? = null
    private var shown = emptyList<String>()
    private var queryPrefix = ""
    private var shownContext: Triple<String, String, String>? = null
    private var observedRoot: View? = null
    private val lastAnchor = Rect()
    private val lastObstruction = Rect()
    private val geometryListener = ViewTreeObserver.OnPreDrawListener {
        if (popup?.isShowing == true) {
            val bounds = Rect(); val obstacle = Rect()
            service.sentenceSuggestionAnchor(bounds)
            service.sentenceSuggestionObstruction(obstacle)
            if (bounds != lastAnchor || obstacle != lastObstruction) position()
        }
        true
    }
    private val accumulator = SentenceAccumulator { text ->
        if (sentenceEnabled()) repository.offer(text)
        dismissed = false
    }
    /** 最近一次引擎上屏的纯中文词（下词预测的 bigram 前键）。 */
    private var lastCommitWord = ""
    private fun enabled() = allowed &&
        (sentenceEnabled() || com.osfans.trime.data.learning.SmartLearningSettings.wordPredictionEnabled) &&
        !TrimeApplication.getInstance().isDirectBootMode
    private fun sentenceEnabled() = prefs.sentenceLearningEnabled.getValue()
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    fun start(info: EditorInfo, restarting: Boolean = false) {
        val next = Editor(info.packageName, info.fieldId, info.fieldName, info.inputType, info.imeOptions)
        val permitted = WordCloudVocabulary.allows(info.inputType, info.imeOptions)
        if (restarting && editor == next && permitted && enabled()) {
            // restartInput can replace the connection before a final selection update.
            // Resolve a Send clear before resetting, or retain an unchanged draft.
            val ic = service.currentInputConnection
            val tail = accumulator.prefix + inlineCandidate
            val before = ic?.getTextBeforeCursor(minOf(SentenceRules.MAX_UTF16 + 1, tail.length + 1), 0)?.toString()
            if (!finishCleared(before, ic?.getTextAfterCursor(1, 0)?.toString())) {
                if (tail.isNotEmpty() && before?.endsWith(tail) == true) {
                    // The service clears Rime composition on restart. A visible candidate
                    // may have become ordinary editor text without a Rime commit event.
                    accumulator.append(inlineCandidate)
                } else reset()
            }
            inlineCandidate = ""; preview = ""; preedit = ""
            version++; query?.cancel(); hide()
        } else reset()
        editorToken++; editor = next; allowed = permitted
        if (enabled()) repository.maintain()
    }
    fun viewHidden() { version++; query?.cancel(); hide() }
    fun stop() {
        if (enabled() && (accumulator.prefix.isNotEmpty() || inlineCandidate.isNotEmpty())) {
            val tail = accumulator.prefix + inlineCandidate
            val ic = service.currentInputConnection
            val before = ic?.getTextBeforeCursor(minOf(SentenceRules.MAX_UTF16 + 1, tail.length + 1), 0)?.toString()
            if (!finishCleared(before, ic?.getTextAfterCursor(1, 0)?.toString()) && before?.endsWith(tail) == true) {
                accumulator.append(inlineCandidate); accumulator.finish()
            }
        }
        reset(); allowed = false; editor = null; editorToken++
    }
    private fun finishCleared(before: String?, after: String?): Boolean {
        val hadInline = inlineCandidate.isNotEmpty()
        val cleared = accumulator.finishClearedEditor(before, after, inlineCandidate,
            SystemClock.uptimeMillis() <= deletingUntil)
        if (cleared) {
            inlineCandidate = ""; preview = ""; preedit = ""; dismissed = false
            if (hadInline) service.postRimeJob { clearComposition() }
            changed()
        }
        return cleared
    }
    fun inlineChanged(text: String) {
        inlineCandidate = if (enabled() && prefs.inlinePreeditMode.getValue() ==
            com.osfans.trime.ime.core.InlinePreeditMode.COMMIT_TEXT_PREVIEW) text.take(SentenceRules.MAX_UTF16) else ""
    }
    private fun reset() {
        accumulator.reset(); inlineCandidate = ""; preview = ""; preedit = ""; dismissed = false
        lastCommitWord = ""
        version++; query?.cancel(); query = null; queryPrefix = ""; hide()
    }
    fun committed(text: String, fromEngine: Boolean) {
        if (!enabled()) { reset(); return }
        // Multi-character paste/voice/literal actions do not train the sentence recorder.
        if (!fromEngine && text.codePointCount(0, text.length) != 1) { reset(); return }
        editingUntil = SystemClock.uptimeMillis() + 500
        inlineCandidate = ""; preview = ""; preedit = ""
        lastCommitWord = text.takeIf { isLearnableWord(it) } ?: ""
        accumulator.append(text)
        changed()
    }
    fun composition(value: com.osfans.trime.core.CompositionProto) {
        if (!enabled()) { hide(); return }
        preedit = value.preedit.orEmpty()
        preview = value.commitTextPreview.orEmpty().take(SentenceRules.MAX_UTF16)
        changed()
    }
    fun key(code: Int, meta: Int) {
        if (!enabled()) return
        editingUntil = SystemClock.uptimeMillis() + 500
        when {
            code == KeyEvent.KEYCODE_DEL -> {
                deletingUntil = editingUntil; accumulator.backspace(); changed()
            }
            code == KeyEvent.KEYCODE_ENTER -> finishSentence()
            meta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) != 0 -> reset()
            code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_FORWARD_DEL -> reset()
            else -> {
                val cp = com.osfans.trime.util.virtualKeyCharacterMap.get(code, meta)
                if (cp > 0 && Character.isValidCodePoint(cp) && !Character.isISOControl(cp)) {
                    accumulator.append(String(Character.toChars(cp))); changed()
                }
            }
        }
    }
    fun finishSentence() {
        if (enabled()) accumulator.finish()
        preview = ""; preedit = ""; dismissed = false; changed()
    }
    fun selection(oldStart: Int, start: Int, end: Int, composingStart: Int) {
        if (!enabled()) return
        if (start != end) { reset(); return }
        if (accumulator.prefix.isEmpty() && inlineCandidate.isEmpty()) return
        if (start == 0) {
            // Verify both sides; old selection/composing offsets may be coalesced or
            // stale after an app-side Send. A caret move leaves text after the cursor.
            val ic = service.currentInputConnection
            val before = ic?.getTextBeforeCursor(1, 0)?.toString()
            val after = ic?.getTextAfterCursor(1, 0)?.toString()
            // A queued zero-position callback may arrive after fresh text was inserted.
            // In that case the live cursor is no longer at zero; keep the new draft.
            if (!finishCleared(before, after) && before.isNullOrEmpty()) reset()
        } else if (start < oldStart && composingStart < 0 && SystemClock.uptimeMillis() > editingUntil) reset()
    }

    fun preferenceChanged() { reset() }
    fun dismiss() { dismissed = true; version++; query?.cancel(); hide() }
    fun dispose() { reset(); allowed = false }
    private fun hide() {
        observedRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(geometryListener)
        observedRoot = null
        popup?.dismiss(); popup = null; shown = emptyList()
    }

    private fun changed() {
        version++
        query?.cancel()
        val prefix = accumulator.prefix + preview
        val sentenceQualified = prefix.codePointCount(0, prefix.length) >= 2 &&
            prefix.length <= SentenceRules.MAX_UTF16
        if (!enabled() || dismissed || prefix.isEmpty() || prefix.length > SentenceRules.MAX_UTF16) {
            queryPrefix = ""; hide(); return
        }
        if (prefix != queryPrefix) hide()
        queryPrefix = prefix
        val ticket = version
        val lastWord = lastCommitWord
        query = service.lifecycleScope.launch {
            delay(100)
            if (ticket != version || dismissed || !enabled()) { hide(); return@launch }
            // 句级优先：以已上屏文本为前缀续句（原逻辑不变）。
            if (sentenceEnabled() && sentenceQualified) {
                runCatching { repository.search(prefix) }.onSuccess { entries ->
                    if (ticket != version || dismissed) return@launch
                    if (entries.isNotEmpty()) {
                        show(entries.map { it.text }, prefix)
                        return@launch
                    }
                }.onFailure { Timber.w(it, "Sentence lookup failed") }
            }
            // 词级兜底：句级未命中或未启用时，按 bigram 习惯预测下一个词。
            if (com.osfans.trime.data.learning.SmartLearningSettings.wordPredictionEnabled && lastWord.isNotEmpty()) {
                val next = com.osfans.trime.data.learning.LearningAggregator.predictNext(lastWord).firstOrNull()
                if (next != null && ticket == version && !dismissed) {
                    showWordPrediction(next)
                    return@launch
                }
            }
            hide()
        }
    }
    private fun show(sentences: List<String>, prefix: String) {
        val displayed = sentences.take(1)
        if (displayed.isEmpty()) { hide(); return }
        if (popup?.isShowing == true && shown == displayed && shownContext == Triple(accumulator.prefix, preview, preedit)) { position(); return }
        hide()
        val committed = accumulator.prefix
        val pending = preview
        val expectedPreedit = preedit
        val token = editorToken
        val sentence = displayed.single()
        popup = buildPopup(sentence, "常用句：$sentence",
            onClick = {
                if (enabled() && token == editorToken && committed == accumulator.prefix &&
                    pending == preview && SentenceRules.suffix(sentence, committed, pending) != null) {
                    val data = RimeMessage.SentenceCompletionMessage.Data(token, committed, sentence, expectedPreedit)
                    version++; query?.cancel(); hide()
                    service.requestSentenceCompletion(data)
                } else hide()
            },
            onLongClick = {
                dismiss()
                service.lifecycleScope.launch { runCatching { repository.forget(sentence) }
                    .onFailure { Timber.w(it, "Sentence delete failed") } }
                android.widget.Toast.makeText(service, "已删除这条常用句", android.widget.Toast.LENGTH_SHORT).show()
            },
        )
        shown = displayed
        shownContext = Triple(accumulator.prefix, preview, preedit)
        position()
    }

    /**
     * 下词联想（词级）：展示 bigram 预测的下一个词，点击直接上屏。
     * 这条 ic 插入刻意绕过学习钩子，防止预测结果自我加权。
     */
    private fun showWordPrediction(word: String) {
        val token = editorToken
        if (popup?.isShowing == true && shown == listOf(word)) { position(); return }
        hide()
        popup = buildPopup(word, "下词联想：$word",
            onClick = {
                if (enabled() && token == editorToken) {
                    val ic = service.currentInputConnection
                    if (ic != null && ic.getSelectedText(0).isNullOrEmpty() && ic.commitText(word, 1)) {
                        version++; query?.cancel()
                        // 直接 reset：虚拟上屏不进句级训练，也避免前缀错位。
                        reset()
                    } else hide()
                } else hide()
            },
            onLongClick = null,
        )
        shown = listOf(word)
        position()
    }

    /** 联想弹窗壳：圆角单行文本 + "×" 关闭按钮。 */
    private fun buildPopup(
        text: String,
        description: String,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
    ): PopupWindow {
        val root = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                setColor(Color.rgb(247, 249, 252)); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.rgb(206, 216, 229))
            }
        }
        val row = TextView(service).apply {
            this.text = text; textSize = 14f; setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Color.rgb(28, 47, 69)); setPadding(dp(4), 0, dp(4), 0)
            contentDescription = description
            setOnClickListener { onClick() }
            onLongClick?.let { handler -> setOnLongClickListener { handler(); true } }
        }
        root.addView(row, LinearLayout.LayoutParams(0, dp(36), 1f))
        root.addView(TextView(service).apply {
            this.text = "×"; textSize = 20f; gravity = Gravity.CENTER
            contentDescription = "关闭联想"; setTextColor(Color.DKGRAY)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(32), dp(36)))
        val maxWidth = minOf(dp(220), (service.resources.displayMetrics.widthPixels * .55f).toInt())
        val width = (row.paint.measureText(text).toInt() + dp(52)).coerceIn(minOf(dp(100), maxWidth), maxWidth)
        return PopupWindow(root, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            elevation = dp(3).toFloat(); isOutsideTouchable = false; isClippingEnabled = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
    }

    /** 下词预测只认「引擎上屏的 2~4 字纯中文词」，与学习日志的词条约束一致。 */
    private fun isLearnableWord(text: String): Boolean {
        val chars = text.codePointCount(0, text.length)
        if (chars < 2 || chars > 4) return false
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            val isChinese = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0xF900..0xFADF
            if (!isChinese) return false
            i += Character.charCount(c)
        }
        return true
    }
    private fun position() {
        val window = popup ?: return
        val anchor = service.window.window?.decorView ?: return
        if (!anchor.isAttachedToWindow || !service.isInputViewShown) { hide(); return }
        val bounds = Rect()
        if (!service.sentenceSuggestionAnchor(bounds)) { hide(); return }
        window.contentView.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val gap = dp(6)
        val minX = dp(8); val maxX = maxOf(minX, anchor.width - window.width - dp(8))
        val x = (bounds.right - window.width).coerceIn(minX, maxX)
        val height = window.contentView.measuredHeight
        val minY = dp(40); val bottom = bounds.top - gap
        val y = bottom - height
        val obstacle = Rect()
        service.sentenceSuggestionObstruction(obstacle)
        lastAnchor.set(bounds); lastObstruction.set(obstacle)
        // Give the existing candidate window/ball priority, including when it moves.
        val placements = listOf(
            x to y,
            (obstacle.right + gap) to y,
            (obstacle.left - window.width - gap) to y,
            x to (obstacle.top - height - gap),
            x to (obstacle.bottom + gap),
        )
        val placement = placements.firstOrNull { (left, top) ->
            left in minX..maxX && top >= minY && top + height <= bottom &&
                !Rect.intersects(Rect(left, top, left + window.width, top + height), obstacle)
        } ?: run { hide(); return }
        if (observedRoot !== anchor) {
            observedRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(geometryListener)
            anchor.viewTreeObserver.addOnPreDrawListener(geometryListener); observedRoot = anchor
        }
        if (window.isShowing) window.update(placement.first, placement.second, -1, -1)
        else window.showAtLocation(anchor, Gravity.TOP or Gravity.LEFT, placement.first, placement.second)
    }

    fun complete(data: RimeMessage.SentenceCompletionMessage.Data) {
        if (!enabled() || data.editorToken != editorToken || accumulator.prefix != data.prefix) return
        val ic = service.currentInputConnection ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) { reset(); return }
        val before = ic.getTextBeforeCursor(data.prefix.length, 0)?.toString() ?: return
        if (!before.endsWith(data.prefix) || !data.sentence.startsWith(data.prefix, ignoreCase = true)) { reset(); return }
        val suffix = data.sentence.substring(data.prefix.length)
        if (suffix.isNotEmpty()) {
            // The engine's clear response has already removed any inline composition.
            // This insertion deliberately bypasses the learning hook, so it cannot self-promote.
            if (ic.commitText(suffix, 1)) repository.used(data.sentence)
            reset()
        }
    }
}
