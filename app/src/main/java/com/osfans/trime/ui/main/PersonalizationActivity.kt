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

open class PersonalizationActivity : AppCompatActivity() {
    protected lateinit var body: LinearLayout
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    protected fun page(title: String) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            this.title = title
            setNavigationIcon(com.osfans.trime.R.drawable.ic_baseline_arrow_back_24)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
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
    private var targetRole = "key"
    private val importer = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            var dest: File? = null
            runCatching {
                val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "font.ttf"
                val safeName = displayName.replace(Regex("""[^\p{L}\p{N}._-]"""), "_").takeLast(100)
                dest = File(FontOverrides.folder(), UUID.randomUUID().toString().take(8) + "_" + safeName)
                contentResolver.openInputStream(uri)!!.use { input -> dest!!.outputStream().use { out ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n
                        require(total <= 32 * 1024 * 1024) { "字体文件不能超过32MB" }; out.write(buffer, 0, n) }
                } }
                Typeface.createFromFile(dest!!)
                FontOverrides.set(targetRole, dest!!.absolutePath)
            }.onFailure { dest?.delete(); Toast.makeText(this, "无法读取字体：请选择有效的 TTF / OTF / TTC 文件", Toast.LENGTH_LONG).show() }
            render()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); targetRole = savedInstanceState?.getString("fontRole") ?: "key"; page("字体设置"); render() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("fontRole", targetRole)
        super.onSaveInstanceState(outState)
    }
    private fun render() {
        body.removeAllViews()
        note("分别设置输入法各区域的字体，也可恢复为跟随当前主题。支持导入 TTF、OTF、TTC 文件；设置对两种键盘模式生效，不修改主题文件。")
        FontOverrides.roles.forEach { (role, title) ->
            button(title + " · " + FontOverrides.name(FontOverrides.selection(role))) { pick(role, title) }.apply {
                typeface = FontOverrides.typeface(role) ?: Typeface.DEFAULT
                minHeight = dp(56)
            }
        }
    }
    private fun pick(role: String, title: String) {
        val choices = FontOverrides.choices()
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setItems((choices.map(FontOverrides::name) + "导入字体文件…").toTypedArray()) { _, index ->
                if (index == choices.size) { targetRole = role; importer.launch("*/*") }
                else { FontOverrides.set(role, choices[index]); render() }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
