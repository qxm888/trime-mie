/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.Gravity
import android.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import com.osfans.trime.R
import com.osfans.trime.data.db.CategoryHelper
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.InputTabLayout
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.segments.SegmentsWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.CategoryManagerActivity
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.ui.main.DataTransferActivity
import com.osfans.trime.util.AppUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.recyclerview.verticalLayoutManager

class ClipboardWindow(di: DI, private val initialTab: Int = 0) : BoardWindow.BarBoardWindow(di) {

    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    private var actionsMenu: PopupMenu? = null

    private lateinit var clipboardLayout: ClipboardLayout
    private lateinit var clipboardPagesAdapter: ClipboardPagesAdapter
    private var wasAttached = false

    private val prefs = AppPrefs.defaultInstance().clipboard
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste

    private val clipboardQuery = kotlinx.coroutines.flow.MutableStateFlow("")
    private val collectionQuery = kotlinx.coroutines.flow.MutableStateFlow("")

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private val clipboardBeansFlow by lazy {
        clipboardQuery.flatMapLatest { query ->
            Pager(PagingConfig(pageSize = 16)) {
                if (query.isBlank()) ClipboardHelper.allBeans() else ClipboardHelper.searchBeans(query)
            }.flow
        }
    }

    @kotlinx.coroutines.ExperimentalCoroutinesApi
    private val collectionBeansFlow by lazy {
        collectionQuery.flatMapLatest { query ->
            Pager(PagingConfig(pageSize = 16)) {
                if (query.isBlank()) CollectionHelper.allBeans() else CollectionHelper.searchBeans(query)
            }.flow
        }
    }
    private var lastSelectedPage = initialTab
    private var keepOpenForSession = false
    private var clipboardBeansSubmitJob: Job? = null
    private var collectionBeansSubmitJob: Job? = null
    private val categoryBeansSubmitJobs = mutableMapOf<Int, Job>()
    private val categoryPageListeners = mutableMapOf<Int, CategoryHelper.OnCategoriesChangeListener>()

