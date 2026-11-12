/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.data.theme.FontOverrides
import java.io.File
import java.util.UUID
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class PersonalizationActivity : AppCompatActivity() {
    protected lateinit var body: LinearLayout
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    protected fun page(title: String) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            this.title = title
            setNavigationIcon(com.osfans.trime.R.drawable.ic_baseline_arrow_back_24)
            setNavigationIconTint(androidx.core.content.ContextCompat.getColor(this@PersonalizationActivity,com.osfans.trime.R.color.framework_text_primary))
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window,root).isAppearanceLightStatusBars =
            androidx.core.graphics.ColorUtils.calculateLuminance(androidx.core.content.ContextCompat.getColor(this,com.osfans.trime.R.color.framework_background)) > 0.5
    }
    protected fun note(text: String) {
        body.addView(TextView(this).apply { this.text = text; textSize = 14f; setPadding(0, dp(8), 0, dp(16)) })
    }
    protected fun button(text: String, action: () -> Unit): MaterialButton = MaterialButton(this, null,
        com.google.android.material.R.attr.materialButtonStyle).apply {
        this.text = text
        textSize = 16f
        setTextColor(androidx.core.content.ContextCompat.getColor(this@PersonalizationActivity, com.osfans.trime.R.color.framework_text_primary))
        cornerRadius = dp(4)
        isAllCaps = false
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        minHeight = dp(56)
        insetTop = 0; insetBottom = 0
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@PersonalizationActivity, com.osfans.trime.R.color.settings_surface))
        setOnClickListener { action() }
        body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(1) })
    }
}

class FontSettingsActivity : PersonalizationActivity() {
    private companion object { const val GLOBAL_ROLE = "__theme_global" }
    private var targetRole = "key"
    private var targetThemeId = ""
    private val importer = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
            var dest: File? = null
            val result = withContext(Dispatchers.IO) { runCatching {
                val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "font.ttf"
                val safeName = displayName.replace(Regex("""[^\p{L}\p{N}._-]"""), "_").takeLast(100)
                dest = File(FontOverrides.folder(), UUID.randomUUID().toString().take(8) + "_" + safeName)
                contentResolver.openInputStream(uri)!!.use { input -> dest!!.outputStream().use { out ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                        require(total <= 128 * 1024 * 1024) { "字体文件不能超过128MB" }; out.write(buffer, 0, n) }
                } }
                Typeface.createFromFile(dest!!)
                FontOverrides.fromSelection(dest!!.absolutePath) ?: error("字体不可用")
                val previous = if (targetRole == GLOBAL_ROLE) FontOverrides.globalSelection(targetThemeId) else FontOverrides.selection(targetRole)
                val value = FontOverrides.encode(FontOverrides.decode(previous) + dest!!.absolutePath)
                if (targetRole == GLOBAL_ROLE) FontOverrides.setGlobal(targetThemeId, value) else FontOverrides.set(targetRole, value)
            } }
            result.onFailure { dest?.delete(); Toast.makeText(this@FontSettingsActivity, "无法读取字体：请选择有效的 TTF / OTF / TTC 文件", Toast.LENGTH_LONG).show() }
            render()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetRole = savedInstanceState?.getString("fontRole") ?: "key"
        targetThemeId = savedInstanceState?.getString("fontThemeId").orEmpty()
        page("主题键盘字体与字号"); render()
    }
    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("fontRole", targetRole)
        outState.putString("fontThemeId", targetThemeId)
        super.onSaveInstanceState(outState)
    }
    private fun render() {
        body.removeAllViews()
        note("字体优先级：单项设置 → 当前主题全局字体 → 主题原始字体。全局字体按主题保存，字号设为0时跟随主题。自定义键盘仍在各自编辑菜单中保存字体与字号。")
        val themeId = com.osfans.trime.data.theme.ThemeManager.prefs.selectedTheme.getValue()
        val current = com.osfans.trime.data.theme.ThemeManager.activeThemeOrNull()
        val themeName = current?.takeIf { !it.isCustom && it.fontConfigId == themeId }?.name ?: themeId.removeSuffix(".trime")
        val drawers = SettingsDrawers(body)
        val global = drawers.section("当前主题全局字体")
        global.addView(TextView(this).apply {
            text = if (themeId.isBlank()) "请先选择主题布局" else themeName
            setPadding(dp(8), dp(4), dp(8), dp(8))
        })
        global.addView(MaterialButton(this).apply {
            text = "字体 · " + FontOverrides.globalSelection(themeId).let { if (it.isEmpty()) "跟随主题原始字体" else FontOverrides.name(it) }
            isEnabled = themeId.isNotBlank()
            setOnClickListener { pickGlobal(themeId) }
        })
        FontOverrides.roles.forEach { (role, title) ->
            val section = drawers.section(title)
            // 「高亮候选文字」只开放颜色：字形与字号跟随「候选文字」。
            if (role != "candidateHl") {
                section.addView(MaterialButton(this).apply {
                    text = "字体 · " + FontOverrides.selection(role).let {
                        if (it.isNotEmpty()) FontOverrides.name(it)
                        else if (FontOverrides.globalSelection(themeId).isNotEmpty()) "跟随当前主题全局字体" else "跟随主题原始字体"
                    }
                    setOnClickListener { pick(role,title) }
                })
                section.addView(MaterialButton(this).apply {
                    text = "字号 · " + FontOverrides.size(role).let { if(it==0f) "跟随主题" else "$it sp" }
                    setOnClickListener { pickSize(role,title) }
                })
            }
            section.addView(MaterialButton(this).apply {
                text = "颜色 · " + FontOverrides.color(role).let { if (it.isEmpty()) "跟随主题配色" else it }
                setOnClickListener { pickColor(role, title) }
            })
        }
    }
    private fun pickColor(role: String, title: String) {
        ColorPickerDialog.show(this, "$title 颜色", FontOverrides.color(role).ifBlank { "#FFFFFFFF" }) { hex ->
            FontOverrides.setColor(role, hex)
            render()
        }
    }
    private fun pickSize(role: String, title: String) {
        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(); setText(FontOverrides.size(role).toString())
        }
        val field = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint="字号 sp（0跟随主题，最大200）"; setPadding(dp(24),0,dp(24),0); addView(input)
        }
        val dialog=MaterialAlertDialogBuilder(this).setTitle(title).setView(field).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val value=input.text.toString().toFloatOrNull()
            if(value==null || !value.isFinite() || value !in 0f..200f) input.error="请输入0–200"
            else { FontOverrides.setSize(role,value); render(); dialog.dismiss() }
        } }; dialog.show()
    }
    private fun pick(role: String, title: String) {
        FontChainPicker.show(this, title, FontOverrides.selection(role),
            onImport = { targetRole = role; importer.launch("*/*") }, defaultLabel = "跟随当前主题全局字体；未设置时跟随主题原始字体") { value ->
            FontOverrides.set(role, value)
            render()
        }
    }
    private fun pickGlobal(themeId: String) {
        FontChainPicker.show(this, "当前主题全局字体", FontOverrides.globalSelection(themeId),
            onImport = { targetRole = GLOBAL_ROLE; targetThemeId = themeId; importer.launch("*/*") },
            defaultLabel = "跟随主题原始字体") { value ->
            FontOverrides.setGlobal(themeId, value)
            render()
        }
    }
}
