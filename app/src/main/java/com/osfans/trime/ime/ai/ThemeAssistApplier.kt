/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.ai

import com.osfans.trime.data.keymap.CustomBackground
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import com.osfans.trime.data.keymap.FontSpec
import com.osfans.trime.data.keymap.KeyAppearance
import com.osfans.trime.data.keymap.KeyboardOptions
import com.osfans.trime.data.keymap.KeyboardSetOperations
import com.osfans.trime.data.keymap.KeyboardSets
import com.osfans.trime.data.theme.ColorKey
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.TextKeyboard.TextKey
import com.osfans.trime.ime.appearance.PanelGradient
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class AssistChange(val label: String, val before: String, val after: String)

data class AssistReport(
    val changes: MutableList<AssistChange> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf(),
) {
    fun change(label: String, before: String, after: String) {
        if (before != after) changes += AssistChange(label, before, after)
    }
}

private val HEX_COLOR = Regex("^#(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

internal fun normalizedColor(raw: String): String? = raw.trim().uppercase().takeIf { HEX_COLOR.matches(it) }

private fun JsonElement.displayText(): String = when (this) {
    is JsonNull -> "继承 / 默认"
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() } ?: "继承 / 默认"
    else -> toString()
}

private fun Float.display(): String = if (this % 1f == 0f) toInt().toString() else toString()

private fun Int.display(): String = toString()

// ------------------------------------------------------------- custom keyboard

/** Labels shown in the change list; the field names are the editor's own. */
private val CUSTOM_TYPOGRAPHY_ROLES = linkedMapOf(
    "candidate" to "候选文字",
    "candidateHl" to "高亮候选文字",
    "comment" to "候选注释",
    "label" to "候选序号",
    "popup" to "按键弹出预览",
    "text" to "编码 / 预编辑文字",
    "toolbar" to "工具栏",
)

private val SURFACE_LABELS = mapOf(
    "keysBackground" to "按键背景",
    "keysBorderColor" to "按键描边颜色",
    "keysBorderWidth" to "按键描边宽度",
    "keysRadius" to "按键圆角",
    "keysBlur" to "按键模糊",
    "keyOpacity" to "按键不透明度",
    "keyBlur" to "按键自模糊",
    "blurRadius" to "面板模糊半径",
    "glassOpacity" to "玻璃浓度",
    "blurSaturation" to "模糊饱和度",
    "keyboardCorners" to "键盘四角圆角",
    "keyboardBorderColor" to "整块描边颜色",
    "keyboardBorderWidth" to "整块描边宽度",
    "keyboardBorderGlow" to "描边高光",
    "keyboardSideMargin" to "左右留白",
    "keyboardTopMargin" to "顶部留白",
    "keyboardBottomMargin" to "底部留白",
    "candidateHighlightColor" to "候选高亮颜色",
    "candidateHighlightRadius" to "候选高亮圆角",
    "gradientEnabled" to "面板渐变",
    "gradientStart" to "渐变起始色",
    "gradientCenter" to "渐变中间色",
    "gradientEnd" to "渐变结束色",
    "gradientDirection" to "渐变方向",
    "gradientRadius" to "径向渐变半径",
)

private val GEOMETRY_LABELS = mapOf(
    "keyboardHeightDp" to "键盘高度",
    "landscapeHeightDp" to "横屏高度",
    "rowSpacing" to "行间距",
    "rowMargin" to "行边距",
    "toolbarHeightDp" to "工具栏高度",
    "toolbarIconSizeDp" to "工具栏图标大小",
    "horizontalGap" to "横向键距",
    "heightPercent" to "高度比例",
    "landscapeHeightPercent" to "横屏高度比例",
    "landscapeSplitPercent" to "横屏分割比例",
)

data class CustomUndo(
    val rows: List<CustomRow>,
    val keyAppearance: KeyAppearance.Style,
    val surfaceAppearance: SurfaceAppearanceConfig?,
    val background: CustomBackground?,
    val keyboardHeightDp: Int,
    val landscapeHeightDp: Int,
    val rowSpacing: Int,
    val rowMargin: Int,
    val toolbarHeightDp: Int,
    val toolbarIconSizeDp: Int,
    val options: KeyboardOptions,
    val typography: Map<String, FontSpec>,
    val importedSkin: com.osfans.trime.data.baiduskin.ImportedSkin?,
)

object CustomThemeApplier {
    /** Visual key fields the assistant may touch; content and behaviour stay out. */
    val keyFields: Set<String> =
        KeyAppearance.fields.keys - setOf("labelSymbol", "hint", "popup", "sendBindings", "icon")