    private val clipboardBeansAdapter by lazy {
        object : ClipboardAdapter(scope) {
            override fun onPaste(bean: DatabaseBean) {
                val text = bean.text ?: return
                service.commitText(text)
                if (clipboardReturnAfterPaste && !keepOpenForSession) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onPin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_CLIPBOARD)
            }

            override fun onShare(bean: DatabaseBean) {
                val text = bean.text ?: return
                launchTextSharing(text)
            }

            override fun onSegment(bean: DatabaseBean) {
                val text = bean.text ?: return
                windowManager.attachWindow(SegmentsWindow(di, text))
            }

            override fun onCollect(bean: DatabaseBean) {
                service.lifecycleScope.launch {
                    CollectionHelper.addNewBean(bean.text ?: "")
                }
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch { ClipboardHelper.delete(id) }
            }

            override val enableCollection: Boolean = true
        }
    }

    /** Shared interactions for the "all" collection page and per-category pages. */
    private fun createCollectionAdapter(categoryId: String?): ClipboardAdapter =
        object : ClipboardAdapter(scope) {
            override fun onPaste(bean: DatabaseBean) {
                val text = bean.text ?: return
                service.commitText(text)
                if (clipboardReturnAfterPaste && !keepOpenForSession) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onPin(id: Int) {
                service.lifecycleScope.launch { CollectionHelper.pin(id) }
            }

            override fun onUnpin(id: Int) {
                service.lifecycleScope.launch { CollectionHelper.unpin(id) }
            }

            override fun onEdit(id: Int) {
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_COLLECTION)
            }

            override fun onShare(bean: DatabaseBean) {
                val text = bean.text ?: return
                launchTextSharing(text)
            }

            override fun onSegment(bean: DatabaseBean) {
                val text = bean.text ?: return
                windowManager.attachWindow(SegmentsWindow(di, text))
            }

            override fun onDelete(id: Int) {
                service.lifecycleScope.launch { CollectionHelper.delete(id) }
            }

            override fun onMoveToCategory(bean: DatabaseBean) {
                promptAssignCategory(bean)
            }

            override val enableCollection: Boolean = false

            /** Category tags are only meaningful in the "all" view. */
            override val showCategory: Boolean = categoryId == null
        }

    private val collectionBeansAdapter by lazy { createCollectionAdapter(null) }

    private val clipboardPage by lazy {
        ClipboardPageUi(context).apply {
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = clipboardBeansAdapter
                attachReorderHelper(this, clipboardBeansAdapter) { list ->
                    list.forEach { ClipboardHelper.updateOrderIndex(it.id, it.orderIndex) }
                }
            }
        }
    }

    private val collectionPage by lazy {
        ClipboardPageUi(context).apply {
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = collectionBeansAdapter
                attachReorderHelper(this, collectionBeansAdapter) { list ->
                    list.forEach { CollectionHelper.updateOrderIndex(it.id, it.orderIndex) }
                }
            }
        }
    }

    override fun onCreateView() = ClipboardLayout(context, scope).apply {
        clipboardLayout = this
        clipboardPagesAdapter =
            object : ClipboardPagesAdapter() {
                override fun getItemCount(): Int = BASE_PAGE_COUNT + CategoryHelper.all().size

                override fun onCreatePage(position: Int): ClipboardPageUi =
                    when {
                        position == PAGE_CLIPBOARD -> clipboardPage
                        position == PAGE_COLLECTION -> collectionPage
                        else -> createCategoryPage(position)
                    }
            }
        viewPager.apply {
            adapter = clipboardPagesAdapter
            // Make sure horizontal swiping between tabs/categories works.
            isUserInputEnabled = true
            offscreenPageLimit = 1
        }
        titleUi.apply {
            tabLayout.onTabLongClick = { position ->
                if (position >= PAGE_COLLECTION) {
                    openCategoryManager()
                }
            }
            tabLayout.onConfigureTab(viewPager) { tabUi, position ->
                configureTab(tabUi, position)
            }
            actionsButton.setOnClickListener {
                showActionsMenu(actionsButton)
            }
            clearSearchButton.setOnClickListener {
                titleUi.setSearchActive(false)
                ClipboardSearchBridge.reset()
                service.hideBottomOverlay()
                windowManager.attachWindow(this@ClipboardWindow)
            }

        }
        CategoryHelper.addOnChangeListener(onCategoriesChanged)
    }

    private fun showActionsMenu(anchor: View) {
        actionsMenu?.dismiss()
        actionsMenu = PopupMenu(context, anchor, Gravity.END).apply {
            menu.add(0, 1, 0, R.string.clipboard_search)
            menu.add(0, 2, 1, R.string.delete)
            menu.add(0, 3, 2, "临时固定（连续粘贴）").apply { isCheckable = true; isChecked = keepOpenForSession }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startSearch()
                    2 -> deleteCurrentPage()
                    3 -> keepOpenForSession = !keepOpenForSession
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            setOnDismissListener { if (actionsMenu === this) actionsMenu = null }
            show()
        }
    }

    private fun startSearch() {
        // The search overlay routes typed text while keeping the regular keyboard available.
        service.currentInputConnection?.finishComposingText()
        service.postRimeJob { clearComposition() }
        val overlay = ClipboardSearchOverlay(context, scope, service, windowManager,
            onReturn = { windowManager.attachWindow(this@ClipboardWindow) },
            keepOpen = { keepOpenForSession || !clipboardReturnAfterPaste },
        )
        ClipboardSearchBridge.activate(
            onText = { overlay.appendText(it) },
            onBackspace = { overlay.backspace() },
            onExit = { overlay.exit() },
        )
        windowManager.attachWindow(KeyboardWindow)
        clipboardLayout.titleUi.setSearchActive(true)
        service.showBottomOverlay(overlay.root, overlay.heightPx)
    }

    private fun deleteCurrentPage() {
        when (val currentItem = clipboardLayout.viewPager.currentItem) {
            PAGE_CLIPBOARD -> promptDeleteAll {
                ClipboardHelper.deleteAll(ClipboardHelper.haveUnpinned())
            }
            PAGE_COLLECTION -> promptDeleteAll {
                CollectionHelper.deleteAll(CollectionHelper.haveUnpinned())
            }
            else -> {
                val categoryId = categoryIdForPage(currentItem) ?: return
                promptDeleteAll { CollectionHelper.deleteAllInCategory(categoryId) }
            }
        }
    }

    /** 给列表挂拖拽排序（把手触发），松手后按新顺序重编号入库（dahiPLUS 移植）。 */
    private fun attachReorderHelper(
        recyclerView: RecyclerView,
        adapter: ClipboardAdapter,
        persist: suspend (List<DatabaseBean>) -> Unit,
    ) {
        val callback =
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder,
                ): Boolean = adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

                override fun onSwiped(
                    viewHolder: RecyclerView.ViewHolder,
                    direction: Int,
                ) = Unit

                // 由条目上的拖拽把手触发，避免与长按弹出菜单冲突
                override fun isLongPressDragEnabled(): Boolean = false

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                ) {
                    super.clearView(recyclerView, viewHolder)
                    val reordered = adapter.commitReorder()
                    if (reordered.isNotEmpty()) {
                        service.lifecycleScope.launch { persist(reordered) }
                    }
                }
            }
        val helper = ItemTouchHelper(callback)
        helper.attachToRecyclerView(recyclerView)
        adapter.onStartDrag = { holder -> helper.startDrag(holder) }
    }

    /** Page position (>=2) to category id; null outside category pages. */
    private fun categoryIdForPage(position: Int): String? =
        CategoryHelper.all().getOrNull(position - BASE_PAGE_COUNT)?.id

    private fun createCategoryPage(position: Int): ClipboardPageUi {
        val categoryId = categoryIdForPage(position)
        val adapter = createCollectionAdapter(categoryId)
        val page =
            ClipboardPageUi(context).apply {
                recyclerView.apply {
                    layoutManager = verticalLayoutManager()
                    this@apply.adapter = adapter
                    attachReorderHelper(this, adapter) { list ->
                        list.forEach { CollectionHelper.updateOrderIndex(it.id, it.orderIndex) }
                    }
                }
            }
        if (wasAttached) {
            submitCategoryPage(position, adapter)
        }
        val changeListener = CategoryHelper.OnCategoriesChangeListener {
            adapter.refreshColors()
        }
        CategoryHelper.addOnChangeListener(changeListener)
        categoryPageListeners[position] = changeListener
        return page
    }

    private fun submitCategoryPage(
        position: Int,
        adapter: ClipboardAdapter,
    ) {
        val categoryId = categoryIdForPage(position) ?: return
        val pager =
            Pager(PagingConfig(pageSize = 16)) {
                CollectionHelper.allBeansByCategory(categoryId)
            }
        categoryBeansSubmitJobs.remove(position)?.cancel()
        categoryBeansSubmitJobs[position] =
            service.lifecycleScope.launch {
                pager.flow.collect {
                    adapter.submitData(it)
                }
            }
    }

    private val onCategoriesChanged = CategoryHelper.OnCategoriesChangeListener {
        service.lifecycleScope.launch {
            rebuildCollectionTabs()
        }
    }

    /**
     * Re-creates tabs and per-category pages after a category change.
     * Pages are cached by position, so stale pages (and their listeners and
     * paging jobs) must be dropped before rebinding.
     */
    private fun rebuildCollectionTabs() {
        val currentItem = clipboardLayout.viewPager.currentItem
        val pages = clipboardPagesAdapter.pages
        var i = pages.size() - 1
        while (i >= 0) {
            val key = pages.keyAt(i)
            if (key >= BASE_PAGE_COUNT) {
                val position = key.toInt()
                categoryBeansSubmitJobs.remove(position)?.cancel()
                categoryPageListeners.remove(position)?.let { CategoryHelper.removeOnChangeListener(it) }
                pages.delete(key)
            }
            i--
        }
        clipboardPagesAdapter.notifyDataSetChanged()
        clipboardLayout.titleUi.tabLayout.onConfigureTab(clipboardLayout.viewPager) { tabUi, position ->
            configureTab(tabUi, position)
        }
        val target = currentItem.coerceAtMost(clipboardPagesAdapter.itemCount - 1)
        if (target != currentItem) {
            clipboardLayout.viewPager.setCurrentItem(target, false)
        } else {
            // Same page selected; onPageSelected won't fire, re-highlight manually.
            clipboardLayout.titleUi.tabLayout.activateTabAt(target)
        }
    }

    private fun cleanupCategoryPages() {
        categoryBeansSubmitJobs.values.forEach { it.cancel() }
        categoryBeansSubmitJobs.clear()
        categoryPageListeners.values.forEach { CategoryHelper.removeOnChangeListener(it) }
        categoryPageListeners.clear()
    }

    /**
     * Category names must be typed in a real editor window. An EditText hosted by
     * the IME's own dialog can never get an input connection, so the manager runs
     * as a separate activity instead.
     */
    private fun openCategoryManager() {
        context.startActivity(
            Intent(context, CategoryManagerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }


    private fun promptAssignCategory(bean: DatabaseBean) {
        val categories = CategoryHelper.all()
        if (categories.isEmpty()) {
            openCategoryManager()
            return
        }
        val names =
            buildList {
                add(context.getString(R.string.collection))
                categories.forEach { add(it.name) }
            }
        val ids: List<String?> =
            buildList {
                add(null)
                categories.forEach { add(it.id) }
            }
        val checked =
            when {
                bean.categoryId == null -> 0
                else -> ids.indexOfFirst { it == bean.categoryId }.coerceAtLeast(0)
            }
        val dialog =
            AlertDialog.Builder(context)
                .setTitle(R.string.category_move_to)
                .setSingleChoiceItems(names.toTypedArray(), checked) { d, which ->
                    d.dismiss()
                    val target = ids.getOrNull(which)
                    if (target != bean.categoryId) {
                        service.lifecycleScope.launch {
                            CollectionHelper.updateCategory(bean.id, target)
                        }
                    }
                }
                .setPositiveButton(R.string.category_manage) { _, _ ->
                    openCategoryManager()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        service.showDialog(dialog)
    }

    private fun launchTextSharing(text: String) {
        val target =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        val chooser =
            Intent.createChooser(target, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        service.startActivity(chooser)
    }

    private fun promptDeleteAll(action: suspend () -> Unit) {
        val dialog =
            AlertDialog.Builder(context)
                .setTitle(R.string.delete_all)
                .setMessage(R.string.ask_to_delete_all)
                .setPositiveButton(R.string.ok) { _, _ ->
                    service.lifecycleScope.launch {
                        action()
                    }
                }.setNegativeButton(R.string.cancel, null)
                .create()
        service.showDialog(dialog)
    }

    private fun configureTab(
        tabUi: InputTabLayout.TabUi,
        position: Int,
    ) {
        val text =
            when (position) {
                PAGE_CLIPBOARD -> context.getString(R.string.clipboard)
                PAGE_COLLECTION -> context.getString(R.string.collection)
                else -> CategoryHelper.all().getOrNull(position - BASE_PAGE_COUNT)?.name ?: return
            }
        tabUi.setLabel(text)
        tabUi.label.apply {
            // Keep the tab strip compact so more categories fit on screen.
            textSize = minOf(theme.style.candidateTextSize, 12f)
            setPadding(dp(2), 0, dp(2), 0)
            setTypeface(theme.fonts.candidate, Typeface.BOLD)
            setTextColor(scope.colors.keyTextColor)
        }
    }

    override fun onAttached() {
        wasAttached = true
        clipboardLayout.viewPager.setCurrentItem(lastSelectedPage.coerceIn(0, BASE_PAGE_COUNT + CategoryHelper.all().size - 1), false)
        clipboardBeansSubmitJob =
            service.lifecycleScope.launch {
                clipboardBeansFlow.collect {
                    clipboardBeansAdapter.submitData(it)
                }
            }
        collectionBeansSubmitJob =
            service.lifecycleScope.launch {
                collectionBeansFlow.collect {
                    collectionBeansAdapter.submitData(it)
                }
            }
    }

    override fun onDetached() {
        lastSelectedPage = clipboardLayout.viewPager.currentItem
        wasAttached = false
        actionsMenu?.dismiss()
        actionsMenu = null
        clipboardBeansAdapter.dismissPopupMenu()
        collectionBeansAdapter.dismissPopupMenu()
        clipboardBeansSubmitJob?.cancel()
        collectionBeansSubmitJob?.cancel()
        cleanupCategoryPages()
        CategoryHelper.removeOnChangeListener(onCategoriesChanged)
    }

    override fun refreshColors() {
        if (!wasAttached) return
        clipboardLayout.titleUi.actionsButton.refreshColors()
        clipboardLayout.titleUi.clearSearchButton.refreshColors()
        clipboardLayout.titleUi.tabLayout.reconfigureTabs { tabUi, position ->
            configureTab(tabUi, position)
        }
        clipboardBeansAdapter.refreshColors()
        collectionBeansAdapter.refreshColors()
        val pages = clipboardPagesAdapter.pages
        var i = 0
        while (i < pages.size()) {
            val page = pages.valueAt(i)
            (page.recyclerView.adapter as? ClipboardAdapter)?.refreshColors()
            i++
        }
    }

    override fun onCreateBarView(): View = clipboardLayout.titleUi.root

    companion object {
        private const val PAGE_CLIPBOARD = 0
        private const val PAGE_COLLECTION = 1
        private const val BASE_PAGE_COUNT = 2
    }
}
