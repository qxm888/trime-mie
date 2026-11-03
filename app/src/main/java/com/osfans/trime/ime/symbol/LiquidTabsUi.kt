/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.symbol

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.symbols.SymbolOrder
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.util.roundedRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.*
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.horizontalPadding
import splitties.views.recyclerview.horizontalLayoutManager

class LiquidTabsUi(override val ctx: Context, private val scope: ThemeScope) : Ui {
    private val theme get() = scope.theme
    val orderNamespace get() = "liquid-tabs:${theme.fontConfigId.ifEmpty { theme.name }}"
    private data class Entry(val tag: LiquidKeyboard.Tag, val source: Int, val id: String)
    private var source = emptyList<Entry>()
    private var entries = mutableListOf<Entry>()
    private var selectedSource = -1
    private var dirty = false
    private var onTabClick: ((Int) -> Unit)? = null
    var onOrderChanged: (() -> Unit)? = null
    private inner class TabUi : Ui {
        override val ctx = this@LiquidTabsUi.ctx
        val text = textView { textSize=theme.style.candidateTextSize; typeface=theme.fonts.candidate }
        override val root = view(::GestureFrame) {
            minimumWidth=dp(40)
            add(text,lParams { gravity=gravityCenter; horizontalPadding=dp(theme.style.candidatePadding) })
        }
        fun bind(entry: Entry) {
            text.text=entry.tag.label
            text.setTextColor(if(entry.source==selectedSource) scope.colors.hilitedCandidateTextColor else scope.colors.candidateTextColor)
            root.background=roundedRippleDrawable(scope.colors.hilitedCandidateBackColor,ctx.dp(theme.style.candidateCornerRadius),
                if(entry.source==selectedSource) scope.colors.hilitedCandidateBackColor else Color.TRANSPARENT)
            root.contentDescription="${entry.tag.label}；长按拖动调整分类顺序"
        }
    }
    private class Holder(val ui: TabUi) : RecyclerView.ViewHolder(ui.root)
    private val adapter: RecyclerView.Adapter<Holder> = object : RecyclerView.Adapter<Holder>() {
        override fun getItemCount()=entries.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int)=Holder(TabUi())
        override fun onBindViewHolder(holder: Holder,index: Int) {
            holder.ui.bind(entries[index])
            holder.ui.root.layoutParams=ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.MATCH_PARENT)
            holder.ui.root.setOnClickListener {
                entries.getOrNull(holder.bindingAdapterPosition)?.let { onTabClick?.invoke(it.source) }
            }
            holder.ui.root.setOnLongClickListener { drag.startDrag(holder); true }
        }
    }
    private val drag: ItemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,0) {
        override fun isLongPressDragEnabled()=false
        override fun onMove(rv: RecyclerView,from: RecyclerView.ViewHolder,to: RecyclerView.ViewHolder): Boolean {
            val a=from.bindingAdapterPosition; val b=to.bindingAdapterPosition
            if(a !in entries.indices || b !in entries.indices) return false
            entries.add(b,entries.removeAt(a)); adapter.notifyItemMoved(a,b); dirty=true; return true
        }
        override fun onSwiped(holder: RecyclerView.ViewHolder,direction: Int)=Unit
        override fun clearView(rv: RecyclerView,holder: RecyclerView.ViewHolder) { super.clearView(rv,holder); saveOrder() }
    })
    override val root=recyclerView {
        layoutManager=horizontalLayoutManager(); adapter=this@LiquidTabsUi.adapter
        isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false
    }.also { drag.attachToRecyclerView(it) }
    fun setTags(tags: List<LiquidKeyboard.Tag>,ids: List<String>) {
        source=tags.mapIndexed { index,tag -> Entry(tag,index,ids[index]) }; reloadOrder()
    }
    fun reloadOrder() { entries=SymbolOrder.ordered(orderNamespace,source) { it.id }.toMutableList(); adapter.notifyDataSetChanged() }
    fun orderedSourceIndices()=entries.map { it.source }
    fun saveOrder() {
        if(dirty) { SymbolOrder.save(orderNamespace,entries) { it.id }; dirty=false; onOrderChanged?.invoke() }
    }
    fun resetOrder() { SymbolOrder.reset(orderNamespace); dirty=false; reloadOrder(); onOrderChanged?.invoke() }
    fun activateTab(index: Int) {
        val old=entries.indexOfFirst { it.source==selectedSource }; selectedSource=index
        val current=entries.indexOfFirst { it.source==index }
        if(old>=0) adapter.notifyItemChanged(old)
        if(current>=0) { adapter.notifyItemChanged(current); root.post { root.scrollToPosition(current) } }
    }
    fun setOnTabClickListener(listener: ((Int)->Unit)?) { onTabClick=listener }
    fun refreshColors() { adapter.notifyDataSetChanged() }
}
