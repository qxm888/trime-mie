/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Intent
import android.graphics.Color
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.CustomBackground
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.StandaloneKeyboardTheme
import com.osfans.trime.data.theme.ColorKey
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeAiOverrides
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.ai.AiTextSettings
import com.osfans.trime.ime.ai.AssistReport
import com.osfans.trime.ime.ai.CustomThemeApplier
import com.osfans.trime.ime.ai.CustomThemePatch
import com.osfans.trime.ime.ai.TextProviders
import com.osfans.trime.ime.ai.ThemeAssistMode
import com.osfans.trime.ime.ai.ThemeAssistProtocol
import com.osfans.trime.ime.ai.ThemeAssistService
import com.osfans.trime.ime.ai.YamlThemeApplier
import com.osfans.trime.ime.ai.YamlThemePatch
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The natural-language front end of the theme assistant.
 *
 * Custom layouts are edited through [CustomKeyboards]; YAML themes are edited
 * through [ThemeAiOverrides] so their source files stay untouched. Both paths
 * share the same provider/key/model as AI polish and translation.
 */
object AiThemeAssistant {
    fun showCustom(activity: FragmentActivity, kbId: String, onApplied: () -> Unit = {}) {
        AiTextSettings.init(activity.applicationContext)
        val keyboard = CustomKeyboards.get(kbId) ?: return
        val surface = SurfaceAppearance.current(true, kbId)
        val snapshot = CustomThemeApplier.snapshot(keyboard, surface)

        fun open(request: String = ""): Unit {
            prompt(
                activity = activity,
                title = "AI 主题助手 · ${keyboard.name}",
                targetName = "自定义键盘 · ${keyboard.name}",
                mode = ThemeAssistMode.CUSTOM,
                snapshot = snapshot,
                initialRequest = request,
            ) { req, json ->
                val warnings = mutableListOf<String>()
                val patch = ThemeAssistProtocol.parseCustom(json, warnings)
                if (patch.isEmpty) {
                    showNoChanges(activity, json, warnings) { open(req) }
                    return@prompt
                }
                val draft = CustomThemeApplier.copyForPreview(keyboard)
                val report = AssistReport()
                CustomThemeApplier.apply(draft, patch, surface, report)
                warnings.forEach { if (it !in report.warnings) report.warnings += it }
                showResult(
                    activity = activity,
                    title = "自定义键盘 · ${keyboard.name}",
                    summary = patch.summary,
                    palette = customPalette(draft, draft.surfaceAppearance ?: surface, patch),
                    report = report,
                    onRegenerate = { open(req) },
                ) {
                    applyCustom(activity, kbId, patch, surface, onApplied)
                }
            }
        }
        open()
    }

    fun showTheme(activity: FragmentActivity, onApplied: () -> Unit = {}) {
        if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) {
            Toast.makeText(activity, "当前是自定义布局，请在键盘编辑里使用 AI 主题助手", Toast.LENGTH_LONG).show()
            return
        }
        AiTextSettings.init(activity.applicationContext)
        val theme = ThemeManager.activeTheme
        val themeId = ThemeManager.prefs.selectedTheme.getValue()
        val schemeId = activeSchemeId(theme)
        if (schemeId == null) {
            Toast.makeText(activity, "当前主题没有配色方案，暂时无法使用 AI 助手", Toast.LENGTH_LONG).show()
            return
        }
        val effectiveColors = effectiveColors(theme, schemeId)
        val snapshot = YamlThemeApplier.snapshot(theme, schemeId, effectiveColors)

