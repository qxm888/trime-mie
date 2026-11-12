/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view

open class PreeditUi(
    final override val ctx: Context,
    private val scope: ThemeScope,
    private val setupPreeditView: (TextView.() -> Unit)? = null,
    private val onMoveCursor: ((Int) -> Unit)? = null,
    private val appearance: () -> com.osfans.trime.ime.appearance.FloatingCandidateStyle? = { null },
) : Ui {
    private val theme: Theme
        get() = scope.theme

    // Read at use time so a scheme switch re-renders with the new colors.
    private val textColor: Int
        get() = com.osfans.trime.data.keymap.LayoutTypography.colorOr(
            "text",
            appearance()?.color("text", scope.colors.textColor) ?: scope.colors.textColor,
        )
    private val highlightTextColor: Int get() = appearance()?.color("highlight.text", scope.colors.hilitedTextColor) ?: scope.colors.hilitedTextColor

    val preedit =
        view(::PreeditTextView) {
            setTextColor(textColor)
            textSize = com.osfans.trime.data.keymap.LayoutTypography.size("text", theme.preedit.foreground.fontSize, theme.isCustom)
            typeface = theme.fonts.text
            setupPreeditView?.invoke(this)
            onMoveCursor = this@PreeditUi.onMoveCursor
        }

    override val root =
        object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

            init {
                orientation = HORIZONTAL
                add(preedit, lParams())
            }
        }

    private fun CompositionProto.toSpannedString() = buildSpannedString {
        if (!preedit.isNullOrEmpty()) {
            append(preedit)
            var start = selStart.coerceIn(0, length)
            var end = selEnd.coerceIn(start, length)
            if (start > 0 && start < length && Character.isLowSurrogate(this[start]) && Character.isHighSurrogate(this[start - 1])) start--
            if (end > 0 && end < length && Character.isLowSurrogate(this[end]) && Character.isHighSurrogate(this[end - 1])) end++
            setSpan(ForegroundColorSpan(highlightTextColor), start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    var visible = false
        private set

    /** Re-applies the text color after a scheme switch. */
    fun refreshColors() {
        preedit.setTextColor(textColor)
    }

    private fun updateTextView(
        str: CharSequence,
        visible: Boolean,
    ) = preedit.run {
        text = str
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(composition: CompositionProto) {
        val defaultSize=com.osfans.trime.data.keymap.LayoutTypography.size("text", theme.preedit.foreground.fontSize, theme.isCustom)
        val style = appearance()
        preedit.textSize = style?.size("text",defaultSize) ?: defaultSize
        preedit.typeface = com.osfans.trime.data.theme.FontOverrides.fromSelection(style?.fonts?.get("text")?.font.orEmpty()) ?: theme.fonts.text
        preedit.setTextColor(textColor)
        val raw = composition.toSpannedString()
        val string = if (theme.isCustom) PreeditFontFallback.apply(raw, preedit.typeface) {
            listOf(theme.fonts.latin, theme.fonts.hanb)
        } else raw
        val hasPreedit = composition.length > 0
        visible = hasPreedit
        if (!visible) {
            updateTextView("", false)
            return
        }
        // Display the untouched UTF-16 string. Splitting/re-appending at a native
        // caret index can split a surrogate pair or an emoji variation sequence.
        updateTextView(string, true)
    }
}