    fun snapshot(kb: CustomKeyboard, surface: SurfaceAppearanceConfig): JsonObject = buildJsonObject {
        put("name", kb.name)
        putJsonObject("keyAppearance") {
            KeyAppearance.values(kb.keyAppearance).forEach { (name, value) ->
                if (name in keyFields) put(name, value)
            }
        }
        putJsonObject("surface") {
            put("keysBackground", surface.keys.background)
            put("keysBorderColor", surface.keys.borderColor)
            surface.keys.borderWidth?.let { put("keysBorderWidth", it) }
            surface.keys.radius?.let { put("keysRadius", it) }
            put("keysBlur", surface.keys.blur)
            put("keyOpacity", surface.keyOpacity)
            put("keyBlur", surface.keyBlur)
            put("blurRadius", surface.blurRadius)
            put("glassOpacity", surface.glassOpacity)
            put("blurSaturation", surface.blurSaturation)
            put("keyboardCorners", buildJsonArray { surface.keyboardCorners.forEach { add(JsonPrimitive(it)) } })
            put("keyboardBorderColor", surface.keyboardBorderColor)
            put("keyboardBorderWidth", surface.keyboardBorderWidth)
            put("keyboardBorderGlow", surface.keyboardBorderGlow)
            put("keyboardSideMargin", surface.keyboardSideMargin)
            put("keyboardTopMargin", surface.keyboardTopMargin)
            put("keyboardBottomMargin", surface.keyboardBottomMargin)
            put("candidateHighlightColor", surface.candidateHighlightColor)
            put("candidateHighlightRadius", surface.candidateHighlightRadius)
            put("gradientEnabled", surface.gradient.enabled)
            put("gradientStart", surface.gradient.start)
            put("gradientCenter", surface.gradient.center)
            put("gradientEnd", surface.gradient.end)
            put("gradientDirection", surface.gradient.direction)
            put("gradientRadius", surface.gradient.radius)
        }
        putJsonObject("geometry") {
            put("keyboardHeightDp", kb.keyboardHeightDp)
            put("landscapeHeightDp", kb.landscapeHeightDp)
            put("rowSpacing", kb.rowSpacing)
            put("rowMargin", kb.rowMargin)
            put("toolbarHeightDp", kb.toolbarHeightDp)
            put("toolbarIconSizeDp", kb.toolbarIconSizeDp)
            put("horizontalGap", kb.options.horizontalGap)
            put("heightPercent", kb.options.heightPercent)
            put("landscapeHeightPercent", kb.options.landscapeHeightPercent)
            put("landscapeSplitPercent", kb.options.landscapeSplitPercent)
        }
        putJsonObject("typography") {
            CUSTOM_TYPOGRAPHY_ROLES.keys.forEach { role ->
                val spec = kb.typography[role] ?: FontSpec()
                putJsonObject(role) {
                    put("size", spec.size)
                    put("color", spec.color)
                }
            }
        }
        put("background", backgroundText(kb))
        putJsonArray("keys") {
            var count = 0
            kb.rows.forEachIndexed { rowIndex, row ->
                row.keys.forEachIndexed { keyIndex, key ->
                    if (count < 80) {
                        count++
                        add(buildJsonObject {
                            put("row", rowIndex)
                            put("key", keyIndex)
                            put("label", key.label)
                            put("binding", key.bindings.entries.firstOrNull { it.value.isNotBlank() }?.value.orEmpty())
                        })
                    }
                }
            }
        }
    }

    fun copyForPreview(kb: CustomKeyboard): CustomKeyboard = kb.copy(
        keyAppearance = kb.keyAppearance.copy(),
        surfaceAppearance = kb.surfaceAppearance?.copy(),
        options = kb.options.copy(),
        typography = kb.typography.toMap(),
        rows = kb.rows.map { row ->
            row.copy(keys = row.keys.map { key -> key.copy(bindings = key.bindings.toMutableMap()) }.toMutableList())
        }.toMutableList(),
    )

    fun captureUndo(kb: CustomKeyboard): CustomUndo = CustomUndo(
        rows = kb.rows.map { row ->
            row.copy(keys = row.keys.map { key -> key.copy(bindings = key.bindings.toMutableMap()) }.toMutableList())
        },
        keyAppearance = kb.keyAppearance.copy(),
        surfaceAppearance = kb.surfaceAppearance?.copy(),
        background = kb.background,
        keyboardHeightDp = kb.keyboardHeightDp,
        landscapeHeightDp = kb.landscapeHeightDp,
        rowSpacing = kb.rowSpacing,
        rowMargin = kb.rowMargin,
        toolbarHeightDp = kb.toolbarHeightDp,
        toolbarIconSizeDp = kb.toolbarIconSizeDp,
        options = kb.options.copy(),
        typography = kb.typography.toMap(),
        importedSkin = kb.importedSkin,
    )

    fun restore(kb: CustomKeyboard, undo: CustomUndo) {
        kb.rows.clear()
        kb.rows.addAll(undo.rows.map { row ->
            row.copy(keys = row.keys.map { key -> key.copy(bindings = key.bindings.toMutableMap()) }.toMutableList())
        })
        kb.keyAppearance = undo.keyAppearance
        kb.surfaceAppearance = undo.surfaceAppearance
        kb.background = undo.background
        kb.keyboardHeightDp = undo.keyboardHeightDp
        kb.landscapeHeightDp = undo.landscapeHeightDp
        kb.rowSpacing = undo.rowSpacing
        kb.rowMargin = undo.rowMargin
        kb.toolbarHeightDp = undo.toolbarHeightDp
        kb.toolbarIconSizeDp = undo.toolbarIconSizeDp
        kb.options = undo.options
        kb.typography = undo.typography
        kb.importedSkin = undo.importedSkin
    }

    fun apply(
        kb: CustomKeyboard,
        patch: CustomThemePatch,
        surfaceBase: SurfaceAppearanceConfig,
        report: AssistReport,
    ) {
        applyKeyboardAppearance(kb, patch.keyAppearance, report)
        applySurface(kb, patch.surface, surfaceBase, report)
        // If the surface config did not exist before this batch, the shape sync
        // inside applyKeyboardAppearance had nothing to write to. Once surface
        // exists, mirror the page radius/border again unless the user explicitly
        // changed the surface shape (then that value wins).
        if (kb.surfaceAppearance != null &&
            ("roundCorner" in patch.keyAppearance || "keyBorder" in patch.keyAppearance) &&
            patch.surface.keysRadius == null && patch.surface.keysBorderWidth == null
        ) {
            kb.surfaceAppearance = KeyboardSetOperations.shapeKeys(
                kb, kb.keyAppearance.roundCorner, kb.keyAppearance.keyBorder,
            ).surfaceAppearance
        }
        applyBackground(kb, patch.backgroundColor, report)
        applyGeometry(kb, patch.geometry, report)
        applyTypography(kb, patch.typography, report)
        applyPerKey(kb, patch.keys, report)
    }

    private fun applyKeyboardAppearance(kb: CustomKeyboard, patch: Map<String, String>, report: AssistReport) {
        if (patch.isEmpty()) return
        val old = kb.keyAppearance
        val new = applyKeyStyle(old, patch, "按键默认外观", report)
        if (new == old) return
        val changed = KeyboardSets.difference(KeyAppearance.values(old), KeyAppearance.values(new)).keys
        kb.keyAppearance = new
        // 圆角/描边两处存储必须同步，否则 KeyView 会读到旧的那一份。
        val shaped = KeyboardSetOperations.shapeKeys(kb, new.roundCorner, new.keyBorder)
        kb.surfaceAppearance = shaped.surfaceAppearance
        val pushed = KeyboardSetOperations.pushAppearance(kb, changed)
        kb.rows.clear()
        kb.rows.addAll(pushed.rows)
    }

