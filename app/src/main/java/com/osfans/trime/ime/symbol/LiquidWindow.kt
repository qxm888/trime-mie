/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.app.AlertDialog
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.symbols.SymbolOrder
import androidx.core.content.ContextCompat
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.SymbolHistory
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ime.window.ResidentWindow
import org.kodein.di.DI
import org.kodein.di.instance

class LiquidWindow(di: DI) :
    BoardWindow.BarBoardWindow(di),
    ResidentWindow {
    override val showTitle = false

    private val service: TrimeInputMethodService by instance()
    private val rime: RimeSession by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()

    private lateinit var liquidLayout: LiquidLayout
    private val symbolHistory = SymbolHistory(180)
    private var sourceIndex = -1
    private var sorting = false
    private var deleting = false
    private var orderDirty = false
    private lateinit var sortButton: TextView
    private lateinit var resetButton: TextView
    private lateinit var deleteButton: TextView
    private val themeIdentity get() = scope.theme.fontConfigId.ifEmpty { scope.theme.name }
    private val orderNamespace get() = "liquid-items:$themeIdentity:${scope.theme.liquidKeyboard.keyboards.getOrNull(sourceIndex)?.id.orEmpty()}"
    private fun itemIdentity(item: LiquidKeyboard.KeyItem) = org.json.JSONArray().put(item.text).put(item.altText).toString()
    var currentDataType: LiquidKeyboard.DataType = LiquidKeyboard.DataType.SINGLE
        private set

    private val adapter by lazy {
        LiquidAdapter(scope) {
            when (currentDataType) {
                LiquidKeyboard.DataType.SYMBOL -> triggerSymbolInput(this.altText)

                LiquidKeyboard.DataType.TABS -> {
                    val realPosition = scope.theme.liquidKeyboard.keyboards.indexOfFirst { it.id == this.altText }
                    setDataByIndex(realPosition)
                }

                else -> {
                    service.commitText(this.text)
                    if (currentDataType != LiquidKeyboard.DataType.HISTORY) {
                        symbolHistory.insert(this.text)
                        symbolHistory.save()
                    }
                }
            }
        }
    }

    private val mainLayoutManager by lazy {
        FlexboxLayoutManager(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
        }
    }

    companion object : ResidentWindow.Key

    override val key: ResidentWindow.Key
        get() = LiquidWindow

    override fun onCreateView(): View = LiquidLayout(context, scope, commonKeyboardActionListener).apply {
        liquidLayout = this
        tabsUi.apply {
            setTags(scope.theme.liquidKeyboard.getTagList(),scope.theme.liquidKeyboard.keyboards.map { it.id })
            onOrderChanged = { if(currentDataType == LiquidKeyboard.DataType.TABS) submitData(tabItems()) }
            setOnTabClickListener { i ->
                setDataByIndex(i)
            }
        }
        recyclerView.apply {
            layoutManager = mainLayoutManager
            this.adapter = this@LiquidWindow.adapter
        }
        val helper=ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,0) {
            override fun isLongPressDragEnabled()=false
            override fun onMove(rv: RecyclerView,from: RecyclerView.ViewHolder,to: RecyclerView.ViewHolder): Boolean {
                val moved=this@LiquidWindow.adapter.move(from.bindingAdapterPosition,to.bindingAdapterPosition)
                if(moved) orderDirty=true
                return moved
            }
            override fun onSwiped(holder: RecyclerView.ViewHolder,direction: Int)=Unit
            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?,state: Int) {
                super.onSelectedChanged(holder,state)
                if(state==ItemTouchHelper.ACTION_STATE_DRAG) holder?.itemView?.apply { scaleX=1.06f; scaleY=1.06f; alpha=.85f }
            }
            override fun clearView(rv: RecyclerView,holder: RecyclerView.ViewHolder) {
                super.clearView(rv,holder); holder.itemView.apply { scaleX=1f; scaleY=1f; alpha=1f }; saveItemOrder()
            }
        })
        helper.attachToRecyclerView(recyclerView)
        this@LiquidWindow.adapter.onStartDrag={ helper.startDrag(it) }
        this@LiquidWindow.adapter.onDeleteItem={ deleteItem(it) }
        this@LiquidWindow.adapter.onItemLongClick={ item ->
            if(currentDataType!=LiquidKeyboard.DataType.TABS) service.showDialog(AlertDialog.Builder(context)
                .setTitle(item.text).setMessage("从当前分类删除，可在“恢复”中找回。")
                .setPositiveButton("删除") { _,_ -> deleteItem(item) }.setNegativeButton("取消",null).create())
        }
    }

    override fun onCreateBarView(): View {
        val density=context.resources.displayMetrics.density
        fun button(label: String)=TextView(context).apply {
            text=label; textSize=13f; gravity=Gravity.CENTER; setTextColor(scope.colors.candidateTextColor)
        }
        sortButton=button("排序").apply {
            contentDescription="调整当前分类内符号和表情的顺序"
            setOnClickListener { saveItemOrder(); deleting=false; sorting=!sorting; updateSorting() }
        }
        deleteButton=button("删除").apply {
            contentDescription="点按删除当前分类的符号或表情"
            setOnClickListener {
                if(currentDataType==LiquidKeyboard.DataType.TABS) return@setOnClickListener
                saveItemOrder(); sorting=false; deleting=!deleting; updateSorting()
            }
        }
        resetButton=button("恢复").apply {
            visibility=View.GONE
            setOnClickListener {
                val tabs=currentDataType==LiquidKeyboard.DataType.TABS
                val options=if(tabs) arrayOf("恢复分类默认顺序") else arrayOf("恢复已删除的符号 / 表情", "恢复当前分类默认顺序", "恢复分类默认顺序")
                service.showDialog(AlertDialog.Builder(context).setTitle("恢复").setItems(options) { _,which ->
                    saveItemOrder()
                    when {
                        tabs || which==2 -> liquidLayout.tabsUi.resetOrder()
                        which==0 -> SymbolOrder.restoreDeleted(orderNamespace)
                        else -> SymbolOrder.reset(orderNamespace)
                    }
                    orderDirty=false; setDataByIndex(sourceIndex)
                }.setNegativeButton("取消",null).create())
            }
        }
        return LinearLayout(context).apply {
            gravity=Gravity.CENTER_VERTICAL
            addView(liquidLayout.tabsUi.root,LinearLayout.LayoutParams(0,-1,1f))
            addView(resetButton,LinearLayout.LayoutParams((48*density).toInt(),-1))
            addView(deleteButton,LinearLayout.LayoutParams((48*density).toInt(),-1))
            addView(sortButton,LinearLayout.LayoutParams((48*density).toInt(),-1))
        }
    }

    override fun onAttached() {}

    override fun onDetached() {
        saveItemOrder(); liquidLayout.tabsUi.saveOrder()
        sorting=false; deleting=false; updateSorting()
    }

    override fun refreshColors() {
        if (::liquidLayout.isInitialized) liquidLayout.refreshColors()
        if (::sortButton.isInitialized) { sortButton.setTextColor(scope.colors.candidateTextColor); resetButton.setTextColor(scope.colors.candidateTextColor); deleteButton.setTextColor(scope.colors.candidateTextColor) }
    }

    fun setDataByIndex(i: Int) {
        val tag = scope.theme.liquidKeyboard.getTagList().getOrNull(i) ?: return
        saveItemOrder(); sorting=false; deleting=false; sourceIndex=i
        currentDataType = tag.type
        updateSorting()
        liquidLayout.tabsUi.activateTab(i)
        when (tag.type) {
            LiquidKeyboard.DataType.HISTORY -> {
                symbolHistory.load()
                submitData(symbolHistory.toOrderedList().map { LiquidKeyboard.KeyItem(it) })
            }

            LiquidKeyboard.DataType.TABS -> submitData(tabItems())
            else -> {
                val data = scope.theme.liquidKeyboard.getDataByIndex(i)
                submitData(data)
            }
        }
    }

    private fun submitData(data: List<LiquidKeyboard.KeyItem>) {
        adapter.submitList(if(currentDataType==LiquidKeyboard.DataType.TABS) data else SymbolOrder.ordered(orderNamespace,data,::itemIdentity))
    }
    private fun tabItems() = liquidLayout.tabsUi.orderedSourceIndices().mapNotNull { index ->
        scope.theme.liquidKeyboard.keyboards.getOrNull(index)?.takeIf { it.type != LiquidKeyboard.DataType.TABS }?.let { LiquidKeyboard.KeyItem(it.name,it.id) }
    }
    private fun deleteItem(item:LiquidKeyboard.KeyItem) {
        if(currentDataType==LiquidKeyboard.DataType.TABS) return
        saveItemOrder()
        val id=itemIdentity(item)
        SymbolOrder.delete(orderNamespace,id)
        adapter.submitList(adapter.snapshot().filter { itemIdentity(it)!=id })
        updateSorting()
    }
    private fun updateSorting() {
        adapter.sorting=sorting; adapter.deleting=deleting; adapter.notifyDataSetChanged()
        if(::sortButton.isInitialized) {
            sortButton.text=if(sorting) "完成" else "排序"
            deleteButton.text=if(deleting) "完成" else "删除"
            deleteButton.contentDescription=if(deleting) "完成删除，返回正常输入" else "删除当前分类内不喜欢的符号或表情"
            deleteButton.isEnabled=currentDataType!=LiquidKeyboard.DataType.TABS
            deleteButton.alpha=if(deleteButton.isEnabled) 1f else .38f
            resetButton.visibility=if(sorting || deleting || SymbolOrder.hasDeleted(orderNamespace)) View.VISIBLE else View.GONE
        }
    }
    private fun saveItemOrder() {
        if(!orderDirty) return
        orderDirty=false
        if(currentDataType==LiquidKeyboard.DataType.TABS) {
            val items=adapter.snapshot()
            val keyboards=scope.theme.liquidKeyboard.keyboards
            val reordered=ArrayDeque(items.map { it.altText })
            // The category chooser itself is not in the grid; retain its tab position.
            val ids=liquidLayout.tabsUi.orderedSourceIndices().map { keyboards[it] }.map {
                if(it.type==LiquidKeyboard.DataType.TABS || reordered.isEmpty()) it.id else reordered.removeFirst()
            }
            SymbolOrder.save(liquidLayout.tabsUi.orderNamespace,ids) { it }; liquidLayout.tabsUi.reloadOrder()
        } else SymbolOrder.save(orderNamespace,adapter.snapshot(),::itemIdentity)
    }

    private fun triggerSymbolInput(symbol: String) {
        rime.launchOnReady {
            val (isAsciiMode, isAsciiPunch) = it.statusCached.run { isAsciiMode to isAsciiPunct }
            if (isAsciiMode) it.setRuntimeOption("ascii_mode", false)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punct", false)
            it.clearComposition()
            it.simulateKeySequence(symbol)
            if (isAsciiPunch) it.setRuntimeOption("ascii_punct", true)
            ContextCompat.getMainExecutor(service).execute {
                windowManager.attachWindow(KeyboardWindow)
            }
        }
    }
}
