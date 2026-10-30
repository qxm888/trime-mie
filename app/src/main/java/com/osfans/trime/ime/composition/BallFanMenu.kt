/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.osfans.trime.data.keymap.BindingActions
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.prefs.AppPrefs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.cos
import kotlin.math.sin

/** 悬浮球长按扇形菜单：条目可编辑，动作走 performFloatingToolbarAction。 */
object BallFanMenu {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun defaultEntries(): MutableList<ToolbarOverrides.Entry> = mutableListOf(
        ToolbarOverrides.Entry("__mie_candidates_window", icon = "候选"),
        ToolbarOverrides.Entry("__mie_floating_keyboard", icon = "悬浮"),
        ToolbarOverrides.Entry("__mie_one_hand", icon = "单手"),
        ToolbarOverrides.Entry("clipboard_window", icon = "剪贴"),
    )

    fun load(): MutableList<ToolbarOverrides.Entry> {
        val raw = AppPrefs.defaultInstance().candidates.ballFanActions.getValue()
        if (raw.isBlank()) return defaultEntries()
        return runCatching { json.decodeFromString<MutableList<ToolbarOverrides.Entry>>(raw) }
            .getOrDefault(defaultEntries())
            .filter { it.enabled }
            .toMutableList()
    }

    fun save(entries: List<ToolbarOverrides.Entry>) {
        AppPrefs.defaultInstance().candidates.ballFanActions.setValue(
            json.encodeToString(entries.toList()),
        )
    }

    fun label(entry: ToolbarOverrides.Entry): String =
        entry.icon.ifBlank {
            BindingActions.label(entry.action).ifBlank { entry.action.removePrefix("__mie_") }
        }.take(4)

    fun show(anchor: View, onAction: (String) -> Unit) {
        val entries = load()
        if (entries.isEmpty()) return
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val size = dp(236)
        val radius = dp(82)
        val buttonSize = dp(52)
        val content = FrameLayout(ctx)
        val holder = TextView(ctx).apply {
            text = ""
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT) }
        }
        content.addView(holder, FrameLayout.LayoutParams(1, 1))
        entries.take(6).forEachIndexed { index, entry ->
            val count = entries.take(6).size
            // Upper half circle: index 0 starts at the left and the arc bows up,
            // so every button stays inside the popup above the ball.
            val angle = Math.toRadians(180.0 - (index + 0.5) * 180.0 / count)
            val cx = size / 2.0
            val cy = size - dp(18).toDouble()
            val x = (cx + radius * cos(angle)).toInt()
            val y = (cy - radius * sin(angle)).toInt()
            val button = TextView(ctx).apply {
                text = label(entry)
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xE6263238.toInt())
                    setStroke(dp(1), 0x66FFFFFF)
                }
                elevation = dp(4).toFloat()
                setOnClickListener {
                    popupRef?.dismiss()
                    onAction(entry.action)
                }
            }
            content.addView(
                button,
                FrameLayout.LayoutParams(buttonSize, buttonSize).apply {
                    leftMargin = (x - buttonSize / 2).coerceIn(0, size - buttonSize)
                    topMargin = (y - buttonSize / 2).coerceIn(0, size - buttonSize)
                },
            )
        }
        val popup = PopupWindow(
            content,
            size,
            size,
            true,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        popupRef = popup
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val x = (location[0] + anchor.width / 2 - size / 2)
            .coerceIn(0, (screenWidth - size).coerceAtLeast(0))
        val y = (location[1] - size + dp(4)).coerceAtLeast(0)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    @Volatile
    private var popupRef: PopupWindow? = null
}
