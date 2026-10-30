/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.CustomKeyboards

/** 一套键盘的次级键盘（数字、符号等跳转目标）集中管理。 */
class SecondaryKeyboardsActivity : AppCompatActivity() {
    private var primaryId: String = ""
    private lateinit var list: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var toolbar: MaterialToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        primaryId = intent.getStringExtra(EXTRA_PRIMARY_ID).orEmpty()
        val primary = CustomKeyboards.get(primaryId)
        if (primary == null) { finish(); return }
        setContentView(R.layout.activity_secondary_keyboards)

        toolbar = findViewById(R.id.secondary_kb_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.secondary_kb_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.title = getString(R.string.secondary_kb_title, primary.name)
        toolbar.setNavigationOnClickListener { finish() }

        list = findViewById(R.id.secondary_kb_list)
        emptyHint = findViewById(R.id.secondary_kb_empty)
        emptyHint.text = getString(R.string.secondary_kb_empty)
        findViewById<MaterialButton>(R.id.secondary_kb_new).setOnClickListener { createSecondary() }
        renderList()
    }

    override fun onResume() {
        super.onResume()
        renderList()
    }

    private fun renderList() {
        val primary = CustomKeyboards.get(primaryId) ?: return
        toolbar.title = getString(R.string.secondary_kb_title, primary.name)
        list.removeAllViews()
        val entries = CustomKeyboards.listSecondaries(primaryId)
        emptyHint.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        entries.forEach { entry ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(8), dp(14))
                background = ContextCompat.getDrawable(this@SecondaryKeyboardsActivity, R.drawable.bg_key_preview)
            }
            val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            title.addView(TextView(this).apply {
                text = entry.name
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@SecondaryKeyboardsActivity, R.color.framework_text_primary))
            })
            title.addView(TextView(this).apply {
                text = getString(R.string.custom_kb_meta, entry.rowCount, entry.keyCount)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SecondaryKeyboardsActivity, R.color.framework_text_secondary))
            })
            val tint = ContextCompat.getColorStateList(this, R.color.framework_text_secondary)
            val borderless = android.util.TypedValue().let {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                it.resourceId
            }
            val rename = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_baseline_edit_24)
                imageTintList = tint
                setBackgroundResource(borderless)
                contentDescription = getString(R.string.custom_kb_rename)
                setOnClickListener { renameKeyboard(entry.id, entry.name) }
            }
            val delete = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_baseline_delete_24)
                imageTintList = tint
                setBackgroundResource(borderless)
                contentDescription = getString(R.string.custom_kb_delete)
                setOnClickListener { deleteKeyboard(entry.id, entry.name) }
            }
            card.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(rename)
            card.addView(delete)
            card.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(entry.name)
                    .setItems(arrayOf(getString(R.string.custom_kb_edit))) { _, _ ->
                        CustomKeyboardEditActivity.start(this, entry.id)
                    }
                    .show()
            }
            list.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
    }

    private fun createSecondary() {
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.secondary_kb_name_hint)
            setPadding(dp(20), dp(4), dp(20), dp(4))
        }
        val input = TextInputEditText(layout.context).apply { setSingleLine(true) }
        layout.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.secondary_kb_new)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val id = CustomKeyboards.createSecondary(name, primaryId)
                    renderList()
                    CustomKeyboardEditActivity.start(this, id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renameKeyboard(id: String, current: String) {
        val layout = TextInputLayout(this).apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        val input = TextInputEditText(layout.context).apply { setSingleLine(true); setText(current) }
        layout.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_kb_rename)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    CustomKeyboards.rename(id, name)
                    renderList()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteKeyboard(id: String, name: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.custom_kb_delete))
            .setMessage(getString(R.string.secondary_kb_delete_confirm, name))
            .setPositiveButton(R.string.ok) { _, _ ->
                CustomKeyboards.delete(id)
                renderList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_PRIMARY_ID = "primary_id"
        fun start(context: Context, primaryId: String) {
            context.startActivity(Intent(context, SecondaryKeyboardsActivity::class.java).putExtra(EXTRA_PRIMARY_ID, primaryId))
        }
    }
}
