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

        /** 候选视图等组件在键盘尚未挂载时重建的容错读取。 */
        val currentKeyboardOrNull: Keyboard?
            get() = presentation.visible?.keyboard

        /** Repaint all key labels after a modifier change from a named function. */
        fun invalidateKeyLabels() {
            presentation.visibleFor(this)?.view?.invalidateAllKeys()
        }

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
    @Volatile private var currentKeyboardId = ""
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    private var tempAsciiMode: Boolean? = null

    /**
     * 最近一次「由 ascii_mode 变化驱动自动切页」的时间戳。
     *
     * 这两条路径互为因果时会形成死循环：
     *   1. `onStartInput()` 对数字/密码/邮箱框强制 `ascii_mode = true`；
     *   2. `onRimeOptionUpdated()` 看到 true → 自动切到该键盘的 `ascii_keyboard`；
     *   3. `attachKeyboard()` 末尾又按**新页**的 `reset_ascii_mode / lastAsciiMode` 反向写一次
     *      `ascii_mode`（此时 `statusCached` 往往还没刷新，读到的是切页前的旧值 false）；
     *   4. 于是又切回中文页，再写回 true …… 无限来回，用户看到的就是中英自己疯狂切换。
     *
     * 这个时间窗内的 attach 一律不再回写 `ascii_mode`：谁发起的切换，谁决定模式。
     */
    @Volatile
    private var asciiAutoSwitchAt = 0L
    /** 那次自动切页的目标键盘 id：即使时间窗过期，只要目标一致也不回写。 */
    @Volatile
    private var asciiAutoSwitchTarget = ""
    private val asciiAutoSwitchGraceMs = 700L
    /** 上一次处理过的 ascii_mode 值：同一值重复广播时不再重复切页。 */
    private var lastAsciiOption: Boolean? = null
    /** 自动切到 ascii_keyboard 时的 (来源键盘, 英文键盘)；切回中文时恢复来源。 */
    private var colorGroupInputRestartPending = false
    private var asciiKeyboardPair: Pair<String, String>? = null
    /** 最近一次选中的自定义主键盘（26/9/14/18 键等）。 */
    private var lastMainKeyboardId = ""

    /** 主键盘：显式 lock 的键盘，或自定义键盘里的顶层键盘（未归为次级）。 */
    private fun isMainKeyboard(id: String): Boolean {
        if (!theme.isCustom) return false
        val resolved = CustomKeyboards.resolveId(id)
        val keyboard = CustomKeyboards.get(resolved) ?: return false
        return keyboard.options.lock || keyboard.secondaryOf.isBlank()
    }
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    var heightLimitPx: Int = 0
        private set

    fun limitHeight(maximum: Int) {
        val limit = maximum.coerceAtLeast(1)
        if (heightLimitPx == limit) return
        heightLimitPx = limit
        val current = activeKeyboard
        // Ordinary sizes keep their view tree; only a real geometry change rebuilds keys.
        cachedKeyboards.keys.filter { it != currentKeyboardId }.forEach(cachedKeyboards::remove)
        if (current != null && current.unconstrainedHeight.coerceAtMost(limit) != current.keyboardHeight) refreshKeyboards()
    }
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

    data class RuntimeState(
        val theme: Theme, val id: String, val last: String, val locked: String,
        val main: String, val asciiPair: Pair<String, String>?, val keyboard: Keyboard?,
        val recolorRestartPending: Boolean = false,
    )
    fun captureRuntimeState() = RuntimeState(theme, currentKeyboardId, lastKeyboardId, lastLockKeyboardId,
        lastMainKeyboardId, asciiKeyboardPair, activeKeyboard, colorGroupInputRestartPending)

    fun restoreRuntimeState(state: RuntimeState) {
        // Presentation changes reuse the same immutable theme. A new theme must
        // never receive old custom ids, modifiers or layout navigation history.
        if (state.theme !== theme || state.id !in theme.presetKeyboards) return
        if (state.id != currentKeyboardId) { detachCurrentView(); attachKeyboard(state.id) }
        lastKeyboardId = state.last; lastLockKeyboardId = state.locked
        lastMainKeyboardId = state.main; asciiKeyboardPair = state.asciiPair
        colorGroupInputRestartPending = state.recolorRestartPending
        state.keyboard?.let(::restoreDockedState)
    }
    /** A background edit refresh keeps the currently visible page, including unlocked panels. */
    fun restoreAfterCustomizationRefresh(state: RuntimeState) {
        if (!theme.isCustom || !state.theme.isCustom || state.id !in theme.presetKeyboards) return
        if (state.id != currentKeyboardId) { detachCurrentView(); attachKeyboard(state.id) }
        fun valid(id: String) = id.takeIf { it in theme.presetKeyboards }.orEmpty()
        lastKeyboardId = valid(state.last); lastLockKeyboardId = valid(state.locked)
        lastMainKeyboardId = valid(state.main)
        asciiKeyboardPair = state.asciiPair?.takeIf { it.first in theme.presetKeyboards && it.second in theme.presetKeyboards }
        colorGroupInputRestartPending = state.recolorRestartPending
    }

    /** Transfer page roles after a color-group family change, never old theme objects or ids. */
    fun restoreFamilyNavigation(main: String?, previous: String?, asciiOrigin: String?) {
        if (!theme.isCustom || theme !== com.osfans.trime.data.theme.ThemeManager.activeThemeOrNull()) return
        main?.takeIf { it in theme.presetKeyboards }?.let { lastMainKeyboardId = it; lastLockKeyboardId = it }
        previous?.takeIf { it in theme.presetKeyboards }?.let { lastKeyboardId = it }
        asciiOrigin?.takeIf { it in theme.presetKeyboards }?.let { asciiKeyboardPair = it to currentKeyboardId }
        colorGroupInputRestartPending = CustomKeyboards.pageRole(CustomKeyboards.resolveId(currentKeyboardId)) in setOf("en", "num")
    }

    fun restoreDockedState(source: Keyboard) {
        if (source.keyboardId != activeKeyboard?.keyboardId) return
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

    /**
     * 取皮肤用的键盘 id：优先自身，其次回退到「当前活动自定义键盘」。
     *
     * `onStartInput()` 会把数字/电话/日期框切到内置 `number`、把密码/邮箱框切到 `.ascii`。
     * 这些内置页在自定义键盘库里通常没有同名条目，`get()` 返回 null，
     * 旧代码就把 `keyboardView.background` 置成 null → 整块键盘底色/主题色全部丢失（全透明）；
     * 顶栏同时因为没有 toolbar 配置而塌成一两个按钮。**同一个根因。**
     * 内置页本来就应该沿用当前键盘的皮肤，所以这里做一次回退解析。
     */
    private fun skinIdFor(target: String): String {
        val direct = CustomKeyboards.resolveId(target)
        if (CustomKeyboards.get(direct) != null) return direct
        val fallback = CustomKeyboards.resolveId("default")
        return if (CustomKeyboards.get(fallback) != null) fallback else direct
    }

    private fun applyKeyboardSkin(target: String, width: Int) {
        val skinId = if (theme.isCustom) skinIdFor(target) else target
        val custom = if (theme.isCustom) CustomKeyboards.get(skinId) else null
        val background = if (theme.isCustom) custom?.backgroundFor(context) else
            com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.videoBackground.getValue().takeIf { it.isNotBlank() }
                ?.let { com.osfans.trime.data.keymap.CustomBackground(com.osfans.trime.data.keymap.CustomBackground.Type.VIDEO, image = it) }
        currentBackground = background
        onBackgroundChanged?.invoke()
        // 「统一背景」也按解析后的皮肤 id 判断：沿用原逻辑走全局偏好时，
        // 数字键盘会退回另一套设置，与主键盘画出不一致的底。
        val unified = com.osfans.trime.ime.appearance.SurfaceAppearance.unifiedBackground(theme.isCustom, skinId)
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
            val reference = if (KeyboardPrefs.run { context.landscapeSizing() } != null) safeWidth
                else minOf(safeWidth, context.resources.displayMetrics.heightPixels)
            reference * KeyboardPrefs.run { context.presentationWidthPercent() } / 100
        } else {
            val (left, right) = KeyboardPrefs.run { context.oneHandPadding(theme) }
            val landscape = KeyboardPrefs.run { context.landscapeSizing() }
            (if (landscape != null) safeWidth * landscape.width.getValue().coerceIn(40, 100) / 100 else safeWidth) - left - right
        }
        allowedWidth = width
        return width
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? = selectKeyboardPreset(theme.presetKeyboards, name)

    /**
     * 输入框类型请求的键盘页 → 实际布局 id。
     *
     * 数字/电话/日期框请求的是 `"number"`，密码/邮箱/网址框请求 `".ascii"`，
     * 但自定义键盘集里通常没有叫 "number" 的布局：`selectKeyboardPreset()` 找不到
     * 就会静默退回第一个布局（也就是用户当时那把键盘），于是点数字框看起来
     * 「根本没有自动切换」。这里在当前键盘所属的那一套里找对应的角色页
     * （数字页 `num`、英文页 `en`），找不到才把请求原样交回给上游逻辑。
     */
    private fun resolveInputPage(request: String): String {
        if (request != "number" && request != ".ascii") return request
        val role = if (request == "number") "num" else "en"
        val current = CustomKeyboards.resolveId(currentKeyboardId)
        val root = runCatching { CustomKeyboards.setRoot(current) }.getOrNull()
        if (!root.isNullOrEmpty()) {
            CustomKeyboards.setPageIds(root)
                .firstOrNull { CustomKeyboards.pageRole(it) == role }
                ?.let { return it }
        }
        if (request == "number" && "number" in theme.presetKeyboards) return "number"
        return request
    }

    private fun attachKeyboard(target: String) {
        if (instance !== this || !presentation.owns(this)) return
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
        // 行边距在 Keyboard 布局内按行生效（背景铺满不动）。
        val keyboard = activeKeyboard ?: Keyboard(context, theme, width, config, target, maxHeightPx = heightLimitPx)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        if (activeKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        runCatching { onKeyboardSwitched?.invoke(target) }
        service.refreshFloatingToolbar(target)
        lastKeyboardHeight = keyboard.keyboardHeight
        keyboard.also {
            _currentKeyboardHeight.tryEmit(it.keyboardHeight)
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val currentMode = rime.run { statusCached }.isAsciiMode
            val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode

            // 由 ascii_mode 变化驱动的那次切页，绝不反过来再写 ascii_mode（见 asciiAutoSwitchAt 注释）。
            val autoSwitchHit = target == asciiAutoSwitchTarget
            if (autoSwitchHit) asciiAutoSwitchTarget = ""   // 一次性标记，用完即清
            val autoSwitched = autoSwitchHit ||
                android.os.SystemClock.uptimeMillis() - asciiAutoSwitchAt < asciiAutoSwitchGraceMs
            if (currentMode != targetMode && !autoSwitched) {
                service.postRimeJob {
                    if (instance !== this@KeyboardWindow || currentKeyboardId != target) return@postRimeJob
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
        service.notifyPetKeyboard(keyboard)
    }

    private fun evalKeyboard(id: String): String = navigation.select(
        id,
        KeyboardNavigation.State(
            current = currentKeyboardId,
            resolvedCurrent = if (theme.isCustom) CustomKeyboards.resolveId(currentKeyboardId) else currentKeyboardId,
            last = lastKeyboardId,
            locked = lastMainKeyboardId.ifEmpty { lastLockKeyboardId }.ifEmpty { if (theme.isCustom) CustomKeyboards.resolveId("default") else "" },
            ascii = activeKeyboard?.asciiKeyboard,
            currentIsLocked = activeKeyboard?.isLock == true,
            schema = rime.run { statusCached }.schemaId,
            alphabet = rime.run { schemaCached }.alphabet,
            landscape = service.isLandscapeMode() && !context.isFloatingEffective(),
        ),
    )

    /** Toolbar English pages often bind a fixed parent id. Return to the
     * exact layout which opened that English page, including 9/14/18/7 columns. */
    fun switchKeyboardFromAction(to: String) {
        val pair = asciiKeyboardPair
        val parent = pair?.first?.let { CustomKeyboards.get(it)?.secondaryOf }.orEmpty()
        if (pair != null && currentKeyboardId == pair.second &&
            (to == ".default" || to == pair.first || (parent.isNotEmpty() && to == parent))) {
            switchKeyboard(pair.first)
        } else switchKeyboard(to)
    }

    fun switchKeyboard(to: String) {
        if (instance !== this || !presentation.owns(this)) return
        colorGroupInputRestartPending = false
        val target = evalKeyboard(to)
        val declaredEnglish = activeKeyboard?.asciiKeyboard.orEmpty()
        if (target != currentKeyboardId && declaredEnglish.isNotEmpty() && target == evalKeyboard(declaredEnglish)) {
            asciiKeyboardPair = (if (theme.isCustom) CustomKeyboards.resolveId(currentKeyboardId) else currentKeyboardId) to target
        }
        // 选中主键盘后把它记为当前自定义键盘：返回主键盘 / 下次启动都回到它。
        if (isMainKeyboard(target)) {
            val resolved = CustomKeyboards.resolveId(target)
            lastMainKeyboardId = resolved
            if (CustomKeyboards.activeId != resolved) {
                CustomKeyboards.activeId = resolved
                com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
            }
        }
        asciiKeyboardPair?.let { (_, english) -> if (target != english) asciiKeyboardPair = null }
        ContextCompat.getMainExecutor(service).execute {
            if (instance !== this || !presentation.owns(this)) return@execute
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
        if (instance !== this || !presentation.owns(this)) return
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
        val inputTarget = KeyboardNavigation.inputRequest(targetKeyboard, currentKeyboardId,
            service.isRestartingInputView, colorGroupInputRestartPending)
        colorGroupInputRestartPending = false
        switchKeyboard(resolveInputPage(inputTarget))
        val isAsciiMode = rime.run { statusCached }.isAsciiMode
        if (targetKeyboard == ".ascii" || targetKeyboard == "number") {
            if (tempAsciiMode == null) {
                tempAsciiMode = isAsciiMode
            }
            if (!isAsciiMode) {
                asciiAutoSwitchAt = android.os.SystemClock.uptimeMillis()
                service.postRimeJob { setRuntimeOption("ascii_mode", true) }
            }
        } else {
            tempAsciiMode?.let { saved ->
                if (isAsciiMode != saved) {
                    asciiAutoSwitchAt = android.os.SystemClock.uptimeMillis()
                    service.postRimeJob { setRuntimeOption("ascii_mode", saved) }
                }
                tempAsciiMode = null
            } ?: visibleKeyboard?.let {
                if (theme.style.resetAsciiModeOnFocusChange) {
                    val targetMode = if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
                    if (isAsciiMode != targetMode) {
                        asciiAutoSwitchAt = android.os.SystemClock.uptimeMillis()
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

            option == "ascii_mode" -> {
                // 同一值重复广播不再重复切页。
                if (lastAsciiOption == value.value) return
                lastAsciiOption = value.value
                // 标记：接下来这次切页由选项驱动，attachKeyboard 不得反向回写 ascii_mode。
                asciiAutoSwitchAt = android.os.SystemClock.uptimeMillis()
                if (value.value) {
                    // 当前键盘声明了 ascii_keyboard（如 9 键 → 英文 26 键），切英文时自动过去。
                    val english = visibleKeyboard?.asciiKeyboard.orEmpty()
                    if (english.isNotEmpty()) {
                        val target = evalKeyboard(english)
                        if (target.isNotEmpty() && target != currentKeyboardId) {
                            asciiKeyboardPair = currentKeyboardId to target
                            asciiAutoSwitchTarget = target
                            switchKeyboard(target)
                        }
                    }
                } else {
                    // 回到中文时释放 Shift 锁定，并把自动切过去的英文键盘换回来。
                    visibleKeyboard?.resetShift()
                    asciiKeyboardPair?.let { (origin, english) ->
                        asciiKeyboardPair = null
                        if (currentKeyboardId == english) {
                            // The English option arrives before detaching the Chinese page.
                            // A page with reset_ascii_mode=false therefore remembers true
                            // on detach. Honour the explicit switch back to Chinese instead
                            // of restoring that stale value and reopening the English page.
                            cachedKeyboards[origin]?.first?.lastAsciiMode = false
                            asciiAutoSwitchTarget = origin
                            switchKeyboard(origin)
                        }
                    }
                }
            }
        }
        visibleKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
        setVideoActive(true)
        currentKeyboardView?.invalidateAllKeys()
        com.osfans.trime.ime.appearance.BackdropBlur.invalidateBackground(keyboardView)
    }

    override fun onDetached() {
        setVideoActive(false)
        visibleKeyboardView?.onDetach()
    }
}