        fun open(request: String = ""): Unit {
            prompt(
                activity = activity,
                title = "AI 主题助手 · ${theme.name.ifBlank { themeId }}",
                targetName = "当前主题 · ${theme.name.ifBlank { themeId }}",
                mode = ThemeAssistMode.THEME,
                snapshot = snapshot,
                initialRequest = request,
                extraAction = if (ThemeAiOverrides.has(themeId)) {
                    "清除本主题 AI 覆盖" to { clearOverlay(activity, themeId, onApplied) }
                } else null,
            ) { req, json ->
                val warnings = mutableListOf<String>()
                val patch = ThemeAssistProtocol.parseTheme(json, warnings)
                if (patch.isEmpty) {
                    showNoChanges(activity, json, warnings) { open(req) }
                    return@prompt
                }
                val report = AssistReport()
                val preview = YamlThemeApplier.apply(theme, schemeId, patch, report)
                warnings.forEach { if (it !in report.warnings) report.warnings += it }
                showResult(
                    activity = activity,
                    title = "当前主题 · ${theme.name.ifBlank { themeId }}",
                    summary = patch.summary,
                    palette = themePalette(preview, schemeId, patch),
                    report = report,
                    onRegenerate = { open(req) },
                ) {
                    applyTheme(activity, themeId, schemeId, patch, onApplied)
                }
            }
        }
        open()
    }

    // ------------------------------------------------------------------ prompt

    private fun prompt(
        activity: FragmentActivity,
        title: String,
        targetName: String,
        mode: ThemeAssistMode,
        snapshot: JsonObject,
        initialRequest: String = "",
        extraAction: Pair<String, () -> Unit>? = null,
        onGenerated: (String, JsonObject) -> Unit,
    ) {
        AiTextSettings.init(activity.applicationContext)
        val ink = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
        val muted = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val primary = ContextCompat.getColor(activity, R.color.m3_primary)

        fun label(value: String, size: Float = 13f, color: Int = muted, top: Int = 4): TextView =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(color)
                setPadding(0, dp(activity, top), 0, dp(activity, 2))
            }

        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8))
        }
        body.addView(label("目标：$targetName", 15f, ink))
        body.addView(label(
            if (mode == ThemeAssistMode.CUSTOM)
                "可以改颜色、圆角、描边、字号、间距、玻璃/模糊/渐变，也能只改某个键。不会改布局和按键绑定。"
            else
                "改的是当前主题的配色、尺寸与单键颜色，不会写回主题文件；单键按名称匹配所有键盘。",
            12f, muted,
        ))
        val provider = TextProviders.byId(AiTextSettings.providerId)
        body.addView(label("服务：${provider.label} · ${AiTextSettings.model()}", 12f, muted))
        body.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "AI 服务设置"
            isAllCaps = false
            setOnClickListener { openSettings(activity) }
        })

        val input = TextInputEditText(activity).apply {
            setText(initialRequest)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            setSingleLine(false)
            hint = "例如：深色磨砂玻璃风，按键半透明、白色字，圆角 12"
        }
        body.addView(TextInputLayout(activity).apply {
            this.hint = "描述你想要的主题"
            setPadding(0, dp(activity, 8), 0, 0)
            addView(input)
        })

        val examples = if (mode == ThemeAssistMode.CUSTOM) listOf(
            "深色磨砂玻璃风，按键半透明、白色字，圆角 12",
            "樱花粉主题，空格键和回车键用深粉色",
            "键盘矮一点，键距大一点，候选字大一号",
        ) else listOf(
            "黑金配色，金色描边，面板一点渐变",
            "浅色简约风，按键白底黑字，圆角小一点",
            "把空格键改成粉色，按键文字大一号",
        )
        examples.forEach { example ->
            body.addView(label("试试：$example", 12f, primary, top = 0).apply {
                setOnClickListener {
                    input.setText(example)
                    input.setSelection(example.length)
                }
            })
        }

        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("取消", null)
            .setPositiveButton("生成修改", null)
            .create()
        extraAction?.let { (text, action) ->
            body.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text
                isAllCaps = false
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            })
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val request = input.text?.toString()?.trim().orEmpty()
                if (request.isBlank()) {
                    input.error = "先描述一下想要的主题"
                    return@setOnClickListener
                }
                val config = AiTextSettings.config()
                if (config.apiKey.isBlank()) {
                    showMissingKey(activity)
                    return@setOnClickListener
                }
                dialog.dismiss()
                val progress = showProgress(activity, "AI 正在设计…") { job?.cancel() }
                job = activity.lifecycleScope.launch {
                    try {
                        val json = withContext(Dispatchers.IO) {
                            ThemeAssistService.generate(config, mode, request, snapshot)
                        }
                        runCatching { progress.dismiss() }
                        onGenerated(request, json)
                    } catch (cancelled: CancellationException) {
                        runCatching { progress.dismiss() }
                        throw cancelled
                    } catch (error: Exception) {
                        runCatching { progress.dismiss() }
                        showError(activity, error)
                    }
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------------ result

    private fun showResult(
        activity: FragmentActivity,
        title: String,
        summary: String,
        palette: ThemePalette,
        report: AssistReport,
        onRegenerate: () -> Unit,
        onApply: () -> Unit,
    ) {
        val ink = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY)
        val muted = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8))
        }
        body.addView(ThemePalettePreview(activity).apply {
            this.palette = palette
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 176))
        })
        if (summary.isNotBlank()) {
            body.addView(TextView(activity).apply {
                text = summary
                textSize = 15f
                setTextColor(ink)
                setPadding(0, dp(activity, 10), 0, dp(activity, 4))
            })
        }
        if (report.changes.isNotEmpty()) {
            val shown = report.changes.take(14)
            val text = buildString {
                append("变更\n")
                shown.forEach { append("· ${it.label}：${it.before} → ${it.after}\n") }
                if (report.changes.size > shown.size) append("…还有 ${report.changes.size - shown.size} 项\n")
            }
            body.addView(TextView(activity).apply {
                this.text = text
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(activity, 8), 0, dp(activity, 4))
            })
        }
        if (report.warnings.isNotEmpty()) {
            body.addView(TextView(activity).apply {
                text = "提示\n" + report.warnings.take(8).joinToString("\n") { "· $it" }
                textSize = 12f
                setTextColor(0xFFB26A00.toInt())
                setPadding(0, dp(activity, 6), 0, dp(activity, 4))
            })
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(body) })
            .setNegativeButton("取消", null)
            .setNeutralButton("重新生成") { _, _ -> onRegenerate() }
            .setPositiveButton("应用", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                onApply()
            }
        }
        dialog.show()
    }

    private fun showNoChanges(
        activity: FragmentActivity,
        json: JsonObject,
        warnings: List<String>,
        onRetry: () -> Unit,
    ) {
        val summary = (json["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: "AI 没有返回可应用的修改"
        val message = buildString {
            append(summary)
            if (warnings.isNotEmpty()) {
                append("\n\n")
                append(warnings.take(6).joinToString("\n") { "· $it" })
            }
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("没有可应用的修改")
            .setMessage(message)
            .setPositiveButton("返回修改") { _, _ -> onRetry() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showApplied(activity: FragmentActivity, title: String, message: String = "$title 已更新。", onUndo: (() -> Unit)?) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("已应用")
            .setMessage("$message${if (onUndo != null) "\n\n如果效果不满意，可以撤销本次 AI 修改。" else ""}")
            .setPositiveButton("完成", null)
        if (onUndo != null) builder.setNegativeButton("撤销") { _, _ -> onUndo() }
        builder.show()
    }

    // ------------------------------------------------------------------- apply

    private fun applyCustom(
        activity: FragmentActivity,
        kbId: String,
        patch: CustomThemePatch,
        surface: SurfaceAppearanceConfig,
        onApplied: () -> Unit,
    ) {
        val current = CustomKeyboards.get(kbId) ?: run {
            Toast.makeText(activity, "键盘已不存在，修改未保存", Toast.LENGTH_LONG).show()
            return
        }
        val undo = CustomThemeApplier.captureUndo(current)
        val applied = runCatching {
            CustomKeyboards.mutate(kbId) { after ->
                CustomThemeApplier.apply(after, patch, surface, AssistReport())
                // Same invalidation as the editor's own mutate wrapper: an imported
                // skin's coordinates would otherwise override new geometry/background.
                val geometryChanged =
                    current.rows.map { row -> listOf(row.height, row.alignment, row.margin, row.keys.map { it.skinId to it.width }) } !=
                        after.rows.map { row -> listOf(row.height, row.alignment, row.margin, row.keys.map { it.skinId to it.width }) } ||
                        current.rowSpacing != after.rowSpacing || current.rowMargin != after.rowMargin ||
                        current.options != after.options
                if (geometryChanged || current.background != after.background) after.importedSkin = null
            }
        }.getOrElse {
            showError(activity, it as? Exception ?: IllegalStateException(it.message))
            return
        }
        if (!applied) {
            showError(activity, IllegalStateException("键盘已不存在，修改未保存"))
            return
        }
        if (!CustomKeyboards.flush()) {
            Toast.makeText(activity, "修改已生效，但写入存储失败，请检查剩余空间", Toast.LENGTH_LONG).show()
        }
        ThemeManager.requestCustomizationRefresh()
        onApplied()
        showApplied(activity, "键盘外观", onUndo = {
            CustomKeyboards.mutate(kbId) { after -> CustomThemeApplier.restore(after, undo) }
            CustomKeyboards.flush()
            ThemeManager.requestCustomizationRefresh()
            onApplied()
        })
    }

    private fun applyTheme(
        activity: FragmentActivity,
        themeId: String,
        schemeId: String,
        patch: YamlThemePatch,
        onApplied: () -> Unit,
    ) {
        val previous = ThemeAiOverrides.raw(themeId)
        val progress = showBusy(activity, "正在应用主题…")
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ThemeAiOverrides.merge(themeId, schemeId, patch) }
                ThemeManager.refreshActiveTheme()
                runCatching { progress.dismiss() }
                onApplied()
                showApplied(activity, "当前主题", onUndo = {
                    ThemeAiOverrides.restore(themeId, previous)
                    activity.lifecycleScope.launch {
                        val undoProgress = showBusy(activity, "正在撤销…")
                        try {
                            ThemeManager.refreshActiveTheme()
                        } finally {
                            runCatching { undoProgress.dismiss() }
                        }
                        onApplied()
                    }
                })
            } catch (cancelled: CancellationException) {
                runCatching { progress.dismiss() }
                throw cancelled
            } catch (error: Exception) {
                runCatching { progress.dismiss() }
                showError(activity, error)
            }
        }
    }

    private fun clearOverlay(activity: FragmentActivity, themeId: String, onApplied: () -> Unit) {
        val previous = ThemeAiOverrides.raw(themeId)
        ThemeAiOverrides.clear(themeId)
        activity.lifecycleScope.launch {
            val progress = showBusy(activity, "正在清除 AI 覆盖…")
            try {
                ThemeManager.refreshActiveTheme()
            } catch (error: Exception) {
                runCatching { progress.dismiss() }
                showError(activity, error)
                return@launch
            }
            runCatching { progress.dismiss() }
            onApplied()
            showApplied(activity, "当前主题的 AI 覆盖", "已清除本主题的 AI 覆盖。", onUndo = {
                ThemeAiOverrides.restore(themeId, previous)
                activity.lifecycleScope.launch {
                    val undoProgress = showBusy(activity, "正在恢复…")
                    try {
                        ThemeManager.refreshActiveTheme()
                    } finally {
                        runCatching { undoProgress.dismiss() }
                    }
                    onApplied()
                }
            })
        }
    }

    // ------------------------------------------------------------------ dialogs

    private fun showProgress(activity: FragmentActivity, title: String, onCancel: () -> Unit): AlertDialog =
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage("正在根据你的描述生成外观，可随时取消。")
            .setNegativeButton("取消") { _, _ -> onCancel() }
            .setCancelable(true)
            .create()
            .also { it.show() }

    private fun showBusy(activity: FragmentActivity, title: String): AlertDialog =
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage("请稍候…")
            .setCancelable(false)
            .create()
            .also { it.show() }

    private fun showMissingKey(activity: FragmentActivity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("还没有配置 AI 服务")
            .setMessage("AI 主题助手复用「AI 服务」里的服务商、模型和 API Key。请先配置后再回来。")
            .setPositiveButton("去配置") { _, _ -> openSettings(activity) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showError(activity: FragmentActivity, error: Throwable) {
        val message = error.message?.takeIf { it.isNotBlank() } ?: "生成失败，请稍后重试"
        MaterialAlertDialogBuilder(activity)
            .setTitle("生成失败")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .setNeutralButton("AI 服务设置") { _, _ -> openSettings(activity) }
            .show()
    }

    private fun openSettings(activity: FragmentActivity) {
        activity.startActivity(Intent(activity, AiTextSettingsActivity::class.java))
    }

    // ----------------------------------------------------------------- preview

    private fun customPalette(
        keyboard: CustomKeyboard,
        surface: SurfaceAppearanceConfig,
        patch: CustomThemePatch,
    ): ThemePalette {
        val base = StandaloneKeyboardTheme.base()
        val scheme = base.presetColorSchemes["default"].orEmpty()
        val key = keyboard.keyAppearance
        val background = keyboard.background?.normalized()?.let { bg ->
            if (bg.type == CustomBackground.Type.COLOR) colorOr(bg.color, Color.TRANSPARENT) else Color.TRANSPARENT
        } ?: Color.TRANSPARENT
        val keyBack = colorOr(
            key.keyBackColor.ifBlank { surface.keys.background },
            colorOr(scheme["key_back_color"].orEmpty(), 0xFFF1F3F5.toInt()),
        )
        val keyText = colorOr(key.keyTextColor, colorOr(scheme["key_text_color"].orEmpty(), 0xFF111827.toInt()))
        val keyBorder = colorOr(
            key.keyBorderColor.ifBlank { surface.keys.borderColor },
            colorOr(scheme["key_border_color"].orEmpty(), Color.TRANSPARENT),
        )
        val accent = patch.keys.firstOrNull()?.let { CustomThemeApplier.keyForPatch(keyboard, it) }
        return ThemePalette(
            panelBackground = background,
            keyBackground = keyBack,
            keyText = keyText,
            keyBorder = keyBorder,
            keyBorderWidth = if (key.keyBorder >= 0) key.keyBorder.toFloat() else surface.keys.borderWidth ?: 0f,
            keyRadius = if (key.roundCorner >= 0f) key.roundCorner else surface.keys.radius ?: 6f,
            gap = keyboard.options.horizontalGap.toFloat(),
            keyTextSize = if (key.keyTextSize > 0f) key.keyTextSize else base.style.keyTextSize,
            pressedBackground = colorOr(
                key.hilitedKeyBackColor,
                colorOr(scheme["hilited_key_back_color"].orEmpty(), 0xFFDCE4ED.toInt()),
            ),
            pressedText = colorOr(
                key.hilitedKeyTextColor,
                colorOr(scheme["hilited_key_text_color"].orEmpty(), keyText),
            ),
            candidateBackground = colorOr(scheme["candidate_background"].orEmpty(), Color.TRANSPARENT),
            candidateText = colorOr(scheme["candidate_text_color"].orEmpty(), keyText),
            candidateTextSize = keyboard.typography["candidate"]?.size?.takeIf { it > 0f } ?: base.style.candidateTextSize,
            highlightBackground = colorOr(scheme["hilited_candidate_back_color"].orEmpty(), 0xFF3B82F6.toInt()),
            highlightText = colorOr(scheme["hilited_candidate_text_color"].orEmpty(), Color.WHITE),
            accentBackground = accent?.let { colorOr(it.appearance.keyBackColor, keyBack) },
            accentText = accent?.let { colorOr(it.appearance.keyTextColor, keyText) },
            accentLabel = accent?.label.orEmpty(),
        )
    }

    private fun themePalette(theme: Theme, schemeId: String, patch: YamlThemePatch): ThemePalette {
        val scheme = LinkedHashMap(theme.presetColorSchemes[schemeId].orEmpty())
        patch.colors.forEach { (name, value) -> scheme[name] = value }
        fun c(name: String, fallback: Int): Int {
            scheme[name]?.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { Color.parseColor(raw) }.getOrNull()?.let { return it }
            }
            return runCatching { ColorManager.getColor(name) }.getOrDefault(fallback)
        }
        val keyBack = c("key_back_color", 0xFFF1F3F5.toInt())
        val keyText = c("key_text_color", 0xFF111827.toInt())
        val accent = patch.keys.firstOrNull()?.let { YamlThemeApplier.matchingKey(theme, it.match) }
        return ThemePalette(
            panelBackground = c("keyboard_back_color", c("back_color", Color.TRANSPARENT)),
            keyBackground = keyBack,
            keyText = keyText,
            keyBorder = c("key_border_color", Color.TRANSPARENT),
            keyBorderWidth = theme.style.keyBorder.toFloat(),
            keyRadius = theme.style.roundCorner,
            gap = theme.style.horizontalGap.toFloat(),
            keyTextSize = theme.style.keyTextSize,
            pressedBackground = c("hilited_key_back_color", 0xFFDCE4ED.toInt()),
            pressedText = c("hilited_key_text_color", keyText),
            candidateBackground = c("candidate_background", Color.TRANSPARENT),
            candidateText = c("candidate_text_color", keyText),
            candidateTextSize = theme.style.candidateTextSize,
            highlightBackground = c("hilited_candidate_back_color", 0xFF3B82F6.toInt()),
            highlightText = c("hilited_candidate_text_color", Color.WHITE),
            accentBackground = accent?.keyBackColor?.takeIf { it.isNotBlank() }?.let { colorOr(it, keyBack) },
            accentText = accent?.keyTextColor?.takeIf { it.isNotBlank() }?.let { colorOr(it, keyText) },
            accentLabel = accent?.label.orEmpty(),
        )
    }

    private fun colorOr(raw: String, fallback: Int): Int =
        raw.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

    // ------------------------------------------------------------------- theme

    private fun activeSchemeId(theme: Theme): String? {
        val active = runCatching { ColorManager.activeColorScheme }.getOrNull()
        if (active != null) {
            theme.presetColorSchemes.entries.firstOrNull { it.value == active }?.key?.let { return it }
        }
        return theme.presetColorSchemes.keys.firstOrNull()
    }

    private fun effectiveColors(theme: Theme, schemeId: String): Map<String, String> {
        val scheme = theme.presetColorSchemes[schemeId].orEmpty()
        return ColorKey.entries.associate { key ->
            val text = scheme[key.key]?.takeIf { it.isNotBlank() }
                ?: runCatching { "#%08X".format(ColorManager.getColor(key.key)) }.getOrNull().orEmpty()
            key.key to text
        }
    }

    private fun dp(activity: FragmentActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
