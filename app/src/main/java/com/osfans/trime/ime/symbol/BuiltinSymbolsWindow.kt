/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.symbol

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.symbols.BuiltinSymbolCatalog
import com.osfans.trime.data.symbols.SymbolOrder
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.DefaultItemAnimator
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.util.ColorUtils
import kotlinx.coroutines.*
import org.kodein.di.DI
import org.kodein.di.instance

/** Recycles visible cells instead of creating thousands of stored custom keyboard pages. */
class BuiltinSymbolsWindow(di: DI, private val initialCategory: String = "emoji") : BoardWindow.BarBoardWindow(di) {
    override val title = "表情与符号"
    override val showTitle = false
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val manager: BoardWindowManager by instance()
    private val actions: CommonKeyboardActionListener by instance()
    private lateinit var root: LinearLayout
    private lateinit var barView: LinearLayout
    private lateinit var moreButton: TextView
    private lateinit var grid: RecyclerView
    private lateinit var count: TextView
    private var categories = emptyList<BuiltinSymbolCatalog.Category>()
    private var items = mutableListOf<String>()
    private var sorting = false
    private var deleting = false
    private var orderDirty = false
    private lateinit var sortButton: TextView
    private lateinit var resetButton: TextView
    private lateinit var deleteButton: TextView
    private val orderNamespace get() = "builtin-items:$selectedId"
    private var selectedId = initialCategory
    /**
     * 顶栏常驻分类：六个两字短名一键直达，不用每次点开「选择分类」再选。
     * 顺序即顶栏从左到右的顺序；它们也不再出现在「选择分类」对话框里
     * （避免同一分类出现两次），对话框底部会提示这几个已经常驻。
     */
    private val pinned = listOf(
        "english" to "英文",
        "chinese" to "中文",
        "emoji_full" to "表情",
        "common" to "常用",
        "punctuation" to "通用",
        "math" to "数学",
    )
    private val pinnedIds get() = pinned.mapTo(HashSet()) { it.first }
    private val pinnedButtons = LinkedHashMap<String, TextView>()
    private val pinnedMarks = LinkedHashMap<String, View>()
    private var wide = false
    private var emoji = false
    private var job: Job? = null
    private val layout = if (scope.theme.isCustom) KeyboardWindow.currentKeyboardOrNull?.keyboardId?.let {
        CustomKeyboards.get(CustomKeyboards.resolveId(it))
    } else null
    private fun color(value: String?, fallback: Int) = value?.let { runCatching { ColorUtils.parseColor(it) }.getOrNull() } ?: fallback
    private val footerButtons = mutableListOf<TextView>()
    private val palette get() = com.osfans.trime.data.symbols.SymbolPanelColors.resolve(
        color(layout?.keyAppearance?.keyTextColor,scope.colors.keyTextColor),
        layout?.keyAppearance?.keyBackColor?.let { runCatching { ColorUtils.parseColor(it) }.getOrNull() }
            ?: scope.colors.keyBackColor.takeIf { layout==null },
        layout?.resolvedBackground()?.takeIf { it.type==com.osfans.trime.data.keymap.CustomBackground.Type.COLOR }?.color
            ?.let { runCatching { ColorUtils.parseColor(it) }.getOrNull() } ?: scope.colors.backColor.takeIf { layout==null },
        layout?.keyAppearance?.keyBorderColor?.let { runCatching { ColorUtils.parseColor(it) }.getOrNull() })
    private val ink get() = palette.text
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun commitSymbol(value: String) {
        service.cancelVoiceIfActive()
        service.postRimeJob { commitLiteralText(value) }
    }
    private fun background() = GradientDrawable().apply {
        setColor(palette.key)
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1), palette.border)
    }
    private val cells = object : RecyclerView.Adapter<Cell>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Cell {
            val frame=FrameLayout(context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, dp(48)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
            }
            val label=TextView(context).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val badge=symbolDeleteBadge(context)
            frame.addView(label,FrameLayout.LayoutParams(-1,-1))
            frame.addView(badge,FrameLayout.LayoutParams(dp(14),dp(14),Gravity.TOP or Gravity.END))
            return Cell(frame,label,badge)
        }
        override fun onBindViewHolder(holder: Cell, index: Int) {
            val value = items[index]
            holder.text.apply {
                // A dotted circle makes combining marks selectable without altering committed text.
                val combining = value.codePointAt(0).let { Character.getType(it) in listOf(6, 7, 8) }
                text = if (combining) "◌$value" else value
                holder.frame.contentDescription = if(deleting) "$value，点击删除" else value
                holder.badge.visibility = if(deleting) View.VISIBLE else View.GONE
                holder.badge.setTextColor(ink)
                setTextColor(ink); holder.frame.background = this@BuiltinSymbolsWindow.background()
                typeface = if (emoji) Typeface.DEFAULT else scope.theme.fonts.key
                maxLines = if (wide) 2 else 1
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, if (wide) 9 else 12, if (wide) 16 else 25, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                holder.frame.setOnClickListener {
                    if(deleting) deleteItem(value) else if(!sorting) commitSymbol(value)
                }
                holder.frame.setOnLongClickListener {
                    if (sorting || deleting) return@setOnLongClickListener false
                    val points = mutableListOf<String>()
                    var offset = 0
                    while (offset < value.length) {
                        val code = value.codePointAt(offset); points.add("U+%04X".format(code)); offset += Character.charCount(code)
                    }
                    val codes = points.joinToString(" ")
                    service.showDialog(AlertDialog.Builder(context).setTitle(value).setMessage(codes)
                        .setPositiveButton("输入") { _, _ -> commitSymbol(value) }
                        .setNeutralButton("删除") { _, _ -> deleteItem(value) }.setNegativeButton("取消", null).create())
                    true
                }
            }
        }
    }
    private class Cell(val frame:FrameLayout,val text: TextView,val badge:TextView) : RecyclerView.ViewHolder(frame)
    private class CategoryCell(val frame: com.osfans.trime.ime.keyboard.GestureFrame, val text: TextView) : RecyclerView.ViewHolder(frame)
    override fun onCreateView(): View {
        barView = buildBar()
        root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(2)) }
        count = TextView(context).apply { text = "正在载入表情与符号…"; textSize = 11f; setPadding(dp(8), dp(2), dp(8), dp(2)); setTextColor(ink) }
        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 8); adapter = cells
            itemAnimator = null; isVerticalScrollBarEnabled = true
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) resizeColumns(right - left)
            }
        }
        sortButton = TextView(context).apply {
            text = "排序"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(ink)
            contentDescription = "调整当前分类内的符号表情顺序"
            setOnClickListener {
                if (categories.isEmpty()) return@setOnClickListener
                saveItemOrder(); deleting = false; sorting = !sorting
                cells.notifyDataSetChanged()
                grid.itemAnimator = if (sorting) DefaultItemAnimator().apply { moveDuration = 120 } else null
                updateSortHeader()
            }
        }
        deleteButton = TextView(context).apply {
            text = "删除"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(ink)
            contentDescription = "删除不喜欢的符号或表情"
            setOnClickListener {
                if(categories.isEmpty()) return@setOnClickListener
                saveItemOrder(); sorting = false; deleting = !deleting
                grid.itemAnimator = null
                updateSortHeader(); cells.notifyDataSetChanged()
            }
        }
        resetButton = TextView(context).apply {
            text = "恢复"; gravity = Gravity.CENTER; textSize = 13f; setTextColor(ink); visibility = View.GONE
            setOnClickListener {
                service.showDialog(AlertDialog.Builder(context).setTitle("恢复当前分类")
                    .setItems(arrayOf("恢复已删除的符号 / 表情", "恢复默认顺序")) { _,which ->
                        saveItemOrder()
                        if(which==0) SymbolOrder.restoreDeleted(orderNamespace) else SymbolOrder.reset(orderNamespace)
                        sorting=false; deleting=false; grid.itemAnimator=null
                        items=SymbolOrder.ordered(orderNamespace,categories.first { it.id==selectedId }.items) { it }.toMutableList()
                        updateSortHeader(); cells.notifyDataSetChanged(); grid.scrollToPosition(0)
                    }.setNegativeButton("取消",null).create())
            }
        }
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            count.maxLines = 1; count.ellipsize = android.text.TextUtils.TruncateAt.END
            addView(count, LinearLayout.LayoutParams(0, dp(32), 1f))
            addView(resetButton, LinearLayout.LayoutParams(dp(48), dp(32)))
            addView(deleteButton, LinearLayout.LayoutParams(dp(48), dp(32)))
            addView(sortButton, LinearLayout.LayoutParams(dp(48), dp(32)))
        })
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun isLongPressDragEnabled() = sorting
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition; val b = to.bindingAdapterPosition
                if (!sorting || a !in items.indices || b !in items.indices) return false
                items.add(b, items.removeAt(a)); cells.notifyItemMoved(a,b); orderDirty = true
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(holder,state)
                if (state == ItemTouchHelper.ACTION_STATE_DRAG) holder?.itemView?.apply { scaleX = 1.08f; scaleY = 1.08f; alpha = .85f }
            }
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv,holder); holder.itemView.apply { scaleX=1f; scaleY=1f; alpha=1f }; saveItemOrder()
            }
        }).attachToRecyclerView(grid)
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(context)
        listOf("返回键盘" to "", "空格" to "space", "删除" to "BackSpace", "回车" to "Return").forEach { (label, action) ->
            bottom.addView(TextView(context).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink)
                footerButtons.add(this)
                background = this@BuiltinSymbolsWindow.background()
                setOnClickListener {
                    if (action.isEmpty()) manager.attachWindow(KeyboardWindow)
                    else actions.listener.onAction(scope.theme.resolveAction(action))
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(2), dp(2), dp(2), 0) })
        }
        root.addView(bottom)
        refreshColors()
        return root
    }
    override fun onCreateBarView(): View = barView

    /**
     * 顶栏：六个常驻分类各占一格（等宽、点一下直接切），末尾一个「⋯」开完整分类列表。
     * 顶栏宽度就是整条状态栏宽度，短名两字在 320dp 屏上也有 47dp/格，够放。
     */
    private fun buildBar(): LinearLayout {
        pinnedButtons.clear(); pinnedMarks.clear()
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        pinned.forEach { (id, label) ->
            val tv = TextView(context).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            // 底部指示条：选中时才显示，不用实心底块，顶栏不会被一格粗块顶得高低不齐。
            val mark = View(context).apply { visibility = View.INVISIBLE }
            val cell = FrameLayout(context).apply {
                contentDescription = "切换到「$label」分类"
                setOnClickListener {
                    val index = categories.indexOfFirst { it.id == id }
                    if (index >= 0) select(index)
                }
                addView(tv, FrameLayout.LayoutParams(-1, -1))
                addView(mark, FrameLayout.LayoutParams(dp(16), dp(2),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(3) })
            }
            pinnedButtons[id] = tv; pinnedMarks[id] = mark
            bar.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(1), dp(2), dp(1), dp(2)) })
        }
        moreButton = TextView(context).apply {
            text = "⋯"; textSize = 16f; gravity = Gravity.CENTER; maxLines = 1
            contentDescription = "选择更多分类；长按可拖动排序"
            setOnClickListener { if (categories.isNotEmpty()) showCategories() }
        }
        bar.addView(moreButton, LinearLayout.LayoutParams(dp(34), -1).apply { setMargins(dp(1), dp(2), dp(2), dp(2)) })
        return bar
    }

    /** 当前分类那格：只加深文字 + 底部一条短指示条；其它格是普通淡色文字，整条栏保持同一高度。 */
    private fun stylePinned() {
        pinnedButtons.forEach { (id, tv) ->
            val active = id == selectedId
            tv.setTextColor(if (active) ink else palette.surfaceText)
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            tv.background = null
            pinnedMarks[id]?.apply {
                visibility = if (active) View.VISIBLE else View.INVISIBLE
                background = if (active) GradientDrawable().apply {
                    setColor(ink)
                    cornerRadius = dp(1).toFloat()
                } else null
            }
        }
        if (::moreButton.isInitialized) {
            moreButton.setTextColor(palette.surfaceText)
            moreButton.background = null
        }
    }
    override fun onAttached() {
        job = service.lifecycleScope.launch {
            try {
                categories = withContext(Dispatchers.IO) { com.osfans.trime.data.symbols.SymbolCategoryOrder.ordered(BuiltinSymbolCatalog.categories) }
                select(categories.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { count.text = "载入失败，请返回后重试"; timber.log.Timber.e(e, "Builtin symbols failed") }
        }
    }
    private fun select(index: Int) {
        val category = categories.getOrNull(index) ?: return
        saveItemOrder(); sorting = false; deleting = false; grid.itemAnimator = null
        selectedId = category.id; stylePinned()
        items = SymbolOrder.ordered(orderNamespace, category.items) { it }.toMutableList(); wide = category.wide
        emoji = category.id in setOf("emoji", "people", "components", "animals", "food", "travel", "activities", "objects", "emoji_symbols", "flags", "emoji_full") || category.id.endsWith("_tones")
        resizeColumns(grid.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels)
        updateSortHeader()
        cells.notifyDataSetChanged(); grid.scrollToPosition(0)
    }
    private fun saveItemOrder() {
        if (orderDirty) { SymbolOrder.save(orderNamespace,items) { it }; orderDirty = false }
    }
    private fun deleteItem(value:String) {
        saveItemOrder()
        SymbolOrder.delete(orderNamespace,value)
        items.removeAll { it==value }
        cells.notifyDataSetChanged(); updateSortHeader()
    }
    private fun updateSortHeader() {
        sortButton.text = if(sorting) "完成" else "排序"
        deleteButton.text = if(deleting) "完成" else "删除"
        resetButton.visibility = if(sorting || deleting || SymbolOrder.hasDeleted(orderNamespace)) View.VISIBLE else View.GONE
        count.text = when {
            deleting -> "点按删除 · ${items.size} 项 · 可恢复"
            sorting -> "长按拖动 · ${items.size} 项"
            items.isEmpty() -> "本分类已空 · 恢复可找回"
            else -> "${categories.firstOrNull { it.id == selectedId }?.name.orEmpty()} · ${items.size} 项 · 上下滑动浏览"
        }
    }
    /**
     * 把对话框里的新顺序合并回 [categories]：常驻分类排在末尾（顶栏按 id 取，顺序无关），
     * 这样「选择分类」里拖动排序既不会把它们弄丢，也不会影响顶栏的固定顺序。
     */
    private fun commitCategoryOrder(visibleOrder: List<String>) {
        val byId = categories.associateBy { it.id }
        val merged = (visibleOrder + pinned.map { it.first } + categories.map { it.id })
            .distinct().mapNotNull(byId::get)
        categories = merged
        com.osfans.trime.data.symbols.SymbolCategoryOrder.save(merged.map { it.id })
    }

    private fun showCategories() {
        // 顶栏常驻的六个分类不再列出（同一分类不必出现两次），其余分类仍可长按拖动排序。
        val working = categories.filterNot { it.id in pinnedIds }.toMutableList()
        val chooser = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2); itemAnimator = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        lateinit var dialog: AlertDialog
        lateinit var categoryDrag: ItemTouchHelper
        var categoryOrderDirty = false
        val rows = object : RecyclerView.Adapter<CategoryCell>() {
            override fun getItemCount() = working.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): CategoryCell {
                val label = TextView(context).apply { gravity = Gravity.CENTER; textSize = 14f; setPadding(dp(8), dp(6), dp(8), dp(6)) }
                val frame = com.osfans.trime.ime.keyboard.GestureFrame(context).apply {
                    layoutParams = RecyclerView.LayoutParams(-1, dp(56)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
                    addView(label, FrameLayout.LayoutParams(-1,-1))
                }
                return CategoryCell(frame,label)
            }
            override fun onBindViewHolder(holder: CategoryCell, index: Int) {
                val category = working[index]
                holder.text.apply {
                    text = (if (category.id == selectedId) "✓ " else "") + category.name
                    setTextColor(ink)
                }
                holder.frame.apply {
                    background = this@BuiltinSymbolsWindow.background()
                    contentDescription = "${category.name}；长按拖动调整顺序"
                    setOnClickListener {
                        select(categories.indexOfFirst { it.id == category.id }); dialog.dismiss()
                    }
                    setOnLongClickListener { categoryDrag.startDrag(holder); true }
                }
            }
        }
        chooser.adapter = rows
        categoryDrag = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(recycler: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition; val b = to.bindingAdapterPosition
                if (a !in working.indices || b !in working.indices) return false
                working.add(b, working.removeAt(a)); rows.notifyItemMoved(a, b)
                categoryOrderDirty = true
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv,holder)
                if (categoryOrderDirty) commitCategoryOrder(working.map { it.id })
                categoryOrderDirty = false
            }
        })
        categoryDrag.attachToRecyclerView(chooser)
        val height = (context.resources.displayMetrics.heightPixels * .55f).toInt()
        val container = FrameLayout(context).apply { addView(chooser, FrameLayout.LayoutParams(-1, height)) }
        dialog = AlertDialog.Builder(context).setTitle("选择分类 · 长按拖动排序")
            .setMessage("顶栏常驻：${pinned.joinToString(" · ") { it.second }}，这里不再列出")
            .setView(container)
            .setNeutralButton("恢复默认顺序") { _, _ ->
                com.osfans.trime.data.symbols.SymbolCategoryOrder.reset()
                categoryOrderDirty = false
                categories = BuiltinSymbolCatalog.categories
                working.clear()
                working.addAll(categories.filterNot { it.id in pinnedIds })
                rows.notifyDataSetChanged()
            }.setNegativeButton("关闭", null).create()
        dialog.setOnDismissListener {
            if (categoryOrderDirty) commitCategoryOrder(working.map { it.id })
            categoryOrderDirty = false
        }
        service.showDialog(dialog)
    }

    private fun resizeColumns(width: Int) {
        (grid.layoutManager as GridLayoutManager).spanCount =
            (width / dp(if (wide) 84 else 44)).coerceIn(if (wide) 2 else 3, 12)
    }
    override fun onDetached() {
        saveItemOrder(); sorting=false; deleting=false
        job?.cancel(); job = null
    }
    override fun refreshColors() {
        if (::root.isInitialized) {
            root.setBackgroundColor(palette.surface)
            footerButtons.forEach { it.setTextColor(ink); it.background=background() }
            count.setTextColor(palette.surfaceText); sortButton.setTextColor(palette.surfaceText); deleteButton.setTextColor(palette.surfaceText); resetButton.setTextColor(palette.surfaceText)
            stylePinned(); cells.notifyDataSetChanged()
        }
    }
}
