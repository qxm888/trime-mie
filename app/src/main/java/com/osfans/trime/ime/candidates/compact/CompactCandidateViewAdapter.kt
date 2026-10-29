/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.compact

import android.content.Context
import android.view.ViewGroup
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.candidates.CandidateItemUi
import com.osfans.trime.ime.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

open class CompactCandidateViewAdapter(
    val scope: ThemeScope,
) : BaseQuickAdapter<CandidateProto, CandidateViewHolder>() {
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items.getOrNull(position).hashCode().toLong()

    var total: Int = -1
        private set

    var highlightedIdx: Int = -1
        private set

    var layoutMinWidth: Int = 0
        private set

    var layoutFlexGrow: Float = 0f
        private set

    private var layoutChanged = false

    fun updateLayoutParams(minWidth: Int, flexGrow: Float) {
        layoutChanged = layoutChanged || layoutMinWidth != minWidth || layoutFlexGrow != flexGrow
        layoutMinWidth = minWidth
        layoutFlexGrow = flexGrow
    }

    fun updateCandidates(
        data: Array<CandidateProto>,
        total: Int,
        highlightedIndex: Int,
    ) {
        val oldHighlight = highlightedIdx
        // A new total can change the expand button even if the visible prefix is identical.
        val sameItems = this.total == total && data.size == items.size && data.indices.all { data[it] == items[it] }
        this.total = total
        this.highlightedIdx = highlightedIndex
        if (sameItems) {
            if (layoutChanged) notifyItemRangeChanged(0, itemCount)
            else if (oldHighlight != highlightedIndex) {
                if (oldHighlight in data.indices) notifyItemChanged(oldHighlight)
                if (highlightedIndex in data.indices) notifyItemChanged(highlightedIndex)
            }
        } else super.submitList(data.toList(), null)
        layoutChanged = false
    }

    override fun onCreateViewHolder(
        context: Context,
        parent: ViewGroup,
        viewType: Int,
    ): CandidateViewHolder {
        val ui = CandidateItemUi(context, scope)
        ui.root.apply {
            minimumWidth = dp(40)
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    override fun onBindViewHolder(
        holder: CandidateViewHolder,
        position: Int,
        item: CandidateProto?,
    ) {
        item ?: return
        val isHighlighted = position == highlightedIdx
        holder.ui.update(item, isHighlighted)
        holder.text = item.text
        holder.comment = item.comment
        holder.idx = position // unused
        val params = holder.ui.root.layoutParams as FlexboxLayoutManager.LayoutParams
        if (params.minWidth != layoutMinWidth || params.flexGrow != layoutFlexGrow) {
            params.minWidth = layoutMinWidth
            params.flexGrow = layoutFlexGrow
            holder.ui.root.layoutParams = params
        }
    }
}
