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
    private lateinit var selector: TextView
    private lateinit var grid: RecyclerView
    private lateinit var count: TextView
    private var categories = emptyList<BuiltinSymbolCatalog.Category>()
    private var items = emptyList<String>()
    private var selectedId = initialCategory
    private var wide = false
    private var emoji = false
    private var job: Job? = null
    private val layout = if (scope.theme.isCustom) KeyboardWindow.currentKeyboardOrNull?.keyboardId?.let {
        CustomKeyboards.get(CustomKeyboards.resolveId(it))
    } else null
    private fun color(value: String?, fallback: Int) = value?.let { runCatching { ColorUtils.parseColor(it) }.getOrNull() } ?: fallback
    private val ink get() = color(layout?.keyAppearance?.keyTextColor, scope.colors.keyTextColor)
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun background() = GradientDrawable().apply {
        setColor(color(layout?.keyAppearance?.keyBackColor, scope.colors.keyBackColor))
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1), color(layout?.keyAppearance?.keyBorderColor, scope.colors.keyBorderColor))
    }
    private val cells = object : RecyclerView.Adapter<Cell>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Cell(TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = RecyclerView.LayoutParams(-1, dp(48)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        })
        override fun onBindViewHolder(holder: Cell, index: Int) {
            val value = items[index]
            holder.text.apply {
                // A dotted circle makes combining marks selectable without altering committed text.
                val combining = value.codePointAt(0).let { Character.getType(it) in listOf(6, 7, 8) }
                text = if (combining) "◌$value" else value
                contentDescription = value
                setTextColor(ink); background = this@BuiltinSymbolsWindow.background()
                typeface = if (emoji) Typeface.DEFAULT else scope.theme.fonts.key
                maxLines = if (wide) 2 else 1
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, if (wide) 9 else 12, if (wide) 16 else 25, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                setOnClickListener { service.commitText(value) }
                setOnLongClickListener {
                    val points = mutableListOf<String>()
                    var offset = 0
                    while (offset < value.length) {
                        val code = value.codePointAt(offset); points.add("U+%04X".format(code)); offset += Character.charCount(code)
                    }
                    val codes = points.joinToString(" ")
                    service.showDialog(AlertDialog.Builder(context).setTitle(value).setMessage(codes)
                        .setPositiveButton("输入") { _, _ -> service.commitText(value) }.setNegativeButton("取消", null).create())
                    true
                }
            }
        }
    }
    private class Cell(val text: TextView) : RecyclerView.ViewHolder(text)
    override fun onCreateView(): View {
        selector = TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL; textSize = 16f; setTextColor(ink)
            setPadding(dp(12), 0, dp(12), 0); text = "选择分类  ▾"
            contentDescription = "选择符号分类；长按分类可拖动排序"
            setOnClickListener { if (categories.isNotEmpty()) showCategories() }
        }
        root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), dp(2)) }
        count = TextView(context).apply { text = "正在载入表情与符号…"; textSize = 11f; setPadding(dp(8), dp(2), dp(8), dp(2)); setTextColor(ink) }
        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 8); adapter = cells
            itemAnimator = null; isVerticalScrollBarEnabled = true
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) resizeColumns(right - left)
            }
        }
        root.addView(count, LinearLayout.LayoutParams(-1, -2))
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(context)
        listOf("返回键盘" to "", "空格" to "space", "删除" to "BackSpace", "回车" to "Return").forEach { (label, action) ->
            bottom.addView(TextView(context).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(ink)
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
    override fun onCreateBarView(): View = selector
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
        selectedId = category.id; selector.text = "${category.name}  ▾"
        items = category.items; wide = category.wide
        emoji = category.id in setOf("emoji", "people", "components", "animals", "food", "travel", "activities", "objects", "emoji_symbols", "flags", "emoji_full") || category.id.endsWith("_tones")
        resizeColumns(grid.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels)
        count.text = "${category.name} · ${items.size} 项 · 上下滑动浏览"
        cells.notifyDataSetChanged(); grid.scrollToPosition(0)
    }
    private fun showCategories() {
        val working = categories.toMutableList()
        val chooser = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2); itemAnimator = null
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        lateinit var dialog: AlertDialog
        val rows = object : RecyclerView.Adapter<Cell>() {
            override fun getItemCount() = working.size
            override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Cell(TextView(context).apply {
                gravity = Gravity.CENTER; textSize = 14f; setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = RecyclerView.LayoutParams(-1, dp(56)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
            })
            override fun onBindViewHolder(holder: Cell, index: Int) {
                val category = working[index]
                holder.text.apply {
                    text = (if (category.id == selectedId) "✓ " else "") + category.name
                    setTextColor(ink); background = this@BuiltinSymbolsWindow.background()
                    setOnClickListener {
                        categories = working.toList()
                        select(categories.indexOfFirst { it.id == category.id }); dialog.dismiss()
                    }
                }
            }
        }
        chooser.adapter = rows
        androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0) {
            override fun onMove(recycler: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition; val b = to.bindingAdapterPosition
                if (a !in working.indices || b !in working.indices) return false
                working.add(b, working.removeAt(a)); rows.notifyItemMoved(a, b)
                categories = working.toList()
                com.osfans.trime.data.symbols.SymbolCategoryOrder.save(working.map { it.id })
                return true
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit
        }).attachToRecyclerView(chooser)
        val height = (context.resources.displayMetrics.heightPixels * .55f).toInt()
        val container = FrameLayout(context).apply { addView(chooser, FrameLayout.LayoutParams(-1, height)) }
        dialog = AlertDialog.Builder(context).setTitle("选择分类 · 长按拖动排序").setView(container)
            .setNeutralButton("恢复默认顺序") { _, _ ->
                com.osfans.trime.data.symbols.SymbolCategoryOrder.reset()
                categories = BuiltinSymbolCatalog.categories
            }.setNegativeButton("关闭", null).create()
        service.showDialog(dialog)
    }

    private fun resizeColumns(width: Int) {
        (grid.layoutManager as GridLayoutManager).spanCount =
            (width / dp(if (wide) 84 else 44)).coerceIn(if (wide) 2 else 3, 12)
    }
    override fun onDetached() { job?.cancel(); job = null }
    override fun refreshColors() {
        if (::root.isInitialized) {
            root.setBackgroundColor(color(layout?.bgColor, scope.colors.textBackColor))
            count.setTextColor(ink); cells.notifyDataSetChanged()
        }
    }
}
