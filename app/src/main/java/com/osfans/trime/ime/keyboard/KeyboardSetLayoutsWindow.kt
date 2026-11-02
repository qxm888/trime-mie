/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import org.kodein.di.DI
import org.kodein.di.instance

/** A two-column keyboard surface; opening/returning never changes the remembered layout. */
class KeyboardSetLayoutsWindow(di: DI, private val setRoot: String) : BoardWindow.NoBarBoardWindow(di) {
    private val manager: BoardWindowManager by instance()
    private val scope: ThemeScope by instance()
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    override fun onCreateView(): View {
        val ids = CustomKeyboards.setPageIds(setRoot)
        val pages = ids.mapNotNull { id -> CustomKeyboards.get(id)?.let { id to it } }.toMap()
        val current = CustomKeyboards.get(CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)) ?: pages[setRoot]
        val style = current?.setKeyStyles?.get("function") ?: current?.keyAppearance ?: KeyAppearance.Style()
        fun color(value: String, fallback: Int) = runCatching { android.graphics.Color.parseColor(value) }.getOrDefault(fallback)
        val ink = color(style.keyTextColor, scope.colors.keyTextColor)
        val back = style.keyBackColor.ifBlank { current?.keyAppearance?.keyBackColor.orEmpty() }
        fun tile(pressed: Boolean) = GradientDrawable().apply {
            setColor(color(if(pressed) KeyAppearance.pressedColor(back) else back, scope.colors.keyBackColor))
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1).coerceAtLeast(1), color(style.keyBorderColor.ifBlank { current?.keyAppearance?.keyBorderColor.orEmpty() }, scope.colors.keyBorderColor))
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(4))
            background = current?.resolvedBackground()?.drawable(context, context.resources.displayMetrics.widthPixels)
        }
        body.addView(TextView(context).apply { text = "选择布局"; textSize = 15f; gravity = Gravity.CENTER; setTextColor(ink) }, LinearLayout.LayoutParams(-1, dp(40)))
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val choices = KeyboardSetLayoutChoices.items(setRoot, pages, ids)
        val rows = ((choices.size + 1) / 2).coerceAtLeast(1)
        val tileHeightPx = dp(((KeyboardWindow.lastKeyboardHeight / context.resources.displayMetrics.density - 94 - 6 * rows) / rows).toInt().coerceIn(32, 72))
        fun button(label: String, action: () -> Unit) = TextView(context).apply {
            text = label; textSize = 18f; maxLines = 2; gravity = Gravity.CENTER; setTextColor(ink)
            background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_pressed), tile(true)); addState(intArrayOf(), tile(false)) }
            isFocusable = true; setOnClickListener { action() }
        }
        choices.chunked(2).forEach { pair ->
            val row = LinearLayout(context)
            pair.forEach { choice ->
                row.addView(button(choice.label) {
                    manager.attachWindow(KeyboardWindow)
                    KeyboardWindow.instance?.switchKeyboardFromAction(choice.id)
                }, LinearLayout.LayoutParams(0, tileHeightPx, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
            }
            if(pair.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, tileHeightPx, 1f))
            grid.addView(row)
        }
        body.addView(ScrollView(context).apply { isFillViewport = true; addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(button("返回") { manager.attachWindow(KeyboardWindow) }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(dp(2), dp(3), dp(2), 0) })
        return body
    }
    override fun onAttached() {}
    override fun onDetached() {}
}
