/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.keymap.ComboKeys
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.util.any
import com.osfans.trime.util.findSectionFrom
import com.osfans.trime.util.forceShowSelf
import com.osfans.trime.util.monitorCursorAnchor
import com.osfans.trime.util.styledFloat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/** [輸入法][InputMethodService]主程序  */

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val prefs = AppPrefs.defaultInstance()
    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private lateinit var lastKnownConfig: Configuration
    private var inputView: InputView? = null
    private val sentenceSuggestions = lazy { com.osfans.trime.ime.sentences.SentenceSuggestionController(this) }
    @Keep
    private val sentencePreferenceListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        if (sentenceSuggestions.isInitialized()) sentenceSuggestions.value.preferenceChanged()
    }
    fun sentenceSuggestionAnchor(outBounds: android.graphics.Rect): Boolean = inputView?.getSentenceSuggestionAnchor(outBounds) == true
    fun sentenceSuggestionObstruction(outBounds: android.graphics.Rect): Boolean {
        outBounds.setEmpty()
        val view = candidatesView?.takeIf { it.isShown && it.width > 0 && it.height > 0 } ?: return false
        val location = IntArray(2); view.getLocationInWindow(location)
        outBounds.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
        return true
    }
    fun requestSentenceCompletion(data: RimeMessage.SentenceCompletionMessage.Data) {
        postRimeJob { completeSentence(data) }
    }
    private val petCompanionDelegate=lazy { com.osfans.trime.ui.pets.PetCompanionController(this) }
    private fun syncPetCompanion() {
        val current=inputView ?: return
        val state=com.osfans.trime.data.pets.PetHouse.state.value
        if(!petCompanionDelegate.isInitialized() && (!state.started || state.mode==0)) return
        if(isInputViewShown) petCompanionDelegate.value.showForInput(current,currentInputEditorInfo)
        else if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.hideInput()
    }
    fun preparePetCompanion() { if(!petCompanionDelegate.isInitialized() && isInputViewShown) syncPetCompanion() }
    fun togglePetCompanion() { syncPetCompanion();petCompanionDelegate.value.toggle() }
    fun notifyPetKey(keyboard:com.osfans.trime.ime.keyboard.Keyboard,key:com.osfans.trime.ime.keyboard.Key,down:Boolean,cancel:Boolean=false) {
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.key(keyboard,key,down,cancel)
    }
    fun notifyPetKeyboard(keyboard:com.osfans.trime.ime.keyboard.Keyboard) {
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.keyboardChanged(keyboard)
    }
    fun typingGameKeyboardBoundsOnScreen(outBounds: android.graphics.Rect): Boolean {
        val view = inputView ?: return false
        if (!view.getSentenceSuggestionAnchor(outBounds)) return false
        val onScreen = IntArray(2); val inWindow = IntArray(2)
        view.getLocationOnScreen(onScreen); view.getLocationInWindow(inWindow)
        outBounds.offset(onScreen[0] - inWindow[0], onScreen[1] - inWindow[1])
        return true
    }
    fun petKeyboardTopOnScreen():Int {
        val view=inputView?.keyboardView ?: return (resources.displayMetrics.heightPixels*.72f).toInt()
        val location=IntArray(2);view.getLocationOnScreen(location);return location[1]
    }
    override fun onWindowShown() {
        super.onWindowShown()
        inputView?.post { syncPetCompanion() }
        // 窗口记录每次显示都可能被系统重建，底色和轮廓要按当前设置重新落一遍。
        inputView?.post { inputView?.refreshSurfaceCorners() }
    }
    override fun onWindowHidden() {
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.hideInput()
        super.onWindowHidden()
    }

    /** 输入栏（悬浮收起态工具栏）拖动移动键盘的入口。 */
    fun dragKeyboardFromToolbar(event: MotionEvent): Boolean =
        inputView?.dragFloatingKeyboardFromToolbar(event) ?: false
    private var candidatesView: CandidatesView? = null
    private val navBarManager = NavigationBarManager()
    private val inputDeviceManager = InputDeviceManager { useVirtualKeyboard, useCandidatesView ->
        postRimeJob {
            setCandidatePagingMode(useCandidatesView)
        }
        currentInputConnection?.monitorCursorAnchor(useCandidatesView)
        val scope = themeScope ?: return@InputDeviceManager
        window.window?.let {
            navBarManager.evaluate(it, useVirtualKeyboard, scope.colors)
        }
    }
    private val rimeIntentReceiver = RimeIntentReceiver()

    private var lastCommittedText: String = ""

    private var composingText: String = ""

    private var cursorUpdateIndex = 0

    private val recreateInputViewPrefs: Array<PreferenceDelegate<*>> = arrayOf(
        prefs.keyboard.landscapeSizeEnabled,
        prefs.keyboard.landscapeWidth,
        prefs.keyboard.landscapeHeight,
        prefs.keyboard.landscapePosition,
        prefs.keyboard.landscapeOpacity,
        prefs.keyboard.expandKeypressArea,
        prefs.keyboard.floatingKeyboard,
        prefs.keyboard.floatingKeyboardAutoLandscape,
        prefs.keyboard.floatingKeyboardWidth,
        prefs.keyboard.floatingKeyboardHeight,
        prefs.keyboard.floatingKeyboardKeepRatio,
        prefs.keyboard.floatingKeyboardCornerRadius,
        prefs.keyboard.floatingPreeditOffsetX,
        prefs.keyboard.floatingPreeditOffsetY,
        prefs.keyboard.oneHandMode,
        prefs.keyboard.oneHandBinding,
        prefs.keyboard.floatingBinding,
        prefs.keyboard.oneHandWidth,
        prefs.keyboard.oneHandSide,
        prefs.keyboard.hideKeySymbol,
        prefs.keyboard.hideKeyHint,
        prefs.keyboard.hideInputBar,
        prefs.keyboard.unifiedBackground,
        prefs.keyboard.keyBackground,
        prefs.keyboard.surfaceAppearance,
        prefs.keyboard.customKeyBackground,
        prefs.keyboard.customSurfaceAppearance,
        prefs.keyboard.customUnifiedBackground,
        prefs.advanced.ignoreSystemGestureInsets,
    )

    private val themeScope: ThemeScope?
        get() = ColorManager.currentScope()

    @Keep
    private val recreateInputViewListener =
        PreferenceDelegate.OnChangeListener<Any> { key, _ ->
            if (updatingKeyboardMode) return@OnChangeListener
            if (key == prefs.keyboard.oneHandSide.key && prefs.keyboard.oneHandMode.getValue() != 0) {
                val target = when (prefs.keyboard.oneHandSide.getValue()) {
                    AppPrefs.Keyboard.OneHandSide.LEFT -> 1
                    AppPrefs.Keyboard.OneHandSide.RIGHT -> 2
                    AppPrefs.Keyboard.OneHandSide.BOTH -> prefs.keyboard.oneHandMode.getValue()
                }
                // 方向变化后重新贴边：清掉拖拽位置，让单手卡片按新手势方向吸附。
                prefs.keyboard.oneHandOffsetX.setValue(0)
                applyKeyboardMode(false, target)
                return@OnChangeListener
            }
            syncFloatingRatio(key)
            if(key in setOf(prefs.keyboard.floatingKeyboard.key, prefs.keyboard.floatingKeyboardAutoLandscape.key,
                    prefs.keyboard.oneHandMode.key, prefs.keyboard.oneHandBinding.key, prefs.keyboard.floatingBinding.key)) syncModeBinding()
            themeScope?.let { replaceInputView(it) }
            inputDeviceManager.refreshPolicy()
        }

    @Keep
    private val recreateCandidatesViewListener =
        PreferenceDelegateProvider.OnChangeListener { key ->
            themeScope?.let {
                if (key == prefs.candidates.mode.key) replaceInputViews(it) else replaceCandidateView(it)
            }
            inputDeviceManager.refreshPolicy()
        }

    @Keep
    private val onThemeChangeListener =
        ThemeManager.OnThemeChangeListener {
            themeScope?.let { replaceInputViews(it) }
        }

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            ContextCompat.getMainExecutor(this).execute {
                // A scheme-only change restyles the tree in place; theme
                // switches rebuild it through onThemeChangeListener.
                val scope = themeScope ?: return@execute
                inputView?.refreshColors()
                candidatesView?.refreshColors()
                window.window?.let {
                    navBarManager.evaluate(it, inputDeviceManager.useVirtualKeyboard, scope.colors)
                }
            }
        }

    private fun postJob(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(block: suspend RimeApi.() -> Unit) = postJob(rime.lifecycleScope) { rime.runOnReady(block) }

    private suspend fun updateRimeOption(api: RimeApi) {
        try {
            api.setRuntimeOption("soft_cursor", prefs.keyboard.useSoftCursor.getValue()) // 軟光標
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun registerReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private var transparentBackdrop = false

    /**
     * 系统跨窗口模糊总开关。省电模式 / 开发者选项 / 播放视频时系统会临时关掉，
     * 关掉再打开后旧的 BackgroundBlurDrawable 不会被重新登记，必须强制重申请。
     */
    private val crossWindowBlurListener = java.util.function.Consumer<Boolean> { _ ->
        com.osfans.trime.ime.appearance.SystemBackdrop.bumpEpoch()
        inputView?.refreshBackdrop()
        candidatesView?.refreshBackdrop()
        com.osfans.trime.util.DiagnosticLog.event("appearance", "cross_window_blur_changed")
    }

    private fun registerCrossWindowBlurListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            getSystemService(WindowManager::class.java)?.addCrossWindowBlurEnabledListener(
                ContextCompat.getMainExecutor(this), crossWindowBlurListener,
            )
        }.onFailure { Timber.w(it, "Could not register cross window blur listener") }
    }

    private fun unregisterCrossWindowBlurListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            getSystemService(WindowManager::class.java)
                ?.removeCrossWindowBlurEnabledListener(crossWindowBlurListener)
        }
    }

    /**
     * 输入法窗口的底色。
     *
     * 「键盘四角」是把键盘容器本身裁成圆角，裁掉的那四块只有露出后面的应用才像圆角；
     * 而 IME 窗口默认带主题的 windowBackground（不透明），裁完露的是窗口底色，和键盘
     * 底色接近，表现出来就是「这个开关没生效」。所以开圆角时把窗口底色清成透明，
     * 关掉时再还原主题里的那一份，避免把原本靠窗口底色兜底的区域变成透空。
     */
    fun setInputWindowBackdropTransparent(transparent: Boolean, force: Boolean = false) {
        if (!force && transparentBackdrop == transparent) return
        val win = window?.window ?: return
        transparentBackdrop = transparent
        if (transparent) {
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        } else {
            val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
            val backdrop = attrs.getDrawable(0)
            attrs.recycle()
            win.setBackgroundDrawable(backdrop)
        }
    }

    override fun onCreate() {
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.run { messageFlow }.collect {
                handleRimeMessage(it)
            }
        }
        prefs.general.sentenceLearningEnabled.registerOnChangeListener(sentencePreferenceListener)
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        // Theme loading only reads files, so it must not wait for the engine.
        // A slow or failed Rime start used to leave themeScope null, which made
        // onCreateInputView return null and the keyboard fail to appear at all.
        runCatching { ThemeManager.init(resources.configuration) }
            .onFailure { Timber.e(it, "Could not initialize theme before rime is ready") }
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        ColorManager.addOnChangedListener(onColorChangeListener)
        // Retry once the engine is ready if storage was not available earlier.
        lifecycleScope.launch {
            rime.runOnReady {
                if (ColorManager.currentScope() == null) {
                    runCatching { ThemeManager.init(resources.configuration) }
                        .onFailure { Timber.e(it, "Could not initialize theme after rime became ready") }
                }
            }
        }
        InputFeedbackManager.init(this)
        registerReceiver()
        super.onCreate()
        com.osfans.trime.data.sync.ClipboardSync.start()
        Timber.d("onCreate")
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = Configuration(resources.configuration)
        registerCrossWindowBlurListener()
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CommitTextMessage -> {
                if (!it.data.text.isNullOrEmpty()) {
                    commitText(it.data.text, fromEngine = true)
                }
            }

            is RimeMessage.SentenceCompletionMessage -> sentenceSuggestions.value.complete(it.data)
            is RimeMessage.CompositionMessage -> sentenceSuggestions.value.composition(it.data)

            is RimeMessage.InlinePreeditMessage -> {
                updateComposingText(it.data)
            }

            is RimeMessage.KeyMessage ->
                it.data.let msg@{
                    if (com.osfans.trime.ime.clipboard.ClipboardSearchBridge.active) {
                        if (it.value.keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            routeSearchKey(it.value.keyCode, it.modifiers.metaState)
                        } else if (!it.modifiers.ctrl && !it.modifiers.alt && !it.modifiers.meta &&
                            Character.isValidCodePoint(it.value.value) && !Character.isISOControl(it.value.value) &&
                            it.value.value !in 0xFF00..0xFFFF
                        ) {
                            com.osfans.trime.ime.clipboard.ClipboardSearchBridge.acceptText(String(Character.toChars(it.value.value)))
                        }
                        return@msg
                    }
                    if (it.isVirtual) {
                        when (it.value.value) {
                            RimeKeyMapping.RimeKey_Return ->
                                if (it.modifiers.isReturnChord()) {
                                    sendDownUpKeyEvent(
                                        it.value.keyCode,
                                        it.modifiers.metaState or meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                } else {
                                    handleReturnKey()
                                }

                            else -> {
                                val keyCode = it.value.keyCode
                                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                                    // recognized keyCode
                                    sendDownUpKeyEvent(
                                        keyCode,
                                        it.modifiers.metaState or meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                    if (it.modifiers.ctrl && keyCode == KeyEvent.KEYCODE_C) clearTextSelection()
                                } else {
                                    if (Character.isValidCodePoint(it.value.value) && it.value.value > 0 &&
                                        it.value.value !in 0xD800..0xDFFF && !Character.isISOControl(it.value.value)) {
                                        runCatching {
                                            commitText(Character.toString(it.value.value))
                                        }.getOrElse { t -> Timber.w(t, "Unhandled Virtual KeyEvent: $it") }
                                    } else {
                                        Timber.w("Unhandled Virtual KeyEvent: $it")
                                    }
                                }
                            }
                        }
                    } else {
                        val keyCode = it.value.keyCode
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // recognized keyCode
                            val eventTime = SystemClock.uptimeMillis()
                            if (it.modifiers.release) {
                                sendUpKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            } else {
                                sendDownKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            }
                        } else {
                            if (!it.modifiers.release && it.value.value > 0) {
                                runCatching {
                                    commitText(Character.toString(it.value.value))
                                }.getOrElse { t -> Timber.w(t, "Unhandled Rime KeyEvent: $it") }
                            } else {
                                Timber.w("Unhandled Rime KeyEvent: $it")
                            }
                        }
                    }
                }

            else -> {}
        }
    }

    private fun replaceInputView(scope: ThemeScope): InputView {
        val began = android.os.SystemClock.elapsedRealtime()
        val previous = com.osfans.trime.ime.keyboard.KeyboardWindow.instance?.captureRuntimeState()
        val newInputView = InputView(this, rime, scope)
        setInputView(newInputView)
        inputDeviceManager.setInputView(newInputView)
        inputView = newInputView
        newInputView.post { if(inputView===newInputView) syncPetCompanion() }
        previous?.let(newInputView::restoreInputState)
        requestInputInsetsUpdate()
        com.osfans.trime.util.DiagnosticLog.event("keyboard", "rebuilt", "ms=${android.os.SystemClock.elapsedRealtime() - began}")
        return newInputView
    }

    fun toggleCandidatesWindow() {
        prefs.candidates.mode.run { setValue(if (getValue() == com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.DISABLED)
            com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.ALWAYS_SHOW else com.osfans.trime.ime.candidates.popup.PopupCandidatesMode.DISABLED) }
    }
    fun toggleInputBar() {
        prefs.keyboard.hideInputBar.run { setValue(!getValue()) }
    }

    fun toggleFloatingBall(): Boolean {
        if (!com.osfans.trime.ime.bar.InputBarVisibility.candidatesFloating()) return false
        prefs.candidates.floatingBall.run { setValue(!getValue()) }
        return true
    }

    /** Floating/one-hand cards temporarily keep the toolbar visible. */
    fun inputBarHiddenForDisplay(): Boolean =
        com.osfans.trime.ime.bar.InputBarVisibility.hidden(prefs.keyboard.hideInputBar.getValue(),
            com.osfans.trime.ime.keyboard.KeyboardPrefs.run { isFloatingEffective() })
    fun openKeyboardMenu() {
        showWindow(true)
        inputView?.post { inputView?.showKeyboardMenu() }
    }
    fun performFloatingToolbarAction(action: String) { inputView?.performToolbarAction(action) }
    fun refreshFloatingToolbar(runtimeId: String) { candidatesView?.refreshToolbar(runtimeId) }

    private fun replaceCandidateView(scope: ThemeScope): CandidatesView {
        val newCandidatesView = CandidatesView(this, rime, scope)
        contentView.removeView(candidatesView)
        contentView.addView(newCandidatesView)
        inputDeviceManager.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        if (decorLocationUpdated) {
            candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
        } else {
            candidatesView?.updateCursorAnchor(contentSize)
        }
        return newCandidatesView
    }

    private fun replaceInputViews(scope: ThemeScope) {
        navBarManager.evaluate(window.window!!, inputDeviceManager.useVirtualKeyboard, scope.colors)
        replaceInputView(scope)
        replaceCandidateView(scope)
        inputView?.updateEnterKeyLabel(currentInputEditorInfo)
    }

    private val mutableVoiceRecordingState = kotlinx.coroutines.flow.MutableStateFlow(com.osfans.trime.ime.voice.VoiceRecordingState())
    val voiceRecordingState: kotlinx.coroutines.flow.StateFlow<com.osfans.trime.ime.voice.VoiceRecordingState>
        get() = mutableVoiceRecordingState

    fun updateVoiceRecording(recording: Boolean, level: Float = 0f) {
        mutableVoiceRecordingState.value = com.osfans.trime.ime.voice.VoiceRecordingState(
            recording, if (recording && level.isFinite()) level.coerceIn(0f, 1f) else 0f,
        )
    }

    fun updateVoicePhase(phase: com.osfans.trime.ime.voice.VoiceRecordingState.Phase, level: Float = 0f) {
        val recording = phase == com.osfans.trime.ime.voice.VoiceRecordingState.Phase.RECORDING
        mutableVoiceRecordingState.value = com.osfans.trime.ime.voice.VoiceRecordingState(
            recording, if (recording && level.isFinite()) level.coerceIn(0f,1f) else 0f, phase)
    }

    private val builtInVoiceDelegate = lazy { com.osfans.trime.ime.voice.builtin.BuiltInVoiceController(this) }
    val builtInVoice by builtInVoiceDelegate

    /** 所有语音按键统一使用内置引擎。 */
    val voiceInput: com.osfans.trime.ime.voice.VoiceInput
        get() = builtInVoice

    /**
     * Cancel an active recording without constructing the controller.
     * Idle key presses used to initialize it just to call cancel().
     */
    fun cancelVoiceIfActive() {
        if (builtInVoiceDelegate.isInitialized()) builtInVoice.cancel()
    }

    private fun disposeVoiceIfInitialized() {
        if (builtInVoiceDelegate.isInitialized()) builtInVoice.dispose()
    }

    override fun onDestroy() {
        unregisterCrossWindowBlurListener()
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.dispose()
        com.osfans.trime.data.sync.ClipboardSync.stop()
        disposeVoiceIfInitialized()
        com.osfans.trime.data.stats.InputStats.endSession()
        InputFeedbackManager.destroy()
        inputView?.releaseKeyboard()
        inputView = null
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.general.sentenceLearningEnabled.unregisterOnChangeListener(sentencePreferenceListener)
        if (sentenceSuggestions.isInitialized()) sentenceSuggestions.value.dispose()
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        unregisterReceiver(rimeIntentReceiver)
        RimeDaemon.destroySession(javaClass.name)
    }

    private fun handleReturnKey() {
        sentenceSuggestions.value.finishSentence()
        currentInputEditorInfo.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL ||
                imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)
            ) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                return
            }
            if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                currentInputConnection.performEditorAction(actionId)
                return
            }
            when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED,
                EditorInfo.IME_ACTION_NONE,
                -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)

                else -> currentInputConnection.performEditorAction(action)
            }
        }
    }

    /**
     * https://github.com/fcitx5-android/fcitx5-android/blob/fe3a618c8fd18842305d2f8ec2880fcc67ec1679/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L523-#L547
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        postRimeJob { clearComposition() }
        val keyboardUiModeMask = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        if (diff and keyboardUiModeMask != diff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig.setTo(newConfig)
        // 整套键盘的「夜间换另一套」不能只靠应用层那条回调：它不一定先到。
        // syncNightMode 幂等（日夜位没翻就不动），两边都调不会重复重建。
        val night = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (com.osfans.trime.data.keymap.CustomKeyboards.syncNightMode(night)) {
            com.osfans.trime.data.theme.ThemeManager.requestLayoutSwitch()
        }
        syncModeBinding()
        // 转屏时 IME 窗口通常不 detach/attach：ViewRootImpl 换了但 View 还在，
        // 旧的模糊 drawable 失效、窗口透明底色也可能被新窗口记录重置。
        // 只在尺寸/方向真的变了才强制重铺：每次 createBackgroundBlurDrawable
        // 都会登记一块模糊区域，键盘隐藏、语言切换不该反复重建、越积越多。
        val shapeMask = ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE or
            ActivityInfo.CONFIG_SCREEN_LAYOUT or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE or ActivityInfo.CONFIG_DENSITY
        if (diff and shapeMask != 0) {
            inputView?.post {
                inputView?.refreshBackdrop()
                candidatesView?.refreshBackdrop()
            }
        }
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] =
            if (inputDeviceManager.useVirtualKeyboard && inputView?.isFloatingKeyboardEnabled != true) {
                inputViewLocation[1].toFloat()
            } else {
                contentView.height.toFloat()
            }
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = RectF()

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        // update anchorPosition
        if (bounds == null) {
            // composing is disabled in target app or trime settings
            // use the position of the insertion marker instead
            anchorPosition.top = info.insertionMarkerTop
            anchorPosition.left = info.insertionMarkerHorizontal
            anchorPosition.bottom = info.insertionMarkerBottom
            anchorPosition.right = info.insertionMarkerHorizontal
        } else {
            // for different writing system (e.g. right to left languages),
            // we have to calculate the correct RectF
            val horizontal = if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition.top = bounds.top
            anchorPosition.left = horizontal
            anchorPosition.bottom = bounds.bottom
            anchorPosition.right = horizontal
        }
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        info.matrix.mapRect(anchorPosition)
        val (dX, dY) = decorLocation
        anchorPosition.offset(-dX, -dY)
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        cursorUpdateIndex += 1
        sentenceSuggestions.value.selection(oldSelStart, newSelStart, newSelEnd, candidatesStart)
        builtInVoice.selectionChanged(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (builtInVoice.isActive) return
        handleCursorUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        updateIndex: Int,
    ) {
        if (newSelStart != newSelEnd) return
        if (candidatesStart == candidatesEnd) return
        if (newSelStart in candidatesStart..candidatesEnd) {
            val position = newSelStart - candidatesStart
            if (position != composingText.length) {
                postRimeJob {
                    if (updateIndex != cursorUpdateIndex) return@postRimeJob
                    Timber.d("handleCursorUpdate: move rime cursor to $position")
                    moveCursorPos(position)
                }
            }
        } else {
            Timber.d("handleCursorUpdate: clear composition")
            postRimeJob {
                clearComposition()
            }
        }
    }

    private val inputViewLocation = intArrayOf(0, 0)

    private var updatingKeyboardMode = false
    private var modeBindingController: KeyboardModeBindingController? = null
    private fun syncModeBinding() {
        if(!::rime.isInitialized) return
        val mode = com.osfans.trime.ime.keyboard.KeyboardPrefs.run {
            when { oneHandActive() -> "oneHand"; isFloatingEffective() -> "floating"; else -> "normal" }
        }
        val controller = modeBindingController ?: KeyboardModeBindingController(this, rime).also { modeBindingController = it }
        controller.request(mode)
    }

    private fun syncFloatingRatio(changedKey: String) {
        if (!prefs.keyboard.floatingKeyboardKeepRatio.getValue()) return
        val tier = when (changedKey) {
            AppPrefs.Keyboard.FLOATING_KEYBOARD_HEIGHT -> prefs.keyboard.floatingKeyboardHeight.getValue()
            AppPrefs.Keyboard.FLOATING_KEYBOARD_WIDTH,
            AppPrefs.Keyboard.FLOATING_KEYBOARD_KEEP_RATIO -> prefs.keyboard.floatingKeyboardWidth.getValue()
            else -> return
        }.coerceIn(40, 100)
        updatingKeyboardMode = true
        try {
            prefs.keyboard.floatingKeyboardWidth.sharedPreferences.edit()
                .putInt(prefs.keyboard.floatingKeyboardWidth.key, tier)
                .putInt(prefs.keyboard.floatingKeyboardHeight.key, tier).apply()
        } finally { updatingKeyboardMode = false }
    }

    /** Switch the two mutually exclusive modes in one preference transaction and one rebuild. */
    private fun applyKeyboardMode(floating: Boolean, hand: Int, disableAutoLandscape: Boolean = false) {
        updatingKeyboardMode = true
        try {
            prefs.keyboard.floatingKeyboard.sharedPreferences.edit()
                .putBoolean(prefs.keyboard.floatingKeyboard.key, floating)
                .putInt(prefs.keyboard.oneHandMode.key, hand)
                // An explicit switch out overrides automatic landscape floating.
                .also { if (disableAutoLandscape) it.putBoolean(prefs.keyboard.floatingKeyboardAutoLandscape.key, false) }
                .apply()
        } finally { updatingKeyboardMode = false }
        syncModeBinding()
        themeScope?.let(::replaceInputView)
        forceShowSelf()
    }

    fun toggleFloatingKeyboard() {
        val enable = inputView?.isFloatingKeyboardEnabled != true
        applyKeyboardMode(enable, 0, disableAutoLandscape = !enable && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    fun toggleOneHandMode() = applyKeyboardMode(false, com.osfans.trime.ime.keyboard.nextOneHandMode(
        prefs.keyboard.oneHandMode.getValue(), prefs.keyboard.oneHandSide.getValue(),
    ))

    fun updateFloatingKeyboardSize(widthPercent: Int, heightPercent: Int) {
        updatingKeyboardMode = true
        try {
            val landscape = com.osfans.trime.ime.keyboard.KeyboardPrefs.run { landscapeSizing() }
            val width = landscape?.width ?: prefs.keyboard.floatingKeyboardWidth
            val height = landscape?.height ?: prefs.keyboard.floatingKeyboardHeight
            width.sharedPreferences.edit()
                .putInt(width.key, widthPercent.coerceIn(40, 100))
                .putInt(height.key, heightPercent.coerceIn(if (landscape != null) 20 else 40, if (landscape != null) 70 else 100))
                .apply()
        } finally { updatingKeyboardMode = false }
    }

    fun updateLandscapeKeyboardPosition(position: Int, offsetY: Int) {
        val size = com.osfans.trime.ime.keyboard.KeyboardPrefs.run { landscapeSizing() } ?: return
        updatingKeyboardMode = true
        try {
            size.position.sharedPreferences.edit().putInt(size.position.key, position.coerceIn(0, 100))
                .putInt(size.offsetY.key, offsetY).apply()
        } finally { updatingKeyboardMode = false }
    }

    fun requestInputInsetsUpdate() { if (::decorView.isInitialized) decorView.requestLayout() }

    fun refreshCandidatesGeometry() {
        val current = inputView ?: return
        val bounds = android.graphics.Rect()
        val floating = inputDeviceManager.useVirtualKeyboard && current.getFloatingKeyboardBoundsInWindow(bounds)
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] = if (floating || !inputDeviceManager.useVirtualKeyboard) contentView.height.toFloat() else {
            current.keyboardView.getLocationInWindow(inputViewLocation)
            inputViewLocation[1].toFloat()
        }
        val obstruction = if (floating) {
            val origin = IntArray(2)
            contentView.getLocationInWindow(origin)
            android.graphics.RectF(bounds).apply { offset(-origin[0].toFloat(), -origin[1].toFloat()) }
        } else null
        candidatesView?.updateFloatingGeometry(contentSize, obstruction)
    }

    /** 剪贴板/收藏搜索浮层（dahiPLUS 移植）。 */
    fun showBottomOverlay(
        view: View,
        heightPx: Int,
    ) {
        inputView?.showBottomOverlay(view, heightPx)
    }

    fun hideBottomOverlay() {
        inputView?.hideBottomOverlay()
    }

    override fun onComputeInsets(outInsets: Insets) {
        val floatingBounds = android.graphics.Rect()
        if (inputDeviceManager.useVirtualKeyboard && inputView?.getFloatingKeyboardBoundsInWindow(floatingBounds) == true) {
            outInsets.apply {
                if (inputView?.isOneHandMode == true) {
                    // Only the panel pushes the editor up. The preedit stays above the app
                    // as an overlay, just like docked mode, while retaining its touch region.
                    val panelBounds = android.graphics.Rect()
                    inputView?.getFloatingKeyboardPanelBoundsInWindow(panelBounds)
                    contentTopInsets = panelBounds.top
                    visibleTopInsets = panelBounds.top
                } else {
                    contentTopInsets = decorView.height
                    visibleTopInsets = decorView.height
                }
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(floatingBounds)
                inputView?.addResizeHandlesToTouchableRegion(touchableRegion)
            }
            refreshCandidatesGeometry()
            return
        }
        if (inputDeviceManager.useVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
        refreshCandidatesGeometry()
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onCreateInputView(): View? {
        Timber.d("onCreateInputView")
        val scope = themeScope
        if (scope == null) {
            Timber.w("ThemeScope is not ready yet; deferring input view creation")
            return null
        }
        replaceInputViews(scope)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val inputArea = contentView.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        sentenceSuggestions.value.start(attribute, restarting)
        com.osfans.trime.data.stats.InputStats.setWordCloudEditor(attribute)
        com.osfans.trime.data.pets.PetHouse.editor(attribute)
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.editor(attribute)
        cancelVoiceIfActive()
        composingText = ""
        Timber.d("onStartInput: restarting=$restarting")
        val isNullType = attribute.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
            setNullInputType(isNullType)
        }
    }

    private val inlineSuggestions by prefs.general.inlineSuggestions

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inlineSuggestions || !inputDeviceManager.useVirtualKeyboard) return null
        val colors = themeScope?.colors ?: return null
        return InlineSuggestions.createRequest(this, colors)
    }

    @SuppressLint("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inputDeviceManager.useVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    var isRestartingInputView: Boolean = false
        private set

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        inputView?.post { syncPetCompanion() }
        isRestartingInputView = restarting
        Timber.d("onStartInputView: restarting=$restarting")
        com.osfans.trime.data.sync.ClipboardSync.setWindowVisible(true)
        val began = android.os.SystemClock.elapsedRealtime()
        inputView?.post { com.osfans.trime.util.DiagnosticLog.event("keyboard", "shown", "ms=${android.os.SystemClock.elapsedRealtime() - began} restarting=$restarting") }
        com.osfans.trime.ime.keyboard.KeyboardWindow.instance?.setVideoActive(true)
        inputView?.setUnifiedVideoActive(true)
        InputFeedbackManager.startInput()
        // A disabled voice feature must leave no recorder or engine running.
        if (!com.osfans.trime.ime.voice.VoiceSettings.enabled) cancelVoiceIfActive()
        postRimeJob {
            updateRimeOption(this)
        }
        val (useVirtualKeyboard, useCandidatesView) =
            inputDeviceManager.evaluateOnStartInputView(attribute, this)
        if (useVirtualKeyboard) {
            inputView?.startInput(attribute, restarting)
            syncModeBinding()
        }
        if (useCandidatesView) {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
        }
    }

    override fun onFinishInput() {
        if (sentenceSuggestions.isInitialized()) sentenceSuggestions.value.stop()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (sentenceSuggestions.isInitialized()) {
            if (finishingInput) sentenceSuggestions.value.stop() else sentenceSuggestions.value.viewHidden()
        }
        com.osfans.trime.data.stats.InputStats.setWordCloudEditor(null)
        com.osfans.trime.data.pets.PetHouse.editor(null)
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.hideInput()
        com.osfans.trime.data.pets.PetHouse.requestFlush()
        com.osfans.trime.data.sync.ClipboardSync.setWindowVisible(false)
        com.osfans.trime.ime.keyboard.KeyboardWindow.instance?.setVideoActive(false)
        inputView?.setUnifiedVideoActive(false)
        com.osfans.trime.ime.clipboard.ClipboardSearchBridge.reset()
        hideBottomOverlay()
        cancelVoiceIfActive()
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        decorLocationUpdated = false
        inputView?.dismissCandidateActionMenu()
        candidatesView?.dismissCandidateActionMenu()
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        composingText = ""
        postRimeJob {
            clearComposition()
            com.osfans.trime.data.stats.InputStats.endSession()
        }
        InputFeedbackManager.finishInput()
    }

    fun commitText(text: String, fromEngine: Boolean = false) {
        if (!fromEngine) com.osfans.trime.data.pets.PetHouse.clearProof()
        if (com.osfans.trime.ime.clipboard.ClipboardSearchBridge.acceptText(text)) return
        val ic = currentInputConnection ?: return

        // when composing text equals commit content, finish composing text as-is
        val committed = if (composingText.isNotEmpty() && composingText == text) {
            ic.finishComposingText()
        } else {
            ic.commitText(text, 1)
        }
        if (committed) sentenceSuggestions.value.committed(text, fromEngine)
        lastCommittedText = text
        composingText = ""
        InputFeedbackManager.textCommitSpeak(text)
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (meta) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (sym) metaState = metaState or KeyEvent.META_SYM_ON
        return metaState
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        val event = KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            )
        val handled = ic.sendKeyEvent(event)
        if (handled) sentenceSuggestions.value.key(keyEventCode, metaState)
        return handled
    }

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    private fun routeSearchKey(keyCode: Int, metaState: Int): Boolean {
        val bridge = com.osfans.trime.ime.clipboard.ClipboardSearchBridge
        if (!bridge.active) return false
        if (keyCode == KeyEvent.KEYCODE_DEL) { bridge.acceptBackspace(); return true }
        if (metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0) {
            val unicode = com.osfans.trime.util.virtualKeyCharacterMap.get(keyCode, metaState)
            if (Character.isValidCodePoint(unicode) && unicode > 0 && !Character.isISOControl(unicode)) {
                bridge.acceptText(String(Character.toChars(unicode)))
            }
        }
        return true
    }

    fun sendDownUpKeyEvent(
        keyEventCode: Int,
        metaState: Int = meta(),
    ): Boolean {
        if (routeSearchKey(keyEventCode, metaState)) return true
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        return true
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        val keyVal = KeyValue.fromKeyEvent(event)
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            val modifiers = KeyModifiers.fromKeyEvent(event)
            postRimeJob {
                processKey(keyVal, modifiers, isVirtual = false)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        cancelVoiceIfActive()
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.physicalKey(keyCode,true)
        if (keyCode == KeyEvent.KEYCODE_BACK && com.osfans.trime.ime.clipboard.ClipboardSearchBridge.exit()) return true
        // User-defined combos (Ctrl+Enter, ...) fire before the app or Rime sees the key.
        ComboKeys.match(event.metaState, keyCode)?.let { combo ->
            val view = inputView
            if (view != null) {
                view.performToolbarAction(combo.action)
                return true
            }
        }
        if (inputDeviceManager.evaluateOnKeyDown(event, this)) {
            decorLocationUpdated = false
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if(petCompanionDelegate.isInitialized()) petCompanionDelegate.value.physicalKey(keyCode,false)
        // Consume the matching key-up so the app never sees a key-down-less release.
        if (ComboKeys.match(event.metaState, keyCode) != null) return true
        return forwardKeyEvent(event) || super.onKeyUp(keyCode, event)
    }

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        inputDeviceManager.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun shareText(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ic = currentInputConnection ?: return false
            val cs = ic.getSelectedText(0)
            if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
            return ic.performContextMenuAction(android.R.id.shareText)
        }
        return false
    }

    /** 編輯操作 */
    fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        if (com.osfans.trime.ime.clipboard.ClipboardSearchBridge.active && mask and KeyEvent.META_CTRL_ON != 0) {
            if (code == KeyEvent.KEYCODE_V) {
                com.osfans.trime.data.db.ClipboardHelper.lastBean?.text?.let {
                    com.osfans.trime.ime.clipboard.ClipboardSearchBridge.acceptText(it)
                }
            }
            return true
        }
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (mask != KeyEvent.META_CTRL_ON) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (prefs.keyboard.hookCtrlZY.getValue()) {
                when (code) {
                    KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                    KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
                }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA.getValue()) {
                    ic.performContextMenuAction(android.R.id.selectAll)
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.w("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) {
                            ic.performContextMenuAction(android.R.id.copy).also { result ->
                                if (result) {
                                    clearTextSelection()
                                }
                                return result
                            }
                        }
                    }
                }
                Timber.w("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        val clipboardText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)
                        if (ic.commitText(clipboardText, 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionStart, true)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    fun clearTextSelection() {
        val ic = currentInputConnection ?: return
        val etr = ExtractedTextRequest().apply { token = 0 }
        val et = currentInputConnection.getExtractedText(etr, 0)
        et?.let {
            if (it.selectionStart != it.selectionEnd) {
                ic.setSelection(it.selectionEnd, it.selectionEnd)
            }
        }
    }

    internal fun updateComposingText(text: String) {
        if (builtInVoice.ownsComposition) return
        if (com.osfans.trime.ime.clipboard.ClipboardSearchBridge.active) { composingText = ""; return }
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composingText.isNotEmpty() || text.isNotEmpty()) {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            }
            val applied = ic.setComposingText(text, 1)
            if (applied) sentenceSuggestions.value.inlineChanged(text)
            if (text.isEmpty()) {
                ic.finishComposingText()
            }
        }
        composingText = text
        ic.endBatchEdit()
    }

    fun getActiveText(type: Int): String {
        val rimeComposition = rime.run { compositionCached }
        val selected = currentInputConnection?.getSelectedText(0)?.toString()
        val commitPreview = rimeComposition.commitTextPreview
        val preedit = rimeComposition.preedit ?: ""
        val beforeCursor = getTextAroundCursor(1024, before = true) ?: ""
        val afterCursor = getTextAroundCursor(before = false) ?: ""
        val lastCommitted = lastCommittedText

        return sequenceOf(
            when (type) {
                2 -> preedit
                3 -> selected
                4 -> beforeCursor
                1 -> lastCommitted
                else -> null
            },
            commitPreview,
            selected,
            lastCommitted,
            beforeCursor,
            afterCursor,
        )
            .firstOrNull { it?.isNotEmpty() == true } ?: ""
    }

    private fun getTextAroundCursor(
        initialStep: Int = 1024,
        before: Boolean,
    ): String? {
        val ic = currentInputConnection ?: return null
        var step = initialStep
        while (true) {
            val text = (if (before) ic.getTextBeforeCursor(step, 0) else ic.getTextAfterCursor(step, 0)) ?: return ""
            if (text.length < step) return text.toString()
            step *= 2
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }
}

/**
 * A virtual Return carrying shift, control, alt, or meta is a key chord and is
 * forwarded with the modifier keys. Caps Lock or Num Lock alone is not.
 */
internal fun KeyModifiers.isReturnChord(): Boolean = shift || ctrl || alt || meta