    private fun applySurface(
        kb: CustomKeyboard,
        patch: SurfacePatch,
        surfaceBase: SurfaceAppearanceConfig,
        report: AssistReport,
    ) {
        if (patch.isEmpty) return
        val base = kb.surfaceAppearance ?: surfaceBase
        var keys = base.keys
        patch.keysBackground?.let { raw ->
            val value = normalizedColor(raw)
            if (value == null) report.warnings += "按键背景颜色格式不正确，已忽略"
            else {
                report.change(SURFACE_LABELS.getValue("keysBackground"), keys.background.ifBlank { "跟随主题" }, value)
                keys = keys.copy(background = value)
            }
        }
        patch.keysBorderColor?.let { raw ->
            val value = normalizedColor(raw)
            if (value == null) report.warnings += "按键描边颜色格式不正确，已忽略"
            else {
                report.change(SURFACE_LABELS.getValue("keysBorderColor"), keys.borderColor.ifBlank { "跟随主题" }, value)
                keys = keys.copy(borderColor = value)
            }
        }
        patch.keysBorderWidth?.let { raw ->
            val value = clamp(raw, 0f, 32f, "按键描边宽度", report)
            report.change(SURFACE_LABELS.getValue("keysBorderWidth"), keys.borderWidth?.display() ?: "继承", value.display())
            keys = keys.copy(borderWidth = value)
        }
        patch.keysRadius?.let { raw ->
            val value = clamp(raw, 0f, 64f, "按键圆角", report)
            report.change(SURFACE_LABELS.getValue("keysRadius"), keys.radius?.display() ?: "继承", value.display())
            keys = keys.copy(radius = value)
        }
        patch.keysBlur?.let { value ->
            report.change(SURFACE_LABELS.getValue("keysBlur"), if (keys.blur) "开" else "关", if (value) "开" else "关")
            keys = keys.copy(blur = value)
        }

        val corners = patch.keyboardCorners?.let { raw ->
            if (raw.size != 4) {
                report.warnings += "键盘四角需要 4 个数字，已忽略"
                base.keyboardCorners
            } else raw.map { it.coerceIn(0f, 64f) }
        } ?: base.keyboardCorners

        val gradient = base.gradient.copy(
            enabled = patch.gradientEnabled ?: base.gradient.enabled,
            start = patch.gradientStart?.let { colorOrWarn(it, "渐变起始色", base.gradient.start, report) } ?: base.gradient.start,
            center = patch.gradientCenter?.let { colorOrWarn(it, "渐变中间色", base.gradient.center, report) } ?: base.gradient.center,
            end = patch.gradientEnd?.let { colorOrWarn(it, "渐变结束色", base.gradient.end, report) } ?: base.gradient.end,
            direction = patch.gradientDirection?.let { clamp(it, 0, 9, "渐变方向", report) } ?: base.gradient.direction,
            radius = patch.gradientRadius?.let { clamp(it, 10, 200, "径向渐变半径", report) } ?: base.gradient.radius,
        )

        val updated = base.copy(
            keys = keys,
            keyboardCorners = corners,
            keyOpacity = patch.keyOpacity?.let { clamp(it, 0, 100, "按键不透明度", report) } ?: base.keyOpacity,
            keyBlur = patch.keyBlur?.let { clamp(it, 0, 100, "按键自模糊", report) } ?: base.keyBlur,
            blurRadius = patch.blurRadius?.let { clamp(it, 2, 32, "面板模糊半径", report) } ?: base.blurRadius,
            glassOpacity = patch.glassOpacity?.let { clamp(it, 0, 100, "玻璃浓度", report) } ?: base.glassOpacity,
            blurSaturation = patch.blurSaturation?.let { clamp(it, 100, 200, "模糊饱和度", report) } ?: base.blurSaturation,
            keyboardBorderColor = patch.keyboardBorderColor?.let {
                colorOrWarn(it, "整块描边颜色", base.keyboardBorderColor, report)
            } ?: base.keyboardBorderColor,
            keyboardBorderWidth = patch.keyboardBorderWidth?.let {
                clamp(it, 0f, 5f, "整块描边宽度", report)
            } ?: base.keyboardBorderWidth,
            keyboardBorderGlow = patch.keyboardBorderGlow?.let { clamp(it, 0, 100, "描边高光", report) } ?: base.keyboardBorderGlow,
            keyboardSideMargin = patch.keyboardSideMargin?.let { clamp(it, 0f, 20f, "左右留白", report) } ?: base.keyboardSideMargin,
            keyboardTopMargin = patch.keyboardTopMargin?.let { clamp(it, 0f, 20f, "顶部留白", report) } ?: base.keyboardTopMargin,
            keyboardBottomMargin = patch.keyboardBottomMargin?.let { clamp(it, 0f, 20f, "底部留白", report) } ?: base.keyboardBottomMargin,
            candidateHighlightColor = patch.candidateHighlightColor?.let {
                colorOrWarn(it, "候选高亮颜色", base.candidateHighlightColor, report)
            } ?: base.candidateHighlightColor,
            candidateHighlightRadius = patch.candidateHighlightRadius?.let {
                clamp(it, -1f, 32f, "候选高亮圆角", report)
            } ?: base.candidateHighlightRadius,
            gradient = gradient,
        ).sanitized()
        if (updated != base) {
            if (patch.keyboardCorners != null) report.change(
                SURFACE_LABELS.getValue("keyboardCorners"),
                base.keyboardCorners.joinToString("/") { it.display() }.ifBlank { "继承" },
                updated.keyboardCorners.joinToString("/") { it.display() }.ifBlank { "继承" },
            )
            if (patch.gradientEnabled != null) report.change(
                SURFACE_LABELS.getValue("gradientEnabled"),
                if (base.gradient.enabled) "开" else "关",
                if (updated.gradient.enabled) "开" else "关",
            )
            kb.surfaceAppearance = updated
        }
    }

