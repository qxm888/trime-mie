/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.R
import com.osfans.trime.data.keymap.CustomBackground
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.StandaloneKeyboardTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.ToolBar

/**
 * 候选栏工具栏编辑：调整按钮顺序、启用/停用、增删按钮。
 * 数据存 ToolbarOverrides，主题文件不受影响；工具栏在下次重建时应用。
 */
class ToolbarEditActivity : AppCompatActivity() {
    private val theme by lazy { if (kbId.isNotEmpty()) StandaloneKeyboardTheme.base() else ThemeManager.activeTheme }
    private val themeButtons by lazy { theme.toolBar.buttons.associateBy { it.action } }

    /** 非空 = 编辑某个自定义键盘的专属工具栏；空 = 编辑全局候选栏工具栏。 */
    private var kbId: String = ""

    private lateinit var adapter: Adapter

    /** 当前编辑中的按钮序列（action + enabled）。 */
    private val working = mutableListOf<ToolbarOverrides.Entry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolbar_edit)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar_edit_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar_edit_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        kbId = intent.getStringExtra(EXTRA_KB_ID).orEmpty()
        if (kbId.isNotEmpty()) {
            require(CustomKeyboards.get(kbId) != null) { "自定义键盘不存在" }
            toolbar.title = getString(R.string.toolbar_edit_kb, CustomKeyboards.get(kbId)?.name ?: "")
        }
        toolbar.menu.add(0, 2180, 0, if(kbId.isEmpty()) "主题键盘外观" else "当前布局外观")
        findViewById<TextView>(R.id.toolbar_edit_hint).text =
            (if (kbId.isEmpty()) "主题键盘状态栏。" else "仅修改当前自定义布局的状态栏。") + "点击按钮名称编辑功能与外观，支持增删、排序和停用。"
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.toolbar_edit)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == 2180) {
                SurfaceAppearanceEditor.show(this, kbId.isNotEmpty(), layoutId = kbId.takeIf { it.isNotEmpty() })
            } else if (item.itemId == R.id.action_reset_toolbar) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.toolbar_edit_reset)
                    .setMessage(R.string.toolbar_edit_reset_confirm)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        if (kbId.isEmpty()) {
                            ToolbarOverrides.resetAll()
                            if (!ToolbarOverrides.flush()) Toast.makeText(this, "工具栏保存失败，请检查剩余存储空间", Toast.LENGTH_LONG).show()
                        } else {
                            CustomKeyboards.mutate(kbId) { it.toolbar = StandaloneKeyboardTheme.defaultToolbar(); it.toolbarBackground = null }
                            if (!CustomKeyboards.flush()) Toast.makeText(this, "工具栏保存失败，请检查剩余存储空间", Toast.LENGTH_LONG).show()
                        }
                        reload()
                        Toast.makeText(this, R.string.key_editor_done, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }

        findViewById<View>(R.id.toolbar_edit_background).apply {
            visibility = if (kbId.isNotEmpty()) View.VISIBLE else View.GONE
            setOnClickListener { pickToolbarBackground() }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.toolbar_edit_height).apply {
            visibility = if (kbId.isNotEmpty()) View.VISIBLE else View.GONE
            fun refresh() {
                val kb = CustomKeyboards.get(kbId)
                text = if (kb != null) "状态栏高度 · ${kb.effectiveToolbarHeightDp()} dp" else "状态栏高度"
            }
            refresh()
            setOnClickListener {
                val kb = CustomKeyboards.get(kbId) ?: return@setOnClickListener
                val input = com.google.android.material.textfield.TextInputEditText(this@ToolbarEditActivity).apply {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    if (kb.toolbarHeightDp > 0) setText(kb.toolbarHeightDp.toString())
                }
                val field = com.google.android.material.textfield.TextInputLayout(this@ToolbarEditActivity).apply {
                    hint = "状态栏高度 dp（24–120，留空默认）"
                    setPadding(dp(24), dp(8), dp(24), 0); addView(input)
                }
                MaterialAlertDialogBuilder(this@ToolbarEditActivity).setTitle("状态栏高度")
                    .setView(field).setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton("默认") { _, _ ->
                        CustomKeyboards.mutate(kbId) { it.toolbarHeightDp = 0 }
                        com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh(); refresh()
                    }
                    .setPositiveButton(android.R.string.ok, null).create().apply {
                        setOnShowListener {
                            getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val value = input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                                if (value !in 0..120 || value in 1..23) {
                                    field.error = "状态栏高度须为 24–120 dp"
                                } else {
                                    CustomKeyboards.mutate(kbId) { it.toolbarHeightDp = value }
                                    com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh(); refresh(); dismiss()
                                }
                            }
                        }
                    }.show()
            }
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.toolbar_edit_alignment).apply {
            visibility = if (kbId.isNotEmpty()) View.VISIBLE else View.GONE
            fun refresh() { text = "按钮排列 · " + (com.osfans.trime.data.keymap.ButtonAlignment.choices[CustomKeyboards.get(kbId)?.toolbarAlignment] ?: "原排列") }
            refresh()
            setOnClickListener {
                val choices = linkedMapOf("legacy" to "原排列") + com.osfans.trime.data.keymap.ButtonAlignment.choices
                MaterialAlertDialogBuilder(this@ToolbarEditActivity).setTitle("状态栏按钮排列")
                    .setSingleChoiceItems(choices.values.toTypedArray(), choices.keys.indexOf(CustomKeyboards.get(kbId)?.toolbarAlignment)) { dialog, which ->
                        CustomKeyboards.mutate(kbId) { it.toolbarAlignment = choices.keys.elementAt(which) }
                        com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh(); refresh(); dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel,null).show()
            }
        }

        working.addAll(initialEntries())

        val recycler = findViewById<RecyclerView>(R.id.toolbar_edit_list)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        recycler.adapter = adapter

        findViewById<com.google.android.material.button.MaterialButton>(R.id.toolbar_edit_add).setOnClickListener { showAddDialog() }
    }

    private val backgroundPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        SkinPicker.handleImageResult(this, uri) { path ->
            if (path != null) setToolbarBackground(CustomBackground(CustomBackground.Type.IMAGE, image = path))
        }
    }

    private fun setToolbarBackground(background: CustomBackground) {
        CustomKeyboards.mutate(kbId) { it.toolbarBackground = background }
        if (!CustomKeyboards.flush()) Toast.makeText(this, "工具栏保存失败，请检查剩余存储空间", Toast.LENGTH_LONG).show()
        ThemeManager.requestCustomizationRefresh()
    }

    private fun pickToolbarBackground() {
        val keyboard = CustomKeyboards.get(kbId) ?: return
        SkinPicker.show(this, "工具栏背景", keyboard.toolbarBackground ?: CustomBackground(color = "#FFF1F3F5"),
            onPicked = ::setToolbarBackground, onPickImage = { backgroundPicker.launch("image/*") })
    }

    override fun onPause() {
        super.onPause()
        val saved = if (kbId.isEmpty()) ToolbarOverrides.flush() else CustomKeyboards.flush()
        if (!saved) Toast.makeText(this, "工具栏保存失败，请检查剩余存储空间", Toast.LENGTH_LONG).show()
        ThemeManager.requestCustomizationRefresh()
    }

    private fun initialEntries(): List<ToolbarOverrides.Entry> =
        ToolbarOverrides.editableEntries(theme.toolBar, (if (kbId.isEmpty()) {
            if (ToolbarOverrides.hasCustomization()) ToolbarOverrides.entries()
            else theme.toolBar.buttons.map { ToolbarOverrides.Entry(it.action) }
        } else {
            CustomKeyboards.get(kbId)?.toolbar.orEmpty()
        })).filterNot { ToolbarOverrides.isModeAction(it.action) || ToolbarOverrides.isModeAction(theme.presetKeys[it.action]?.command.orEmpty()) }

    private fun commit() {
        if (kbId.isEmpty()) {
            ToolbarOverrides.setEntries(working.toList())
            if (!ToolbarOverrides.flush()) Toast.makeText(this, "工具栏保存失败，请检查剩余存储空间", Toast.LENGTH_LONG).show()
        } else {
            CustomKeyboards.mutate(kbId) { it.toolbar = working.toMutableList() }
            if (!CustomKeyboards.flush()) Toast.makeText(this, "工具栏保存失败，请检查剩余存储空间", Toast.LENGTH_LONG).show()
        }
    }

    private fun reload() {
        working.clear()
        findViewById<View>(R.id.toolbar_edit_background).apply {
            visibility = if (kbId.isNotEmpty()) View.VISIBLE else View.GONE
            setOnClickListener { pickToolbarBackground() }
        }

        working.addAll(initialEntries())
        adapter.notifyDataSetChanged()
    }

    private fun displayName(action: String): String {
        com.osfans.trime.data.keymap.BindingActions.resolve(action)?.let { return com.osfans.trime.data.keymap.BindingActions.label(action) }
        ToolbarOverrides.builtinName(action)?.let { return it }
        com.osfans.trime.data.theme.Theme.BUILTIN_ACTIONS[action]?.label?.let { return it }
        val btn = themeButtons[action] ?: return theme.presetKeys[action]?.label?.ifEmpty { action } ?: action
        val fg = btn.foreground.style.ifEmpty { btn.foreground.optionStyles.firstOrNull().orEmpty() }
        return fg.takeUnless { value -> value.isEmpty() || value.any { it in '\uE000'..'\uF8FF' } } ?: com.osfans.trime.data.keymap.BindingActions.label(action)
    }

    private fun editButton(position: Int) {
        if (position !in working.indices) return
        val entry = working[position]
        ToolbarButtonEditor.show(this, entry, kbId.isNotEmpty(),layoutId=kbId.takeIf { it.isNotEmpty() }) { next ->
            if (position in working.indices && working[position] == entry) {
                working[position] = next
                commit()
                adapter.notifyItemChanged(position)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showAddDialog() {
        val available = (com.osfans.trime.data.theme.Theme.BUILTIN_ACTIONS.keys + themeButtons.keys + theme.presetKeys.keys)
            .distinct().filterNot { ToolbarOverrides.isModeAction(it) || ToolbarOverrides.isModeAction(theme.presetKeys[it]?.command.orEmpty()) }
        val displays = arrayOf("新增自定义按钮…") + available.map { "${displayName(it)} · $it" }.toTypedArray()
        MaterialAlertDialogBuilder(this).setTitle(R.string.toolbar_edit_add)
            .setItems(displays) { _, which ->
                val entry = if (which == 0) ToolbarOverrides.Entry("") else ToolbarOverrides.Entry(available[which - 1])
                ToolbarButtonEditor.show(this, entry, kbId.isNotEmpty(),layoutId=kbId.takeIf { it.isNotEmpty() }) { next ->
                    working.add(next)
                    commit()
                    adapter.notifyItemInserted(working.lastIndex)
                }
            }.setNegativeButton(R.string.cancel, null).show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.toolbar_entry_name)
            val action: TextView = view.findViewById(R.id.toolbar_entry_action)
            val enableSwitch: MaterialSwitch = view.findViewById(R.id.toolbar_entry_switch)
            val up: ImageButton = view.findViewById(R.id.toolbar_entry_up)
            val down: ImageButton = view.findViewById(R.id.toolbar_entry_down)
            val delete: ImageButton = view.findViewById(R.id.toolbar_entry_delete)
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder = Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_toolbar_button, parent, false))

        override fun getItemCount(): Int = working.size

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val entry = working[position]
            holder.name.text = if(entry.primary) "菜单按钮" else entry.icon.takeUnless { it.startsWith("/") || it.startsWith("ic@") || it.isBlank() || it.any { c -> c in '\uE000'..'\uF8FF' } } ?: displayName(entry.action)
            holder.action.text =
                if (entry.primary) "最左侧菜单按钮 · 点击可编辑，开关可停用" else if (entry.icon.isNotEmpty()) com.osfans.trime.data.keymap.BindingActions.label(entry.action) + " · " + entry.icon else com.osfans.trime.data.keymap.BindingActions.label(entry.action)
            holder.enableSwitch.setOnCheckedChangeListener(null)
            holder.enableSwitch.isChecked = entry.enabled
            holder.enableSwitch.setOnCheckedChangeListener { _, checked ->
                val idx = holder.bindingAdapterPosition
                if (idx in working.indices) {
                    working[idx] = working[idx].copy(enabled = checked)
                    commit()
                }
            }
            holder.name.setOnClickListener { editButton(holder.bindingAdapterPosition) }
            holder.action.setOnClickListener { editButton(holder.bindingAdapterPosition) }
            holder.up.isEnabled = !entry.primary && position > 0 && !working[position-1].primary
            holder.down.isEnabled = !entry.primary
            holder.up.setOnClickListener { move(holder.bindingAdapterPosition, -1) }
            holder.down.setOnClickListener { move(holder.bindingAdapterPosition, +1) }
            holder.delete.setOnClickListener {
                val idx = holder.bindingAdapterPosition
                if (idx in working.indices) {
                    if (working[idx].primary) working[idx] = working[idx].copy(enabled = false) else working.removeAt(idx)
                    commit()
                    notifyDataSetChanged()
                }
            }
        }

        private fun move(
            position: Int,
            dir: Int,
        ) {
            val target = position + dir
            if (position !in working.indices || target !in working.indices) return
            val e = working.removeAt(position)
            working.add(target, e)
            commit()
            notifyDataSetChanged()
        }
    }

    companion object {
        private const val EXTRA_KB_ID = "kbId"

        fun start(
            context: Context,
            kbId: String = "",
            themeMode: Boolean = false,
        ) {
            context.startActivity(
                Intent(context, ToolbarEditActivity::class.java).apply {
                    val target = kbId.ifEmpty { if (!themeMode && CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) CustomKeyboards.resolveId("default") else "" }
                    putExtra(EXTRA_KB_ID, target)
                    if (context !is AppCompatActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
