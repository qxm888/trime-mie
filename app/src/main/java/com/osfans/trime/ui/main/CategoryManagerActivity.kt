/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.db.CategoryBean
import com.osfans.trime.data.db.CategoryHelper
import com.osfans.trime.data.db.CollectionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Category manager for the collection tab.
 *
 * This used to be an [android.app.AlertDialog] attached to the IME window, but an
 * EditText living inside the IME's own window can never receive an input
 * connection: the input method is not allowed to show itself, so the name field
 * was simply dead. Hosting the manager in a regular activity gives the EditText
 * a normal editor window, so the current IME works as usual.
 *
 * - Tap a category (or the edit icon) to rename it
 * - Tap the delete icon to delete it (its items fall back to uncategorized)
 */
class CategoryManagerActivity : AppCompatActivity() {
    private val scope: CoroutineScope = MainScope()

    private lateinit var nameInput: TextInputEditText
    private lateinit var emptyHint: TextView
    private lateinit var adapter: CategoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_manager)

        val toolbar = findViewById<MaterialToolbar>(R.id.category_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.category_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.subtitle = "长按分类拖动排序"
        toolbar.setNavigationOnClickListener { finish() }

        nameInput = findViewById(R.id.category_name_input)
        nameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                createCategory()
                true
            } else {
                false
            }
        }
        findViewById<MaterialButton>(R.id.category_add_button).setOnClickListener { createCategory() }

        val recycler = findViewById<RecyclerView>(R.id.category_recycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = CategoryAdapter()
        recycler.adapter = adapter
        androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0,
        ) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean =
                adapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                val ids = adapter.orderedIds()
                scope.launch { CategoryHelper.reorder(ids) }
            }
        }).attachToRecyclerView(recycler)

        emptyHint = findViewById(R.id.category_empty_hint)

        nameInput.requestFocus()
        refresh()
    }

    private fun createCategory() {
        val name = nameInput.text?.toString().orEmpty().trim()
        if (name.isEmpty()) {
            findViewById<TextInputLayout>(R.id.category_name_layout).error =
                getString(R.string.category_name_empty)
            return
        }
        findViewById<TextInputLayout>(R.id.category_name_layout).error = null
        scope.launch {
            CategoryHelper.create(name)
            nameInput.setText("")
            refresh()
        }
    }

    private fun refresh() {
        scope.launch {
            val categories = CategoryHelper.all()
            val counts = categories.associate { it.id to CollectionHelper.itemCountByCategory(it.id) }
            adapter.submit(categories, counts)
            val empty = categories.isEmpty()
            emptyHint.visibility = if (empty) TextView.VISIBLE else TextView.GONE
            findViewById<RecyclerView>(R.id.category_recycler).visibility =
                if (empty) RecyclerView.GONE else RecyclerView.VISIBLE
        }
    }

    private fun showRenameDialog(category: CategoryBean) {
        val input =
            TextInputEditText(this).apply {
                setText(category.name)
                setSelection(category.name.length)
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT
            }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.category_rename)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    findViewById<TextInputLayout>(R.id.category_name_layout).error =
                        getString(R.string.category_name_empty)
                    return@setPositiveButton
                }
                scope.launch {
                    val ok = CategoryHelper.rename(category.id, name)
                    if (!ok) {
                        findViewById<TextInputLayout>(R.id.category_name_layout).error =
                            getString(R.string.category_name_exists)
                    }
                    refresh()
                }
            }.setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(category: CategoryBean) {
        scope.launch {
            val count = CollectionHelper.itemCountByCategory(category.id)
            val message =
                getString(R.string.category_delete_confirm) +
                    "\n" + getString(R.string.category_delete_with_items, count)
            MaterialAlertDialogBuilder(this@CategoryManagerActivity)
                .setTitle(R.string.category_delete)
                .setMessage(message)
                .setPositiveButton(R.string.ok) { _, _ ->
                    scope.launch {
                        CategoryHelper.delete(category.id, deleteBeans = false)
                        refresh()
                    }
                }.setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        private var items: List<CategoryBean> = emptyList()
        private var counts: Map<String, Int> = emptyMap()

        fun orderedIds(): List<String> = items.map { it.id }

        fun move(from: Int, to: Int): Boolean {
            if (from !in items.indices || to !in items.indices) return false
            items = items.toMutableList().apply { add(to, removeAt(from)) }
            notifyItemMoved(from, to)
            return true
        }

        fun submit(
            categories: List<CategoryBean>,
            itemCounts: Map<String, Int>,
        ) {
            items = categories
            counts = itemCounts
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(
            holder: Holder,
            position: Int,
        ) {
            val category = items[position]
            holder.nameView.text = category.name
            holder.avatarView.text = category.name.take(1)
            holder.countView.text = getString(R.string.category_item_count, counts[category.id] ?: 0)
            holder.itemView.setOnClickListener { showRenameDialog(category) }
            holder.renameButton.setOnClickListener { showRenameDialog(category) }
            holder.deleteButton.setOnClickListener { showDeleteDialog(category) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val avatarView: TextView = view.findViewById(R.id.category_avatar)
            val nameView: TextView = view.findViewById(R.id.category_name)
            val countView: TextView = view.findViewById(R.id.category_count)
            val renameButton: ImageButton = view.findViewById(R.id.category_rename)
            val deleteButton: ImageButton = view.findViewById(R.id.category_delete)
        }
    }
}
