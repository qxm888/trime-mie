/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.data.keymap.BindingActions
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.ime.composition.BallFanMenu

/** 编辑悬浮球长按扇形菜单的条目：新增、修改、删除，动作沿用工具栏按钮编辑器。 */
class BallFanMenuEditActivity : AppCompatActivity() {
    private var entries: MutableList<ToolbarOverrides.Entry> = mutableListOf()
    private lateinit var list: LinearLayout

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@BallFanMenuEditActivity, R.color.framework_background))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.ball_fan_menu_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
        val scroll = android.widget.ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val add = MaterialButton(this).apply {
            text = getString(R.string.ball_fan_menu_add)
            setOnClickListener { edit(ToolbarOverrides.Entry("__mie_menu", icon = "菜单"), isNew = true) }
        }
        root.addView(add, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })
        setContentView(root)
        entries = BallFanMenu.load()
        render()
    }

    private fun render() {
        list.removeAllViews()
        entries.forEachIndexed { index, entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(8), dp(12))
                background = ContextCompat.getDrawable(this@BallFanMenuEditActivity, R.drawable.bg_key_preview)
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(this).apply {
                text = BallFanMenu.label(entry)
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@BallFanMenuEditActivity, R.color.framework_text_primary))
            })
            labels.addView(TextView(this).apply {
                text = BindingActions.label(entry.action).ifBlank { entry.action }
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@BallFanMenuEditActivity, R.color.framework_text_secondary))
            })
            val del = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_baseline_delete_24)
                contentDescription = getString(R.string.custom_kb_delete)
                setOnClickListener {
                    entries.removeAt(index)
                    BallFanMenu.save(entries)
                    render()
                }
            }
            card.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(del)
            card.setOnClickListener { edit(entry, isNew = false, index = index) }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.ball_fan_menu_empty)
                textSize = 14f
                setPadding(dp(8), dp(16), dp(8), dp(16))
            })
        }
    }

    private fun edit(entry: ToolbarOverrides.Entry, isNew: Boolean, index: Int = -1) {
        ToolbarButtonEditor.show(this, entry, standalone = false) { updated ->
            if (isNew) entries.add(updated) else if (index in entries.indices) entries[index] = updated
            BallFanMenu.save(entries)
            render()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