    private fun applyBackground(kb: CustomKeyboard, raw: String?, report: AssistReport) {
        if (raw == null) return
        val text = raw.trim()
        val background = when {
            text.equals("transparent", ignoreCase = true) -> CustomBackground(CustomBackground.Type.TRANSPARENT)
            else -> normalizedColor(text)?.let { CustomBackground(CustomBackground.Type.COLOR, color = it) }
        }
        if (background == null) {
            report.warnings += "键盘背景颜色格式不正确（只支持 #AARRGGBB / #RRGGBB / transparent），已忽略"
            return
        }
        report.change("键盘背景", backgroundText(kb).ifBlank { "跟随默认" }, backgroundText(background))
        kb.background = background
    }

    private fun applyGeometry(kb: CustomKeyboard, patch: GeometryPatch, report: AssistReport) {
        if (patch.isEmpty) return
        patch.keyboardHeightDp?.let {
            val value = clamp(it, 0, 800, "键盘高度", report)
            report.change(GEOMETRY_LABELS.getValue("keyboardHeightDp"), kb.keyboardHeightDp.display(), value.display())
            kb.keyboardHeightDp = value
        }
        patch.landscapeHeightDp?.let {
            val value = clamp(it, 0, 800, "横屏高度", report)
            report.change(GEOMETRY_LABELS.getValue("landscapeHeightDp"), kb.landscapeHeightDp.display(), value.display())
            kb.landscapeHeightDp = value
        }
        patch.rowSpacing?.let {
            val value = clamp(it, 0, kb.maxRowSpacing(), "行间距", report)
            report.change(GEOMETRY_LABELS.getValue("rowSpacing"), kb.rowSpacing.display(), value.display())
            kb.rowSpacing = value
        }
        patch.rowMargin?.let {
            val value = clamp(it, 0, 48, "行边距", report)
            report.change(GEOMETRY_LABELS.getValue("rowMargin"), kb.rowMargin.display(), value.display())
            kb.rowMargin = value
        }
        patch.toolbarHeightDp?.let {
            val value = clamp(it, 0, 120, "工具栏高度", report)
            report.change(GEOMETRY_LABELS.getValue("toolbarHeightDp"), kb.toolbarHeightDp.display(), value.display())
            kb.toolbarHeightDp = value
        }
        patch.toolbarIconSizeDp?.let {
            val value = clamp(it, 0, 96, "工具栏图标大小", report)
            report.change(GEOMETRY_LABELS.getValue("toolbarIconSizeDp"), kb.toolbarIconSizeDp.display(), value.display())
            kb.toolbarIconSizeDp = value
        }
        var options = kb.options
        patch.horizontalGap?.let {
            val value = clamp(it, 0, 32, "横向键距", report)
            report.change(GEOMETRY_LABELS.getValue("horizontalGap"), options.horizontalGap.display(), value.display())
            options = options.copy(horizontalGap = value)
        }
        patch.heightPercent?.let {
            val value = clamp(it, 50, 200, "高度比例", report)
            report.change(GEOMETRY_LABELS.getValue("heightPercent"), options.heightPercent.display(), value.display())
            options = options.copy(heightPercent = value)
        }
        patch.landscapeHeightPercent?.let {
            val value = clamp(it, 50, 200, "横屏高度比例", report)
            report.change(GEOMETRY_LABELS.getValue("landscapeHeightPercent"), options.landscapeHeightPercent.display(), value.display())
            options = options.copy(landscapeHeightPercent = value)
        }
        patch.landscapeSplitPercent?.let {
            val value = clamp(it, 0, 100, "横屏分割比例", report)
            report.change(GEOMETRY_LABELS.getValue("landscapeSplitPercent"), options.landscapeSplitPercent.display(), value.display())
            options = options.copy(landscapeSplitPercent = value)
        }
        if (options != kb.options) {
            runCatching { options.validated() }
                .onSuccess { kb.options = it }
                .onFailure { report.warnings += "尺寸设置有误：${it.message}" }
        }
    }

    private fun applyTypography(kb: CustomKeyboard, patch: Map<String, TypographyPatch>, report: AssistReport) {
        patch.forEach { (role, value) ->
            if (role !in CUSTOM_TYPOGRAPHY_ROLES) {
                report.warnings += "typography.$role 不是可修改的文字角色，已忽略"
                return@forEach
            }
            val old = kb.typography[role] ?: FontSpec()
            var updated = old
            if (value.size != null) {
                if (role == "candidateHl") {
                    report.warnings += "高亮候选文字只支持改颜色，字号跟随候选文字，已忽略字号"
                } else {
                    val size = value.size.takeIf { it.isFinite() && it in 0f..200f }
                    if (size == null) report.warnings += "${CUSTOM_TYPOGRAPHY_ROLES.getValue(role)}字号需在 0–200，已忽略"
                    else updated = updated.copy(size = size)
                }
            }
            if (value.color != null) {
                val color = if (value.color.isBlank()) "" else normalizedColor(value.color)
                if (color == null) report.warnings += "${CUSTOM_TYPOGRAPHY_ROLES.getValue(role)}颜色格式不正确，已忽略"
                else updated = updated.copy(color = color)
            }
            if (updated != old) {
                if (updated.size != old.size) report.change(
                    "${CUSTOM_TYPOGRAPHY_ROLES.getValue(role)}字号",
                    old.size.display().takeIf { old.size > 0f } ?: "跟随默认",
                    updated.size.display().takeIf { updated.size > 0f } ?: "跟随默认",
                )
                if (updated.color != old.color) report.change(
                    "${CUSTOM_TYPOGRAPHY_ROLES.getValue(role)}颜色",
                    old.color.ifBlank { "跟随主题" },
                    updated.color.ifBlank { "跟随主题" },
                )
                kb.typography = kb.typography + (role to updated)
            }
        }
    }

