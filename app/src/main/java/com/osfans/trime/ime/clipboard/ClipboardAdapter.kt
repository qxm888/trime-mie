/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.os.Build
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.osfans.trime.R
import com.osfans.trime.data.db.CategoryHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.util.DeviceUtils
import com.osfans.trime.util.item
import splitties.resources.color
import kotlin.math.min

abstract class ClipboardAdapter(
    private val scope: ThemeScope,
) : PagingDataAdapter<DatabaseBean, ClipboardAdapter.ViewHolder>(diffCallback) {
    companion object {
        private val diffCallback =
            object : DiffUtil.ItemCallback<DatabaseBean>() {
                override fun areItemsTheSame(
                    oldItem: DatabaseBean,
                    newItem: DatabaseBean,
                ): Boolean = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: DatabaseBean,
                    newItem: DatabaseBean,
                ): Boolean = oldItem == newItem
            }

        private fun excerptText(
            str: String,
            lines: Int = 4,
            chars: Int = 128,
        ): String = buildString {
            val length = str.length
            var lineBreak = -1
            for (i in 1..lines) {
                val start = lineBreak + 1 // skip previous '\n'
                val excerptEnd = min(start + chars, length)
                lineBreak = str.indexOf('\n', start)
                if (lineBreak < 0) {
                    // no line breaks remaining, substring to end of text
                    append(str.substring(start, excerptEnd))
                    break
                } else {
                    val end = min(excerptEnd, lineBreak)
                    // append one line exactly
                    appendLine(str.substring(start, end))
                }
            }
        }
    }

    private var popupMenu: PopupMenu? = null

    /** 由 Window 注入：从条目上的拖拽把手开始拖拽（dahiPLUS 移植）。 */
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null

    /** 是否允许手动拖动排序（搜索过滤时禁用，避免打乱未显示条目的顺序）。 */
    var reorderEnabled: Boolean = true

    /**
     * 拖拽排序期间使用的本地顺序镜像。
     * [PagingDataAdapter] 的数据来自 PagingSource，无法直接原地重排，
     * 因此拖动时读取该镜像，松开后再持久化。
     */
    private var reorderItems: MutableList<DatabaseBean>? = null

    private fun itemAt(position: Int): DatabaseBean? =
        reorderItems?.getOrNull(position) ?: getItem(position)

    private fun buildReorderItems(): MutableList<DatabaseBean>? {
        val items = ArrayList<DatabaseBean>(itemCount)
        for (i in 0 until itemCount) {
            items.add(getItem(i) ?: return null)
        }
        return items
    }

    /** [ItemTouchHelper] 回调：把 [from] 处的条目移动到 [to]。置顶与普通条目不互相跨越。 */
    fun moveItem(
        from: Int,
        to: Int,
    ): Boolean {
        if (!reorderEnabled || from == to) return false
        val items = reorderItems ?: buildReorderItems()?.also { reorderItems = it } ?: return false
        val moving = items.getOrNull(from) ?: return false
        val target = items.getOrNull(to) ?: return false
        if (moving.pinned != target.pinned) return false
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
        return true
    }

    /** 拖拽结束时调用：返回按新顺序重新编号的条目（orderIndex = 列表下标），由调用方入库。 */
    fun commitReorder(): List<DatabaseBean> {
        val items = reorderItems ?: return emptyList()
        reorderItems = null
        return items.mapIndexed { index, bean -> bean.copy(orderIndex = index) }
    }

    /** Shows a category tag chip on rows; only used in the "all" collection view. */
    open val showCategory: Boolean = false

    class ViewHolder(
        val ui: ClipboardBeanUi,
    ) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ViewHolder = ViewHolder(ClipboardBeanUi(parent.context, scope))

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
    ) {
        val bean = itemAt(position) ?: return
        with(holder.ui) {
            refreshColors()
            val categoryLabel =
                if (showCategory && bean.categoryId != null) {
                    CategoryHelper.nameOf(bean.categoryId)
                } else {
                    null
                }
            setBean(excerptText(bean.text ?: ""), bean.pinned, categoryLabel)
            dragHandle.isVisible = reorderEnabled
            dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag?.invoke(holder)
                    true
                } else {
                    false
                }
            }
            root.setOnClickListener {
                onPaste(bean)
            }
            root.setOnLongClickListener {
                val popup = PopupMenu(ctx, it)
                val menu = popup.menu
                val iconTint = ctx.color(R.color.framework_control_normal)
                menu.item(R.string.edit, R.drawable.ic_baseline_edit_24, iconTint) {
                    onEdit(bean.id)
                }
                menu.item(R.string.share, R.drawable.ic_baseline_share_24, iconTint) {
                    onShare(bean)
                }
                menu.item(R.string.word_segment, R.drawable.ic_baseline_view_comfy_24, iconTint) {
                    onSegment(bean)
                }

                if (enableCollection) {
                    menu.item(R.string.collect, R.drawable.ic_baseline_star_24, iconTint) {
                        onCollect(bean)
                    }
                    if (bean.pinned) {
                        menu.item(R.string.simple_key_unpin, R.drawable.ic_outline_push_pin_24, iconTint) {
                            onUnpin(bean.id)
                        }
                    } else {
                        menu.item(R.string.simple_key_pin, R.drawable.ic_baseline_push_pin_24, iconTint) {
                            onPin(bean.id)
                        }
                    }
                } else if (showMoveToCategory) {
                    menu.item(
                        R.string.category_move_to,
                        R.drawable.ic_baseline_snippet_folder_24,
                        iconTint,
                    ) {
                        onMoveToCategory(bean)
                    }
                }
                menu.item(R.string.delete, R.drawable.ic_baseline_delete_24, iconTint) {
                    onDelete(bean.id)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !DeviceUtils.isSamsungOneUI) {
                    popup.setForceShowIcon(true)
                }
                popup.setOnDismissListener { p ->
                    if (p === popupMenu) popupMenu = null
                }
                popupMenu?.dismiss()
                popupMenu = popup
                popup.show()
                true
            }
        }
    }

    abstract fun onPaste(bean: DatabaseBean)

    open fun onPin(id: Int) {}

    open fun onUnpin(id: Int) {}

    abstract fun onEdit(id: Int)

    abstract fun onShare(bean: DatabaseBean)

    abstract fun onSegment(bean: DatabaseBean)

    open fun onCollect(bean: DatabaseBean) {}

    /** Moves a collection bean into a user-defined category. */
    open fun onMoveToCategory(bean: DatabaseBean) {}

    /** Whether the "move to category" menu entry is offered on collection rows. */
    open val showMoveToCategory: Boolean = !enableCollection

    abstract fun onDelete(id: Int)

    abstract val enableCollection: Boolean

    fun dismissPopupMenu() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    /** Re-colors the visible rows after a scheme switch; rows re-apply colors on bind. */
    fun refreshColors() {
        notifyDataSetChanged()
    }
}
