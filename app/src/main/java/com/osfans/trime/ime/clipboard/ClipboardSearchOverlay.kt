/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * 剪贴板 / 收藏夹的搜索浮层（dahiPLUS 移植）。
 *
 * 打开剪贴板窗口时同文会把「键盘窗口」整体替换掉，剪贴板窗口里的
 * EditText 收不到键盘。本浮层在「键盘重新显示」的前提下贴在键盘上方：
 * 用户用同文键盘输入，提交的文本/退格经 [ClipboardSearchBridge] 路由进来，
 * 实时过滤剪贴板与收藏夹条目，点条目即上屏。
 */
class ClipboardSearchOverlay(
    private val ctx: Context,
    private val scope: ThemeScope,
    private val service: TrimeInputMethodService,
    private val windowManager: BoardWindowManager,
    private val onReturn: () -> Unit = { windowManager.attachWindow(KeyboardWindow) },
    private val keepOpen: () -> Boolean = { false },
) {
    private val theme: Theme get() = scope.theme
    private val density = ctx.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private val barSize = theme.style.run { candidateViewHeight + commentHeight }

    private val textColor = ColorManager.getColor("key_text_color")
    private val symbolColor = ColorManager.getColor("key_symbol_color")

    /** 浮层高度：约占屏幕 42%，保证不占满整屏、又能看到足够条目。 */
    val heightPx: Int = (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()

    private var query: String = ""
    private var refreshJob: Job? = null

    private val queryView =
        TextView(ctx).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.style.candidateTextSize)
            setTextColor(textColor)
            setHintTextColor(symbolColor)
            hint = ctx.getString(R.string.dahi_clipboard_search_hint)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }

    private val clearButton =
        ImageView(ctx).apply {
            setImageResource(R.drawable.ic_outline_cancel_24)
            setColorFilter(symbolColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            setOnClickListener {
                query = ""
                render()
            }
        }

    private val backButton =
        ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_arrow_back_24)
            setColorFilter(symbolColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { exit() }
        }

    private val searchIcon =
        ImageView(ctx).apply {
            setImageResource(R.drawable.ic_baseline_search_24)
            setColorFilter(symbolColor)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

    private class ResultHolder(
        val ui: ClipboardBeanUi,
    ) : RecyclerView.ViewHolder(ui.root)

    private val resultAdapter =
        object : RecyclerView.Adapter<ResultHolder>() {
            private val items = ArrayList<DatabaseBean>()

            fun submit(list: List<DatabaseBean>) {
                items.clear()
                items.addAll(list)
                notifyDataSetChanged()
            }

            override fun getItemCount(): Int = items.size

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ResultHolder = ResultHolder(ClipboardBeanUi(parent.context, scope))

            override fun onBindViewHolder(
                holder: ResultHolder,
                position: Int,
            ) {
                val bean = items[position]
                holder.ui.dragHandle.visibility = View.GONE
                holder.ui.setBean(bean.text.orEmpty(), bean.pinned)
                holder.ui.root.setOnClickListener { pick(bean) }
            }
        }

    private val listView =
        RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = resultAdapter
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

    private val background: android.graphics.drawable.Drawable =
        GradientDrawable().apply {
            setColor(runCatching { ColorManager.getColor("key_back_color") }.getOrDefault(0xFF2B2B2B.toInt()))
            cornerRadius = dp(8).toFloat()
        }

    val root: View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = this@ClipboardSearchOverlay.background
            addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(searchIcon, LinearLayout.LayoutParams(dp(barSize), dp(barSize)))
                    addView(queryView, LinearLayout.LayoutParams(0, dp(barSize), 1f))
                    addView(clearButton, LinearLayout.LayoutParams(dp(barSize), dp(barSize)))
                    addView(backButton, LinearLayout.LayoutParams(dp(barSize), dp(barSize)))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(barSize)),
            )
            addView(
                listView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

    init {
        render()
    }

    /** 键盘提交的文本追加到搜索词。 */
    fun appendText(text: String) {
        if (text.isEmpty()) return
        query += text
        render()
    }

    /** 退格：删除搜索词末位。 */
    fun backspace() {
        if (query.isEmpty()) return
        query = query.substring(0, query.offsetByCodePoints(query.length, -1))
        render()
    }

    private fun render() {
        queryView.text = query
        clearButton.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
        refreshJob?.cancel()
        val requestedQuery = query
        refreshJob = service.lifecycleScope.launch {
            delay(120)
            val filtered = withContext(Dispatchers.IO) {
                val pattern = "%" + requestedQuery.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
                val clipboard = ClipboardHelper.databaseOrNull()?.databaseDao()?.searchSnapshot(pattern).orEmpty()
                val collection = CollectionHelper.databaseOrNull()?.databaseDao()?.searchSnapshot(pattern).orEmpty()
                (clipboard + collection).distinctBy { it.text }.take(200)
            }
            resultAdapter.submit(filtered)
        }
    }

    private fun pick(bean: DatabaseBean) {
        val text = bean.text ?: return
        service.postRimeJob { clearComposition() }
        if (keepOpen()) {
            ClipboardSearchBridge.reset()
            service.commitText(text)
            ClipboardSearchBridge.activate(onText = ::appendText, onBackspace = ::backspace, onExit = ::exit)
        } else {
            close()
            service.commitText(text)
        }
    }

    /** 退出搜索：清理桥接与浮层，回到键盘。 */
    fun exit() {
        close()
        onReturn()
    }

    private fun close() {
        refreshJob?.cancel()
        service.postRimeJob { clearComposition() }
        ClipboardSearchBridge.reset()
        service.hideBottomOverlay()
        windowManager.attachWindow(KeyboardWindow)
    }
}