    private fun applyPerKey(kb: CustomKeyboard, patches: List<CustomKeyPatch>, report: AssistReport) {
        patches.forEach { patch ->
            val (rowIndex, keyIndex) = resolveKey(kb, patch) ?: run {
                report.warnings += "找不到要修改的按键${patch.row?.let { r -> "（第 ${r + 1} 行" } ?: ""}${patch.key?.let { k -> "第 ${k + 1} 键）" } ?: ""}，已忽略"
                return@forEach
            }
            val key = kb.rows[rowIndex].keys[keyIndex]
            val prefix = "第 ${rowIndex + 1} 行第 ${keyIndex + 1} 键${key.label.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}"
            val newStyle = applyKeyStyle(key.appearance, patch.appearance, prefix, report)
            if (newStyle == key.appearance) return@forEach
            val own = key.setStyleOverrides?.toMutableMap() ?: mutableMapOf()
            if ("roundCorner" in patch.appearance) own["roundCorner"] = JsonPrimitive(newStyle.roundCorner)
            if ("keyBorder" in patch.appearance) own["keyBorder"] = JsonPrimitive(newStyle.keyBorder)
            kb.rows[rowIndex].keys[keyIndex] = key.copy(
                appearance = newStyle,
                setStyleOverrides = own.takeIf { it.isNotEmpty() }?.let { JsonObject(it) },
            )
        }
    }

    fun keyForPatch(kb: CustomKeyboard, patch: CustomKeyPatch): CustomKey? =
        resolveKey(kb, patch)?.let { (row, key) -> kb.rows[row].keys[key] }

    private fun resolveKey(kb: CustomKeyboard, patch: CustomKeyPatch): Pair<Int, Int>? {
        val row = patch.row
        val key = patch.key
        if (row != null && key != null) {
            return if (row in kb.rows.indices && key in kb.rows[row].keys.indices) row to key else null
        }
        val label = patch.label?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        kb.rows.forEachIndexed { rowIndex, customRow ->
            customRow.keys.forEachIndexed { keyIndex, customKey ->
                if (customKey.label == label || customKey.bindings.values.any { it == label }) {
                    return rowIndex to keyIndex
                }
            }
        }
        return null
    }

    private fun applyKeyStyle(
        base: KeyAppearance.Style,
        patch: Map<String, String>,
        prefix: String,
        report: AssistReport,
    ): KeyAppearance.Style {
        var current = base
        patch.forEach { (name, text) ->
            if (name !in keyFields) {
                report.warnings += "$prefix：不支持修改 $name，已忽略"
                return@forEach
            }
            val candidate = runCatching {
                KeyAppearance.parse(KeyAppearance.textValues(current) + (name to text))
            }.getOrElse {
                report.warnings += "$prefix · ${KeyAppearance.fields[name] ?: name}：${it.message ?: "格式不正确"}，已忽略"
                return@forEach
            }
            val before = KeyAppearance.values(current)[name] ?: JsonNull
            val after = KeyAppearance.values(candidate)[name] ?: JsonNull
            if (before != after) {
                report.change(
                    "$prefix · ${KeyAppearance.fields[name] ?: name}",
                    before.displayText(),
                    after.displayText(),
                )
            }
            current = candidate
        }
        return current
    }

    private fun backgroundText(kb: CustomKeyboard): String = backgroundText(kb.background)

    private fun backgroundText(background: CustomBackground?): String = when (background?.normalized()?.type) {
        CustomBackground.Type.COLOR -> background.color
        CustomBackground.Type.TRANSPARENT -> "transparent"
        else -> ""
    }

    private fun colorOrWarn(raw: String, label: String, fallback: String, report: AssistReport): String {
        val value = normalizedColor(raw)
        if (value == null) {
            report.warnings += "${label}颜色格式不正确，已保留原值"
            return fallback
        }
        return value
    }

    private fun clamp(value: Float, min: Float, max: Float, label: String, report: AssistReport): Float {
        if (!value.isFinite()) {
            report.warnings += "$label 不是有效数字，已忽略"
            return min
        }
        if (value !in min..max) {
            report.warnings += "$label 超出范围，已收敛到 ${value.coerceIn(min, max).display()}"
            return value.coerceIn(min, max)
        }
        return value
    }

    private fun clamp(value: Int, min: Int, max: Int, label: String, report: AssistReport): Int {
        if (value !in min..max) {
            report.warnings += "$label 超出范围，已收敛到 ${value.coerceIn(min, max)}"
            return value.coerceIn(min, max)
        }
        return value
    }
}

// ---------------------------------------------------------------- YAML theme

private val THEME_STYLE_LABELS = mapOf(
    "keyboard_height" to "键盘高度",
    "keyboard_height_land" to "横屏高度",
    "key_height" to "按键高度",
    "key_width" to "按键宽度",
    "horizontal_gap" to "横向键距",
    "vertical_gap" to "纵向键距",
    "round_corner" to "按键圆角",
    "key_border" to "按键描边",
    "key_text_size" to "按键字号",
    "key_long_text_size" to "长按标签字号",
    "symbol_text_size" to "符号字号",
    "label_text_size" to "序号字号",
    "candidate_text_size" to "候选字号",
    "candidate_view_height" to "候选栏高度",
    "candidate_corner_radius" to "候选圆角",
    "candidate_border_round" to "候选描边圆角",
    "candidate_padding" to "候选内边距",
    "candidate_spacing" to "候选间距",
    "comment_text_size" to "注释字号",
    "comment_height" to "注释高度",
    "popup_text_size" to "弹出预览字号",
    "popup_key_height" to "弹出按键高度",
    "popup_width" to "弹出宽度",
    "popup_height" to "弹出高度",
    "shadow_radius" to "阴影半径",
    "keyboard_padding" to "键盘内边距",
    "keyboard_padding_left" to "左内边距",
    "keyboard_padding_right" to "右内边距",
    "keyboard_padding_bottom" to "底部内边距",
    "keyboard_padding_land" to "横屏内边距",
    "keyboard_padding_land_bottom" to "横屏底部内边距",
)

