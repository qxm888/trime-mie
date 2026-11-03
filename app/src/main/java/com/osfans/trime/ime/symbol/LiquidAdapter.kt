/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.symbol

import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.core.AutoScaleTextView
import splitties.dimensions.dp
import splitties.views.gravityCenter

class LiquidAdapter(
    private val scope: ThemeScope,
    private val onItemClick: LiquidKeyboard.KeyItem.(Int) -> Unit,
) : RecyclerView.Adapter<LiquidAdapter.ViewHolder>() {
    private val data = mutableListOf<LiquidKeyboard.KeyItem>()
    var sorting = false
    var deleting = false
    var onDeleteItem: ((LiquidKeyboard.KeyItem) -> Unit)? = null
    var onItemLongClick: ((LiquidKeyboard.KeyItem) -> Unit)? = null
    var onStartDrag: ((ViewHolder) -> Unit)? = null
    inner class ViewHolder(val ui: LiquidItemUi) : RecyclerView.ViewHolder(ui.root)
    override fun getItemCount() = data.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ui = LiquidItemUi(parent.context, scope)
        ui.mainText.apply {
            scaleMode = AutoScaleTextView.Mode.Proportional; gravity = gravityCenter
            updateLayoutParams { width = context.dp(scope.theme.liquidKeyboard.singleWidth.coerceAtLeast(40)) }
        }
        return ViewHolder(ui)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = data[position]
        holder.ui.refreshColors(); holder.ui.mainText.text = item.text
        holder.ui.mainText.importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        holder.ui.deleteBadge.visibility = if(deleting) android.view.View.VISIBLE else android.view.View.GONE
        holder.ui.root.contentDescription = if(deleting) "${item.text}，点击删除" else item.text
        holder.ui.root.setOnClickListener {
            val at = holder.bindingAdapterPosition
            if(at in data.indices) {
                if(deleting) onDeleteItem?.invoke(data[at]) else if(!sorting) onItemClick.invoke(data[at],at)
            }
        }
        holder.ui.root.setOnLongClickListener(if(sorting) android.view.View.OnLongClickListener {
            onStartDrag?.invoke(holder); true
        } else android.view.View.OnLongClickListener {
            val at=holder.bindingAdapterPosition
            if(!deleting && at in data.indices && onItemLongClick!=null) {
                onItemLongClick?.invoke(data[at]); true
            } else false
        })
    }
    fun submitList(items: List<LiquidKeyboard.KeyItem>) { data.clear(); data.addAll(items); notifyDataSetChanged() }
    fun snapshot() = data.toList()
    fun move(from: Int, to: Int): Boolean {
        if(!sorting || from !in data.indices || to !in data.indices) return false
        data.add(to,data.removeAt(from)); notifyItemMoved(from,to); return true
    }
}
