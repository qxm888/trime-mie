// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isFloatingEffective
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class KeyboardWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    InputBroadcastReceiver {

    init {
        instance = this
        presentation.begin(this)
    }
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val rime: RimeSession by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    companion object : ResidentWindow.Key {
        private val presentation = KeyboardPresentationState<Keyboard, KeyboardView>()
        /** The displayed surface is the only source of keyboard actions and appearance updates. */
        val currentKeyboard: Keyboard
            get() = checkNotNull(presentation.visible) { "No keyboard is attached" }.keyboard

        internal fun clearFloating(owner: Any) = presentation.detachFloating(owner)

        /** Last attached preset id, so the key editor can preselect it. */
        @Volatile
        var lastAttachedId: String = ""

        /** 初始（主）键盘的 preset id——热力统计只对它生效。 */
        @Volatile
        var mainKeyboardId: String = ""

        /** 键盘切换回调（参数 = 新键盘 preset id），候选栏用它应用专属工具栏。 */
        @Volatile
        var onKeyboardSwitched: ((String) -> Unit)? = null

        /** 当前实例（悬浮键盘收起主键盘时用）。 */
        @Volatile
        var instance: KeyboardWindow? = null

        /** 最近一次构建键盘的纯键盘高度（不含候选栏），悬浮键盘据此定尺寸。 */
        @Volatile
        var lastKeyboardHeight: Int = 0
    }

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val navigation by lazy { KeyboardNavigation(theme.presetKeyboards, theme.isCustom) }
    private var currentKeyboardId = ""
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val activeKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    private val visibleKeyboard: Keyboard? get() = presentation.visibleFor(this)?.keyboard
    private val visibleKeyboardView: KeyboardView? get() = presentation.visibleFor(this)?.view

    internal fun publishFloating(owner: Any, keyboard: Keyboard, view: KeyboardView) {
        presentation.attachFloating(this, owner, keyboard, view)
    }

    /** Safe even when an old InputView detaches after the replacement was already created. */
    fun release() {
        videoBackground?.release()
        if (presentation.end(this) && instance === this) {
            instance = null
            lastAttachedId = ""
            mainKeyboardId = ""
            lastKeyboardHeight = 0
            onKeyboardSwitched = null
        }
        cachedKeyboards.clear()
    }

    fun restoreDockedState(source: Keyboard) {
        activeKeyboard?.copyInputStateFrom(source)
        currentKeyboardView?.invalidateAllKeys()
    }

    val currentBakedFloatingWidthPercent get() = activeKeyboard?.bakedFloatingWidthPercent
    val currentBakedFloatingHeightPercent get() = activeKeyboard?.bakedFloatingHeightPercent

    private val keyboardActionListener = commonKeyboardActionListener.listener

    private var lastIsPortrait: Boolean? = null
    private var containerWidth: Int = 0
    private var allowedWidth: Int = 0

    private val onKeyboardViewLayoutChangeListener =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && allowedWidth != width) {
                val isPortrait = !context.resources.configuration.isLandscape()
                lastIsPortrait = isPortrait
                containerWidth = width
                allowedWidth = width
                v.post { refreshKeyboards() }
            }
        }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onKeyboardViewLayoutChangeListener)
        val main = evalKeyboard(".default")
        mainKeyboardId = main
        attachKeyboard(main)
        return keyboardView
    }

    /** Overall skin of custom keyboards: color or image under all keys. */
    private var videoBackground: VideoKeyboardBackground? = null
    private var videoBackgroundPath: String? = null
    var currentBackground: com.osfans.trime.data.keymap.CustomBackground? = null
        private set
    var onBackgroundChanged: (() -> Unit)? = null
    fun drawBlurBackdrop(root: android.view.ViewGroup, canvas: android.graphics.Canvas) {
        com.osfans.trime.ime.appearance.BackdropBlur.drawLayer(root, keyboardView, canvas) { c ->
            keyboardView.background?.draw(c)
        }
        videoBackground?.let { video ->
            com.osfans.trime.ime.appearance.BackdropBlur.drawLayer(root, video, canvas) { c -> video.drawBlurFrame(c) }
        }
    }
    fun setVideoActive(active: Boolean) { videoBackground?.setActive(active) }

    private fun applyKeyboardSkin(target: String, width: Int) {
        val custom = if (theme.isCustom) CustomKeyboards.get(CustomKeyboards.resolveId(target)) else null
        val background = if (theme.isCustom) custom?.backgroundFor(context) else
            com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.videoBackground.getValue().takeIf { it.isNotBlank() }
                ?.let { com.osfans.trime.data.keymap.CustomBackground(com.osfans.trime.data.keymap.CustomBackground.Type.VIDEO, image = it) }
        currentBackground = background
        onBackgroundChanged?.invoke()
        val unified = com.osfans.trime.ime.appearance.SurfaceAppearance.unifiedBackground(theme.isCustom)
        keyboardView.background = if (unified) null else background?.drawable(context, width.coerceAtLeast(1))
        val videoPath = background?.takeIf { !unified && it.type == com.osfans.trime.data.keymap.CustomBackground.Type.VIDEO }?.image
        if (videoPath == videoBackgroundPath && videoBackground != null) return
        videoBackground?.also { it.release(); keyboardView.removeView(it) }
        videoBackground = null
        videoBackgroundPath = videoPath
        if (videoPath != null) {
            videoBackground = VideoKeyboardBackground(context, videoPath).also {
                keyboardView.addView(it, 0, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
        }
    }

    private fun detachCurrentView() {
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        activeKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
    }

    /** 计算键盘可用宽度：优先使用已测量的容器宽度，否则回退到系统窗口测量。 */
    private fun computeAllowedWidth(): Int {
        val isPortrait = !context.resources.configuration.isLandscape()

        if (containerWidth > 0 && lastIsPortrait == isPortrait) {
            return containerWidth
        }

        val safeWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = context.windowManager.maximumWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val displayWidth = context.resources.displayMetrics.widthPixels
            val windowWidth = windowMetrics.bounds.width() - insets.left - insets.right
            if (windowWidth < displayWidth - context.dp(1)) displayWidth else windowWidth
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            context.windowManager.defaultDisplay.getSize(size)
            size.x
        }

        val width = if (context.isFloatingEffective()) {
            minOf(safeWidth, context.resources.displayMetrics.heightPixels) *
                KeyboardPrefs.run { context.presentationWidthPercent() } / 100
        } else {
            val (left, right) = KeyboardPrefs.run { context.oneHandPadding(theme) }
            safeWidth - left - right
        }
        allowedWidth = width
        return width
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? = selectKeyboardPreset(theme.presetKeyboards, name)

    private fun attachKeyboard(target: String) {
        if (currentKeyboardId.isNotEmpty() && currentKeyboardId != target) lastKeyboardId = currentKeyboardId
        currentKeyboardId = target
        lastAttachedId = target

        // per-key overrides or custom layouts may have changed since this
        // keyboard was cached
        cachedKeyboards[target]?.let { (kb, _) ->
            if (kb.builtWithOverridesVersion != KeyBindingOverrides.version ||
                kb.builtWithCustomVersion != CustomKeyboards.version
            ) {
                cachedKeyboards.remove(target)
            }
        }

        val config = selectKeyboardConfig(target)
        val width = computeAllowedWidth()
        applyKeyboardSkin(target, width)
        val keyboard = activeKeyboard ?: Keyboard(context, theme, width, config, target)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        if (activeKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        runCatching { onKeyboardSwitched?.invoke(target) }
        lastKeyboardHeight = keyboard.keyboardHeight
        keyboard.also {
            _currentKeyboardHeight.tryEmit(it.keyboardHeight)
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val currentMode = rime.run { statusCached }.isAsciiMode
            val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode

            if (currentMode != targetMode) {
                service.postRimeJob {
                    commitComposition()
                    setRuntimeOption("ascii_mode", targetMode)
                }
            }

            presentation.attachDocked(this, it, view)
            currentKeyboard.recordHeatLayout()
        }

        view.let {
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                add(it, lParams(matchParent, matchParent))
            }
        }
    }

    private fun evalKeyboard(id: String): String = navigation.select(
        id,
        KeyboardNavigation.State(
            current = currentKeyboardId,
            resolvedCurrent = if (theme.isCustom) CustomKeyboards.resolveId(currentKeyboardId) else currentKeyboardId,
            last = lastKeyboardId,
            locked = lastLockKeyboardId,
            ascii = activeKeyboard?.asciiKeyboard,
            currentIsLocked = activeKeyboard?.isLock == true,
            schema = rime.run { statusCached }.schemaId,
            alphabet = rime.run { schemaCached }.alphabet,
            landscape = service.isLandscapeMode() && !context.isFloatingEffective(),
        ),
    )

    fun switchKeyboard(to: String) {
        val target = evalKeyboard(to)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                val cached = cachedKeyboards[target]?.first
                if (target == currentKeyboardId && cached?.builtWithOverridesVersion == KeyBindingOverrides.version &&
                    cached.builtWithCustomVersion == CustomKeyboards.version
                ) return@execute
            }
            detachCurrentView()
            attachKeyboard(target)
        }
        Timber.d("Switched to keyboard: $target")
    }

    fun refreshKeyboards(isAll: Boolean = false) {
        val id = currentKeyboardId.ifEmpty { return }
        val previous = activeKeyboard
        detachCurrentView()
        if (isAll) {
            cachedKeyboards.clear()
        } else {
            cachedKeyboards.remove(id)
        }
        attachKeyboard(id)
        previous?.let(::restoreDockedState)
    }

    /** Repaints the keyboard after a color-scheme switch; keys re-resolve their colors. */
    override fun refreshColors() {
        visibleKeyboardView?.invalidateAllKeys()
    }

    override fun onStartInput(info: EditorInfo) {
        val targetKeyboard =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"

                else -> {
                    when (info.inputType and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> "number"

                        InputType.TYPE_CLASS_TEXT -> {
                            when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> ".ascii"

                                else -> ""
                            }
                        }

                        else -> ""
                    }
                }
            }
        switchKeyboard(targetKeyboard)
        val isAsciiMode = rime.run { statusCached }.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: visibleKeyboard?.let {
                if (theme.style.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        service.postRimeJob { setRuntimeOption("ascii_mode", targetMode) }
                    }
                }
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.run { statusCached }
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.style.autoCaps == true && status.isAsciiMode && visibleKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.run { statusCached }.isAsciiMode) {
            visibleKeyboard?.appearanceStateKeys?.forEach { key ->
                visibleKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            visibleKeyboard?.setShifted(on, shifted)?.let { if (it) visibleKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        switchKeyboard(".default")
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        when {
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }

            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(theme.resolveAction(what))
                }
            }

            option == "ascii_mode" && !value.value -> {
                // 回到中文时，释放 Shift 锁定
                visibleKeyboard?.resetShift()
            }
        }
        visibleKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
        setVideoActive(true)
    }

    override fun onDetached() {
        setVideoActive(false)
        visibleKeyboardView?.onDetach()
    }
}
