/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.BindingActions
import com.osfans.trime.data.keymap.NamedFunctions

/**
 * 全部可绑定动作的代码目录：与按键编辑器"添加动作"共用同一份
 * [KeyTokenPicker.categories] 数据。列表按分类分节，支持按代码或说明搜索；
 * 点击条目把代码复制到剪贴板。
 */
class ActionCatalogActivity : AppCompatActivity() {
    private data class Row(val header: String?, val display: String, val token: String)

    private data class Flat(
        val category: String,
        val display: String,
        val token: String,
    )

    private lateinit var adapter: android.widget.ArrayAdapter<String>
    private val visible = mutableListOf<Row>()
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ActionCatalogActivity, R.color.framework_background))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.action_catalog)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val summaryText = TextView(this).apply {
            text = getString(R.string.action_catalog_summary)
            textSize = 13f
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        root.addView(summaryText)
        val field = TextInputLayout(this).apply {
            setPadding(dp(12), 0, dp(12), dp(4))
            hint = getString(R.string.action_catalog_search_hint)
        }
        search = EditText(this).apply { setSingleLine() }
        field.addView(search)
        root.addView(field, LinearLayout.LayoutParams(-1, -2))

        val list = ListView(this).apply { divider = null }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val flat = mutableListOf<Flat>()
        KeyTokenPicker.categories(this, standalone = false).forEach { (category, entries) ->
            entries.forEach { entry ->
                // 人类可读的"代码"：
                // - __parameter: 前缀是选择器内部形态，用户在"自定义输入"里填命令名本身；
                // - __mie_action:{...} 是选择器生成的序列化 token，解析回 send/command/select 展示；
                // - 自定义功能的 typeable 形态是功能名（自定义输入里直接填名字）；
                // - 其余（Rime 键名、系统键名、__mie_* 内置动作、组合键）本身就是可填代码。
                val code = readableCode(category, entry.token)
                flat.add(Flat(category, entry.display, code))
            }
        }
        adapter = object : android.widget.ArrayAdapter<String>(this, 0) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = visible[position]
                val view = convertView as? LinearLayout ?: LinearLayout(this@ActionCatalogActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                }
                if (row.header != null) {
                    val header = view.findViewWithTag<TextView>("header") ?: TextView(this@ActionCatalogActivity).apply {
                        tag = "header"
                        setTextColor(ContextCompat.getColor(context, com.osfans.trime.R.color.framework_text_secondary))
                        textSize = 13f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    header.text = row.header
                    header.visibility = View.VISIBLE
                    if (header.parent == null) view.addView(header, 0)
                } else {
                    view.findViewWithTag<TextView>("header")?.visibility = View.GONE
                }
                val title = view.findViewWithTag<TextView>("title") ?: TextView(this@ActionCatalogActivity).apply {
                    tag = "title"
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(context, com.osfans.trime.R.color.framework_text_primary))
                }
                title.text = row.display
                if (title.parent == null) view.addView(title)
                val code = view.findViewWithTag<TextView>("code") ?: TextView(this@ActionCatalogActivity).apply {
                    tag = "code"
                    textSize = 12f
                    setTypeface(android.graphics.Typeface.MONOSPACE)
                    setTextColor(ContextCompat.getColor(context, com.osfans.trime.R.color.framework_text_secondary))
                }
                code.text = row.token
                if (code.parent == null) view.addView(code)
                view.setOnClickListener { copyCode(row.token) }
                return view
            }
        }
        list.adapter = adapter
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyFilter(flat, s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        applyFilter(flat, "")
    }

    /** 把选择器的内部 token 转成用户可读（多数可直接填入"自定义输入"）的代码。 */
    private fun readableCode(
        category: String,
        token: String,
    ): String {
        if (token.startsWith("__parameter:")) return token.removePrefix("__parameter:")
        if (token.startsWith(NamedFunctions.PREFIX)) {
            val id = token.removePrefix(NamedFunctions.PREFIX)
            return NamedFunctions.list().firstOrNull { it.id == id }?.name ?: id
        }
        val action = BindingActions.resolve(token)
        if (action != null) {
            if (action.command.isNotEmpty()) {
                return action.command + if (action.option.isEmpty()) "" else " · ${action.option}"
            }
            if (action.select.isNotEmpty()) return "${action.select}（键盘跳转）"
            if (action.send.isNotEmpty() && action.send != "FUNCTION") return action.send
            if (action.commit.isNotEmpty()) return "commit ${action.commit}"
            if (action.text.isNotEmpty()) return "text ${action.text}"
        }
        return token
    }

    private fun applyFilter(
        flat: List<Flat>,
        query: String,
    ) {        val q = query.trim()
        visible.clear()
        var lastCategory: String? = null
        val matched =
            if (q.isEmpty()) flat
            else flat.filter { it.display.contains(q, true) || it.token.contains(q, true) || it.category.contains(q, true) }
        matched.forEach { entry ->
            if (entry.category != lastCategory) {
                visible.add(Row(entry.category, "", ""))
                lastCategory = entry.category
            }
            visible.add(Row(null, entry.display, entry.token))
        }
        adapter.clear()
        adapter.addAll(visible.map { it.display + it.token })
        adapter.notifyDataSetChanged()
    }

    private fun copyCode(token: String) {
        val manager = getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(ClipData.newPlainText("action", token))
        // 命令类代码在说明里，补充可读名方便确认复制对了。
        Toast.makeText(this, getString(R.string.action_catalog_copied, token), Toast.LENGTH_SHORT).show()
    }
}
