/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Manager of the user-defined keyboards and the layout-mode switch:
 * theme-file layouts and fully custom layouts are two exclusive modes and
 * never mix. Editing data lives in [CustomKeyboards]; theme files untouched.
 */
class CustomKeyboardActivity : AppCompatActivity() {
    private var pendingExportName: String? = null
    private lateinit var list: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var themeSection: View
    private lateinit var listHeader: TextView
    private lateinit var newButton: MaterialButton
    private lateinit var modeCurrent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportName = savedInstanceState?.getString("pending_export_name")
        setContentView(R.layout.activity_custom_kb)

        val toolbar = findViewById<MaterialToolbar>(R.id.custom_kb_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.custom_kb_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.custom_kb)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_theme_keyboard -> importThemeKeyboard()
                R.id.action_import_baidu_skin -> baiduLauncher.launch(arrayOf("*/*"))
                R.id.action_export_custom_kbs -> exportLayouts()
                R.id.action_import_custom_kbs ->
                    importLauncher.launch(arrayOf("application/yaml", "application/x-yaml", "text/yaml", "text/plain", "application/json", "*/*"))

            }
            true
        }

        list = findViewById(R.id.custom_kb_list)
        emptyHint = findViewById(R.id.custom_kb_empty)
        themeSection = findViewById(R.id.custom_kb_theme_section)
        listHeader = findViewById(R.id.custom_kb_list_header)
        newButton = findViewById(R.id.custom_kb_new)
        modeCurrent = findViewById(R.id.custom_kb_mode_current)

        val toggle = findViewById<MaterialButtonToggleGroup>(R.id.custom_kb_mode_toggle)
        toggle.check(
            if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) {
                R.id.custom_kb_mode_custom_btn
            } else {
                R.id.custom_kb_mode_theme_btn
            },
        )
        toggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode =
                if (findViewById<MaterialButton>(R.id.custom_kb_mode_theme_btn).isChecked) {
                    CustomKeyboards.MODE_THEME
                } else {
                    CustomKeyboards.MODE_CUSTOM
                }
            CustomKeyboards.setMode(newMode)
            renderSections()
            refreshTheme()
        }

        newButton.setOnClickListener { createKeyboard() }
        findViewById<View>(R.id.custom_kb_import_theme).setOnClickListener { importThemeKeyboard() }
        themeSection.setOnClickListener { KeyEditActivity.start(this) }
        findViewById<View>(R.id.custom_kb_toolbar_section).setOnClickListener {
            startActivity(android.content.Intent(this, ToolbarEditActivity::class.java))
        }
        renderSections()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_export_name", pendingExportName)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        if (!CustomKeyboards.flush()) android.widget.Toast.makeText(this, "配置保存失败，请检查剩余存储空间", android.widget.Toast.LENGTH_LONG).show()
    }

    // ----------------------------------------------------------------
    // export / import (SAF, YAML + legacy JSON; the theme files are never touched)
    // ----------------------------------------------------------------

    private val exportLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/yaml")) { uri ->
            val snapshot = pendingExportName?.takeIf { it.startsWith("keyboard-export-") && '/' !in it && '\\' !in it }
                ?.let { java.io.File(cacheDir, it) }
            pendingExportName = null
            if (uri == null) {
                snapshot?.delete()
            } else lifecycleScope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) {
                        require(snapshot != null && snapshot.isFile) { "导出快照已失效，请重新选择键盘" }
                        try {
                            contentResolver.openOutputStream(uri, "wt")?.use { output -> snapshot.inputStream().use { it.copyTo(output) } }
                                ?: error("无法写入所选文件")
                        } finally { snapshot.delete() }
                    }
                }.isSuccess
                Toast.makeText(this@CustomKeyboardActivity, if (ok) R.string.key_editor_exported else R.string.key_editor_export_failed, Toast.LENGTH_SHORT).show()
            }
        }

    private val importLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val readResult =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { input ->
                                val bytes = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer); if (count < 0) break
                                    require(bytes.size() + count <= 10 * 1024 * 1024) { "布局文件超过10MB" }
                                    bytes.write(buffer, 0, count)
                                }
                                bytes.toString(Charsets.UTF_8.name())
                            } ?: error("cannot open input")
                        }
                    }
                if (!isActive || isFinishing || isDestroyed) return@launch
                val text = readResult.getOrElse { error ->
                    MaterialAlertDialogBuilder(this@CustomKeyboardActivity).setTitle(R.string.key_editor_import_failed)
                        .setMessage(error.message).setPositiveButton(android.R.string.ok, null).show()
                    return@launch
                }
                val options =
                    arrayOf(
                        getString(R.string.key_editor_import_merge),
                        getString(R.string.key_editor_import_replace),
                    )
                var choice = 0
                MaterialAlertDialogBuilder(this@CustomKeyboardActivity)
                    .setTitle(R.string.key_editor_import_mode)
                    .setSingleChoiceItems(options, 0) { _, which -> choice = which }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            val imported = runCatching { withContext(Dispatchers.IO) {
                                CustomKeyboards.importDocument(text, choice == 1)
                                check(CustomKeyboards.flush()) { "布局保存失败，请检查剩余存储空间" }
                            } }
                            if (!isActive || isFinishing || isDestroyed) return@launch
                            imported.onSuccess {
                                renderSections(); refreshTheme()
                                Toast.makeText(this@CustomKeyboardActivity, R.string.key_editor_imported, Toast.LENGTH_SHORT).show()
                            }.onFailure { error ->
                                MaterialAlertDialogBuilder(this@CustomKeyboardActivity).setTitle(R.string.key_editor_import_failed)
                                    .setMessage(error.message).setPositiveButton(android.R.string.ok, null).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }


    private val baiduLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val progress = MaterialAlertDialogBuilder(this).setTitle(R.string.import_baidu_skin)
                .setMessage("正在解析皮肤和转换素材，请稍候…").setCancelable(false).create()
            progress.show()
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else "百度皮肤.bds"
                        } ?: "百度皮肤.bds"
                        val temp = java.io.File.createTempFile("baidu-import-", ".bds", cacheDir)
                        try {
                            contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output ->
                                val bytes = ByteArray(8192); var total = 0L
                                while (true) { val count = input.read(bytes); if (count < 0) break
                                    total += count; require(total <= 64L * 1024 * 1024) { "皮肤文件超过64MB" }; output.write(bytes, 0, count) }
                            } } ?: error("无法读取皮肤文件")
                            com.osfans.trime.data.baiduskin.BdsImporter.import(this@CustomKeyboardActivity, temp, name)
                        } finally { temp.delete() }
                    }
                }
                progress.dismiss()
                if (!isActive || isFinishing || isDestroyed) return@launch
                result.onSuccess { imported ->
                    renderSections()
                    val details = buildString {
                        append("已导入 ${imported.name}，共 ${imported.ids.size} 个布局。包含按键图层、按压动效和原包音效。\n\n音效跟随同文的按键声音开关。九宫格需搭配九键输入方案，识别和候选由同文 / Rime 提供。")
                        if (imported.warnings.isNotEmpty()) append("\n\n兼容说明：\n" + imported.warnings.take(15).joinToString("\n"))
                    }
                    MaterialAlertDialogBuilder(this@CustomKeyboardActivity).setTitle("皮肤导入完成").setMessage(details)
                        .setPositiveButton("立即使用") { _, _ ->
                            CustomKeyboards.activeId = imported.ids.first()
                            CustomKeyboards.setMode(CustomKeyboards.MODE_CUSTOM)
                            findViewById<MaterialButtonToggleGroup>(R.id.custom_kb_mode_toggle).check(R.id.custom_kb_mode_custom_btn)
                            CustomKeyboards.flush(); renderSections(); ThemeManager.requestCustomizationRefresh()
                        }.setNegativeButton("稍后选择", null).show()
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(this@CustomKeyboardActivity).setTitle("皮肤导入失败")
                        .setMessage(error.message ?: "无法识别此皮肤格式").setPositiveButton(android.R.string.ok, null).show()
                }
            }
        }
    }

    private fun exportLayouts() {
        val entries = CustomKeyboards.list()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.key_editor_nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(entries.size) { entries[it].id == CustomKeyboards.activeId }
        if (checked.none { it }) checked[0] = true
        val duplicateNames = entries.groupingBy { it.name }.eachCount()
        val names = entries.map { entry ->
            val suffix = if (duplicateNames.getValue(entry.name) > 1) {
                CustomKeyboards.get(entry.id)?.importedSkin?.sourceName?.takeIf { it.isNotBlank() } ?: entry.id.takeLast(8)
            } else ""
            "${entry.name}（${entry.rowCount}行 · ${entry.keyCount}键）" + if (suffix.isEmpty()) "" else "\n$suffix"
        }.toTypedArray()
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.custom_kb_export_select)
            .setMultiChoiceItems(names, checked) { _, which, selected -> checked[which] = selected }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.custom_kb_export_all, null)
            .setPositiveButton(R.string.custom_kb_export_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                checked.fill(true)
                for (i in entries.indices) dialog.listView.setItemChecked(i, true)
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = entries.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, R.string.custom_kb_export_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                lifecycleScope.launch {
                    val snapshot = runCatching { withContext(Dispatchers.IO) {
                        val content = CustomKeyboards.exportYaml(selected.map { it.id })
                        java.io.File.createTempFile("keyboard-export-", ".yaml", cacheDir).apply { writeText(content, Charsets.UTF_8) }
                    } }
                    if (!isActive || isFinishing || isDestroyed) { snapshot.getOrNull()?.delete(); return@launch }
                    snapshot.onSuccess { file ->
                        pendingExportName?.let { java.io.File(cacheDir, it).delete() }
                        pendingExportName = file.name
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                        val name = if (selected.size == 1) selected.first().name else "trime-keyboards-$stamp"
                        val safeName = name.map { if (it in "/\\:*?\"<>|" || it.isISOControl()) '_' else it }.joinToString("").take(64).ifBlank { "keyboard" }
                        exportLauncher.launch("$safeName.trime.yaml")
                    }.onFailure { error ->
                        MaterialAlertDialogBuilder(this@CustomKeyboardActivity).setTitle(R.string.key_editor_export_failed)
                            .setMessage(error.message).setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun refreshTheme() {
        ThemeManager.requestCustomizationRefresh()
        Toast.makeText(this, R.string.custom_kb_mode_changed, Toast.LENGTH_SHORT).show()
    }

    private fun importThemeKeyboard() {
        ThemeKeyboardImportDialog(this) { result ->
            renderSections()
            val primary=CustomKeyboards.get(result.primaryId)?.name.orEmpty()
            val message=buildString {
                append("已生成「$primary」。")
                if(result.ids.size>1) append("同时生成 ${result.ids.size-1} 个跳转布局，数字／符号等按键已关联到新布局。")
                append("\n\n可继续编辑按键、工具栏和外观。")
                if(result.warnings.isNotEmpty()) append("\n\n兼容说明：\n"+result.warnings.take(10).joinToString("\n"))
            }
            MaterialAlertDialogBuilder(this).setTitle("主题键盘导入完成").setMessage(message)
                .setPositiveButton("编辑新键盘") { _,_ -> CustomKeyboardEditActivity.start(this,result.primaryId) }
                .setNeutralButton("立即使用") { _,_ ->
                    CustomKeyboards.activeId=result.primaryId
                    CustomKeyboards.setMode(CustomKeyboards.MODE_CUSTOM)
                    findViewById<MaterialButtonToggleGroup>(R.id.custom_kb_mode_toggle).check(R.id.custom_kb_mode_custom_btn)
                    CustomKeyboards.flush();renderSections();ThemeManager.requestCustomizationRefresh()
                }.setNegativeButton("完成",null).show()
        }.show()
    }

    private fun createKeyboard() {
        val layout =
            TextInputLayout(this).apply {
                hint = getString(R.string.custom_kb_name_hint)
                setPadding(dp(20), dp(4), dp(20), dp(4))
            }
        val input =
            TextInputEditText(layout.context).apply {
                setSingleLine(true)
            }
        layout.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_kb_new)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val id = CustomKeyboards.create(name)
                    renderList()
                    CustomKeyboardEditActivity.start(this, id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 模式联动：主题模式只显示「主题键盘按键定制」，自定义模式只显示自定义键盘列表。 */
    private fun renderSections() {
        val isTheme = CustomKeyboards.mode == CustomKeyboards.MODE_THEME
        modeCurrent.text =
            getString(
                R.string.custom_kb_current,
                getString(
                    if (isTheme) R.string.custom_kb_mode_theme else R.string.custom_kb_mode_custom,
                ),
            )
        themeSection.visibility = if (isTheme) View.VISIBLE else View.GONE
        findViewById<View>(R.id.custom_kb_toolbar_section).visibility =
            if (isTheme) View.VISIBLE else View.GONE
        listHeader.visibility = if (isTheme) View.GONE else View.VISIBLE
        list.visibility = if (isTheme) View.GONE else View.VISIBLE
        newButton.visibility = if (isTheme) View.GONE else View.VISIBLE
        renderList()
    }

    private fun renderList() {
        list.removeAllViews()
        // 主列表只显示主键盘；次级键盘（跳转用）在主键盘详情的“次级键盘”入口里管理。
        val entries = CustomKeyboards.list().filter { it.secondaryOf.isEmpty() }
        val isTheme = CustomKeyboards.mode == CustomKeyboards.MODE_THEME
        emptyHint.visibility = if (!isTheme && entries.isEmpty()) View.VISIBLE else View.GONE

        entries.forEach { entry ->
            val active = CustomKeyboards.isActive(entry.id)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(8), dp(14))
                background =
                    ContextCompat.getDrawable(
                        this@CustomKeyboardActivity,
                        if (active) R.drawable.bg_key_preview_custom else R.drawable.bg_key_preview,
                    )
            }
            val title =
                LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.apply {
                    val name =
                        TextView(this@CustomKeyboardActivity).apply {
                            text = entry.name
                            textSize = 16f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(ContextCompat.getColor(this@CustomKeyboardActivity, R.color.framework_text_primary))
                        }
                    val meta =
                        TextView(this@CustomKeyboardActivity).apply {
                            text =
                                getString(R.string.custom_kb_meta, entry.rowCount, entry.keyCount) +
                                    if (active) " · " + getString(R.string.custom_kb_in_use) else ""
                            textSize = 12f
                            setTextColor(
                                ContextCompat.getColor(
                                    this@CustomKeyboardActivity,
                                    if (active) R.color.m3_primary else R.color.framework_text_secondary,
                                ),
                            )
                        }
                    addView(name)
                    addView(meta)
                }
            val tint = ContextCompat.getColorStateList(this, R.color.framework_text_secondary)
            val borderless = android.util.TypedValue().let {
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
                it.resourceId
            }
            val rename =
                android.widget.ImageButton(this).apply {
                    setImageResource(R.drawable.ic_baseline_edit_24)
                    imageTintList = tint
                    setBackgroundResource(borderless)
                    contentDescription = getString(R.string.custom_kb_rename)
                    setOnClickListener { renameKeyboard(entry.id, entry.name) }
                }
            val delete =
                android.widget.ImageButton(this).apply {
                    setImageResource(R.drawable.ic_baseline_delete_24)
                    imageTintList = tint
                    setBackgroundResource(borderless)
                    contentDescription = getString(R.string.custom_kb_delete)
                    setOnClickListener { deleteKeyboard(entry.id, entry.name) }
                }
            card.addView(
                title,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            card.addView(rename)
            card.addView(delete)
            card.setOnClickListener {
                val options =
                    arrayOf(
                        getString(R.string.custom_kb_use_this),
                        getString(R.string.custom_kb_edit),
                        getString(R.string.secondary_kb_entry),
                    )
                MaterialAlertDialogBuilder(this)
                    .setTitle(entry.name)
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                CustomKeyboards.activeId = entry.id
                                renderSections()
                                refreshTheme()
                            }

                            1 -> CustomKeyboardEditActivity.start(this, entry.id)
                            else -> SecondaryKeyboardsActivity.start(this, entry.id)
                        }
                    }
                    .show()
            }
            list.addView(
                card,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(6)
                },
            )
        }
    }

    private fun renameKeyboard(
        id: String,
        old: String,
    ) {
        val layout =
            TextInputLayout(this).apply {
                hint = getString(R.string.custom_kb_name_hint)
                setPadding(dp(20), dp(4), dp(20), dp(4))
            }
        val input =
            TextInputEditText(layout.context).apply {
                setText(old)
                setSelection(old.length)
                setSingleLine(true)
            }
        layout.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_kb_rename)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    CustomKeyboards.rename(id, name)
                    renderList()
                    refreshTheme()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteKeyboard(
        id: String,
        name: String,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.custom_kb_delete)
            .setMessage(getString(R.string.custom_kb_delete_confirm, name))
            .setPositiveButton(R.string.ok) { _, _ ->
                CustomKeyboards.delete(id)
                renderSections()
                refreshTheme()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun Int.sp12(): Int = this // placeholder, see usage

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, CustomKeyboardActivity::class.java).apply {
                    if (context !is AppCompatActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
