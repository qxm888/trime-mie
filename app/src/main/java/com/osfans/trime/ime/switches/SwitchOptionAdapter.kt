/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.osfans.trime.data.theme.ThemeScope

abstract class SwitchOptionAdapter : BaseQuickAdapter<SwitchOptionEntry, SwitchOptionAdapter.ViewHolder>() {
    inner class ViewHolder(
        val ui: SwitchOptionEntryUi,
    ) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(SwitchOptionEntryUi(context, scope))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        item: SwitchOptionEntry?,
    ) {
        item ?: return
        holder.ui.refreshColors()
        holder.ui.setEntry(item)
        val enabled = item !is SwitchOptionEntry.Static || item.type != SwitchOptionEntry.Static.Type.FloatingBall ||
            com.osfans.trime.ime.bar.InputBarVisibility.candidatesFloating()
        holder.ui.root.isEnabled = enabled
        holder.ui.root.alpha = if (enabled) 1f else .4f
        holder.ui.root.setOnClickListener {
            onItemClick(it, item)
        }
    }

    /** Re-colors the visible rows after a scheme switch; rows re-apply colors on bind. */
    fun refreshColors() {
        notifyDataSetChanged()
    }

    abstract val scope: ThemeScope

    abstract fun onItemClick(
        view: View,
        entry: SwitchOptionEntry,
    )
}
