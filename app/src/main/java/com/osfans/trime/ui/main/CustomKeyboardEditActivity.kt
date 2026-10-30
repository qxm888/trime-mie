/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.CustomBackground
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.KeyBindingOverrides
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.launch

/**
 * The layout editor of one custom keyboard (靓企鹅-style): rows of keys with
 * free ordering — add/remove/move rows and keys, set each key's label, width
 * and per-gesture bindings. Changes persist immediately; the running keyboard
 * picks them up the next time it is brought up.
 */
class CustomKeyboardEditActivity : AppCompatActivity() {
    private var kbId: String = ""
    private lateinit var rowsBox: LinearLayout

    private data class KeyDrag(val keyboard: String, val row: Int, val key: Int, val version: Long)
    private var returningFromAppearance = false
    override fun onResume() {
        super.onResume()
        if (returningFromAppearance) {
            returningFromAppearance = false
            refreshAppearancePreview { render() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        returningFromAppearance = savedInstanceState?.getBoolean("returningFromAppearance") ?: false
        kbId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val kb = CustomKeyboards.get(kbId)
        if (kb == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_custom_kb_edit)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES

        val toolbar = findViewById<MaterialToolbar>(R.id.cbe_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cbe_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = kb.name

        findViewById<View>(R.id.cbe_global_appearance).setOnClickListener {
            SurfaceAppearanceEditor.show(this, true, layoutId = kbId) { refreshAppearancePreview { render() } }
        }
        findViewById<View>(R.id.cbe_key_appearance).setOnClickListener {
            val current = CustomKeyboards.get(kbId) ?: return@setOnClickListener
            KeyAppearanceEditor.show(this, "按键默认外观", current.keyAppearance) { style -> mutate { it.keyAppearance = style } }
        }
        findViewById<View>(R.id.cbe_all_keys_bg).setOnClickListener {
            val current = CustomKeyboards.get(kbId) ?: return@setOnClickListener
            AppearancePicker.show(this, "当前布局全体按键背景", current.allKeysBackground, true) { raw -> mutate { it.allKeysBackground = raw } }
        }
        findViewById<View>(R.id.cbe_keyboard_bg).setOnClickListener { pickKeyboardBackground() }
        findViewById<View>(R.id.cbe_toolbar_edit).setOnClickListener { ToolbarEditActivity.start(this, kbId) }
        findViewById<View>(R.id.cbe_layout_options).setOnClickListener {
            val current = CustomKeyboards.get(kbId) ?: return@setOnClickListener
            if (current.importedSkin != null) findViewById<View>(R.id.cbe_imported_layout).performClick()
            else KeyboardOptionsEditor.show(this, current.options) { options -> mutate { it.options = options } }
        }
        findViewById<View>(R.id.cbe_fonts).setOnClickListener { LayoutTypographyEditor.show(this, kbId) }
        findViewById<View>(R.id.cbe_floating_candidates).setOnClickListener { FloatingCandidatesEditActivity.start(this, kbId) }
        findViewById<View>(R.id.cbe_functions).setOnClickListener { NamedFunctionsEditor.show(this) }

        findViewById<View>(R.id.cbe_keyboard_height).setOnClickListener { showKeyboardHeightEditor() }
        findViewById<View>(R.id.cbe_row_spacing).setOnClickListener { showRowSpacingEditor() }
        findViewById<View>(R.id.cbe_row_margin).setOnClickListener { showRowMarginEditor() }
        rowsBox = findViewById(R.id.cbe_rows)
        findViewById<TextView>(R.id.cbe_add_row).setOnClickListener {
            mutate { it.rows.add(CustomKeyboards.CustomRow()) }
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("returningFromAppearance", returningFromAppearance)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        if (!CustomKeyboards.flush()) android.widget.Toast.makeText(this, "配置保存失败，请检查剩余存储空间", android.widget.Toast.LENGTH_LONG).show()
        ThemeManager.requestCustomizationRefresh()
    }

    // ----------------------------------------------------------------
    // skin: whole-keyboard and per-key backgrounds
    // ----------------------------------------------------------------

    private var keyBgImageResult: ((String?) -> Unit)? = null

    private val imagePicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            val onResult = keyBgImageResult
            keyBgImageResult = null
            SkinPicker.handleImageResult(this, uri) { path -> onResult?.invoke(path) }
        }

    private val videoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        SkinPicker.handleVideoResult(this, uri) { path ->
            if (path != null) mutate { it.background = CustomBackground(CustomBackground.Type.VIDEO, image = path) }
        }
    }

    private fun pickKeyboardBackground() {
        val kb = CustomKeyboards.get(kbId) ?: return
        SkinPicker.show(
            this,
            getString(R.string.custom_kb_bg),
            kb.resolvedBackground(),
            onPickVideo = { videoPicker.launch("video/*") },
            onPicked = { background -> mutate { it.background = background } },
            onPickImage = {
                keyBgImageResult = { path ->
                    if (path != null) {
                        val heightDp = kb.effectivePortraitHeightDp().toFloat().coerceAtLeast(24f)
                        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
                        SkinPicker.showBackgroundCrop(this@CustomKeyboardEditActivity, path, widthDp / heightDp) { cropped ->
                            if (cropped != null) {
                                mutate { k -> k.background = CustomBackground(CustomBackground.Type.IMAGE, image = cropped) }
                            }
                        }
                    }
                }
                imagePicker.launch("image/*")
            },
        )
    }

    private fun mutate(block: (CustomKeyboards.CustomKeyboard) -> Unit) {
        val before = CustomKeyboards.get(kbId) ?: return
        CustomKeyboards.mutate(kbId) { after ->
            block(after)
            val geometryChanged = before.rows.map { row -> listOf(row.height, row.alignment, row.margin, row.keys.map { it.skinId to it.width }) } !=
                after.rows.map { row -> listOf(row.height, row.alignment, row.margin, row.keys.map { it.skinId to it.width }) } || before.rowSpacing != after.rowSpacing || before.rowMargin != after.rowMargin || before.options != after.options
            if (geometryChanged || before.background != after.background) after.importedSkin = null
        }
        render()
    }

    // ----------------------------------------------------------------
    // rows
    // ----------------------------------------------------------------

    private fun render() {
        val kb = CustomKeyboards.get(kbId) ?: run {
            finish()
            return
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.cbe_imported_layout).apply {
            visibility = if (kb.importedSkin != null) View.VISIBLE else View.GONE
            text = "百度原皮肤坐标 · 转为自由布局"
            setOnClickListener {
                MaterialAlertDialogBuilder(this@CustomKeyboardEditActivity).setTitle("转为自由布局")
                    .setMessage("转换后可调整行高、行间距和按键顺序。按键素材和动效会保留，原皮肤横竖屏坐标将改为行布局。")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        CustomKeyboards.mutate(kbId) { it.importedSkin = null }; render()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        findViewById<TextView>(R.id.cbe_keyboard_height).text = if (kb.keyboardHeightDp > 0)
            getString(R.string.custom_kb_keyboard_height_value, kb.keyboardHeightDp)
            else getString(R.string.custom_kb_keyboard_height_auto_value, kb.effectivePortraitHeightDp())
        findViewById<TextView>(R.id.cbe_row_spacing).text = getString(R.string.custom_kb_row_spacing_value, kb.rowSpacing)
        findViewById<TextView>(R.id.cbe_row_margin).text = getString(R.string.custom_kb_row_margin_value, kb.rowMargin)
        rowsBox.removeAllViews()
        if (kb.rows.isEmpty()) {
            kb.rows.add(CustomKeyboards.CustomRow())
        }
        kb.rows.forEachIndexed { rowIdx, row ->
            rowsBox.addView(rowCard(kb, rowIdx, row))
        }
        renderPreview(kb)
    }

    /** 底部整体实时预览：按行权重渲染最终键盘形态（运行时行尾自动补空隙）。 */
    private fun renderPreview(kb: CustomKeyboards.CustomKeyboard) {
        val preview = findViewById<LinearLayout>(R.id.cbe_preview)
        preview.removeAllViews()
        // 行边距是按键与键盘边缘的距离：背景铺满不动，只有键排布收进两侧。
        preview.setPadding(dp(8), dp(8), dp(8), dp(8))
        preview.background = kb.backgroundFor(this).drawable(this, resources.displayMetrics.widthPixels)
        val importedPreview = kb.importedSkin?.layout(this)?.preview?.takeIf { it.isNotEmpty() }
        if (importedPreview != null && kb.allKeysBackground.ifBlank { com.osfans.trime.ime.appearance.SurfaceAppearance.current(true,kbId).keys.background }.isEmpty()) {
            val image = android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_XY
                setImageDrawable(com.osfans.trime.data.theme.AssetBitmapDrawable(importedPreview))
            }
            preview.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(160)))
            preview.post {
                if (image.parent === preview) {
                    val usableWidth = (preview.width - preview.paddingLeft - preview.paddingRight).coerceAtLeast(1)
                    val displayWidth = resources.displayMetrics.widthPixels.coerceAtLeast(1)
                    image.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                        (kb.importedKeyboardHeight(this).toFloat() * usableWidth / displayWidth).toInt().coerceAtLeast(1))
                }
            }
            return
        }
        val rowSum = kb.rows.sumOf { it.height.coerceIn(24, 160) }.coerceAtLeast(24).toFloat()
        val totalHeight = kb.effectivePortraitHeightDp().toFloat()
        val toolbarHeight = kb.effectiveToolbarHeightDp().toFloat()
        val heightScale = (totalHeight - toolbarHeight).coerceAtLeast(rowSum) / rowSum
        preview.addView(
            toolbarStrip(kb, (toolbarHeight * 0.65f).toInt().coerceAtLeast(1)),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp((toolbarHeight * 0.65f).toInt().coerceAtLeast(1))),
        )
        kb.rows.forEach { row ->
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val rowHeight = dp((row.height * 0.65f * heightScale).toInt().coerceAtLeast(1))
            val gap = dp((kb.rowSpacing * 0.65f * heightScale).toInt())
            rowView.weightSum = 100f
            val rowMarginPx = dp(((row.margin ?: kb.rowMargin) * 0.65f).toInt())
            rowView.setPadding(rowMarginPx, 0, rowMarginPx, 0)
            com.osfans.trime.data.keymap.ButtonAlignment.slots(row).forEach { (ck, w) ->
                if (ck == null) { rowView.addView(View(this),LinearLayout.LayoutParams(0,-1,w)); return@forEach }
                val cell =
                    TextView(this).apply {
                        text = ck.label.ifEmpty {
                            ck.bindings["click"]?.takeIf { it.isNotEmpty() } ?: "·"
                        }
                        gravity = Gravity.CENTER
                        textSize = 12f
                        maxLines = 1
                        isSingleLine = true
                        includeFontPadding = false
                        setPadding(dp(1), 0, dp(1), 0)
                        background = outlinedPreview(AppearancePicker.preview(kb.allKeysBackground.ifBlank { com.osfans.trime.ime.appearance.SurfaceAppearance.current(true,kbId).keys.background }) ?: ck.resolvedBackground().drawable(this@CustomKeyboardEditActivity, dp(80)))
                        setTextColor(android.graphics.Color.parseColor(ck.resolvedBackground().textColor(kb.resolvedBackground())))
                    }
                rowView.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w).apply { setMargins(dp(1), gap / 2, dp(1), gap - gap / 2) },
                )
            }
            preview.addView(
                rowView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowHeight),
            )
        }
    }

    private fun rowCard(
        kb: CustomKeyboards.CustomKeyboard,
        rowIdx: Int,
        row: CustomKeyboards.CustomRow,
    ): View {
        val weight = row.keys.sumOf { (it.width.takeIf { w -> w > 0f } ?: 10f).toDouble() }.toFloat()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(ContextCompat.getColor(this@CustomKeyboardEditActivity, R.color.settings_surface))
            }
        }

        // header: 第N行 · 权重/100 + 上移 下移 删除
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        header.addView(
            TextView(this).apply {
                text = getString(R.string.custom_kb_row_n, rowIdx + 1) + " · " + getString(R.string.custom_kb_weight, weight.toInt())
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@CustomKeyboardEditActivity, R.color.framework_text_secondary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val borderless = TypedValue().let {
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
            it.resourceId
        }
        fun imgBtn(icon: Int, desc: Int, onClick: () -> Unit) =
            ImageButton(this).apply {
                setImageResource(icon)
                imageTintList = ContextCompat.getColorStateList(context, R.color.framework_text_secondary)
                setBackgroundResource(borderless)
                contentDescription = getString(desc)
                setOnClickListener { onClick() }
            }
        header.addView(imgBtn(R.drawable.ic_baseline_expand_less_24, R.string.custom_kb_move_up) {
            mutate { k ->
                if (rowIdx > 0) {
                    val r = k.rows.removeAt(rowIdx)
                    k.rows.add(rowIdx - 1, r)
                }
            }
        })
        header.addView(imgBtn(R.drawable.ic_baseline_expand_more_24, R.string.custom_kb_move_down) {
            mutate { k ->
                if (rowIdx < k.rows.size - 1) {
                    val r = k.rows.removeAt(rowIdx)
                    k.rows.add(rowIdx + 1, r)
                }
            }
        })
        header.addView(imgBtn(R.drawable.ic_baseline_delete_24, R.string.custom_kb_row_delete) {
            mutate { k ->
                if (k.rows.size <= 1) {
                    Toast.makeText(this, R.string.custom_kb_min_one_row, Toast.LENGTH_SHORT).show()
                } else {
                    k.rows.removeAt(rowIdx)
                }
            }
        })
        card.addView(header)

        val rowControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(rowControls)
        rowControls.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "行高 ${row.height} dp"
            contentDescription = "调整第${rowIdx + 1}行高度，当前${row.height}dp"
            setOnClickListener { showRowHeightEditor(rowIdx) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        rowControls.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "行边距 · " + (row.margin?.let { "$it dp" } ?: "跟随")
            contentDescription = "调整第${rowIdx + 1}行边距"
            setOnClickListener { showRowMarginEditor(rowIdx) }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        rowControls.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            textSize = 14f
            text = "排列 · " + com.osfans.trime.data.keymap.ButtonAlignment.choices[com.osfans.trime.data.keymap.ButtonAlignment.normalized(row.alignment)]
            setOnClickListener {
                val choices = com.osfans.trime.data.keymap.ButtonAlignment.choices
                MaterialAlertDialogBuilder(this@CustomKeyboardEditActivity).setTitle("第${rowIdx + 1}行按钮排列")
                    .setSingleChoiceItems(choices.values.toTypedArray(), choices.keys.indexOf(row.alignment)) { dialog, which ->
                        mutate { it.rows[rowIdx].alignment = choices.keys.elementAt(which) }; dialog.dismiss()
                    }.setNegativeButton(android.R.string.cancel,null).show()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        // key strip
        val strip =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        val usable = (resources.displayMetrics.widthPixels * 0.82f)
        row.keys.forEachIndexed { keyIdx, ck ->
            val w = (usable * ((ck.width.takeIf { it > 0f } ?: 10f) / 100f)).toInt().coerceAtLeast(dp(44))
            val chip =
                TextView(this).apply {
                    text = ck.label.ifEmpty { getString(R.string.custom_kb_key_untitled) }
                    gravity = Gravity.CENTER
                    textSize = 14f
                    maxLines = 1
                    isSingleLine = true
                    includeFontPadding = false
                    setPadding(dp(2), 0, dp(2), 0)
                    setTypeface(typeface, if (ck.bindings.isNotEmpty()) Typeface.BOLD else Typeface.NORMAL)
                    background = outlinedPreview(AppearancePicker.preview(kb.allKeysBackground.ifBlank { com.osfans.trime.ime.appearance.SurfaceAppearance.current(true,kbId).keys.background }) ?: ck.resolvedBackground().drawable(this@CustomKeyboardEditActivity, w))
                    setTextColor(android.graphics.Color.parseColor(ck.resolvedBackground().textColor(kb.resolvedBackground())))
                }
            chip.contentDescription = "${ck.label.ifEmpty { "未命名按键" }}；点击编辑，长按拖动位置"
            chip.setOnClickListener { showKeyEditor(rowIdx, keyIdx) }
            chip.setOnLongClickListener {
                val token = KeyDrag(kbId,rowIdx,keyIdx,CustomKeyboards.version)
                val data = android.content.ClipData.newPlainText("keyboard-key","")
                if (android.os.Build.VERSION.SDK_INT >= 24) chip.startDragAndDrop(data,View.DragShadowBuilder(chip),token,0)
                else @Suppress("DEPRECATION") chip.startDrag(data,View.DragShadowBuilder(chip),token,0)
            }
            strip.addView(
                chip,
                LinearLayout.LayoutParams(w, dp(46)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) },
            )
        }
        val addKey =
            TextView(this).apply {
                text = "+"
                gravity = Gravity.CENTER
                textSize = 18f
                setPadding(dp(14), 0, dp(14), 0)
                background = ContextCompat.getDrawable(this@CustomKeyboardEditActivity, R.drawable.bg_key_preview)
                setTextColor(ContextCompat.getColor(this@CustomKeyboardEditActivity, R.color.framework_text_secondary))
            }
        addKey.setOnClickListener { showKeyEditor(rowIdx, row.keys.size) }
        strip.addView(
            addKey,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) },
        )

        card.addView(
            HorizontalScrollView(this).apply { addView(strip); enableKeyDrop(this,strip,rowIdx) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
        )
        return card
    }

    // ----------------------------------------------------------------
    // key editor dialog
    // ----------------------------------------------------------------

    private fun outlinedPreview(skin: android.graphics.drawable.Drawable): android.graphics.drawable.Drawable =
        android.graphics.drawable.LayerDrawable(arrayOf(skin, android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), android.graphics.Color.parseColor("#E88CAD"))
        }))

    /** 状态栏示意：按当前启用的工具栏按钮绘制，非像素级还原。 */
    private fun toolbarStrip(kb: CustomKeyboards.CustomKeyboard, heightPx: Int): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
            background = kb.toolbarBackground?.drawable(this@CustomKeyboardEditActivity, dp(80))
                ?: kb.resolvedBackground().drawable(this@CustomKeyboardEditActivity, dp(80))
        }
        val buttons = kb.toolbar.filter { it.enabled }
        if (buttons.isEmpty()) return strip
        val weight = 1f / buttons.size
        buttons.forEach { entry ->
            val label = entry.icon.ifEmpty { entry.action.removePrefix("__mie_") }
            strip.addView(
                TextView(this).apply {
                    text = label.take(6)
                    gravity = Gravity.CENTER
                    textSize = 10f
                    maxLines = 1
                    setTextColor(ContextCompat.getColor(this@CustomKeyboardEditActivity, R.color.framework_text_secondary))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply { setMargins(dp(1), 0, dp(1), 0) },
            )
        }
        return strip
    }

    private fun showKeyboardHeightEditor() {
        val kb = CustomKeyboards.get(kbId) ?: return
        val portrait = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (kb.keyboardHeightDp > 0) setText(kb.keyboardHeightDp.toString())
        }
        val landscape = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (kb.landscapeHeightDp > 0) setText(kb.landscapeHeightDp.toString())
        }
        val form = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        form.addView(com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.custom_kb_keyboard_height_range)
            helperText = getString(R.string.custom_kb_keyboard_height_hint)
            setPadding(dp(24), dp(8), dp(24), 0); addView(portrait)
        })
        form.addView(com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = getString(R.string.custom_kb_keyboard_height_land_range)
            setPadding(dp(24), dp(8), dp(24), 0); addView(landscape)
        })
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.custom_kb_keyboard_height)
            .setView(form).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.custom_kb_keyboard_height_reset) { _, _ ->
                mutate { it.keyboardHeightDp = 0; it.landscapeHeightDp = 0 }
            }
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p = portrait.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                val l = landscape.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                if (p !in 0..800 || l !in 0..800 || (p in 1..79) || (l in 1..79)) {
                    portrait.error = getString(R.string.custom_kb_keyboard_height_range)
                } else {
                    mutate { it.keyboardHeightDp = p; it.landscapeHeightDp = l }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showImportedHeightEditor() {
        val kb = CustomKeyboards.get(kbId)?.takeIf { it.importedSkin != null } ?: return
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(kb.importedHeightPercent.toString())
            selectAll()
        }
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.custom_kb_imported_height_range)
            helperText = getString(R.string.custom_kb_imported_height_hint)
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.custom_kb_imported_height)
            .setView(field).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.custom_kb_imported_height_reset) { _, _ -> mutate { it.importedHeightPercent = 100 } }
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val percent = input.text?.toString()?.toIntOrNull()
                if (percent == null || percent !in 70..150) {
                    field.error = getString(R.string.custom_kb_imported_height_range)
                } else {
                    mutate { it.importedHeightPercent = percent }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showRowSpacingEditor() {
        if (CustomKeyboards.get(kbId)?.importedSkin != null) {
            findViewById<View>(R.id.cbe_imported_layout).performClick(); return
        }
        val kb = CustomKeyboards.get(kbId) ?: return
        val limit = kb.maxRowSpacing()
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(kb.rowSpacing.toString())
            selectAll()
        }
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.custom_kb_row_spacing_range, limit)
            helperText = getString(R.string.custom_kb_row_spacing_hint)
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.custom_kb_row_spacing)
            .setView(field).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val spacing = input.text?.toString()?.toIntOrNull()
                if (spacing == null || spacing !in 0..limit) {
                    field.error = getString(R.string.custom_kb_row_spacing_range, limit)
                } else {
                    mutate { it.rowSpacing = spacing }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showRowMarginEditor(rowIdx: Int = -1) {
        val kb = CustomKeyboards.get(kbId) ?: return
        val isRow = rowIdx >= 0
        val current = if (isRow) kb.rows.getOrNull(rowIdx)?.margin else kb.rowMargin
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(current?.toString().orEmpty())
            hint = if (isRow) "留空跟随键盘行边距" else ""
            selectAll()
        }
        val field = TextInputLayout(this).apply {
            hint = getString(R.string.custom_kb_row_margin_range)
            helperText = if (isRow) "留空跟随键盘级行边距；0–48 dp" else getString(R.string.custom_kb_row_margin_hint)
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isRow) "第${rowIdx + 1}行 · 行边距" else getString(R.string.custom_kb_row_margin))
            .setView(field).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        if (isRow) (dialog as androidx.appcompat.app.AlertDialog).setButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL, "跟随键盘") { _, _ -> }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                mutate { it.rows.getOrNull(rowIdx)?.margin = null }
                dialog.dismiss()
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text?.toString()?.trim().orEmpty()
                if (isRow && text.isEmpty()) {
                    mutate { it.rows.getOrNull(rowIdx)?.margin = null }
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val margin = text.toIntOrNull()
                if (margin == null || margin !in 0..48) {
                    field.error = getString(R.string.custom_kb_row_margin_range)
                } else if (isRow) {
                    mutate { it.rows.getOrNull(rowIdx)?.margin = margin }
                    dialog.dismiss()
                } else {
                    mutate { it.rowMargin = margin }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showRowHeightEditor(rowIdx: Int) {
        if (CustomKeyboards.get(kbId)?.importedSkin != null) {
            findViewById<View>(R.id.cbe_imported_layout).performClick(); return
        }
        val row = CustomKeyboards.get(kbId)?.rows?.getOrNull(rowIdx) ?: return
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setText(row.height.toString())
            selectAll()
        }
        val field = TextInputLayout(this).apply {
            hint = "行高（24–160 dp）"
            helperText = "各行独立设置；悬浮缩放时按比例保持行高"
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("调整行高")
            .setView(field)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val height = input.text?.toString()?.toIntOrNull()
                if (height == null || height !in 24..160) {
                    field.error = "请输入 24–160 之间的整数"
                } else {
                    mutate { it.rows.getOrNull(rowIdx)?.height = height }
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** Drops insert between keys, including into empty rows. No mutation before ACTION_DROP. */
    private fun enableKeyDrop(scroll: HorizontalScrollView, strip: LinearLayout, row: Int) {
        var boundary = 0
        fun boundaryAt(x:Float) = (0 until strip.childCount-1).firstOrNull { x < strip.getChildAt(it).let { v -> (v.left+v.right)/2f } } ?: (strip.childCount-1)
        fun clearHint() { for (i in 0 until strip.childCount) strip.getChildAt(i).alpha=1f }
        scroll.setOnDragListener { _, event ->
            val token=event.localState as? KeyDrag
            if(token==null || token.keyboard!=kbId) return@setOnDragListener false
            when(event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    val x=event.x+scroll.scrollX
                    boundary=boundaryAt(x)
                    clearHint();strip.getChildAt(boundary)?.alpha=.45f
                    val edge=dp(32)
                    if(event.x < edge) scroll.smoothScrollBy(-dp(20),0)
                    else if(event.x > scroll.width-edge) scroll.smoothScrollBy(dp(20),0)
                    var owner=scroll.parent
                    while(owner is View && owner !is android.widget.ScrollView) owner=owner.parent
                    (owner as? android.widget.ScrollView)?.let { vertical ->
                        val rect=android.graphics.Rect();scroll.getDrawingRect(rect);vertical.offsetDescendantRectToMyCoords(scroll,rect)
                        val y=rect.top+event.y-vertical.scrollY
                        if(y < edge) vertical.smoothScrollBy(0,-dp(16))
                        else if(y > vertical.height-edge) vertical.smoothScrollBy(0,dp(16))
                    }
                    true
                }
                android.view.DragEvent.ACTION_DROP -> {
                    boundary=boundaryAt(event.x+scroll.scrollX)
                    clearHint()
                    if(token.version==CustomKeyboards.version) mutate { keyboard ->
                        com.osfans.trime.data.keymap.KeyReorder.move(keyboard.rows,token.row,token.key,row,boundary)
                    } else Toast.makeText(this,"布局已更新，请重新拖动",Toast.LENGTH_SHORT).show()
                    true
                }
                android.view.DragEvent.ACTION_DRAG_EXITED,android.view.DragEvent.ACTION_DRAG_ENDED -> { clearHint();true }
                else -> true
            }
        }
    }

    private fun showKeyEditor(
        rowIdx: Int,
        keyIdx: Int,
    ) {
        var editingIndex = keyIdx
        val kb = CustomKeyboards.get(kbId) ?: return
        val isNew = keyIdx >= (kb.rows.getOrNull(rowIdx)?.keys?.size ?: 0)
        val key =
            if (isNew) {
                CustomKey()
            } else {
                kb.rows[rowIdx].keys[keyIdx].let { it.copy(bindings = it.bindings.toMutableMap()) }
            }

        var keyBackground = key.resolvedBackground()
        var keyAppearance = key.appearance
        var replaceImportedStyle = false

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

        val bgRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(12), dp(10), dp(12))
                background = ContextCompat.getDrawable(this@CustomKeyboardEditActivity, R.drawable.bg_key_preview)
            }
        val bgValue =
            TextView(this).apply {
                gravity = Gravity.END
                textSize = 13f
                maxLines = 1
                isSingleLine = true
            }
        bgRow.addView(
            TextView(this).apply {
                text = getString(R.string.custom_kb_key_bg)
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@CustomKeyboardEditActivity, R.color.framework_text_primary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bgRow.addView(bgValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        bgRow.setOnClickListener {
            SkinPicker.show(
                this,
                getString(R.string.custom_kb_key_bg),
                keyBackground,
                onPicked = { background ->
                    keyBackground = background
                    bgValue.text = describeBg(keyBackground)
                },
                onPickImage = {
                    keyBgImageResult = { path ->
                        if (path != null) {
                            keyBackground = CustomBackground(CustomBackground.Type.IMAGE, image = path)
                            bgValue.text = describeBg(keyBackground)
                        }
                    }
                    imagePicker.launch("image/*")
                },
            )
        }
        bgValue.text = describeBg(keyBackground)
        root.addView(
            bgRow,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
        )

        // label + width inputs
        val labelLayout =
            TextInputLayout(this).apply {
                hint = getString(R.string.custom_kb_key_label)
            }
        val labelInput =
            TextInputEditText(labelLayout.context).apply {
                setText(key.label)
                setSingleLine(true)
            }
        labelLayout.addView(labelInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            labelLayout,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )

        val widthLayout =
            TextInputLayout(this).apply {
                hint = getString(R.string.custom_kb_key_width)
                setPadding(0, dp(8), 0, 0)
            }
        val widthInput =
            TextInputEditText(widthLayout.context).apply {
                setText((key.width.takeIf { it > 0f } ?: 10f).toString())
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                setSingleLine(true)
            }
        widthLayout.addView(widthInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(widthLayout)
        root.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = if (key.skin == null) "文字、符号、提示与按下外观" else "替换为文字按键并设置外观"
            setOnClickListener { KeyAppearanceEditor.show(this@CustomKeyboardEditActivity, text.toString(), keyAppearance) { keyAppearance = it; replaceImportedStyle = true } }
        })

        val gestureViews = mutableMapOf<String, TextView>()
        KeyBindingOverrides.EDITABLE_BEHAVIORS.forEach { (behavior, meta) ->
            val row =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                    background = ContextCompat.getDrawable(this@CustomKeyboardEditActivity, R.drawable.bg_key_preview)
                }
            gestureViews[behavior] =
                TextView(this).apply {
                    gravity = Gravity.END
                    textSize = 13f
                    maxLines = 1
                    isSingleLine = true
                }
            row.addView(
                TextView(this).apply {
                    text = meta.labelZh
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(this@CustomKeyboardEditActivity, R.color.framework_text_primary))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(gestureViews[behavior], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
            row.setOnClickListener {
                KeyTokenPicker.show(
                    this,
                    "${meta.labelZh} · ${key.label.ifEmpty { getString(R.string.custom_kb_key_untitled) }}",
                    key.bindings.containsKey(behavior),
                ) { token ->
                    if (token == null) {
                        key.bindings.remove(behavior)
                    } else {
                        key.bindings[behavior] = token
                    }
                    refreshGestureRows(gestureViews, key)
                }
            }
            root.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) },
            )
        }
        refreshGestureRows(gestureViews, key)

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(if (isNew) getString(R.string.custom_kb_add_key) else key.label.ifEmpty { getString(R.string.custom_kb_key_untitled) })
                // 内容（标签/宽度/7 手势行）可能超过屏高，必须垂直可滚动
                .setView(
                    android.widget.ScrollView(this).apply {
                        addView(root)
                    },
                )
                .setPositiveButton(R.string.ok) { _, _ ->
                    val label = labelInput.text?.toString()?.trim().orEmpty()
                    val width = widthInput.text?.toString()?.toFloatOrNull()?.coerceIn(1f, 100f) ?: 10f
                    val newKey = key.copy(label = label, width = width, bgColor = "", bgImage = "", background = keyBackground, appearance = keyAppearance, skin = key.skin.takeIf { keyBackground == key.resolvedBackground() && !replaceImportedStyle })
                    mutate { k ->
                        val row = k.rows[rowIdx]
                        if (isNew) {
                            row.keys.add(newKey)
                        } else {
                            row.keys[editingIndex] = newKey
                        }
                    }
                }
                .apply {
                    if (!isNew) {
                        setNeutralButton(R.string.custom_kb_key_delete) { _, _ ->
                            mutate { k -> k.rows[rowIdx].keys.removeAt(editingIndex) }
                        }
                        setNegativeButton(R.string.custom_kb_key_move) { _, _ -> }
                    } else {
                        setNegativeButton(R.string.cancel, null)
                    }
                }
                .create()
        dialog.show()

        if (!isNew) {
            // 自定义「移动」：覆盖左侧按钮，弹二级菜单后不关闭本对话框
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.custom_kb_key_move)
                    .setItems(
                        arrayOf(
                            getString(R.string.custom_kb_move_left),
                            getString(R.string.custom_kb_move_right),
                        ),
                    ) { _, which ->
                        val dir = if (which == 0) -1 else 1
                        mutate { k ->
                            val row = k.rows[rowIdx]
                            val target = editingIndex + dir
                            if (target in row.keys.indices) {
                                val boundary = if (dir > 0) target + 1 else target
                                editingIndex = com.osfans.trime.data.keymap.KeyReorder.move(k.rows,rowIdx,editingIndex,rowIdx,boundary) ?: editingIndex
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun describeBg(background: CustomBackground): String = when (background.type) {
        CustomBackground.Type.COLOR -> getString(R.string.custom_bg_color) + " · " + background.color
        CustomBackground.Type.TRANSPARENT -> getString(R.string.custom_bg_transparent)
        CustomBackground.Type.IMAGE -> getString(R.string.custom_bg_image)
        CustomBackground.Type.VIDEO -> "视频（静音循环）"
    }

    private fun refreshGestureRows(
        gestureViews: Map<String, TextView>,
        key: CustomKey,
    ) {
        KeyBindingOverrides.EDITABLE_BEHAVIORS.forEach { (behavior, _) ->
            gestureViews[behavior]?.let { v ->
                val bound = key.bindings[behavior]
                v.text =
                    when {
                        bound == null && behavior == "longClick" && key.bindings["click"] in listOf("space", "Space", " ") &&
                            com.osfans.trime.ime.voice.VoiceSettings.enabled -> "内置语音 · 默认"
                        bound == null -> getString(R.string.key_editor_empty_bind)
                        bound.isEmpty() -> getString(R.string.key_editor_unbound)
                        else -> com.osfans.trime.data.keymap.BindingActions.label(bound)
                    } + if (bound != null) " · " + getString(R.string.key_editor_customized) else ""
                v.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (bound != null) R.color.m3_primary else R.color.framework_text_secondary,
                    ),
                )
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_ID = "id"

        fun start(
            context: Context,
            id: String,
        ) {
            context.startActivity(
                Intent(context, CustomKeyboardEditActivity::class.java).apply {
                    putExtra(EXTRA_ID, id)
                    if (context !is AppCompatActivity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
