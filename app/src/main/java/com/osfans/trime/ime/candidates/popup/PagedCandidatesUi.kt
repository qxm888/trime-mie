/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.theme.ThemeScope
import splitties.views.dsl.core.Ui
import splitties.views.dsl.recyclerview.recyclerView

class PagedCandidatesUi(
    override val ctx: Context,
    val scope: ThemeScope,
    private val onCandidateClick: (Int) -> Unit,
    private val onCandidateAction: (Int, String, View) -> Unit,
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit,
) : Ui {
    private var candidates = Candidates.Paged()

    private var isHorizontal = true
    private var viewportWidth = 0

    fun constrainWidth(width: Int) {
        if (width <= 0 || viewportWidth == width) return
        viewportWidth = width
        root.post { if (root.isAttachedToWindow) candidatesAdapter.notifyDataSetChanged() }
    }

    sealed class UiHolder(
        open val ui: Ui,
    ) : RecyclerView.ViewHolder(ui.root) {
        class Candidate(
            override val ui: LabeledCandidateItemUi,
        ) : UiHolder(ui)

        class Pagination(
            override val ui: PaginationUi,
        ) : UiHolder(ui)
    }

    private val candidatesAdapter =
        object : BaseQuickAdapter<CandidateProto, UiHolder>() {
            init {
                // We must do this to avoid ArrayIndexOutOfBoundsException
                // https://github.com/google/flexbox-layout/issues/363#issuecomment-382949953
                setHasStableIds(true)
            }

            override fun getItemId(position: Int): Long = items.getOrNull(position).hashCode().toLong()

            override fun getItemCount(items: List<CandidateProto>) = items.size + (if (candidates.hasPrevPage || candidates.hasNextPage) 1 else 0)

            override fun getItemViewType(
                position: Int,
                list: List<CandidateProto>,
            ) = if (position < list.size) 0 else 1

            override fun onCreateViewHolder(
                context: Context,
                parent: ViewGroup,
                viewType: Int,
            ): UiHolder = when (viewType) {
                0 -> UiHolder.Candidate(LabeledCandidateItemUi(ctx, scope))

                else -> UiHolder.Pagination(PaginationUi(ctx, scope)).apply {
                    ui.prevIcon.setOnClickListener {
                        onPrevPage.invoke()
                    }
                    ui.nextIcon.setOnClickListener {
                        onNextPage.invoke()
                    }
                }
            }.apply {
                // assign default LayoutParams, otherwise updateLayoutParams won't work
                ui.root.layoutParams = FlexboxLayoutManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }

            override fun onBindViewHolder(
                holder: UiHolder,
                position: Int,
                item: CandidateProto?,
            ) {
                when (holder) {
                    is UiHolder.Candidate -> {
                        val candidate = item ?: return
                        holder.ui.update(candidate, position == candidates.highlighted,
                            (viewportWidth.takeIf { it > 0 } ?: root.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels) - root.paddingLeft - root.paddingRight,
                            com.osfans.trime.data.prefs.AppPrefs.defaultInstance().candidates.let { if (it.limitLines.getValue()) it.maxLines.getValue() else 0 },
                            autoShrink = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().candidates.autoShrink.getValue(),
                        )
                        holder.ui.root.setOnClickListener {
                            onCandidateClick.invoke(position)
                        }
                        holder.ui.root.setOnLongClickListener { v ->
                            onCandidateAction.invoke(position, candidate.text, v)
                            true
                        }
                        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                            width = if (isHorizontal) WRAP_CONTENT else MATCH_PARENT
                            flexShrink = 0f
                        }
                    }

                    is UiHolder.Pagination -> {
                        holder.ui.update(candidates)
                        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                            flexGrow = 1f
                            width = if (isHorizontal) WRAP_CONTENT else MATCH_PARENT
                            flexShrink = 0f
                            alignSelf = if (isHorizontal) AlignItems.CENTER else AlignItems.STRETCH
                        }
                    }
                }
            }
        }

    private val candidatesLayoutManager =
        FlexboxLayoutManager(ctx).apply {
            flexWrap = FlexWrap.WRAP
        }

    private val verticalLayoutManager = LinearLayoutManager(ctx, RecyclerView.VERTICAL, false)

    override val root: RecyclerView =
        recyclerView {
            itemAnimator = null
            isFocusable = false
            adapter = candidatesAdapter
            layoutManager = candidatesLayoutManager
            overScrollMode = View.OVER_SCROLL_NEVER
        }

    fun update(
        candidates: Candidates.Paged,
        layout: PopupCandidatesLayout,
    ) {
        this.candidates = candidates
        this.isHorizontal = when (layout) {
            PopupCandidatesLayout.AUTOMATIC -> candidates.isHorizontalLayout
            else -> layout == PopupCandidatesLayout.HORIZONTAL
        }
        // Flexbox COLUMN_REVERSE can report a zero wrap-content height and no children.
        // Vertical lists need only a linear manager; keep Flexbox for wrapped horizontal rows.
        if (isHorizontal) {
            candidatesLayoutManager.apply {
                flexWrap = FlexWrap.WRAP
                flexDirection = FlexDirection.ROW
                alignItems = AlignItems.BASELINE
            }
            if (root.layoutManager !== candidatesLayoutManager) root.layoutManager = candidatesLayoutManager
        } else {
            verticalLayoutManager.reverseLayout = layout == PopupCandidatesLayout.VERTICAL_REVERSE
            if (root.layoutManager !== verticalLayoutManager) root.layoutManager = verticalLayoutManager
        }
        candidatesAdapter.submitList(candidates.candidates.toList())
    }

    /** Re-binds visible rows so they re-render with the current scheme's colors. */
    fun refreshColors() {
        candidatesAdapter.notifyDataSetChanged()
    }
}
