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

        val toolbar = findViewById<MaterialToolbar>(R.id.cbe_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cbe_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = kb.name
        toolbar.inflateMenu(R.menu.custom_kb_edit)
        toolbar.menu.add(0, 2180, 0, "自定义键盘全局外观")
        toolbar.menu.add(0, 2182, 2, "字体与字号（当前布局）")
        toolbar.menu.add(0, 2183, 3, "自定义功能")
        toolbar.menu.add(0, 2181, 1, "全体按键背景 · 一键设置")
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                2180 -> SurfaceAppearanceEditor.show(this, true, layoutId = kbId) { refreshAppearancePreview { render() } }
                2182 -> LayoutTypographyEditor.show(this, kbId)
                2183 -> NamedFunctionsEditor.show(this)
                2181 -> {
                    val current = CustomKeyboards.get(kbId) ?: return@setOnMenuItemClickListener true
                    AppearancePicker.show(this, "当前布局全体按键背景", current.allKeysBackground, true) { raw -> mutate { it.allKeysBackground = raw } }
                }
                R.id.action_kb_background -> pickKeyboardBackground()
                R.id.action_kb_toolbar -> ToolbarEditActivity.start(this, kbId)
                R.id.action_kb_key_appearance -> {
                    val current = CustomKeyboards.get(kbId) ?: return@setOnMenuItemClickListener true
                    KeyAppearanceEditor.show(this, "按键默认外观", current.keyAppearance) { style -> mutate { it.keyAppearance = style } }
                }
                R.id.action_kb_layout_options -> {
                    val current = CustomKeyboards.get(kbId) ?: return@setOnMenuItemClickListener true
                    if (current.importedSkin != null) findViewById<View>(R.id.cbe_imported_layout).performClick()
                    else KeyboardOptionsEditor.show(this, current.options) { options -> mutate { it.options = options } }
                }
            }
            true
        }

        findViewById<View>(R.id.cbe_imported_height).setOnClickListener { showImportedHeightEditor() }
        findViewById<View>(R.id.cbe_row_spacing).setOnClickListener { showRowSpacingEditor() }
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
                        mutate { k ->
                            k.background = CustomBackground(CustomBackground.Type.IMAGE, image = path)
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
            val geometryChanged = before.rows.map { row -> row.height to row.keys.map { it.skinId to it.width } } !=
                after.rows.map { row -> row.height to row.keys.map { it.skinId to it.width } } || before.rowSpacing != after.rowSpacing || before.options != after.options
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
        findViewById<TextView>(R.id.cbe_imported_height).apply {
            visibility = if (kb.importedSkin != null) View.VISIBLE else View.GONE
            text = getString(R.string.custom_kb_imported_height_value, kb.importedHeightPercent)
        }
        findViewById<TextView>(R.id.cbe_row_spacing).text = getString(R.string.custom_kb_row_spacing_value, kb.rowSpacing)
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
        preview.background = kb.backgroundFor(this).drawable(this, resources.displayMetrics.widthPixels)
        val importedPreview = kb.importedSkin?.layout(this)?.preview?.takeIf { it.isNotEmpty() }
        if (importedPreview != null && com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.keyBackground.getValue().isEmpty()) {
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
        kb.rows.forEach { row ->
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val rowHeight = dp((row.height * 0.65f).toInt())
            val gap = dp((kb.rowSpacing * 0.65f).toInt())
            var weightSum = 0f
            row.keys.forEach { ck ->
                val w = ck.width.takeIf { it > 0f } ?: 10f
                weightSum += w
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
                        background = outlinedPreview(AppearancePicker.preview(com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.keyBackground.getValue()) ?: ck.resolvedBackground().drawable(this@CustomKeyboardEditActivity, dp(80)))
                        setTextColor(android.graphics.Color.parseColor(ck.resolvedBackground().textColor(kb.resolvedBackground())))
                    }
                rowView.addView(
                    cell,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w).apply { setMargins(dp(1), gap / 2, dp(1), gap - gap / 2) },
                )
            }
            // 行尾空隙补齐，与运行时 toTextKeyboard 的 spacer 一致
            val rest = 100f - weightSum
            if (rest > 0.5f) {
                rowView.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, rest),
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

        card.addView(com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "行高 ${row.height} dp · 点击调整"
            contentDescription = "调整第${rowIdx + 1}行高度，当前${row.height}dp"
            setOnClickListener { showRowHeightEditor(rowIdx) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

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
                    background = outlinedPreview(AppearancePicker.preview(com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.keyBackground.getValue()) ?: ck.resolvedBackground().drawable(this@CustomKeyboardEditActivity, w))
                    setTextColor(android.graphics.Color.parseColor(ck.resolvedBackground().textColor(kb.resolvedBackground())))
                }
            chip.setOnClickListener { showKeyEditor(rowIdx, keyIdx) }
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
            HorizontalScrollView(this).apply { addView(strip) },
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

    private fun showImportedHeightEditor() {
        val kb = CustomKeyboards.get(kbId)?.takeIf { it.importedSkin != null } ?: return
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
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

    private fun showRowHeightEditor(rowIdx: Int) {
        if (CustomKeyboards.get(kbId)?.importedSkin != null) {
            findViewById<View>(R.id.cbe_imported_layout).performClick(); return
        }
        val row = CustomKeyboards.get(kbId)?.rows?.getOrNull(rowIdx) ?: return
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
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

    private fun showKeyEditor(
        rowIdx: Int,
        keyIdx: Int,
    ) {
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
                            row.keys[keyIdx] = newKey
                        }
                    }
                }
                .apply {
                    if (!isNew) {
                        setNeutralButton(R.string.custom_kb_key_delete) { _, _ ->
                            mutate { k -> k.rows[rowIdx].keys.removeAt(keyIdx) }
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
                            val target = keyIdx + dir
                            if (target in row.keys.indices) {
                                val moved = row.keys.removeAt(keyIdx)
                                row.keys.add(target, moved)
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
