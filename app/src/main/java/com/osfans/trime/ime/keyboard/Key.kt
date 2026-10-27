// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.graphics.drawable.Drawable
import android.view.KeyEvent
import androidx.annotation.ColorInt
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.orAbsent
import splitties.bitflags.hasFlag
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/** [鍵盤][Keyboard]中的各個按鍵，包含單擊、長按、滑動等多種[事件][KeyAction]  */
@Suppress("ktlint:standard:mixed-condition-operators")
class Key(
    private val parent: Keyboard,
    private val theme: Theme,
    private val keyDef: TextKeyboard.TextKey? = null,
) {
    private val rime get() = RimeDaemon.getFirstSessionOrNull()!!

    val allowDefaultVoice: Boolean get() = keyDef?.allowDefaultVoice != false

    var importedStyle: com.osfans.trime.data.baiduskin.ImportedKeyStyle? = null
    var index: Int = -1

    val keyActions: Map<KeyBehavior, KeyAction> = buildMap {
        if (keyDef == null) return@buildMap

        // A blank token means the behavior is unset, see [orAbsent].
        fun bind(behavior: KeyBehavior, token: KeyActionToken?) {
            token.orAbsent?.let { put(behavior, theme.resolveAction(it)) }
        }

        bind(KeyBehavior.CLICK, keyDef.click)
        bind(KeyBehavior.DOUBLE_CLICK, keyDef.doubleClick)
        bind(KeyBehavior.LAZY_DOUBLE_CLICK, keyDef.lazyDoubleClick)
        bind(KeyBehavior.LONG_CLICK, keyDef.longClick)
        bind(KeyBehavior.SWIPE_UP, keyDef.swipeUp)
        bind(KeyBehavior.SWIPE_DOWN, keyDef.swipeDown)
        bind(KeyBehavior.SWIPE_LEFT, keyDef.swipeLeft)
        bind(KeyBehavior.SWIPE_RIGHT, keyDef.swipeRight)
        bind(KeyBehavior.COMPOSING, keyDef.composing)
        bind(KeyBehavior.HAS_MENU, keyDef.hasMenu)
        bind(KeyBehavior.PAGING, keyDef.paging)
        bind(KeyBehavior.COMBO, keyDef.combo)
        bind(KeyBehavior.ASCII, keyDef.ascii)
    }

    var edgeFlags = 0
    private val sendBindings: Boolean

    var isPressed = false
        private set
    var isOn = false
        private set

    var x = 0
    var y = 0

    var width = 0
    var height = 0
    var gap = 0
    var row = 0
    var column = 0

    var extraWidthLeft = 0
    var extraWidthRight = 0

    private val label = keyDef?.label ?: ""
    private val labelSymbol = keyDef?.labelSymbol ?: ""
    val hint: String = keyDef?.hint ?: ""
    val popup = keyDef?.popup ?: emptyList()

    val keyTextSize: Float = keyDef?.keyTextSize ?: 0f
    val symbolTextSize: Float = keyDef?.symbolTextSize ?: 0f
    val roundCorner: Float = keyDef?.roundCorner?.takeIf { it >= 0 } ?: parent.roundCorner
    val keyBorder: Int = keyDef?.keyBorder?.takeIf { it >= 0 } ?: parent.keyBorder
    var keyTextOffsetX = 0f
        get() = field + keyOffsetX
    var keyTextOffsetY = 0f
        get() = field + keyOffsetY
    var keySymbolOffsetX = 0f
        get() = field + keyOffsetX
    var keySymbolOffsetY = 0f
        get() = field + keyOffsetY
    var keyHintOffsetX = 0f
        get() = field + keyOffsetX
    var keyHintOffsetY = 0f
        get() = field + keyOffsetY
    var keyPressOffsetX = 0f
    var keyPressOffsetY = 0f

    // get color from key customization or just fallback to specified color
    private fun getColor(
        src: TextKeyboard.TextKey.() -> String,
        fallback: String,
    ): Int = keyDef?.let {
        runCatching { ColorManager.getColor(src(it)) }.getOrNull()
    } ?: ColorManager.getColor(fallback)

    // get color from common color schemes or just fallback to default color
    private fun getColor(
        key: String,
        @ColorInt default: Int,
    ): Int = runCatching { ColorManager.getColor(key) }.getOrDefault(default)

    private fun getDrawable(
        src: TextKeyboard.TextKey.() -> String,
        fallback: String,
    ) = keyDef?.let {
        if (src(it).isEmpty()) null else ColorManager.getDrawable(src(it))
    } ?: ColorManager.getDrawable(fallback)

    // Colors are cached per color-scheme generation: the cache re-resolves
    // when the active scheme changes, so an invalidated keyboard view picks up
    // the new colors without being rebuilt.
    private class SchemeColorCache<T>(private val resolve: () -> T) : ReadOnlyProperty<Any?, T> {
        private var generation = Long.MIN_VALUE
        private var value: T? = null

        override fun getValue(
            thisRef: Any?,
            property: KProperty<*>,
        ): T {
            val current = ColorManager.colorGeneration
            if (generation != current) {
                generation = current
                value = resolve()
            }
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
    }

    private fun <T> schemeColor(resolve: () -> T) = SchemeColorCache(resolve)

    private val keyBackground by schemeColor { getDrawable({ keyBackColor }, "key_back_color") }
    private val offKeyBackground by schemeColor { ColorManager.getDrawable("off_key_back_color") ?: keyBackground }
    private val onKeyBackground by schemeColor { ColorManager.getDrawable("on_key_back_color") ?: keyBackground }
    private val hlKeyBackground by schemeColor { getDrawable({ hilitedKeyBackColor }, "hilited_key_back_color") }
    private val hlOffKeyBackground by schemeColor { ColorManager.getDrawable("hilited_off_key_back_color") ?: hlKeyBackground }
    private val hlOnKeyBackground by schemeColor { ColorManager.getDrawable("hilited_on_key_back_color") ?: hlKeyBackground }

    private val keyBorderColor by schemeColor { getColor({ keyBorderColor }, "key_border_color") }
    private val offKeyBorderColor by schemeColor { getColor("off_key_border_color", keyBorderColor) }
    private val onKeyBorderColor by schemeColor { getColor("on_key_border_color", keyBorderColor) }
    private val hlKeyBorderColor by schemeColor { getColor({ hilitedKeyBorderColor }, "hilited_key_border_color") }
    private val hlOffKeyBorderColor by schemeColor { getColor("hilited_off_key_border_color", hlKeyBorderColor) }
    private val hlOnKeyBorderColor by schemeColor { getColor("hilited_on_key_border_color", hlKeyBorderColor) }

    private val keyTextColor by schemeColor { getColor({ keyTextColor }, "key_text_color") }
    private val offKeyTextColor by schemeColor { getColor("off_key_text_color", keyTextColor) }
    private val onKeyTextColor by schemeColor { getColor("on_key_text_color", keyTextColor) }
    private val hlKeyTextColor by schemeColor { getColor({ hilitedKeyTextColor }, "hilited_key_text_color") }
    private val hlOffKeyTextColor by schemeColor { getColor("hilited_off_key_text_color", hlKeyTextColor) }
    private val hlOnKeyTextColor by schemeColor { getColor("hilited_on_key_text_color", hlKeyTextColor) }
    private val keySymbolColor by schemeColor { getColor({ keySymbolColor }, "key_symbol_color") }
    private val offKeySymbolColor by schemeColor { getColor("off_key_symbol_color", keySymbolColor) }
    private val onKeySymbolColor by schemeColor { getColor("on_key_symbol_color", keySymbolColor) }
    private val hlKeySymbolColor by schemeColor { getColor({ hilitedKeySymbolColor }, "hilited_key_symbol_color") }
    private val hlOffKeySymbolColor by schemeColor { getColor("hilited_off_key_symbol_color", hlKeySymbolColor) }
    private val hlOnKeySymbolColor by schemeColor { getColor("hilited_on_key_symbol_color", hlKeySymbolColor) }

    init {
        if (keyDef != null) {
            val hasStateDependentBehavior =
                keyDef.composing.orAbsent != null || keyDef.hasMenu.orAbsent != null || keyDef.paging.orAbsent != null
            if (hasStateDependentBehavior) parent.appearanceStateKeys.add(this)
            sendBindings = keyDef.sendBindings || hasStateDependentBehavior
        } else {
            sendBindings = true
        }
        parent.setModifierKey(this.code, this)
    }

    fun setOn(on: Boolean): Boolean {
        isOn = if (on && isOn) false else on
        return isOn
    }

    private val keyOffsetX: Float
        get() = if (isPressed) keyPressOffsetX else 0f
    private val keyOffsetY: Float
        get() = if (isPressed) keyPressOffsetY else 0f

    /**
     * Informs the key that it has been pressed, in case it needs to change its appearance or state.
     *
     * @see .onReleased
     */
    fun onPressed() {
        isPressed = true
    }

    /**
     * Changes the pressed state of the key. If it is a sticky key, it will also change the toggled
     * state of the key if the finger was release inside.
     *
     * @see .onPressed
     */
    fun onReleased() {
        isPressed = false
        if (click?.isSticky == true) isOn = !isOn
    }

    /**
     * Detects if a point falls inside this key.
     *
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return whether or not the point falls inside the key. If the key is attached to an edge, it
     * will assume that all points between the key and the edge are considered to be inside the
     * key.
     */
    fun isInside(
        x: Int,
        y: Int,
    ): Boolean {
        val leftEdge = edgeFlags and Keyboard.EDGE_LEFT > 0
        val rightEdge = edgeFlags and Keyboard.EDGE_RIGHT > 0
        val topEdge = edgeFlags and Keyboard.EDGE_TOP > 0
        val bottomEdge = edgeFlags and Keyboard.EDGE_BOTTOM > 0
        return (
            (x >= this.x || leftEdge && x <= this.x + width) &&
                (x < this.x + width || rightEdge && x >= this.x) &&
                (y >= this.y || topEdge && y <= this.y + height) &&
                (y < this.y + height || bottomEdge && y >= this.y)
            )
    }

    /**
     * Returns the square of the distance between the center of the key and the given point.
     *
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the square of the distance of the point from the center of the key
     */
    fun squaredDistanceFrom(
        x: Int,
        y: Int,
    ): Int {
        val xDist = this.x + width / 2 - x
        val yDist = this.y + height / 2 - y
        return xDist * xDist + yDist * yDist
    }

    val isShift: Boolean
        get() = this.code == KeyEvent.KEYCODE_SHIFT_LEFT || this.code == KeyEvent.KEYCODE_SHIFT_RIGHT

    /**
     * @param behavior 同文按键模式（点击/长按/滑动）
     * @return
     */
    fun sendBindings(behavior: KeyBehavior): Boolean = keyActions[behavior]?.takeIf { behavior != KeyBehavior.CLICK } != null || checkKeyAction(sendBindings) != null

    private val keyAction: KeyAction?
        get() = checkKeyAction() ?: click

    val click: KeyAction?
        get() = keyActions[KeyBehavior.CLICK]
    val longClick: KeyAction?
        get() = keyActions[KeyBehavior.LONG_CLICK]

    fun hasAction(behavior: KeyBehavior): Boolean = keyActions[behavior] != null

    fun getAction(behavior: KeyBehavior): KeyAction? = keyActions[behavior]?.takeIf { behavior != KeyBehavior.CLICK } ?: checkKeyAction(sendBindings) ?: click

    private fun checkKeyAction(): KeyAction? {
        val rime = rime
        val asciiMode = rime.run { statusCached }.isAsciiMode
        val paging = rime.run { paging }
        val hasMenu = rime.run { hasMenu }
        val composing = rime.run { statusCached }.isComposing
        return keyActions[KeyBehavior.ASCII].takeIf { asciiMode }
            ?: keyActions[KeyBehavior.PAGING]?.takeIf { paging }
            ?: keyActions[KeyBehavior.HAS_MENU]?.takeIf { hasMenu }
            ?: keyActions[KeyBehavior.COMPOSING]?.takeIf { composing }
    }

    private fun checkKeyAction(sendBindings: Boolean): KeyAction? = checkKeyAction().takeIf { sendBindings }

    val code: Int
        get() = click?.code ?: KeyEvent.KEYCODE_UNKNOWN

    fun getCode(behavior: KeyBehavior): Int = getAction(behavior)?.code ?: KeyEvent.KEYCODE_UNKNOWN

    fun getLabel(): String = when {
        theme.isCustom && label.isNotEmpty() -> label
        // 无点击动作的展示键：只显示自身标签
        keyAction == null -> label
        label.isNotEmpty() &&
            keyAction == click &&
            !keyActions.containsKey(KeyBehavior.ASCII) &&
            !rime.run { statusCached }.let { it.isAsciiMode || it.isAsciiPunct } -> label

        else -> keyAction?.getLabel(parent) ?: label // 中文狀態顯示標籤
    }

    fun getPreviewText(behavior: KeyBehavior): String = when (behavior) {
        KeyBehavior.CLICK -> keyAction?.getPreview(parent) ?: ""
        else -> getAction(behavior)?.getPreview(parent) ?: ""
    }

    val symbolLabel: String
        get() = labelSymbol.ifEmpty { longClick?.getLabel(parent) ?: "" }

    private val appearanceType: Int
        get() {
            return when {
                click?.isModifierKey == true && parent.modifier.hasFlag(click!!.modifierKeyOnMask) || isOn -> 2
                click?.isSticky == true || click?.isFunctional == true -> 1
                else -> 0
            }
        }

    /** Per-key background image of custom keyboards; wins over theme colors. */
    var customBgDrawable: Drawable? = null

    private val wholeKeyBackground by schemeColor {
        val raw = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.keyBackground.getValue()
        if (raw.isBlank()) null else ColorManager.getDrawable(raw)
    }
    fun getBackgroundDrawable(): Drawable? = wholeKeyBackground ?: customBgDrawable ?: when (appearanceType) {
        // Locked modifiers use the YAML on-state skin; normal per-key colours must not mask it.
        // Fully custom/imported skins still win through customBgDrawable above.
        2 -> if (isPressed) hlOnKeyBackground else onKeyBackground

        1 -> {
            if (isPressed) {
                hlOffKeyBackground
            } else {
                keyDef?.keyBackColor.takeIf { !it.isNullOrEmpty() }?.let { keyBackground }
                    ?: offKeyBackground
            }
        }

        else -> if (isPressed) hlKeyBackground else keyBackground
    }

    /**
     * 是否属于"打字键"（参与热力统计）：切换键盘/输入法、命令功能键
     * （菜单、剪贴板、隐藏符号层……）都只操作系统行为，不计入。
     */
    fun isStatsKey(): Boolean {
        val click = keyActions[KeyBehavior.CLICK] ?: return false
        if (click.isFunctional) return false
        if (click.select.isNotEmpty()) return false
        if (click.code == KeyEvent.KEYCODE_LANGUAGE_SWITCH ||
            click.code == KeyEvent.KEYCODE_SETTINGS ||
            click.code == KeyEvent.KEYCODE_EISU
        ) {
            return false
        }
        return true
    }

    /**
     * 热力统计用的语义标签：主题里功能键的 label 常是私有区图标字符或
     * "enter_labels" 占位符，这里换成可读的名字，其余按键保持原 label。
     */
    fun heatLabel(): String {
        val label = (this.label.ifEmpty { click?.getLabel(parent).orEmpty() }).trim { it.isWhitespace() || Character.isSpaceChar(it) }
        if (code == KeyEvent.KEYCODE_SPACE) return "空格"
        if (label == "enter_labels") return "回车"
        if (label.isBlank()) {
            // 无标签的空格键（主题常用空格字符做 label）
            if (code == KeyEvent.KEYCODE_SPACE) return "空格"
            return label
        }
        val hasPua = label.any { it in '\ue000'..'\uf8ff' }
        if (!hasPua) return label.lowercase()
        return when (code) {
            KeyEvent.KEYCODE_DEL, 0xFF08 -> "退格" // XK_BackSpace
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, 0xFF0D, 0xFF8D -> "回车" // XK_Return / XK_KP_Enter
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, 0xFFE1, 0xFFE2 -> "Shift"
            KeyEvent.KEYCODE_SPACE, 0x0020 -> "空格"
            KeyEvent.KEYCODE_TAB, 0xFF09 -> "Tab"
            KeyEvent.KEYCODE_ESCAPE, 0xFF1B -> "Esc"
            0xFF51, 0xFF52, 0xFF53, 0xFF54 -> "光标"
            else -> label
        }
    }

    fun getBorderColor(): Int = when (appearanceType) {
        2 -> if (isPressed) hlOnKeyBorderColor else onKeyBorderColor
        1 -> if (isPressed) hlOffKeyBorderColor else getColor(keyDef?.keyBorderColor ?: "", offKeyBorderColor)
        else -> if (isPressed) hlKeyBorderColor else keyBorderColor
    }

    fun getTextColor(): Int = if (theme.isCustom) keyTextColor else when (appearanceType) {
        2 -> if (isPressed) hlOnKeyTextColor else onKeyTextColor
        1 -> if (isPressed) hlOffKeyTextColor else getColor(keyDef?.keyTextColor ?: "", offKeyTextColor)
        else -> if (isPressed) hlKeyTextColor else keyTextColor
    }

    fun getSymbolColor(): Int = when (appearanceType) {
        2 -> if (isPressed) hlOnKeySymbolColor else onKeySymbolColor
        1 -> if (isPressed) hlOffKeySymbolColor else getColor(keyDef?.keySymbolColor ?: "", offKeySymbolColor)
        else -> if (isPressed) hlKeySymbolColor else keySymbolColor
    }
}

private const val ICON_PREFIX = "ic@"

val String.isIconFont: Boolean
    get() = startsWith(ICON_PREFIX)

fun String.toIconName(): String = replace(ICON_PREFIX, "cmd_")