private val THEME_COLOR_LABELS = mapOf(
    "back_color" to "面板背景",
    "border_color" to "面板描边",
    "key_back_color" to "按键背景",
    "key_text_color" to "按键文字",
    "key_symbol_color" to "按键符号",
    "key_border_color" to "按键描边",
    "keyboard_back_color" to "键盘背景",
    "keyboard_background" to "键盘背景图",
    "candidate_background" to "候选栏背景",
    "candidate_text_color" to "候选文字",
    "candidate_border_color" to "候选栏描边",
    "candidate_separator_color" to "候选分隔线",
    "comment_text_color" to "候选注释",
    "hilited_candidate_back_color" to "高亮候选背景",
    "hilited_candidate_text_color" to "高亮候选文字",
    "hilited_key_back_color" to "按下按键背景",
    "hilited_key_text_color" to "按下按键文字",
    "hilited_key_symbol_color" to "按下按键符号",
    "hilited_key_border_color" to "按下按键描边",
    "on_key_back_color" to "锁定按键背景",
    "on_key_text_color" to "锁定按键文字",
    "off_key_back_color" to "关闭按键背景",
    "off_key_text_color" to "关闭按键文字",
    "popup_back_color" to "弹出背景",
    "popup_text_color" to "弹出文字",
    "text_color" to "预编辑文字",
    "text_back_color" to "预编辑背景",
    "label_color" to "序号颜色",
    "root_background" to "根背景",
    "shadow_color" to "阴影颜色",
    "liquid_keyboard_background" to "液体键盘背景",
    "long_text_color" to "长文本颜色",
    "long_text_back_color" to "长文本背景",
)

private val THEME_KEY_LABELS = mapOf(
    "key_back_color" to "背景",
    "key_text_color" to "文字",
    "key_border_color" to "描边",
    "key_symbol_color" to "符号",
    "hilited_key_back_color" to "按下背景",
    "hilited_key_text_color" to "按下文字",
    "hilited_key_border_color" to "按下描边",
    "hilited_key_symbol_color" to "按下符号",
    "key_text_size" to "主字号",
    "symbol_text_size" to "符号字号",
    "hint_text_size" to "注解字号",
    "round_corner" to "圆角",
    "key_border" to "描边宽度",
)

@Serializable
data class StoredKeyPatch(val match: String, val appearance: Map<String, String>)

@Serializable
data class ThemeOverlay(
    val style: Map<String, JsonElement> = emptyMap(),
    val schemes: Map<String, Map<String, String>> = emptyMap(),
    val keys: List<StoredKeyPatch> = emptyList(),
) {
    val isEmpty get() = style.isEmpty() && schemes.isEmpty() && keys.isEmpty()

    fun merge(schemeId: String, patch: YamlThemePatch): ThemeOverlay {
        val nextStyle = LinkedHashMap(style)
        patch.style.forEach { (name, value) -> nextStyle[name] = value }
        val nextSchemes = LinkedHashMap(schemes)
        val colors = LinkedHashMap(nextSchemes[schemeId].orEmpty())
        patch.colors.forEach { (name, value) -> colors[name] = value }
        if (colors.isNotEmpty()) nextSchemes[schemeId] = colors
        val nextKeys = keys.toMutableList()
        patch.keys.forEach { incoming ->
            val index = nextKeys.indexOfFirst { it.match.equals(incoming.match, ignoreCase = true) }
            if (index >= 0) {
                val merged = LinkedHashMap(nextKeys[index].appearance)
                incoming.appearance.forEach { (name, value) -> merged[name] = value }
                nextKeys[index] = StoredKeyPatch(nextKeys[index].match, merged)
            } else {
                nextKeys += StoredKeyPatch(incoming.match, incoming.appearance)
            }
        }
        return ThemeOverlay(nextStyle, nextSchemes, nextKeys)
    }
}

data class YamlThemeUndo(val themeId: String, val previousRaw: String?)

object YamlThemeApplier {
    fun snapshot(theme: Theme, schemeId: String, effectiveColors: Map<String, String>): JsonObject = buildJsonObject {
        put("themeId", theme.fontConfigId.ifBlank { theme.name })
        put("schemeId", schemeId)
        putJsonObject("style") {
            styleFields.forEach { (name, read) -> put(name, read(theme.style)) }
        }
        putJsonObject("colors") {
            ColorKey.entries.forEach { key ->
                put(key.key, effectiveColors[key.key].orEmpty())
            }
        }
        putJsonArray("keys") {
            val counts = linkedMapOf<Pair<String, String>, Int>()
            theme.presetKeyboards.values.forEach { keyboard ->
                keyboard.keys.forEach { key ->
                    val binding = (key.click as? KeyActionToken.Plain)?.token.orEmpty()
                    val label = key.label.ifBlank { key.labelSymbol }.ifBlank { binding }
                    if (label.isNotBlank() || binding.isNotBlank()) {
                        val pair = label to binding
                        counts[pair] = (counts[pair] ?: 0) + 1
                    }
                }
            }
            counts.entries.take(80).forEach { (pair, count) ->
                add(buildJsonObject {
                    put("label", pair.first)
                    put("binding", pair.second)
                    put("count", count)
                })
            }
        }
    }

