/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.bar

import android.os.Build
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ViewAnimator
import android.widget.inline.InlineContentView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.ui.AlwaysUi
import com.osfans.trime.ime.bar.ui.CandidateUi
import com.osfans.trime.ime.bar.ui.TabUi
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.unrolled.window.FlexboxUnrolledCandidateWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isFloatingEffective
import com.osfans.trime.ime.keyboard.KeyboardPrefs.oneHandActive
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import java.util.concurrent.Executor
import kotlin.coroutines.resume

class InputBarDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme
    private val windowManager: BoardWindowManager by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val candidate: CompactCandidateDelegate by instance()
    private val rime: RimeSession by instance()

    val themedHeight = theme.style.run { candidateViewHeight + commentHeight }

    /** 自定义键盘可单独设置状态栏高度；主题键盘也可在「主题键盘全局外观」里设上/下留白。上/下留白生效时总高=上+下+图标行高。 */
    fun toolbarHeightPx(): Int {
        if (!theme.isCustom) {
            val (top, bottom) = com.osfans.trime.ime.appearance.SurfaceAppearance.themeToolbarPads()
            if (top > 0 || bottom > 0) {
                return context.dp(top + bottom + com.osfans.trime.data.keymap.TOOLBAR_ICON_ROW_DP)
            }
            return context.dp(themedHeight)
        }
        val id = com.osfans.trime.data.keymap.CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
        val kb = com.osfans.trime.data.keymap.CustomKeyboards.get(id)
        // 工具栏上/下留白是整套（全局）外观，读整套根键盘；切页不再跳变。
        val (top, bottom) = com.osfans.trime.data.keymap.CustomKeyboards.setToolbarPads(KeyboardWindow.lastAttachedId)
        // 上/下留白生效时不再叠加主题默认整高：总高 = 上 + 下 + 紧凑图标行高。
        if (top > 0 || bottom > 0) {
            return context.dp(top + bottom + com.osfans.trime.data.keymap.TOOLBAR_ICON_ROW_DP)
        }
        val custom = kb?.toolbarHeightDp?.takeIf { it > 0 } ?: themedHeight
        return context.dp(custom)
    }

    /** 工具栏图标上下留白（px）：任一端有值时套到容器 padding 上，图标按留白排布而不再垂直居中。 */
    private fun toolbarPadsPx(): Pair<Int, Int> {
        if (!theme.isCustom) {
            val (top, bottom) = com.osfans.trime.ime.appearance.SurfaceAppearance.themeToolbarPads()
            if (top <= 0 && bottom <= 0) return 0 to 0
            return context.dp(top) to context.dp(bottom)
        }
        // 工具栏上/下留白是整套（全局）外观，读整套根键盘；切页不再跳变。
        val (top, bottom) = com.osfans.trime.data.keymap.CustomKeyboards.setToolbarPads(KeyboardWindow.lastAttachedId)
        if (top <= 0 && bottom <= 0) return 0 to 0
        return context.dp(top) to context.dp(bottom)
    }

    /** 工具栏图标行的上留白（px）。InputView 算圆角让位时要拿它当图标真实上沿的一部分。 */
    fun toolbarTopPadPx(): Int = toolbarPadsPx().first

    /**
     * 工具栏的尺寸/留白被重设后的回调，由 InputView 挂上。
     *
     * 让位（左右两份 padding）和工具栏自己的上/下留白是两条写入路径，这边一重写，
     * 那边就得跟着补——换工具栏方案后圆角避让失效，就是缺了这一步。
     */
    var onMetricsSynced: (() -> Unit)? = null

    /** 键盘切换/挂载后同步工具栏容器高度与上下留白。 */
    fun syncToolbarMetrics() {
        val (padTop, padBottom) = toolbarPadsPx()
        // 只写上下：左右是「单手模式留白 + 圆角让位」，由 InputView.applyInputBarPadding 管。
        // 以前这里整行重写成 0，换工具栏方案/换键盘时把圆角让位清零，而且清完不会自动补回来。
        view.setPadding(view.paddingLeft, padTop, view.paddingRight, padBottom)
        view.layoutParams?.let { params -> params.height = toolbarHeightPx(); view.layoutParams = params }
        onMetricsSynced?.invoke()
    }

    private val prefs = AppPrefs.defaultInstance()

    private val hideQuickBar get() = InputBarVisibility.hidden(prefs.keyboard.hideInputBar.getValue())

    private val clipboardSuggestion by prefs.clipboard.clipboardSuggestion

    private val clipboardSuggestionTimeout by prefs.clipboard.clipboardSuggestionTimeout

    private var clipboardTimeoutJob: Job? = null

    private var isClipboardFresh: Boolean = false
    private var isInlineSuggestionPresent: Boolean = false
    @Volatile private var latestToolbarTarget: String? = null

    @Keep
    private val onClipboardUpdateListener = ClipboardHelper.OnClipboardUpdateListener {
        if (!clipboardSuggestion) return@OnClipboardUpdateListener
        service.lifecycleScope.launch {
            if (it.text.isNullOrEmpty()) {
                isClipboardFresh = false
            } else {
                alwaysUi.clipboardUi.text.text = it.text.take(42)
                isClipboardFresh = true
                launchClipboardTimeoutJob()
            }
            evalAlwaysUiState()
        }
    }

    private fun launchClipboardTimeoutJob() {
        clipboardTimeoutJob?.cancel()
        val timeout = clipboardSuggestionTimeout * 1000L
        if (timeout < 0L) return
        clipboardTimeoutJob = service.lifecycleScope.launch {
            delay(timeout)
            isClipboardFresh = false
            clipboardTimeoutJob = null
            evalAlwaysUiState()
        }
    }

    private fun evalAlwaysUiState() {
        val newState =
            when {
                isClipboardFresh -> AlwaysUi.State.Clipboard
                isInlineSuggestionPresent -> AlwaysUi.State.InlineSuggestion
                else -> AlwaysUi.State.Toolbar
            }
        if (newState == alwaysUi.currentState) return
        alwaysUi.updateState(newState)
    }

    private val swipeDownHideKeyboardCallback: ((KeyBehavior) -> Unit) = { d ->
        if (d == KeyBehavior.SWIPE_DOWN) {
            service.requestHideSelf(0)
        }
    }

    private val alwaysUi: AlwaysUi by lazy {
        AlwaysUi(context, scope) { action ->
            when {
                action == ToolbarOverrides.BUILTIN_FLOATING -> service.toggleFloatingKeyboard()

                action.isNotEmpty() -> {
                    commonKeyboardActionListener.listener.onAction(theme.resolveAction(action))
                }

                else -> {
                    windowManager.attachWindow(SwitchOptionWindow(di))
                }
            }
        }.apply {
            onToolbarBlankDrag = { event -> service.dragKeyboardFromToolbar(event) }
            hideKeyboardButton.apply {
                setOnClickListener { service.requestHideSelf(0) }
                onSwipe = swipeDownHideKeyboardCallback
            }
            // 切换键盘时应用对应工具栏：自定义键盘用它自己的，其它键盘用全局覆盖/主题
            KeyboardWindow.onKeyboardSwitched = { target ->
                // Drop queued callbacks for earlier switches so the toolbar
                // cannot end up showing icons from the previous schema.
                latestToolbarTarget = target
                ContextCompat.getMainExecutor(context).execute {
                    if (latestToolbarTarget != target) return@execute
                    runCatching {
                        val bar = ToolbarOverrides.forKeyboard(theme, target)
                        // 双保险：算出来一个按钮都没有时保留现有工具栏，
                        // 绝不让顶栏塌成空白（切换中英时曾出现整排图标消失）。
                        if (bar.buttons.isEmpty() && bar.primaryButton == null) return@execute
                        alwaysUi.applyToolbar(bar)
                        refreshColors()
                    }
                }
            }
            clipboardUi.suggestionView.apply {
                setOnClickListener {
                    val content = ClipboardHelper.lastBean?.text
                    content?.let { service.commitText(it) }
                    dismissClipboardSuggestion()
                }
                setOnLongClickListener {
                    ClipboardHelper.lastBean?.let {
                        AppUtils.launchClipEdit(context, it.id, ClipEditActivity.FROM_CLIPBOARD)
                    }
                    true
                }
            }
            clipboardUi.dismiss.setOnClickListener {
                dismissClipboardSuggestion()
            }
        }
    }

    private fun dismissClipboardSuggestion() {
        clipboardTimeoutJob?.cancel()
        clipboardTimeoutJob = null
        isClipboardFresh = false
        evalAlwaysUiState()
    }

    private val candidateUi by lazy {
        CandidateUi(context, scope, candidate.view).apply {
            unrollButton.apply {
                onSwipe = swipeDownHideKeyboardCallback
            }
        }
    }

    private val tabUi by lazy {
        TabUi(context, scope)
    }

    private val barStateMachine =
        QuickBarStateMachine.new {
            switchUiByState(it)
        }

    val unrollButtonStateMachine =
        UnrollButtonStateMachine.new {
            when (it) {
                UnrollButtonStateMachine.State.ClickToAttachWindow -> {
                    setUnrollButtonToAttach()
                    setUnrollButtonEnabled(true)
                }

                UnrollButtonStateMachine.State.ClickToDetachWindow -> {
                    setUnrollButtonToDetach()
                    setUnrollButtonEnabled(true)
                }

                UnrollButtonStateMachine.State.Hidden -> {
                    setUnrollButtonEnabled(false)
                }
            }
        }

    private fun setUnrollButtonToAttach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(FlexboxUnrolledCandidateWindow(di))
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_more_24)
    }

    private fun setUnrollButtonToDetach() {
        candidateUi.unrollButton.setOnClickListener {
            windowManager.attachWindow(KeyboardWindow)
        }
        candidateUi.unrollButton.setIcon(R.drawable.ic_baseline_expand_less_24)
    }

    private fun setUnrollButtonEnabled(enabled: Boolean) {
        candidateUi.unrollButton.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    override fun onCandidateListUpdate(data: Candidates.Bulk) {
        barStateMachine.push(
            QuickBarStateMachine.TransitionEvent.CandidatesUpdated,
            QuickBarStateMachine.BooleanKey.CandidateEmpty to data.candidates.isEmpty(),
        )
    }

    private var normalBarState = QuickBarStateMachine.State.Always
    private var isVoiceActive = false
    private var voiceStateJob: Job? = null
    private val voiceRecordingView by lazy {
        com.osfans.trime.ime.bar.ui.VoiceRecordingView(context, scope.colors.candidateTextColor)
    }

    private fun switchUiByState(state: QuickBarStateMachine.State) {
        normalBarState = state
        if (isVoiceActive) {
            view.visibility = View.VISIBLE
            view.displayedChild = 3
            return
        }
        val index = state.ordinal
        val keepCandidates = state == QuickBarStateMachine.State.Candidate && !InputBarVisibility.candidatesFloating()
        view.visibility = if (!hideQuickBar || keepCandidates || state == QuickBarStateMachine.State.Tab) View.VISIBLE else View.GONE
        if (view.displayedChild == index) return
        val new = view.getChildAt(index)
        if (new != tabUi.root) {
            tabUi.setBackButtonOnClickListener { }
            tabUi.setTitle("")
            tabUi.removeExternal()
        }
        view.displayedChild = index
    }

    private fun customBarBackground(): android.graphics.drawable.Drawable? {
        if (!theme.isCustom) return null
        val id = com.osfans.trime.data.keymap.CustomKeyboards.resolveId(KeyboardWindow.lastAttachedId)
        return com.osfans.trime.data.keymap.CustomKeyboards.get(id)?.toolbarBackgroundFor(context)
            ?.drawable(context, context.resources.displayMetrics.widthPixels)
    }

    private fun barBackground(host: View): android.graphics.drawable.Drawable? {
        // 面板渐变是整块一张：工具栏和候选栏都不画自己的底色，
        // 否则会在渐变上叠出两条独立色块，交界处一眼就能看出断开。
        if (com.osfans.trime.ime.appearance.SurfaceAppearance.panelGradient(theme.isCustom) != null) return null
        // 真模糊背板同理：整块面板共用系统糊出来的那一层，工具栏再有自己的底色，
        // 顶栏就会是一块不透明的色条压在模糊上，和下面的键盘区分成两截。
        if (com.osfans.trime.ime.appearance.SurfaceAppearance.panelBackdrop(theme.isCustom)?.enabled == true) return null
        val appearance = com.osfans.trime.ime.appearance.SurfaceAppearance.current(theme.isCustom)
        val unified = com.osfans.trime.ime.appearance.SurfaceAppearance.unifiedBackground(theme.isCustom)
        val custom = customBarBackground()
        // 用户在「工具栏背景」里选了图片时，原样显示这张图。
        // 走 SurfaceDrawable 会用 effectiveBar.background 的纯色 fill 盖住 fallback，
        // 再叠一层圆角/特效，结果就是「设置里明明有图片，键盘上却是一块白板」。
        val imagePicked = custom != null &&
            custom !is android.graphics.drawable.GradientDrawable &&
            custom !is android.graphics.drawable.ColorDrawable
        if ((!unified || appearance.retainToolbarBackground) && imagePicked) return custom
        val fallback = if (unified && !appearance.retainToolbarBackground) null else custom ?: scope.decorDrawable(
            "candidate_background", "candidate_border_color", context.dp(theme.style.candidateBorder), context.dp(theme.style.candidateBorderRound))
        return if (appearance.barEffects.customized || appearance.effectiveBar.customized)
            com.osfans.trime.ime.appearance.SurfaceDrawable(host, fallback,
                appearance.effectiveBar,
                defaultRadius = theme.style.candidateBorderRound.toFloat(), inset = 0f, effects = appearance.barEffects)
        else fallback
    }

    val view by lazy {
        com.osfans.trime.ime.bar.ui.ToolbarShadowAnimator(context).apply {
            val (padTop, padBottom) = toolbarPadsPx()
            // 左右留白当时还是 0（InputView 尚未算过圆角让位），保留现值即可，别整行清零。
            setPadding(paddingLeft, padTop, paddingRight, padBottom)
            visibility =
                if (hideQuickBar) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
            background = barBackground(this)
            add(alwaysUi.root, lParams(matchParent, matchParent))
            add(candidateUi.root, lParams(matchParent, matchParent))
            add(tabUi.root, lParams(matchParent, matchParent))
            add(voiceRecordingView, lParams(matchParent, matchParent))
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    voiceStateJob?.cancel()
                    voiceStateJob = service.lifecycleScope.launch {
                        service.voiceRecordingState.collect { state ->
                            isVoiceActive = state.active
                            voiceRecordingView.update(state)
                            switchUiByState(normalBarState)
                        }
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    voiceStateJob?.cancel()
                    voiceStateJob = null
                    isVoiceActive = false
                    voiceRecordingView.update(com.osfans.trime.ime.voice.VoiceRecordingState())
                }
            })

            evalAlwaysUiState()
            ClipboardHelper.addOnUpdateListener(onClipboardUpdateListener)
            syncToolbarOptionStates()
        }
    }

    /** Restyles the bar after a scheme switch without rebuilding its views. */
    fun refreshColors() {
        view.background = barBackground(view)
        alwaysUi.refreshColors()
        candidateUi.refreshColors()
        // the compact candidate rows live inside candidateUi and re-bind via the delegate
        candidate.refreshColors()
        tabUi.refreshColors()
        voiceRecordingView.setForegroundColor(scope.colors.candidateTextColor)
    }

    override fun onStartInput(info: EditorInfo) {
        evalAlwaysUiState()
    }

    override fun onWindowAttached(window: BoardWindow) {
        // 键盘切换后状态栏高度/上下留白可能随布局变化，同步容器尺寸。
        syncToolbarMetrics()
        if (window is BoardWindow.BarBoardWindow) {
            tabUi.setTitle(window.title)
            window.onCreateBarView()?.let { tabUi.addExternal(it, window.showTitle) }
            tabUi.setBackButtonOnClickListener {
                windowManager.attachWindow(KeyboardWindow)
            }
            barStateMachine.push(QuickBarStateMachine.TransitionEvent.BarBoardWindowAttached)
        }
    }

    override fun onWindowDetached(window: BoardWindow) {
        barStateMachine.push(QuickBarStateMachine.TransitionEvent.WindowDetached)
    }

    private val suggestionSize by lazy {
        Size(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(themedHeight))
    }

    private val directExecutor by lazy {
        Executor { it.run() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            isInlineSuggestionPresent = false
            return true
        }
        var pinned: InlineSuggestion? = null
        val scrollable = mutableListOf<InlineSuggestion>()
        var extraPinnedCount = 0
        suggestions.forEach {
            if (it.info.isPinned) {
                if (pinned == null) {
                    pinned = it
                } else {
                    scrollable.add(extraPinnedCount++, it)
                }
            } else {
                scrollable.add(it)
            }
        }
        service.lifecycleScope.launch {
            alwaysUi.inlineSuggestionsUi.setPinnedView(
                pinned?.let { inflateInlineContentView(it) },
            )
        }
        service.lifecycleScope.launch {
            val views = scrollable.map { s ->
                service.lifecycleScope.async {
                    inflateInlineContentView(s)
                }
            }.awaitAll()
            alwaysUi.inlineSuggestionsUi.setScrollableViews(views)
        }
        isInlineSuggestionPresent = true
        evalAlwaysUiState()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun inflateInlineContentView(suggestion: InlineSuggestion): InlineContentView? = suspendCancellableCoroutine { c ->
        // callback view might be null
        suggestion.inflate(context, suggestionSize, directExecutor) { v ->
            c.resume(v)
        }
    }

    /**
     * Seed the toolbar toggle buttons with the current value of their rime
     * options. Rime access stays here in the delegate: the buttons themselves
     * are pure views and only react to [updateButtonsStyle].
     */
    private fun syncToolbarOptionStates() {
        val options = alwaysUi.toggleOptions()
        if (options.isEmpty()) return
        rime.launchOnReady { api ->
            val states = options.associateWith { api.getRuntimeOption(it) }
            ContextCompat.getMainExecutor(context).execute {
                states.forEach { (option, enabled) ->
                    alwaysUi.updateButtonsStyle(option, enabled)
                }
            }
        }
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        alwaysUi.updateButtonsStyle(value.option, value.value)
    }
}
