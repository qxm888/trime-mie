/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.view.KeyEvent
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.orAbsent
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isFloatingEffective
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.pow

/** 從YAML中加載鍵盤配置，包含多個[按鍵][Key]。  */
@Suppress("ktlint:standard:property-naming")
class Keyboard(
    private val context: Context,
    private val theme: Theme,
    private val allowedWidth: Int,
    private val selfConfig: TextKeyboard? = null,
    /** Preset id this keyboard is attached as; keys the per-key overrides. */
    val keyboardId: String = "",
    /** Explicit pixel height for floating keyboards; zero uses the theme height. */
    heightOverridePx: Int = 0,
    /** Actual measured space after toolbar, navigation insets and layout padding. */
    maxHeightPx: Int = 0,
) {
    /** [KeyBindingOverrides.version] at build time, for cache staleness checks. */
    val builtWithOverridesVersion: Long = KeyBindingOverrides.version

    /** [CustomKeyboards.version] at build time, for cache staleness checks. */
    val builtWithCustomVersion: Long = CustomKeyboards.version
    internal val allKeysBackground = if (theme.isCustom) CustomKeyboards.get(CustomKeyboards.resolveId(keyboardId))?.allKeysBackground.orEmpty() else ""


    /** 按鍵默認水平間距  */
    internal val horizontalGap: Int =
        if (theme.isCustom && selfConfig != null) context.dp(selfConfig.horizontalGap.coerceAtLeast(0))
        else resolvePositive(selfConfig?.horizontalGap, theme.style.horizontalGap) { context.dp(it) }

    /**
     * 外观「整体」页的键盘左右留白（px）。只作用于按键，不挪动面板背景/描边：
     * 背景所在的容器必须保持全宽，否则背景色会跟着一起被挤进来。
     * 与自定义键盘自己的 rowMargin 叠加，作用域不同但效果相加。
     */
    private val sideMarginPx: Int =
        context.dp(
            com.osfans.trime.ime.appearance.SurfaceAppearance
                .keyboardSideMargin(theme.isCustom).toInt(),
        )

    /**
     * 外观「整体」页的键盘上/下留白（px）。
     *
     * 和左右留白同一套口径：只把**按键区**从面板上下沿收进来，键盘总高
     * （[keyboardHeight]）不变，背景、描边、底部条仍铺满整块面板——
     * 所以这里缩的是「行盒放进来的高度」，不是键盘高度本身。
     */
    private val topMarginPx: Int =
        context.dp(
            com.osfans.trime.ime.appearance.SurfaceAppearance
                .keyboardTopMargin(theme.isCustom).toInt(),
        )
    private val bottomMarginPx: Int =
        context.dp(
            com.osfans.trime.ime.appearance.SurfaceAppearance
                .keyboardBottomMargin(theme.isCustom).toInt(),
        )

    /** 默認鍵寬  */
    private val keyWidth: Int = (allowedWidth * theme.style.keyWidth / 100).toInt()

    /** 默認鍵高 (NOTE: 无需 dp 转换，会被 keyboardHeight 等比例缩放) */
    private val keyHeight: Int =
        resolvePositive(selfConfig?.height?.toInt(), theme.style.keyHeight)

    /** 默認行距  */
    internal val verticalGap: Int
        get() = if (importedLayout != null) 0 else if (theme.isCustom && selfConfig != null) {
            // Custom layouts accept zero and retain their spacing ratio while floating is resized.
            val naturalHeight = context.dp(selfConfig.keyboardHeight.coerceAtLeast(1))
            (context.dp(selfConfig.verticalGap.coerceAtLeast(0)) * keyboardHeight.toFloat() / naturalHeight).toInt()
        } else {
            resolvePositive(selfConfig?.verticalGap, theme.style.verticalGap) { context.dp(it) }
        }

    /** 默認按鍵圓角半徑  */
    val roundCorner: Float =
        resolveNonNegative(selfConfig?.roundCorner, theme.style.roundCorner)

    /** 默認按鍵邊框寬度  */
    val keyBorder: Int =
        resolveNonNegative(selfConfig?.keyBorder, theme.style.keyBorder)

    /** 鍵盤的Shift鍵  */
    var mShiftKey: Key? = null
    var mCtrlKey: Key? = null
    var mAltKey: Key? = null
    var mMetaKey: Key? = null
    var mSymKey: Key? = null

    /**
     * Total height of the keyboard, including the padding and keys
     *
     * @return the total height of the keyboard
     */
    var height = 0
        private set

    /**
     * Total width of the keyboard, including left side gaps and keys, but not any gaps on the right
     * side.
     */
    var minWidth = 0
        private set

    /** List of keys in this keyboard  */
    private val mKeys = mutableListOf<Key>()
    val appearanceStateKeys = mutableListOf<Key>()
    var modifier = 0
        private set

    var firstPressedKeyIndex: Int = -1

    /** Keyboard default ascii mode  */
    val asciiMode = selfConfig?.asciiMode == 1
    val resetAsciiMode = selfConfig?.resetAsciiMode ?: true
    var lastAsciiMode: Boolean = asciiMode

    val landscapeKeyboard: String? = selfConfig?.landscapeKeyboard
    private val preferredSplitPercent by AppPrefs.defaultInstance().keyboard.splitSpacePercent
    private val landscapePercent =
        resolvePositive(selfConfig?.landscapeSplitPercent, preferredSplitPercent)

    // Variables for pre-computing nearest keys.
    private val labelTransform = selfConfig?.labelTransform ?: TextKeyboard.LabelTransform.NONE
    private var mCellWidth = 0
    private var mCellHeight = 0
    private var gridNeighbors: Array<IntArray?>? = null

    private val proximityThreshold: Int =
        (keyWidth * SEARCH_DISTANCE).pow(2).toInt() // Square it for comparison
    val isLock = selfConfig?.lock ?: false // 切換程序時記憶鍵盤
    val asciiKeyboard: String? = selfConfig?.asciiKeyboard // 英文鍵盤

    private val importedData = if (theme.isCustom) CustomKeyboards.get(CustomKeyboards.resolveId(keyboardId)) else null
    val importedLayout = importedData?.importedSkin?.layout(context)
    private val customKeys = importedData?.let { data -> CustomKeyboards.run { data.generatedKeys() } }

    private val landscapeSizing = KeyboardPrefs.run { context.landscapeSizing() }
    val bakedFloatingWidthPercent = KeyboardPrefs.run { context.presentationWidthPercent() }
    val bakedFloatingHeightPercent = KeyboardPrefs.run { context.presentationHeightPercent() }
    private val floatingGeometry = context.isFloatingEffective()
    val keyTextScale: Float
        get() = if (landscapeSizing != null) (keyboardHeight.toFloat() / naturalKeyboardHeight.coerceAtLeast(1)).coerceIn(0.6f, 1f)
        else floatingKeyTextScale(floatingGeometry, bakedFloatingWidthPercent, bakedFloatingHeightPercent)
    private val useLandscapeGeometry = context.isLandscapeMode() && !floatingGeometry

    private val naturalKeyboardHeight: Int =
        importedLayout?.let {
            // Source pixel dimensions describe a coordinate space. Use a readable physical
            // height just like ordinary keyboards; floating resize still takes precedence.
            importedData?.importedKeyboardHeight(context)
        } ?: resolvePositive(
            selfConfig?.let {
                pickLandscape(it.keyboardHeight, it.keyboardHeightLand, useLandscapeGeometry)
            },
            pickLandscape(
                theme.style.keyboardHeight,
                theme.style.keyboardHeightLand,
                useLandscapeGeometry,
            ),
        ) { context.dp(it) }

    private val requestedKeyboardHeight: Int = heightOverridePx.takeIf { it > 0 } ?: if (floatingGeometry) {
        (naturalKeyboardHeight * bakedFloatingHeightPercent / 100f).toInt().coerceAtLeast(1)
    } else naturalKeyboardHeight

    // Bake a docked landscape height into the actual key geometry, leaving room for the editor
    // and candidate comments even when a portrait theme specifies a very tall keyboard.
    internal val unconstrainedHeight: Int = if (landscapeSizing != null && heightOverridePx <= 0) {
        // Apply the size to key geometry, not canvas scale: touch targets and popup anchors agree.
        (context.resources.displayMetrics.heightPixels * landscapeSizing.height.getValue().coerceIn(20, 70) / 100f)
            .toInt().coerceAtLeast(context.dp(56))
    } else if (!floatingGeometry && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
        val bar = context.dp(theme.style.candidateViewHeight + theme.style.commentHeight)
        requestedKeyboardHeight.coerceAtMost((context.resources.displayMetrics.heightPixels * 0.5f).toInt() - bar).coerceAtLeast(context.dp(80))
    } else requestedKeyboardHeight

    val keyboardHeight: Int = if (maxHeightPx > 0) unconstrainedHeight.coerceAtMost(maxHeightPx).coerceAtLeast(1) else unconstrainedHeight

    /**
     * 行盒实际可用的高度 = 键盘总高 − 上留白 − 下留白。
     *
     * 键盘总高不跟着变小，所以留白只是把按键往中间挤：上留白从顶部让开，
     * 下留白从底部让开，背景仍然铺到边。
     */
    private val keyAreaHeight: Int = (keyboardHeight - topMarginPx - bottomMarginPx).coerceAtLeast(1)

    private val expandKeypressArea: Boolean by AppPrefs.defaultInstance().keyboard.expandKeypressArea

    init {

        if (importedLayout != null && importedData != null) {
            buildImported(importedLayout, importedData)
        } else if (selfConfig != null) {

            val keys = selfConfig.keys.mapIndexed { index, key ->
                if (!theme.isCustom && keyboardId.isNotEmpty()) KeyBindingOverrides.applyTo(keyboardId, index, key) else key
            }
            val keyboardKeyWidth = selfConfig.width

            val maxColumns = if (selfConfig.columns == -1) Int.MAX_VALUE else selfConfig.columns

            val isSplit = !floatingGeometry && !theme.isCustom && context.isLandscapeMode() && landscapePercent > 0
            val splitRatio = if (isSplit) landscapePercent / 100f else 0f

            val oneWeightWidthPx =
                allowedWidth.toFloat() / (MAX_TOTAL_WEIGHT * (1 + splitRatio))

            // total width weight for each row.
            val rowWidthTotalWeight = mutableListOf<Float>()

            // raw height of each row before scaling
            val rowRawHeight = mutableListOf<Int>()

            var x = 0
            var column = 0
            var rowHeight = keyHeight
            var totalKeyWidth = 0f

            // determine row count, row heights, total row weights; does not create Key objects
            for (key in keys) {

                // determine the width weight of this key
                val keyWidthWeight =
                    if (key.width == 0f && key.click.orAbsent != null) keyboardKeyWidth else key.width

                val widthPx = (keyWidthWeight * allowedWidth / MAX_TOTAL_WEIGHT).toInt()

                // wrap to next row if column or width limit is reached
                if ((key.newRow && x > 0) || column >= maxColumns || x + widthPx > allowedWidth) {
                    rowWidthTotalWeight.add(totalKeyWidth)
                    rowRawHeight.add(rowHeight)
                    x = 0
                    column = 0
                    totalKeyWidth = 0f
                }

                // first key of a row defines the row height
                if (column == 0) {
                    rowHeight = if (key.height > 0) key.height.toInt() else keyHeight
                }

                totalKeyWidth += keyWidthWeight

                // only clickable keys count toward column count
                if (!key.isSpacer()) {
                    column++
                }

                x += widthPx
            }

            rowWidthTotalWeight.add(totalKeyWidth)
            rowRawHeight.add(rowHeight)

            val rows = rowRawHeight.size
            val rawHeightSum = rowRawHeight.sum()

            // 自定义键盘在「自动高度」下，行高是绝对 dp：1dp 就是 1dp。
            // 旧逻辑把 keyboardHeight 按行高比例分给各行，而 keyboardHeight 又被
            // 「高度百分比 / 键盘高度」往上抬，于是加一行（甚至加 1dp 间距）总高变大，
            // 比例随之变大 → 每一行按键都跟着放大一圈，而不是把键盘整体往上推。
            // 现在：行高固定，多出来的高度摊到行间距里，只有间距变、按键不变。
            val autoCustomHeight = theme.isCustom && selfConfig != null &&
                (CustomKeyboards.get(CustomKeyboards.resolveId(keyboardId))?.keyboardHeightDp ?: 0) <= 0
            val density = context.resources.displayMetrics.density
            val naturalHeightPx = (rawHeightSum * density).toInt()
            val keepAbsolute = autoCustomHeight && keyAreaHeight >= naturalHeightPx
            val extraPerGap =
                if (keepAbsolute && rows > 1) (keyAreaHeight - naturalHeightPx) / (rows - 1) else 0

            // scaled row heights after fitting into keyboardHeight
            val rowHeightScaled = MutableList(rows) { 0 }

            var remainHeight = keyAreaHeight
            val scale = if (keepAbsolute) density else keyAreaHeight.toFloat() / rawHeightSum

            // scale row heights to keyboardHeight; last row absorbs rounding errors
            for (i in 0 until rows - 1) {
                val h = if (keepAbsolute) (rowRawHeight[i] * density).toInt() else (rowRawHeight[i] * scale).toInt()
                rowHeightScaled[i] = h
                remainHeight -= h
            }

            rowHeightScaled[rows - 1] = if (keepAbsolute) (rowRawHeight[rows - 1] * density).toInt() else remainHeight

            var xPos = 0
            var yPos = topMarginPx

            var row = 0
            column = 0

            var rowWeightAccumulo = 0f
            var currentRowHeight = rowHeightScaled[0]
            // 当前行行边距（自定义键盘 rowMargin；-1 表示尚未遇到行首键）。
            var currentRowMargin = -1
            // 已计入 xPos 的行首左边距，用于区分"行内尚无内容"与"仅有边距"。
            var rowInset = 0

            // indicates whether the split gap has been inserted in the current row
            var splitInserted = false

            minWidth = 0

            val spacers = mutableListOf<Triple<Int, Int, Int>>()

            // create Key objects, assign position, size, offsets
            for ((keyIndex, rawKey) in keys.withIndex()) {

                val customId = if (theme.isCustom) CustomKeyboards.resolveId(keyboardId) else ""
                val textKey = rawKey

                if (textKey.newRow || currentRowMargin < 0) currentRowMargin = rowMarginOf(textKey)

                val keyWidthWeight =
                    if (textKey.width == 0f && textKey.click.orAbsent != null) keyboardKeyWidth else textKey.width

                var widthPx = (keyWidthWeight * oneWeightWidthPx).toInt()

                // wrap to next row if limits are exceeded
                if ((textKey.newRow && xPos > rowInset) || column >= maxColumns || xPos + widthPx > allowedWidth) {
                    xPos = 0
                    rowInset = 0
                    yPos += currentRowHeight + extraPerGap
                    row++
                    column = 0
                    rowWeightAccumulo = 0f
                    splitInserted = false
                    currentRowHeight = rowHeightScaled[row]
                    currentRowMargin = rowMarginOf(textKey)
                }

                // 行边距：本行按键在 (allowedWidth - 2*margin) 内按权重排布，两侧各留 margin。
                val rowContentWidth = allowedWidth - 2 * currentRowMargin
                if (currentRowMargin > 0) {
                    widthPx = (keyWidthWeight * rowContentWidth / MAX_TOTAL_WEIGHT).toInt()
                    if (xPos == 0) {
                        xPos = currentRowMargin
                        rowInset = currentRowMargin
                    }
                }

                rowWeightAccumulo += keyWidthWeight

                val totalWeight = rowWidthTotalWeight[row]

                // if split keyboard layout is enabled, insert split gap at row middle when cumulative width > 50%
                if (isSplit && !splitInserted && rowWeightAccumulo > totalWeight * 0.5f) {
                    splitInserted = true
                    val gap = (totalWeight * splitRatio * oneWeightWidthPx).toInt()

                    // large keys absorb the gap; small keys shift right
                    if (keyWidthWeight > 20f) {
                        widthPx += gap
                    } else {
                        if (expandKeypressArea && !theme.isCustom) spacers.add(Triple(xPos, gap, row))
                        xPos += gap
                    }
                }

                // 无 click 且无显示内容的才是真正的空白占位；
                // 有 label 无动作的键保留为"不可点击的展示键"，不能当 spacer 吞掉
                if (textKey.isSpacer()) {
                    if (expandKeypressArea && !theme.isCustom) spacers.add(Triple(xPos, widthPx, row))
                    xPos += widthPx
                    continue
                }

                val key = Key(this, theme, textKey)

                // per-key image skin of custom keyboards (color rides via keyBackColor)
                if (customId.isNotEmpty()) {
                    customKeys?.getOrNull(keyIndex)?.first?.let { ck ->
                        key.importedStyle = ck.skin
                        key.customBgDrawable = ck.skin?.let { com.osfans.trime.data.baiduskin.BdsKeyDrawable(it) }
                            ?: ck.resolvedBackground().takeIf { it.type == com.osfans.trime.data.keymap.CustomBackground.Type.IMAGE }
                                ?.drawable(context, widthPx.coerceAtLeast(1))
                    }
                }

                key.keyTextOffsetX = resolveOffset(textKey.keyTextOffsetX, selfConfig.keyTextOffsetX, theme.style.keyTextOffsetX)
                key.keyTextOffsetY = resolveOffset(textKey.keyTextOffsetY, selfConfig.keyTextOffsetY, theme.style.keyTextOffsetY)
                key.keySymbolOffsetX = resolveOffset(textKey.keySymbolOffsetX, selfConfig.keySymbolOffsetX, theme.style.keySymbolOffsetX)
                key.keySymbolOffsetY = resolveOffset(textKey.keySymbolOffsetY, selfConfig.keySymbolOffsetY, theme.style.keySymbolOffsetY)
                key.keyHintOffsetX = resolveOffset(textKey.keyHintOffsetX, selfConfig.keyHintOffsetX, theme.style.keyHintOffsetX)
                key.keyHintOffsetY = resolveOffset(textKey.keyHintOffsetY, selfConfig.keyHintOffsetY, theme.style.keyHintOffsetY)
                key.keyPressOffsetX = resolveOffset(textKey.keyPressOffsetX, selfConfig.keyPressOffsetX, theme.style.keyPressOffsetX)
                key.keyPressOffsetY = resolveOffset(textKey.keyPressOffsetY, selfConfig.keyPressOffsetY, theme.style.keyPressOffsetY)

                key.x = xPos
                key.y = yPos

                // correct minor rounding errors on the right edge
                val rowRight = allowedWidth - currentRowMargin
                val rightGap = abs(rowRight - xPos - widthPx)
                key.width = if (rightGap <= allowedWidth / 100) rowRight - xPos else widthPx

                key.height = currentRowHeight
                key.row = row
                key.column = column

                column++
                xPos += key.width

                mKeys.add(key)

                if (xPos > minWidth) {
                    minWidth = xPos
                }
            }

            // Expand keypress area to edge by distributing spacer widths to neighbors
            if (expandKeypressArea && !theme.isCustom && spacers.isNotEmpty()) {
                for ((spacerX, spacerWidth, spacerRow) in spacers) {
                    val (leftKeys, rightKeys) = mKeys.filter { it.row == spacerRow }.partition { it.x + it.width <= spacerX }
                    val leftKey = leftKeys.maxByOrNull { it.x }
                    val rightKey = rightKeys.minByOrNull { it.x }
                    when {
                        leftKey != null && rightKey != null -> {
                            leftKey.extraWidthRight += spacerWidth / 2
                            rightKey.extraWidthLeft += spacerWidth - spacerWidth / 2
                        }

                        leftKey != null -> leftKey.extraWidthRight += spacerWidth

                        rightKey != null -> rightKey.extraWidthLeft += spacerWidth
                    }
                }
            }

            mKeys.lastOrNull()?.edgeFlags = mKeys.lastOrNull()?.edgeFlags?.or(EDGE_RIGHT) ?: 0

            height = yPos + currentRowHeight
            if (theme.isCustom) minWidth = allowedWidth

            mKeys.forEachIndexed { index, key ->
                key.index = index
                if (key.column == 0) key.edgeFlags = key.edgeFlags or EDGE_LEFT
                if (key.row == 0) key.edgeFlags = key.edgeFlags or EDGE_TOP
                if (key.row == row) key.edgeFlags = key.edgeFlags or EDGE_BOTTOM
            }

            recordHeatLayout()
        }
    }

    private fun buildImported(layout: com.osfans.trime.data.baiduskin.ImportedLayout, data: CustomKeyboards.CustomKeyboard) {
        val edits = data.rows.flatMap { it.keys }.filter { it.skinId.isNotEmpty() }.associateBy { it.skinId }
        val rows = layout.keys.map { it.box.y }.distinct().sorted()
        // 和主排布一致：按键整体收进 sideMarginPx，面板背景仍铺满 allowedWidth。
        val contentWidth = (allowedWidth - 2 * sideMarginPx).coerceAtLeast(1)
        // 上下留白同理：纵向按 keyAreaHeight 排，再整体下移 topMarginPx。
        val importedHeight = (keyboardHeight - topMarginPx - bottomMarginPx).coerceAtLeast(1)
        layout.keys.forEach { placed ->
            val edited = edits[placed.id] ?: CustomKeyboards.CustomKey(placed.label, bindings = placed.bindings.toMutableMap(), skinId = placed.id, skin = placed.style)
            val text = CustomKeyboards.run { edited.toTextKey(data.backgroundFor(context), data.keyAppearance) }
            val key = Key(this, theme, text)
            key.x = sideMarginPx + (placed.box.x / layout.width * contentWidth).toInt()
            key.y = topMarginPx + (placed.box.y / layout.height * importedHeight).toInt()
            key.width = (placed.box.width / layout.width * contentWidth).toInt().coerceAtLeast(1)
            key.height = (placed.box.height / layout.height * importedHeight).toInt().coerceAtLeast(1)
            key.row = rows.indexOf(placed.box.y)
            key.column = layout.keys.count { it.box.y == placed.box.y && it.box.x < placed.box.x }
            key.index = mKeys.size
            // A replacement background in the editor intentionally removes the imported layers.
            key.importedStyle = if (edited.skin == null) null else placed.style
            key.customBgDrawable = key.importedStyle?.let { com.osfans.trime.data.baiduskin.BdsKeyDrawable(it) }
                ?: edited.resolvedBackground().takeIf { it.type == com.osfans.trime.data.keymap.CustomBackground.Type.IMAGE }?.drawable(context, key.width)
            mKeys.add(key)
        }
        minWidth = allowedWidth
        height = keyboardHeight
        recordHeatLayout()
    }

    fun recordHeatLayout() {
        InputStats.recordLayoutGeometry(mKeys.groupBy { it.row }.toSortedMap().values.map { rowKeys ->
            rowKeys.sortedBy { it.x }.map {
                InputStats.HeatKey(it.heatLabel(), it.x.toFloat() / allowedWidth,
                    it.width.toFloat() / allowedWidth, it.height.toFloat() / context.resources.displayMetrics.density)
            }
        })
    }


    fun setModifierKey(
        c: Int,
        key: Key?,
    ) {
        when (c) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                mShiftKey = key
            }

            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                mCtrlKey = key
            }

            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> {
                mMetaKey = key
            }

            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                mAltKey = key
            }

            KeyEvent.KEYCODE_SYM -> {
                mSymKey = key
            }
        }
    }

    val keys: List<Key>
        get() = mKeys

    /** Keep modifier latches when changing presentation or rebuilding geometry. */
    fun copyInputStateFrom(other: Keyboard) {
        if (other === this) return
        modifier = other.modifier
        listOf(mShiftKey to other.mShiftKey, mAltKey to other.mAltKey,
            mCtrlKey to other.mCtrlKey, mMetaKey to other.mMetaKey, mSymKey to other.mSymKey).forEach { (target, source) ->
            target?.setOn(false)
            if (source?.isOn == true) target?.setOn(true)
        }
        lastAsciiMode = other.lastAsciiMode
    }

    private fun setModifier(
        mask: Int,
        value: Boolean,
    ): Boolean {
        if (modifier.hasFlag(mask) == value) return false
        modifier = if (value) modifier or mask else modifier and mask.inv()
        return true
    }

    val isShifted: Boolean
        get() = modifier.hasFlag(KeyEvent.META_SHIFT_ON) || mShiftKey?.isOn == true

    val isOnlyShiftOn: Boolean
        get() =
            isShifted &&
                !modifier.hasFlag(KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SYM_ON or KeyEvent.META_META_ON)

    /**
     * 设置Shift键状态（用于自动大写）
     *
     * @param on 是否锁定Shift键
     * @param shifted 是否按下Shift键
     * @return Shift键状态是否改变
     */
    fun setShifted(
        on: Boolean,
        shifted: Boolean,
    ): Boolean {
        mShiftKey?.setOn(on)
        return setModifier(KeyEvent.META_SHIFT_ON, shifted)
    }

    /**
     * 设置修饰键的状态
     *
     * @param on 是否锁定修饰键
     * @param keycode 修饰键的 KeyEvent 掩码
     * @return 修饰键状态是否改变
     */
    fun clickModifierKey(
        on: Boolean,
        keycode: Int,
    ): Boolean {
        val keyDown = !modifier.hasFlag(keycode)
        val modifierKey =
            when (keycode) {
                KeyEvent.META_SHIFT_ON -> mShiftKey
                KeyEvent.META_ALT_ON -> mAltKey
                KeyEvent.META_CTRL_ON -> mCtrlKey
                KeyEvent.META_META_ON -> mMetaKey
                KeyEvent.KEYCODE_SYM -> mSymKey
                else -> null
            }
        val keepOn = modifierKey?.setOn(on) ?: on
        return if (on) setModifier(keycode, keepOn) else setModifier(keycode, keyDown)
    }

    fun resetShift() {
        mShiftKey?.setOn(false)
        setModifier(KeyEvent.META_SHIFT_ON, false)
    }

    /**
     * Caps-Lock action for soft keys and named functions. The IME has no real
     * hardware caps state, so latch the keys' shift state until it is toggled off.
     */
    fun toggleCapsLock(): Boolean {
        val lock = mShiftKey?.isOn != true
        setShifted(lock, lock)
        return true
    }

    fun refreshModifier(): Boolean {
        // 这里改为了一次性重置全部修饰键状态并返回TRUE刷新UI，可能有bug
        var result = false
        if (mShiftKey != null && !mShiftKey!!.isOn) result = result || setModifier(KeyEvent.META_SHIFT_ON, false)
        if (mAltKey != null && !mAltKey!!.isOn) result = result || setModifier(KeyEvent.META_ALT_ON, false)
        if (mCtrlKey != null && !mCtrlKey!!.isOn) result = result || setModifier(KeyEvent.META_CTRL_ON, false)
        if (mMetaKey != null && !mMetaKey!!.isOn) result = result || setModifier(KeyEvent.META_META_ON, false)
        if (mSymKey != null && !mSymKey!!.isOn) result = result || setModifier(KeyEvent.KEYCODE_SYM, false)
        return result
    }

    private fun computeNearestNeighbors() {
        // Round-up so we don't have any pixels outside the grid
        mCellWidth = (minWidth + GRID_WIDTH - 1) / GRID_WIDTH
        mCellHeight = (height + GRID_HEIGHT - 1) / GRID_HEIGHT
        gridNeighbors = arrayOfNulls(GRID_SIZE)
        val indices = IntArray(mKeys.size)
        val gridWidth = GRID_WIDTH * mCellWidth
        val gridHeight = GRID_HEIGHT * mCellHeight
        var x = 0
        while (x < gridWidth) {
            var y = 0
            while (y < gridHeight) {
                var count = 0
                for (i in mKeys.indices) {
                    val key = mKeys[i]
                    if (key.squaredDistanceFrom(x, y) < proximityThreshold ||
                        key.squaredDistanceFrom(x + mCellWidth - 1, y) < proximityThreshold ||
                        (
                            key.squaredDistanceFrom(x + mCellWidth - 1, y + mCellHeight - 1)
                                < proximityThreshold
                            ) ||
                        key.squaredDistanceFrom(x, y + mCellHeight - 1) < proximityThreshold ||
                        key.isInside(x, y) ||
                        key.isInside(x + mCellWidth - 1, y) ||
                        key.isInside(x + mCellWidth - 1, y + mCellHeight - 1) ||
                        key.isInside(x, y + mCellHeight - 1)
                    ) {
                        indices[count++] = i
                    }
                }
                val cell = IntArray(count)
                System.arraycopy(indices, 0, cell, 0, count)
                gridNeighbors?.set(y / mCellHeight * GRID_WIDTH + x / mCellWidth, cell)
                y += mCellHeight
            }
            x += mCellWidth
        }
    }

    /**
     * Returns the indices of the keys that are closest to the given point.
     *
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the array of integer indices for the nearest keys to the given point. If the given
     * point is out of range, then an array of size zero is returned.
     */
    fun getNearestKeys(
        x: Int,
        y: Int,
    ): IntArray? {
        if (gridNeighbors == null) computeNearestNeighbors()
        if (x in 0 until minWidth && y in 0 until height) {
            val index = y / mCellHeight * GRID_WIDTH + x / mCellWidth
            if (index < GRID_SIZE) {
                return gridNeighbors!![index]
            }
        }
        return IntArray(0)
    }

    val isLabelUppercase: Boolean
        get() = labelTransform == TextKeyboard.LabelTransform.UPPERCASE

    /**
     * 行边距 px（由 generatedKeys 填充；主题键盘无此概念返回 0），
     * 叠加外观「整体」页的键盘左右留白。
     */
    private fun rowMarginOf(textKey: TextKeyboard.TextKey): Int =
        (if (theme.isCustom && textKey.rowMargin >= 0) context.dp(textKey.rowMargin) else 0) + sideMarginPx

    /** 按键上方符号的水平对齐：left / center / right。 */
    val symbolAlign: String
        get() = selfConfig?.symbolAlign?.takeIf { it in setOf("left", "center", "right") } ?: "center"
    val hintAlign: String
        get() = selfConfig?.hintAlign?.takeIf { it in setOf("left", "center", "right") } ?: symbolAlign

    companion object {
        const val EDGE_LEFT = 0x01
        const val EDGE_RIGHT = 0x02
        const val EDGE_TOP = 0x04
        const val EDGE_BOTTOM = 0x08
        private const val GRID_WIDTH = 10
        private const val GRID_HEIGHT = 5
        private const val GRID_SIZE = GRID_WIDTH * GRID_HEIGHT
        private const val MAX_TOTAL_WEIGHT = 100

        /** Number of key widths from current touch point to search for nearest keys.  */
        const val SEARCH_DISTANCE = 1.4f

        fun Keyboard.decodeBgImage(path: String): android.graphics.drawable.Drawable =
            com.osfans.trime.data.theme.AssetBitmapDrawable(path)

    }
}


private fun TextKeyboard.TextKey.isSpacer(): Boolean = label.isEmpty() && listOf(
    click, longClick, doubleClick, lazyDoubleClick, swipeUp, swipeDown, swipeLeft, swipeRight,
    composing, hasMenu, paging, combo, ascii,
).all { it.orAbsent == null }