    fun apply(
        theme: Theme,
        schemeId: String,
        patch: YamlThemePatch,
        report: AssistReport,
    ): Theme {
        var style = theme.style
        patch.style.forEach { (name, value) ->
            val updated = applyStyleField(style, name, value, report)
            if (updated != null) style = updated
        }
        val schemes = theme.presetColorSchemes.toMutableMap()
        if (patch.colors.isNotEmpty()) {
            val scheme = schemes[schemeId]
            if (scheme == null) {
                report.warnings += "当前主题没有配色方案 $schemeId，颜色修改已跳过"
            } else {
                val colors = LinkedHashMap(scheme)
                patch.colors.forEach { (name, raw) ->
                    if (name !in THEME_COLOR_KEYS) {
                        report.warnings += "不支持的颜色键 $name，已忽略"
                        return@forEach
                    }
                    val value = normalizedColor(raw)
                    if (value == null) {
                        report.warnings += "${THEME_COLOR_LABELS[name] ?: name}颜色格式不正确，已忽略"
                        return@forEach
                    }
                    report.change(THEME_COLOR_LABELS[name] ?: name, colors[name] ?: "继承", value)
                    colors[name] = value
                }
                schemes[schemeId] = colors
            }
        }
        val keyboards = theme.presetKeyboards.toMutableMap()
        if (patch.keys.isNotEmpty()) {
            val matched = mutableMapOf<String, Int>()
            patch.keys.forEach { keyPatch ->
                val match = keyPatch.match.trim()
                var hits = 0
                keyboards.entries.toList().forEach { (id, keyboard) ->
                    val keys = keyboard.keys.map { key ->
                        if (!matches(key, match)) return@map key
                        val updated = applyTextKey(key, keyPatch.appearance, match, report)
                        if (updated != null) {
                            hits++
                            updated
                        } else key
                    }
                    if (keys != keyboard.keys) keyboards[id] = keyboard.copy(keys = keys)
                }
                matched[match] = hits
            }
            matched.forEach { (match, hits) ->
                if (hits == 0) report.warnings += "没有找到匹配“$match”的按键，已忽略"
            }
        }
        return theme.copy(style = style, presetColorSchemes = schemes, presetKeyboards = keyboards)
    }

    fun applyOverlay(theme: Theme, overlay: ThemeOverlay): Theme {
        if (overlay.isEmpty) return theme
        var style = theme.style
        overlay.style.forEach { (name, value) ->
            applyStyleField(style, name, value, AssistReport())?.let { style = it }
        }
        val schemes = theme.presetColorSchemes.toMutableMap()
        overlay.schemes.forEach { (schemeId, colors) ->
            val scheme = schemes[schemeId] ?: return@forEach
            val merged = LinkedHashMap(scheme)
            colors.forEach { (name, raw) ->
                if (name in THEME_COLOR_KEYS) normalizedColor(raw)?.let { merged[name] = it }
            }
            schemes[schemeId] = merged
        }
        val keyboards = theme.presetKeyboards.toMutableMap()
        if (overlay.keys.isNotEmpty()) {
            overlay.keys.forEach { keyPatch ->
                keyboards.entries.toList().forEach { (id, keyboard) ->
                    val keys = keyboard.keys.map { key ->
                        if (!matches(key, keyPatch.match)) key
                        else applyTextKey(key, keyPatch.appearance, keyPatch.match, AssistReport()) ?: key
                    }
                    if (keys != keyboard.keys) keyboards[id] = keyboard.copy(keys = keys)
                }
            }
        }
        return theme.copy(style = style, presetColorSchemes = schemes, presetKeyboards = keyboards)
    }

    /** Name/value table used by the prompt and the change list. */
    val styleFields: Map<String, (GeneralStyle) -> Number> = linkedMapOf(
        "keyboard_height" to { it.keyboardHeight },
        "keyboard_height_land" to { it.keyboardHeightLand },
        "key_height" to { it.keyHeight },
        "key_width" to { it.keyWidth },
        "horizontal_gap" to { it.horizontalGap },
        "vertical_gap" to { it.verticalGap },
        "round_corner" to { it.roundCorner },
        "key_border" to { it.keyBorder },
        "key_text_size" to { it.keyTextSize },
        "key_long_text_size" to { it.keyLongTextSize },
        "symbol_text_size" to { it.symbolTextSize },
        "label_text_size" to { it.labelTextSize },
        "candidate_text_size" to { it.candidateTextSize },
        "candidate_view_height" to { it.candidateViewHeight },
        "candidate_corner_radius" to { it.candidateCornerRadius },
        "candidate_border_round" to { it.candidateBorderRound },
        "candidate_padding" to { it.candidatePadding },
        "candidate_spacing" to { it.candidateSpacing },
        "comment_text_size" to { it.commentTextSize },
        "comment_height" to { it.commentHeight },
        "popup_text_size" to { it.popupTextSize },
        "popup_key_height" to { it.popupKeyHeight },
        "popup_width" to { it.popupWidth },
        "popup_height" to { it.popupHeight },
        "shadow_radius" to { it.shadowRadius },
        "keyboard_padding" to { it.keyboardPadding },
        "keyboard_padding_left" to { it.keyboardPaddingLeft },
        "keyboard_padding_right" to { it.keyboardPaddingRight },
        "keyboard_padding_bottom" to { it.keyboardPaddingBottom },
        "keyboard_padding_land" to { it.keyboardPaddingLand },
        "keyboard_padding_land_bottom" to { it.keyboardPaddingLandBottom },
    )

