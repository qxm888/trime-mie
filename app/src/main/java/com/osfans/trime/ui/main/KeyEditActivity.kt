/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import com.osfans.trime.util.readBoundedText
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.osfans.trime.R
import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.orAbsent
import com.osfans.trime.ime.keyboard.KeyboardWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Visual key editor (靓企鹅-style): pick a keyboard, tap a key, bind actions to
 * tap / long press / four-way swipes / double tap.
 *
 * Edits live in [KeyBindingOverrides] (app-private storage) and are applied when
 * a keyboard is (re)built — the theme files themselves are never touched.
 */
class KeyEditActivity : AppCompatActivity() {
    private lateinit var rowsContainer: LinearLayout
    private lateinit var keyboardInput: MaterialAutoCompleteTextView

    private var keyboardIds: List<String> = emptyList()
    private var currentKeyboardId: String = ""

    private val theme by lazy { ThemeManager.activeTheme }

    private var returningFromAppearance = false
    override fun onResume() {
        super.onResume()
        if (returningFromAppearance) {
            returningFromAppearance = false
            refreshAppearancePreview { rebuildPreview() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returningFromAppearance = savedInstanceState?.getBoolean("returningFromAppearance") ?: false
        setContentView(R.layout.activity_key_edit)

        val toolbar = findViewById<MaterialToolbar>(R.id.key_edit_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.key_edit_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.key_edit)
        toolbar.menu.add(0, 2180, 0, "全局外观设置")
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2180 -> { returningFromAppearance = true; SettingsPageFragment.launch(this, "appearance") }
                R.id.action_export_bindings -> exportBindings()
                R.id.action_import_bindings -> importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                R.id.action_theme_video -> { returningFromAppearance = true; SettingsPageFragment.launch(this, "background") }
                R.id.action_reset_all_bindings -> {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.key_editor_reset_all)
                        .setMessage(R.string.key_editor_reset_all_confirm)
                        .setPositiveButton(R.string.ok) { _, _ ->
                            KeyBindingOverrides.resetAll()
                            KeyBindingOverrides.flush()
                            rebuildPreview()
                            Toast.makeText(this, R.string.key_editor_done, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            true
        }

        rowsContainer = findViewById(R.id.key_edit_rows)
        keyboardInput = findViewById(R.id.key_edit_keyboard_input)

        keyboardIds = theme.presetKeyboards.keys.toList()
        if (keyboardIds.isEmpty()) {
            Toast.makeText(this, R.string.key_editor_no_keyboards, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val preferred = savedInstanceState?.getString("editingKeyboard") ?: runCatching { KeyboardWindow.lastAttachedId }.getOrDefault("")
        currentKeyboardId = if (preferred in keyboardIds) preferred else keyboardIds.first()
        val displayNames = keyboardIds.map { id ->
            displayNameOf(id) + "  ($id)"
        }
        keyboardInput.setSimpleItems(displayNames.toTypedArray())
        keyboardInput.setText(displayNames[keyboardIds.indexOf(currentKeyboardId)], false)
        keyboardInput.setOnItemClickListener { _, _, position, _ ->
            currentKeyboardId = keyboardIds.getOrNull(position) ?: return@setOnItemClickListener
            rebuildPreview()
        }

        rebuildPreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("editingKeyboard", currentKeyboardId)
        outState.putBoolean("returningFromAppearance", returningFromAppearance)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        if (!KeyBindingOverrides.flush()) android.widget.Toast.makeText(this, "配置保存失败，请检查剩余存储空间", android.widget.Toast.LENGTH_LONG).show()
        ThemeManager.requestCustomizationRefresh()
    }

    // ----------------------------------------------------------------
    // export / import (SAF, JSON; the theme files are never touched)
    // ----------------------------------------------------------------

    private val exportLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val ok =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.use {
                                it.write(KeyBindingOverrides.exportJson().toByteArray(Charsets.UTF_8))
                            } ?: error("cannot open output")
                        }
                    }.isSuccess
                toast(if (ok) R.string.key_editor_exported else R.string.key_editor_export_failed)
            }
        }

    private val importLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val text =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { it.readBoundedText(16 * 1024 * 1024) }
                                ?: error("cannot open input")
                        }
                    }.getOrNull()
                if (text == null) {
                    toast(R.string.key_editor_import_failed)
                    return@launch
                }
                chooseImportMode { replace ->
                    lifecycleScope.launch {
                        val ok = runCatching { withContext(Dispatchers.IO) {
                            KeyBindingOverrides.importJson(text, replace)
                            check(KeyBindingOverrides.flush()) { "按键绑定保存失败" }
                        } }.isSuccess
                        if (ok) { rebuildPreview(); toast(R.string.key_editor_imported) }
                        else toast(R.string.key_editor_import_failed)
                    }
                }
            }
        }

    private fun exportBindings() {
        if (KeyBindingOverrides.count() == 0) {
            toast(R.string.key_editor_nothing_to_export)
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
        exportLauncher.launch("trime-keys-$stamp.json")
    }

    private fun chooseImportMode(onChosen: (Boolean) -> Unit) {
        val options = arrayOf(getString(R.string.key_editor_import_merge), getString(R.string.key_editor_import_replace))
        var choice = 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.key_editor_import_mode)
            .setSingleChoiceItems(options, 0) { _, which -> choice = which }
            .setPositiveButton(R.string.ok) { _, _ -> onChosen(choice == 1) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    // ----------------------------------------------------------------
    // preview
    // ----------------------------------------------------------------

    /** Same import_preset resolution as KeyboardWindow.selectKeyboardConfig. */
    private fun resolveConfig(id: String): TextKeyboard? {
        val config = theme.presetKeyboards[id] ?: theme.presetKeyboards["default"] ?: return null
        val import = config.importPreset
        return if (import.isNotEmpty()) resolveConfig(import) else config
    }

    private fun displayNameOf(id: String): String =
        theme.presetKeyboards[id]?.name?.takeIf { it.isNotEmpty() } ?: id

    private fun rebuildPreview() {
        rowsContainer.removeAllViews()
        val config = resolveConfig(currentKeyboardId) ?: return
        val overrides = KeyBindingOverrides.bindingsFor(currentKeyboardId)

        val defaultWidth = config.width.takeIf { it > 0f } ?: 10f
        val maxColumns = config.columns.takeIf { it > 0 } ?: 30

        var row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        var weightAcc = 0f
        var column = 0

        fun finishRow() {
            if (row.childCount > 0) rowsContainer.addView(row)
            row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            weightAcc = 0f
            column = 0
        }

        config.keys.forEachIndexed { index, key ->
            val isSpacer = key.click.orAbsent == null
            val w = key.width.takeIf { it > 0 } ?: defaultWidth
            if (column >= maxColumns || weightAcc + w > 100.5f) finishRow()
            weightAcc += w
            column++

            if (isSpacer) {
                row.addView(
                    Space(this),
                    LinearLayout.LayoutParams(0, dp(52)).apply { weight = w },
                )
                return@forEachIndexed
            }

            val customized = overrides[index]?.isNotEmpty() == true
            // 只显示按键本身的字母/名称，不带主题的 label_symbol / hint 等符号
            val label = key.label.ifEmpty {
                (key.click as? KeyActionToken.Plain)?.token ?: "?"
            }

            val keyView = TextView(this).apply {
                text = if (customized) "$label ●" else label
                gravity = Gravity.CENTER
                textSize = 15f
                maxLines = 1
                isSingleLine = true
                includeFontPadding = false
                setPadding(dp(2), 0, dp(2), 0)
                setTypeface(typeface, if (customized) Typeface.BOLD else Typeface.NORMAL)
                background = AppearancePicker.preview(com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.keyBackground.getValue()) ?: ContextCompat.getDrawable(
                    this@KeyEditActivity,
                    if (customized) R.drawable.bg_key_preview_custom else R.drawable.bg_key_preview,
                )
                setTextColor(
                    ContextCompat.getColor(
                        this@KeyEditActivity,
                        if (customized) R.color.m3_primary else android.R.color.darker_gray,
                    ),
                )
            }
            keyView.setOnClickListener { showKeyEditor(index, label) }
            row.addView(
                keyView,
                LinearLayout.LayoutParams(0, dp(52), w).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                },
            )
        }
        finishRow()
    }

    // ----------------------------------------------------------------
    // per-key binding editor
    // ----------------------------------------------------------------

    private fun showKeyEditor(
        index: Int,
        keyLabel: String,
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
        val rowTokens = mutableMapOf<String, TextView>()

        KeyBindingOverrides.EDITABLE_BEHAVIORS.forEach { (behavior, meta) ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(12), dp(10), dp(12))
                background = ContextCompat.getDrawable(this@KeyEditActivity, R.drawable.bg_key_preview)
            }
            val name = TextView(this).apply {
                text = meta.labelZh
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@KeyEditActivity, R.color.framework_text_primary))
            }
            val value = TextView(this).apply {
                gravity = Gravity.END
                textSize = 13f
                maxLines = 1
                setSingleLine(true)
            }
            rowTokens[behavior] = value
            rowView.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rowView.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            rowView.setOnClickListener {
                pickAction(behavior, index, keyLabel) {
                    refreshRow(rowTokens, behavior, index)
                    rebuildPreview()
                }
            }
            container.addView(rowView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }

        KeyBindingOverrides.EDITABLE_BEHAVIORS.forEach { (behavior, _) ->
            refreshRow(rowTokens, behavior, index)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(keyLabel)
            .setView(container)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** Updates one behavior row's value text ("token · 已定制" or the theme default). */
    private fun refreshRow(
        rowTokens: Map<String, TextView>,
        behavior: String,
        index: Int,
    ) {
        val view = rowTokens[behavior] ?: return
        val override = KeyBindingOverrides.binding(currentKeyboardId, index, behavior)
        val themeDefault = themeTokenOf(index, behavior)
        val text = when {
            override != null && override.isEmpty() -> getString(R.string.key_editor_unbound)
            override != null -> override
            themeDefault != null -> themeDefault
            else -> getString(R.string.key_editor_empty_bind)
        }
        val suffix =
            if (override != null) {
                " · " + getString(R.string.key_editor_customized)
            } else if (themeDefault != null) {
                " · " + getString(R.string.key_editor_default)
            } else {
                ""
            }
        view.text = text + suffix
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (override != null) R.color.m3_primary else R.color.framework_text_secondary,
            ),
        )
    }

    private fun themeTokenOf(
        index: Int,
        behavior: String,
    ): String? {
        val key = resolveConfig(currentKeyboardId)?.keys?.getOrNull(index) ?: return null
        val token =
            when (behavior) {
                "click" -> key.click
                "doubleClick" -> key.doubleClick
                "lazyDoubleClick" -> key.lazyDoubleClick
                "longClick" -> key.longClick
                "swipeUp" -> key.swipeUp
                "swipeDown" -> key.swipeDown
                "swipeLeft" -> key.swipeLeft
                "swipeRight" -> key.swipeRight
                else -> null
            } ?: return null
        return when (token) {
            is KeyActionToken.Plain -> token.token.ifEmpty { null }
            is KeyActionToken.Inline -> token.label ?: token.commit ?: token.text
            null -> null
        }
    }

    // ----------------------------------------------------------------
    // action picker
    // ----------------------------------------------------------------

    private fun pickAction(
        behavior: String,
        index: Int,
        keyLabel: String,
        onChanged: () -> Unit,
    ) {
        KeyTokenPicker.show(
            this,
            "${KeyBindingOverrides.EDITABLE_BEHAVIORS.firstOrNull { it.first == behavior }?.second?.labelZh} · $keyLabel",
            KeyBindingOverrides.binding(currentKeyboardId, index, behavior) != null,
        ) { token ->
            // null = 恢复主题默认；"" = 清空绑定；其余 = 绑定该 token
            KeyBindingOverrides.setBinding(currentKeyboardId, index, behavior, token)
            onChanged()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    companion object {
        private const val OP_RESTORE = 0
        private const val OP_UNBIND = 1
        private const val OP_CUSTOM = 2
        private const val OP_CATEGORY = 100

        /** 常用符号（直接作为 token 上屏该符号）。 */
        private val SYMBOLS = listOf(
            "，", "。", "、", "；", "：", "？", "！", "…", "—", "·",
            "（", "）", "《", "》", "“", "”", "‘", "’",
            ",", ".", "?", "!", ";", ":", "'", "\"", "@", "#", "$", "%",
            "&", "*", "-", "_", "+", "=", "/", "\\", "|", "~", "<", ">", "[", "]", "{", "}", "^", "`",
        )

        fun start(context: Context) {
            context.startActivity(
                android.content.Intent(context, KeyEditActivity::class.java).apply {
                    if (context !is AppCompatActivity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
