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
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        kbId = intent.getStringExtra(EXTRA_KB_ID).orEmpty()
        if (kbId.isNotEmpty()) {
            toolbar.title = getString(R.string.toolbar_edit_kb, CustomKeyboards.get(kbId)?.name ?: "")
        }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.toolbar_edit)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset_toolbar) {
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
        commit()
        ThemeManager.requestCustomizationRefresh()
    }

    private fun initialEntries(): List<ToolbarOverrides.Entry> =
        (if (kbId.isEmpty()) {
            if (ToolbarOverrides.hasCustomization()) ToolbarOverrides.entries()
            else theme.toolBar.buttons.map { ToolbarOverrides.Entry(it.action) }
        } else {
            CustomKeyboards.get(kbId)?.toolbar.orEmpty()
        }).filterNot { ToolbarOverrides.isModeAction(it.action) || ToolbarOverrides.isModeAction(theme.presetKeys[it.action]?.command.orEmpty()) }

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
        ToolbarOverrides.builtinName(action)?.let { return it }
        val btn = themeButtons[action] ?: return theme.presetKeys[action]?.label?.ifEmpty { action } ?: action
        val fg = btn.foreground.style.ifEmpty { btn.foreground.optionStyles.firstOrNull().orEmpty() }
        return fg.ifEmpty { action }
    }

    private var iconPickTarget = -1

    private val iconImagePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            val pos = iconPickTarget
            if (uri == null || pos !in working.indices) return@registerForActivityResult
            val target = working[pos]
            SkinPicker.handleImageResult(this, uri) { name ->
                if (name != null && pos in working.indices && working[pos] == target) {
                    working[pos] = target.copy(icon = name)
                    commit()
                    adapter.notifyItemChanged(pos)
                }
            }
        }

    private fun showIconDialog(position: Int) {
        if (position !in working.indices) return
        val entry = working[position]
        val root =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), 0)
            }
        val layout =
            com.google.android.material.textfield.TextInputLayout(this).apply {
                hint = getString(R.string.toolbar_edit_icon_hint)
            }
        val input =
            com.google.android.material.textfield.TextInputEditText(layout.context).apply {
                setText(entry.icon)
                setSingleLine(true)
            }
        layout.addView(
            input,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            layout,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val pickButton =
            com.google.android.material.button.MaterialButton(this).apply {
                androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                text = getString(R.string.toolbar_edit_pick_image)
                icon = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_baseline_add_24)
                setOnClickListener {
                    iconPickTarget = position
                    iconImagePicker.launch("image/*")
                }
            }
        root.addView(pickButton,
            android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.toolbar_edit_icon, displayName(entry.action)))
            .setView(root)
            .setPositiveButton(R.string.ok) { _, _ ->
                working[position] = entry.copy(icon = input.text?.toString()?.trim().orEmpty())
                commit()
                adapter.notifyDataSetChanged()
            }
            .setNeutralButton(R.string.key_editor_restore_default) { _, _ ->
                working[position] = entry.copy(icon = "")
                commit()
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun showAddDialog() {
        val catalog =
            themeButtons.keys.filterNot { ToolbarOverrides.isModeAction(it) || ToolbarOverrides.isModeAction(theme.presetKeys[it]?.command.orEmpty()) }
        val available = catalog.filter { action -> working.none { it.action == action } }
        val displays = arrayOf(getString(R.string.toolbar_edit_custom_action)) + available.map { "${displayName(it)} · $it" }.toTypedArray()
        fun addAction(action: String) {
            if (action.isEmpty() || ToolbarOverrides.isModeAction(action) || ToolbarOverrides.isModeAction(theme.presetKeys[action]?.command.orEmpty()) || working.any { it.action == action }) return
            working.add(ToolbarOverrides.Entry(action))
            commit()
            adapter.notifyDataSetChanged()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.toolbar_edit_add)
            .setItems(displays) { _, which ->
                if (which == 0) {
                    KeyTokenPicker.show(this, getString(R.string.toolbar_edit_custom_action), false) { token ->
                        if (token != null) addAction(token)
                    }
                } else addAction(available[which - 1])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            holder.name.text = displayName(entry.action)
            holder.action.text =
                if (entry.icon.isNotEmpty()) entry.action + " · " + entry.icon else entry.action
            holder.enableSwitch.isChecked = entry.enabled
            holder.enableSwitch.setOnCheckedChangeListener { _, checked ->
                val idx = holder.bindingAdapterPosition
                if (idx in working.indices) {
                    working[idx] = working[idx].copy(enabled = checked)
                    commit()
                }
            }
            // 点名字改自定义图标
            holder.name.setOnClickListener { showIconDialog(holder.bindingAdapterPosition) }
            holder.up.setOnClickListener { move(holder.bindingAdapterPosition, -1) }
            holder.down.setOnClickListener { move(holder.bindingAdapterPosition, +1) }
            holder.delete.setOnClickListener {
                val idx = holder.bindingAdapterPosition
                if (idx in working.indices) {
                    working.removeAt(idx)
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
        ) {
            context.startActivity(
                Intent(context, ToolbarEditActivity::class.java).apply {
                    putExtra(EXTRA_KB_ID, kbId)
                    if (context !is AppCompatActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