    private fun applyStyleField(
        style: GeneralStyle,
        name: String,
        value: JsonElement,
        report: AssistReport,
    ): GeneralStyle? {
        if (name !in styleFields) {
            report.warnings += "不支持的 style 字段 $name，已忽略"
            return null
        }
        val number = (value as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        if (number == null) {
            report.warnings += "${THEME_STYLE_LABELS[name] ?: name}不是数字，已忽略"
            return null
        }
        fun int(range: IntRange): Int? {
            val v = number.toInt()
            if (number != v.toDouble() || v !in range) {
                report.warnings += "${THEME_STYLE_LABELS[name] ?: name}需为 ${range.first}–${range.last} 的整数，已忽略"
                return null
            }
            return v
        }
        fun float(range: ClosedFloatingPointRange<Float>): Float? {
            val v = number.toFloat()
            if (!v.isFinite() || v !in range) {
                report.warnings += "${THEME_STYLE_LABELS[name] ?: name}需在 ${range.start}–${range.endInclusive}，已忽略"
                return null
            }
            return v
        }
        val updated = when (name) {
            "keyboard_height" -> int(0..2000)?.let { style.copy(keyboardHeight = it) }
            "keyboard_height_land" -> int(0..2000)?.let { style.copy(keyboardHeightLand = it) }
            "key_height" -> int(0..400)?.let { style.copy(keyHeight = it) }
            "key_width" -> float(0f..100f)?.let { style.copy(keyWidth = it) }
            "horizontal_gap" -> int(0..64)?.let { style.copy(horizontalGap = it) }
            "vertical_gap" -> int(0..64)?.let { style.copy(verticalGap = it) }
            "round_corner" -> float(0f..64f)?.let { style.copy(roundCorner = it) }
            "key_border" -> int(0..32)?.let { style.copy(keyBorder = it) }
            "key_text_size" -> float(0f..200f)?.let { style.copy(keyTextSize = it) }
            "key_long_text_size" -> float(0f..200f)?.let { style.copy(keyLongTextSize = it) }
            "symbol_text_size" -> float(0f..200f)?.let { style.copy(symbolTextSize = it) }
            "label_text_size" -> float(0f..200f)?.let { style.copy(labelTextSize = it) }
            "candidate_text_size" -> float(0f..200f)?.let { style.copy(candidateTextSize = it) }
            "candidate_view_height" -> int(0..200)?.let { style.copy(candidateViewHeight = it) }
            "candidate_corner_radius" -> float(0f..64f)?.let { style.copy(candidateCornerRadius = it) }
            "candidate_border_round" -> float(0f..64f)?.let { style.copy(candidateBorderRound = it) }
            "candidate_padding" -> int(0..100)?.let { style.copy(candidatePadding = it) }
            "candidate_spacing" -> float(0f..64f)?.let { style.copy(candidateSpacing = it) }
            "comment_text_size" -> float(0f..200f)?.let { style.copy(commentTextSize = it) }
            "comment_height" -> int(0..100)?.let { style.copy(commentHeight = it) }
            "popup_text_size" -> float(0f..200f)?.let { style.copy(popupTextSize = it) }
            "popup_key_height" -> int(0..200)?.let { style.copy(popupKeyHeight = it) }
            "popup_width" -> int(0..1000)?.let { style.copy(popupWidth = it) }
            "popup_height" -> int(0..1000)?.let { style.copy(popupHeight = it) }
            "shadow_radius" -> float(0f..64f)?.let { style.copy(shadowRadius = it) }
            "keyboard_padding" -> int(0..100)?.let { style.copy(keyboardPadding = it) }
            "keyboard_padding_left" -> int(0..100)?.let { style.copy(keyboardPaddingLeft = it) }
            "keyboard_padding_right" -> int(0..100)?.let { style.copy(keyboardPaddingRight = it) }
            "keyboard_padding_bottom" -> int(0..100)?.let { style.copy(keyboardPaddingBottom = it) }
            "keyboard_padding_land" -> int(0..100)?.let { style.copy(keyboardPaddingLand = it) }
            "keyboard_padding_land_bottom" -> int(0..100)?.let { style.copy(keyboardPaddingLandBottom = it) }
            else -> null
        } ?: return null
        val before = styleFields.getValue(name)(style)
        val after = styleFields.getValue(name)(updated)
        report.change(THEME_STYLE_LABELS[name] ?: name, before.toString(), after.toString())
        return updated
    }

    fun matchingKey(theme: Theme, match: String): TextKey? =
        theme.presetKeyboards.values.asSequence()
            .flatMap { it.keys.asSequence() }
            .firstOrNull { matches(it, match) }

    private fun applyTextKey(
        key: TextKey,
        patch: Map<String, String>,
        match: String,
        report: AssistReport,
    ): TextKey? {
        var updated = key
        var changed = false
        patch.forEach { (name, raw) ->
            if (name !in THEME_KEY_LABELS) {
                report.warnings += "单键 $match：不支持修改 $name，已忽略"
                return@forEach
            }
            val next: TextKey? = when (name) {
                "key_back_color", "key_text_color", "key_border_color", "key_symbol_color",
                "hilited_key_back_color", "hilited_key_text_color", "hilited_key_border_color",
                "hilited_key_symbol_color" -> {
                    val value = normalizedColor(raw)
                    if (value == null) {
                        report.warnings += "单键 $match · ${THEME_KEY_LABELS[name]}颜色格式不正确，已忽略"
                        null
                    } else when (name) {
                        "key_back_color" -> updated.copy(keyBackColor = value)
                        "key_text_color" -> updated.copy(keyTextColor = value)
                        "key_border_color" -> updated.copy(keyBorderColor = value)
                        "key_symbol_color" -> updated.copy(keySymbolColor = value)
                        "hilited_key_back_color" -> updated.copy(hilitedKeyBackColor = value)
                        "hilited_key_text_color" -> updated.copy(hilitedKeyTextColor = value)
                        "hilited_key_border_color" -> updated.copy(hilitedKeyBorderColor = value)
                        else -> updated.copy(hilitedKeySymbolColor = value)
                    }
                }
                "key_text_size" -> raw.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..200f }
                    ?.let { updated.copy(keyTextSize = it) }
                "symbol_text_size" -> raw.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..200f }
                    ?.let { updated.copy(symbolTextSize = it) }
                "hint_text_size" -> raw.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..200f }
                    ?.let { updated.copy(hintTextSize = it) }
                "round_corner" -> raw.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..64f }
                    ?.let { updated.copy(roundCorner = it, ownRoundCorner = true) }
                "key_border" -> raw.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..32f && it % 1f == 0f }
                    ?.toInt()?.let { updated.copy(keyBorder = it, ownKeyBorder = true) }
                else -> null
            }
            if (next == null) {
                if (name in setOf("key_text_size", "symbol_text_size", "hint_text_size", "round_corner", "key_border")) {
                    report.warnings += "单键 $match · ${THEME_KEY_LABELS[name]}数值超出范围，已忽略"
                }
            } else {
                if (next != updated) changed = true
                updated = next
            }
        }
        if (!changed) return null
        report.change("单键 $match", "原外观", patch.entries.joinToString("，") { (name, value) ->
            "${THEME_KEY_LABELS[name] ?: name}=$value"
        })
        return updated
    }

    private fun matches(key: TextKey, match: String): Boolean {
        if (key.label == match || key.labelSymbol == match) return true
        val click = key.click
        return when (click) {
            is KeyActionToken.Plain -> click.token == match
            is KeyActionToken.Inline -> click.label == match || click.commit == match || click.text == match
            null -> false
        }
    }
}

private val THEME_COLOR_KEYS: Set<String> = ColorKey.entries.map { it.key }.toSet()
