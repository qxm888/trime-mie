/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeLoader
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.appearance.SurfaceAppearance
import com.osfans.trime.ime.keyboard.resolveKeyboardPreset
import kotlinx.serialization.json.*

/** One set, three short pages. Existing editors are reused through a scoped virtual id. */
class KeyboardSetActivity : AppCompatActivity() {
    private var root = ""
    private var previewId = ""
    private var tab = 0
    private var ready = false
    private lateinit var body: LinearLayout
    private lateinit var preview: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private val scopeId get() = KeyboardSets.EDIT_PREFIX + root
    private var imageResult: ((String) -> Unit)? = null
    private val image = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        SkinPicker.handleImageResult(this, uri) { path -> if(path != null) imageResult?.invoke(path); imageResult = null }
    }
    private val video = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        SkinPicker.handleVideoResult(this, uri) { path -> if(path != null) change { it.background = CustomBackground(CustomBackground.Type.VIDEO, image = path) } }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = intent.getStringExtra("root").orEmpty()
        if (CustomKeyboards.get(root) == null) { finish(); return }
        tab = state?.getInt("tab") ?: 0; previewId = state?.getString("preview") ?: root
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundResource(R.color.framework_background) }
        toolbar = MaterialToolbar(this).apply {
            title = CustomKeyboards.get(root)?.name
            setNavigationIcon(com.google.android.material.R.drawable.ic_arrow_back_black_24)
            setNavigationOnClickListener { finish() }
            menu.add("更多").setOnMenuItemClickListener { if(ready) more(); true }
        }
        page.addView(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        preview = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        page.addView(preview)
        val tabs = TabLayout(this).apply {
            listOf("布局", "整套设置", "复用").forEach { addTab(newTab().setText(it)) }
            getTabAt(tab)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(item: TabLayout.Tab) { tab = item.position; render() }
                override fun onTabUnselected(item: TabLayout.Tab) {}
                override fun onTabReselected(item: TabLayout.Tab) {}
            })
        }; page.addView(tabs)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        page.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        hint("正在读取整套设置…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                if(root in KeyboardLayoutCatalog.builtinIds(this@KeyboardSetActivity)) KeyboardSetOperations.adoptBuiltin(root)
                else KeyboardSetOperations.ensure(root)
            } }
            if(isFinishing || isDestroyed) return@launch
            result.onSuccess { ready = true; render() }.onFailure {
                MaterialAlertDialogBuilder(this@KeyboardSetActivity).setTitle("读取失败").setMessage(it.message)
                    .setPositiveButton("返回") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            }
        }
    }
    override fun onResume() { super.onResume(); if(::body.isInitialized) render() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", tab); outState.putString("preview", previewId); super.onSaveInstanceState(outState) }
    override fun onPause() { super.onPause(); CustomKeyboards.flush(); com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh() }
    /** #AARRGGBB → 不透明色。预览只是示意图，半透明色叠在设置页上会看成「另一个颜色」。 */
    private fun solid(value: String): Int {
        val c = runCatching { android.graphics.Color.parseColor(value) }.getOrNull()
            ?: return getColor(R.color.framework_text_primary)
        return android.graphics.Color.rgb(
            android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c),
        )
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun hint(text: String, target: LinearLayout = body) { target.addView(TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(getColor(R.color.framework_text_secondary)); setPadding(dp(4), dp(8), dp(4), dp(10))
    }) }
    private fun button(label: String, target: LinearLayout = body, action: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = label; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL; cornerRadius = dp(16)
        setOnClickListener { action() }; target.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun change(after: (() -> Unit)? = null, block: (CustomKeyboards.CustomKeyboard) -> Unit) {
        KeyboardSetDialogs.run(this, { render() }) {
            check(CustomKeyboards.mutate(scopeId, block)) { "整套键盘已删除" }
            after?.invoke()
            check(CustomKeyboards.flush()) { "设置保存失败" }
        }
    }
    private fun render() {
        if(!ready) return
        if(CustomKeyboards.get(root) == null) { finish(); return }
        body.removeAllViews(); toolbar.title = CustomKeyboards.get(root)?.name
        showPreview()
        when(tab) { 0 -> layouts(); 1 -> settings(); else -> reuse() }
    }
    private fun showPreview() {
        preview.removeAllViews()
        val ids = KeyboardSetOperations.ordered(root)
        if(previewId !in ids) previewId = ids.firstOrNull() ?: root
        val kb = CustomKeyboards.get(previewId) ?: return
        button("预览 · ${kb.name}  ▾", preview) {
            MaterialAlertDialogBuilder(this).setTitle("预览布局").setItems(ids.map { CustomKeyboards.get(it)?.name.orEmpty() }.toTypedArray()) { _, i -> previewId = ids[i]; render() }.show()
        }
        val keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 预览是示意图：底色实心化，否则半透明的键盘色会透出设置页的米色，
        // 看起来像「换了套颜色」（真机上底下是 App，颜色本来就不同）。
        val panel = kb.resolvedBackground()
        keys.background = if (panel.type == CustomBackground.Type.COLOR) {
            android.graphics.drawable.GradientDrawable().apply {
                setColor(solid(panel.color)); cornerRadius = dp(8).toFloat()
            }
        } else {
            panel.drawable(this@KeyboardSetActivity, resources.displayMetrics.widthPixels)
        }
        // A small live style preview; the full editor remains available on each layout card.
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        kb.rows.take(if(landscape) 2 else 5).forEach { row ->
            val line = LinearLayout(this)
            row.keys.forEach { key ->
                val style = KeyAppearance.merge(kb.keyAppearance, key.appearance)
                line.addView(TextView(this).apply {
                    text = key.label; textSize = 12f; gravity = Gravity.CENTER; maxLines = 1
                    // AppCompat 的 TextView 会拿主题的 backgroundTint 给背景染色，
                    // 白键帽会被染成米灰（预览里看着像「换了颜色」），这里显式去掉。
                    backgroundTintList = null
                    val color = style.keyBackColor.ifBlank { KeyboardSets.backgroundToken(key.resolvedBackground()) }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(solid(color))
                        cornerRadius = dp(5).toFloat()
                    }
                    setTextColor(solid(style.keyTextColor))
                }, LinearLayout.LayoutParams(0, dp(if(landscape) 18 else 25), key.width).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
            }; keys.addView(line)
        }; preview.addView(keys)
    }
    private fun layouts() {
        val ids = KeyboardSetOperations.ordered(root)
        hint("${ids.size} 个布局 · 点按编辑，长按管理")
        ids.chunked(2).forEach { pair ->
            val row = LinearLayout(this); body.addView(row)
            pair.forEach { id -> val kb = CustomKeyboards.get(id) ?: return@forEach
                val value = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = kb.name.removePrefix(CustomKeyboards.get(root)!!.name).trim(' ', '·').ifEmpty { KeyboardSetOperations.layoutNames[kb.setPageRole] ?: "主布局" } +
                        if(CustomKeyboards.get(root)?.setConfig?.defaultPage == id) " ★" else ""
                    isAllCaps = false; minHeight = 0; insetTop = 0; insetBottom = 0; cornerRadius = dp(18)
                    // 同一行两张卡片必须等高：文字最多两行，超长省略，单行文字垂直居中。
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setOnClickListener { CustomKeyboardEditActivity.start(this@KeyboardSetActivity, id) }
                    setOnLongClickListener { managePage(id); true }
                }; row.addView(value, LinearLayout.LayoutParams(0, dp(72), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
        }
        button("＋ 添加布局") { KeyboardSetDialogs.chooseTemplate(this) { template ->
            val entries = KeyboardSetOperations.layoutNames.entries.toList()
            MaterialAlertDialogBuilder(this).setTitle("添加布局").setItems(entries.map { it.value }.toTypedArray()) { _, i ->
                KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.addLayout(root, entries[i].key, template) }
            }.show()
        } }
        button("＋ 空白布局") { KeyboardSetDialogs.name(this, "布局名称") { name ->
            var id = ""; KeyboardSetDialogs.run(this, { render(); CustomKeyboardEditActivity.start(this, id) }) { id = KeyboardSetOperations.addBlank(root, name) }
        } }
        button("⇄ 从其它布局复制按键") { pickCopySource() }
    }

    private fun toast(text: String) { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show() }

    private fun letterPages(exclude: String = "") = KeyboardSetOperations.ordered(root)
        .filter { it != exclude }
        .mapNotNull { id -> CustomKeyboards.get(id)?.takeIf { KeyboardKeyCopy.isLetterLayout(it) }?.let { id to it } }

    /**
     * 所有键盘（内置皮肤与自建键盘都算）里含 26 字母键的布局，按「键盘 → 布局」分组。
     *
     * 内置皮肤是以独立的整套键盘注入的，所以「从其它布局复制按键」原来只列当前这一套、
     * 看不到别的键盘；这里改成跨整套收集，来源和去处都能选到别的键盘。
     */
    private fun allLetterGroups(): List<Pair<String, List<Pair<String, String>>>> {
        val entries = CustomKeyboards.list()
        val byId = entries.associateBy { it.id }
        fun rootOf(id: String): String {
            var current = id
            val seen = mutableSetOf<String>()
            while (seen.add(current)) {
                val parent = byId[current]?.secondaryOf.orEmpty()
                if (parent.isBlank() || parent !in byId) return current
                current = parent
            }
            return current
        }
        return entries.groupBy({ rootOf(it.id) }, { it.id })
            .map { (setRoot, _) ->
                val name = byId[setRoot]?.name ?: setRoot
                name to KeyboardSetOperations.ordered(setRoot)
                    .mapNotNull { pid -> CustomKeyboards.get(pid)?.takeIf { KeyboardKeyCopy.isLetterLayout(it) }?.let { pid to it.name } }
            }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first }
    }

    private fun pickCopySource() {
        val groups = allLetterGroups()
        if (groups.isEmpty()) { toast("没有含 26 个字母键的布局"); return }
        val items = groups.map { it.first } + "从键盘主题导入…"
        MaterialAlertDialogBuilder(this).setTitle("选择按键来源").setItems(items.toTypedArray()) { _, index ->
            if (index == groups.size) pickTheme() else pickLayout(groups[index]) { id -> pickCopyTarget(id) }
        }.setNegativeButton("取消", null).show()
    }

    private fun pickLayout(group: Pair<String, List<Pair<String, String>>>, onPick: (String) -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(group.first)
            .setItems(group.second.map { it.second }.toTypedArray()) { _, i -> onPick(group.second[i].first) }
            .setNegativeButton("取消", null).show()
    }

    private fun pickCopyTarget(sourceId: String) {
        val source = CustomKeyboards.get(sourceId) ?: return
        pickCopyTarget(source, source.name, sourceId)
    }

    private fun pickCopyTarget(source: CustomKeyboards.CustomKeyboard, sourceName: String, sourceId: String = "") {
        val groups = allLetterGroups().map { (name, pages) -> name to pages.filter { it.first != sourceId } }
        if (groups.none { it.second.isNotEmpty() }) { toast("没有可复制的 26 键布局"); return }
        MaterialAlertDialogBuilder(this).setTitle("复制到 · 选择键盘").setItems(groups.map { it.first }.toTypedArray()) { _, i ->
            val pages = groups[i].second
            if (pages.isEmpty()) { toast("这组没有可复制的 26 键布局"); return@setItems }
            MaterialAlertDialogBuilder(this).setTitle("复制到 · ${groups[i].first}")
                .setItems(pages.map { it.second }.toTypedArray()) { _, j -> runCopy(source, sourceName, pages[j].first) }
                .setNegativeButton("取消", null).show()
        }.setNegativeButton("取消", null).show()
    }

    private fun runCopy(source: CustomKeyboards.CustomKeyboard, sourceName: String, targetId: String) {
        lifecycleScope.launch {
            val plan = withContext(Dispatchers.Default) { runCatching { KeyboardKeyCopy.plan(source, sourceName, targetId) } }
            plan.onSuccess { confirmCopy(it) }.onFailure { toast(it.message ?: "无法读取布局") }
        }
    }

    private fun confirmCopy(plan: KeyboardKeyCopy.Plan) {
        MaterialAlertDialogBuilder(this).setTitle("复制按键")
            .setMessage("来源：${plan.sourceName}\n目标：${plan.targetName}\n\n将复制 ${plan.matches.size} 个字母键的手势、符号与注解" +
                (if (plan.skipped > 0) "；${plan.skipped} 个键未匹配，保留原样" else "") +
                "。\n主输入、键面标签、宽度、背景和图标保持不变。")
            .setPositiveButton("复制") { _, _ ->
                lifecycleScope.launch {
                    val copied = withContext(Dispatchers.IO) { runCatching { KeyboardKeyCopy.apply(plan) } }
                    copied.onSuccess { ThemeManager.requestCustomizationRefresh(); render(); toast("复制成功") }
                        .onFailure { toast(it.message ?: "复制失败") }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun pickTheme() {
        lifecycleScope.launch {
            val themes = withContext(Dispatchers.IO) { runCatching { ThemeManager.getAllThemes().distinctBy { it.configId }.sortedBy { it.name } } }
                .getOrElse { toast("读取主题失败：${it.message}"); return@launch }
            if (themes.isEmpty()) { toast("没有可用主题"); return@launch }
            MaterialAlertDialogBuilder(this@KeyboardSetActivity).setTitle("选择主题")
                .setItems(themes.map { "${it.name}（${it.configId}）" }.toTypedArray()) { _, i -> pickThemeKeyboard(themes[i].configId) }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun pickThemeKeyboard(configId: String) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { ThemeLoader.loadTheme(configId) } }
            val theme = loaded.getOrNull()?.let { result -> (result as? ThemeLoader.ThemeLoadResult.Success)?.theme }
            if (theme == null) { toast("无法读取主题 $configId"); return@launch }
            val ids = theme.presetKeyboards.keys.toList()
            val names = ids.map { id -> runCatching { resolveKeyboardPreset(theme.presetKeyboards, id)?.name?.ifBlank { id } }.getOrNull() ?: id }
            MaterialAlertDialogBuilder(this@KeyboardSetActivity).setTitle("选择键盘布局")
                .setItems(names.toTypedArray()) { _, i -> useThemeKeyboard(theme, ids[i]) }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun useThemeKeyboard(theme: Theme, keyboardId: String) {
        val appearance = SurfaceAppearance.current(false)
        val unified = SurfaceAppearance.unifiedBackground(false)
        val layoutName = runCatching { resolveKeyboardPreset(theme.presetKeyboards, keyboardId)?.name.orEmpty() }.getOrDefault("")
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { ThemeKeyboardImporter(applicationContext).parse(theme, keyboardId, layoutName, appearance, unified) }
            }
            parsed.onSuccess { source ->
                if (!KeyboardKeyCopy.isLetterLayout(source)) { toast("这个键盘没有 26 个独立字母键，不能复制"); return@launch }
                pickCopyTarget(source, source.name.ifBlank { keyboardId })
            }.onFailure { toast(it.message ?: "无法读取该键盘") }
        }
    }
    private fun managePage(id: String) {
        MaterialAlertDialogBuilder(this).setTitle(CustomKeyboards.get(id)?.name)
            .setItems(arrayOf("设为默认中文布局", "向前移动", "向后移动", "重命名", "跟随／独立设置", "删除布局")) { _, i -> when(i) {
                0 -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.setDefault(root, id) }
                1, 2 -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.move(root, id, if(i == 1) -1 else 1) }
                3 -> KeyboardSetDialogs.name(this, "布局名称", CustomKeyboards.get(id)?.name.orEmpty()) { CustomKeyboards.rename(id, it); render() }
                4 -> KeyboardSetPageSettings.show(this, id) { render() }
                else -> if(id == root) android.widget.Toast.makeText(this, "主布局请在整套列表中删除", 0).show() else MaterialAlertDialogBuilder(this)
                    .setTitle("删除此布局？").setMessage("其它布局的跳转将保留；可重新添加此布局。")
                    .setPositiveButton("删除") { _, _ -> CustomKeyboards.delete(id); render() }.setNegativeButton("取消", null).show()
            } }.show()
    }
    private fun settings() {
        hint("应用于整套 · ${KeyboardSetOperations.ordered(root).size} 个布局：改哪一项就推哪一项，之后在单页里再改则以单页为准。")
        // 复用单页那套「外观」面板：按键 / 工具栏 / 整体一次改完，推到这套皮肤里的每一页。
        button("外观 · 按键／工具栏／整体") { SurfaceAppearanceEditor.show(this, true, scopeId) { render() } }
        KeyboardSets.groups.forEach { (group, name) -> button(name + "  ›") { editGroup(group) } }
        button("整理已有布局为整套共用") {
            MaterialAlertDialogBuilder(this).setTitle("整理整套设置")
                .setMessage("与主布局相同的设置转为跟随，不同设置保留独立。后续修改整套设置时，跟随项会一起更新。")
                .setPositiveButton("整理") { _, _ -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.consolidate(root) } }.setNegativeButton("取消", null).show()
        }
    }
    private fun editGroup(group: String) {
        val kb = CustomKeyboards.get(scopeId) ?: return
        when(group) {
            "geometry" -> editGeometry(kb)
            // 工具栏背景与叠加在顶层「外观 · 工具栏」里改，这里只留键盘背景。
            "background" -> SkinPicker.show(this, "整套键盘背景", kb.resolvedBackground(), onPicked = { bg -> change { it.background = bg } },
                onPickImage = {
                    imageResult = { path ->
                        // 和单页那边一致：先按键盘的实际宽高比裁一刀再落盘。
                        // 不裁的话背景图会被 stretch 到整块面板，比例不对就明显变形。
                        val heightDp = kb.effectivePortraitHeightDp().toFloat().coerceAtLeast(24f)
                        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
                        SkinPicker.showBackgroundCrop(this, path, widthDp / heightDp) { cropped ->
                            if (cropped != null) {
                                change { it.background = CustomBackground(CustomBackground.Type.IMAGE, image = cropped) }
                            }
                        }
                    }
                    image.launch("image/*")
                },
                onPickVideo = { video.launch("video/*") })
            "appearance" -> MaterialAlertDialogBuilder(this).setTitle("整套按键外观").setItems(arrayOf("全部按键统一外观", "普通键", "功能键", "强调键", "强制覆盖全部按键背景")) { _, i ->
                when(i) {
                    4 -> AppearancePicker.show(this, "整套全体按键背景", kb.allKeysBackground, true) { raw -> change { it.allKeysBackground = raw } }
                    else -> { val role = listOf("normal", "normal", "function", "accent")[i]
                        val old = kb.setKeyStyles[role] ?: kb.keyAppearance
                        KeyAppearanceEditor.show(this, if(i == 0) "整套全部按键" else listOf("", "普通键", "功能键", "强调键")[i], old) { style ->
                            val diff = KeyboardSets.difference(KeyAppearance.values(old), KeyAppearance.values(style))
                            // 整套只是一块推送面板：改到哪一项就推哪一项，其余字段保留各页自己的值。
                            change { target ->
                                if (i == 0) {
                                    target.keyAppearance = KeyboardSets.style(KeyboardSets.overlay(KeyAppearance.values(target.keyAppearance), diff))
                                    target.setKeyStyles = listOf("normal", "function", "accent").associateWith { type ->
                                        KeyboardSets.style(KeyboardSets.overlay(KeyAppearance.values(target.setKeyStyles[type] ?: old), diff)) }
                                } else target.setKeyStyles = target.setKeyStyles + (role to style)
                            }
                        }
                    }
                }
            }.show()
            "fonts" -> LayoutTypographyEditor.show(this, scopeId)
            "toolbar", "menus" -> ToolbarEditActivity.start(this, scopeId)
            "floating" -> FloatingCandidatesEditActivity.start(this, scopeId)
            "bindings" -> editBindings()
        }
    }
    private fun editGeometry(kb: CustomKeyboards.CustomKeyboard) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val definitions = listOf("竖屏总高度 dp（0 自动，80–800）" to kb.keyboardHeightDp,
            "横屏总高度 dp（0 跟随横屏设置，80–800）" to kb.landscapeHeightDp,
            "行间距 dp（0–32）" to kb.rowSpacing, "行左右边距 dp（0–48）" to kb.rowMargin)
        val inputs = definitions.map { (label, value) ->
            val input = com.google.android.material.textfield.TextInputEditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(value.toString())
            }
            content.addView(com.google.android.material.textfield.TextInputLayout(this).apply { hint = label; addView(input) })
            input
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("整套尺寸与间距").setView(ScrollView(this).apply { addView(content) })
            .setMessage("跟随整套的页面一同更新，独立设置的页面保留。高度包含工具栏；行间距与行高各自独立，间距过大会把按键压扁。")
            .setNegativeButton("取消", null).setPositiveButton("应用", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val values = inputs.map { it.text?.toString()?.toIntOrNull() }
            val valid = values.mapIndexed { i, value -> value != null && when(i) {
                0, 1 -> value == 0 || value in 80..800; 2 -> value in 0..32; else -> value in 0..48
            } }
            inputs.forEachIndexed { i, input -> input.error = if(valid[i]) null else "请输入范围内的整数" }
            if(valid.all { it }) {
                change { it.keyboardHeightDp = values[0]!!; it.landscapeHeightDp = values[1]!!; it.rowSpacing = values[2]!!; it.rowMargin = values[3]!! }
                dialog.dismiss()
            }
        } }; dialog.show()
    }
    private fun editBindings() {
        val allKeys = KeyboardSetOperations.ordered(root).flatMap { CustomKeyboards.get(it)?.rows.orEmpty().flatMap { it.keys } }
        val keys = allKeys.filter { it.bindings.isNotEmpty() }.distinctBy(KeyboardSets::identity)
        MaterialAlertDialogBuilder(this).setTitle("整套常用绑定 · 选择按键")
            .setItems(keys.map { it.label.ifBlank { KeyboardSets.identity(it) } }.toTypedArray()) { _, index ->
                val key = keys[index]; val identity = KeyboardSets.identity(key)
                val behaviors = KeyBindingOverrides.EDITABLE_BEHAVIORS.toList()
                MaterialAlertDialogBuilder(this).setTitle(key.label).setItems(behaviors.map { it.second.labelZh }.toTypedArray()) { _, i ->
                    val behavior = behaviors[i].first
                    KeyTokenPicker.show(this, "整套 · ${key.label} · ${behaviors[i].second.labelZh}", true) { token ->
                        change { kb -> val rules = kb.setBindings[identity].orEmpty().toMutableMap()
                            if(token == null) rules.remove(behavior) else rules[behavior] = token
                            kb.setBindings = kb.setBindings + (identity to rules)
                        }
                    }
                }.show()
            }.setNegativeButton("取消", null).show()
    }
    private fun reuse() {
        button("复制其它整套的设置") { KeyboardSetDialogs.chooseSet(this, "选择来源整套", root) { source ->
            val entries = KeyboardSets.groups.entries.toList(); val selected = BooleanArray(entries.size) { true }
            MaterialAlertDialogBuilder(this).setTitle("选择复制内容").setMultiChoiceItems(entries.map { it.value }.toTypedArray(), selected) { _, i, checked -> selected[i] = checked }
                .setPositiveButton("下一步") { _, _ ->
                    val groups = entries.indices.filter { selected[it] }.map { entries[it].key }.toSet()
                    if(groups.isEmpty()) return@setPositiveButton
                    MaterialAlertDialogBuilder(this).setTitle("复制到 ${CustomKeyboards.get(root)?.name}")
                        .setMessage("来源：${CustomKeyboards.get(source)?.name}\n目标：${KeyboardSetOperations.ordered(root).size} 个布局\n内容：${groups.joinToString { KeyboardSets.groups[it].orEmpty() }}\n\n保留独立项：只更新跟随整套的设置。覆盖单页独立项：所选类别转为跟随新的整套设置；单键独立设置保留。")
                        .setPositiveButton("保留独立项") { _, _ -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.copySettings(source, root, groups, false) } }
                        .setNeutralButton("覆盖单页独立项") { _, _ -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.copySettings(source, root, groups, true) } }
                        .setNegativeButton("取消", null).show()
                }.setNegativeButton("取消", null).show()
        } }
        hint("持续共用方案：只影响主动引用它的整套。")
        val config = CustomKeyboards.get(root)?.setConfig
        KeyboardSets.groups.forEach { (group, title) ->
            val profile = CustomKeyboards.sharedProfiles().firstOrNull { it.id == config?.profiles?.get(group) }
            button("$title · ${profile?.name ?: "本套独立"}") { profiles(group) }
        }
        button("复制整套按键绑定（匹配现有布局）") { KeyboardBindingCopyDialog.show(this, root) { render() } }
    }
    private fun profiles(group: String) {
        val list = CustomKeyboards.sharedProfiles().filter { it.group == group }
        val linked = CustomKeyboards.get(root)?.setConfig?.profiles?.get(group)
        val options = mutableListOf("另存为共用方案", "转为本套独立设置")
        if(linked != null) options += "用本套设置更新原共用方案"
        options += list.map { "使用 · ${it.name}" }
        MaterialAlertDialogBuilder(this).setTitle(KeyboardSets.groups[group]).setItems(options.toTypedArray()) { _, i ->
            when {
                i == 0 -> KeyboardSetDialogs.name(this, "共用方案名称") { name -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.saveProfile(root, group, name) } }
                i == 1 -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.useProfile(root, group, null) }
                linked != null && i == 2 -> { val old = list.firstOrNull { it.id == linked } ?: return@setItems
                    MaterialAlertDialogBuilder(this).setTitle("更新共用方案 ${old.name}？")
                        .setMessage("将影响：${KeyboardSetOperations.profileUsers(linked).joinToString("、")}")
                        .setPositiveButton("保存共用方案") { _, _ -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.saveProfile(root, group, old.name, linked) } }
                        .setNegativeButton("取消", null).show()
                }
                else -> { val item = list[i - if(linked != null) 3 else 2]
                    KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.useProfile(root, group, item.id) }
                }
            }
        }.setNeutralButton("管理方案") { _, _ -> manageProfiles(group) }.setNegativeButton("取消", null).show()
    }
    private fun manageProfiles(group: String) {
        val list = CustomKeyboards.sharedProfiles().filter { it.group == group }
        MaterialAlertDialogBuilder(this).setTitle("管理共用方案").setItems(list.map { it.name }.toTypedArray()) { _, i ->
            val item = list[i]
            MaterialAlertDialogBuilder(this).setTitle(item.name).setMessage("使用中：${KeyboardSetOperations.profileUsers(item.id).joinToString("、").ifBlank { "无" }}")
                .setPositiveButton("用本套设置更新") { _, _ -> MaterialAlertDialogBuilder(this).setTitle("更新 ${item.name}？")
                    .setMessage("将影响：${KeyboardSetOperations.profileUsers(item.id).joinToString("、")}")
                    .setPositiveButton("保存共用方案") { _, _ -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.saveProfile(root, group, item.name, item.id) } }
                    .setNegativeButton("取消", null).show() }
                .setNegativeButton("重命名") { _, _ -> KeyboardSetDialogs.name(this, "方案名称", item.name) { name -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.renameProfile(item.id, name) } } }
                .setNeutralButton("删除并转为独立") { _, _ -> KeyboardSetDialogs.run(this, { render() }) { KeyboardSetOperations.deleteProfile(item.id) } }
                .show()
        }.show()
    }
    private fun more() {
        MaterialAlertDialogBuilder(this).setTitle("整套键盘").setItems((listOf("启用此整套", if(root in KeyboardLayoutCatalog.builtinIds(this)) "另存为自定义整套" else "复制整套", "重命名", "撤销上次整套操作") + if(root in KeyboardLayoutCatalog.builtinIds(this)) listOf("恢复本套内置默认") else emptyList()).toTypedArray()) { _, i -> when(i) {
            0 -> { CustomKeyboards.activeId = CustomKeyboards.get(root)?.setConfig?.defaultPage?.takeIf { CustomKeyboards.get(it) != null } ?: root
                com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh(); android.widget.Toast.makeText(this, "已启用", 0).show() }
            1 -> KeyboardSetDialogs.name(this, "副本名称", CustomKeyboards.get(root)!!.name + " 副本") { name -> var id = ""; KeyboardSetDialogs.run(this, { start(this, id) }) { id = KeyboardSetOperations.copySet(root, name) } }
            2 -> KeyboardSetDialogs.name(this, "整套名称", CustomKeyboards.get(root)!!.name) { CustomKeyboards.rename(root, it); render() }
            3 -> KeyboardSetDialogs.run(this, { render() }) { CustomKeyboards.undoSetOperation() }
            else -> MaterialAlertDialogBuilder(this).setTitle("恢复本套内置默认？").setMessage("本套内置布局与外观将恢复默认。可先另存为自定义整套保留修改。")
                .setPositiveButton("恢复") { _, _ -> KeyboardSetDialogs.run(this, { render() }) { CustomKeyboards.resetBuiltinSet(root) } }.setNegativeButton("取消", null).show()
        } }.show()
    }
    companion object { fun start(context: Context, root: String) { context.startActivity(Intent(context, KeyboardSetActivity::class.java).putExtra("root", root)) } }
}

object KeyboardSetPageSettings {
    fun show(activity: androidx.fragment.app.FragmentActivity, id: String, saved: () -> Unit = {}) {
        val root = runCatching { CustomKeyboards.setRoot(id) }.getOrNull() ?: return
        if(CustomKeyboards.get(root)?.setConfig == null) {
            KeyboardSetDialogs.run(activity, { show(activity, id, saved) }) { KeyboardSetOperations.ensure(root) }
            return
        }
        val groups = KeyboardSets.groups.entries.toList()
        MaterialAlertDialogBuilder(activity).setTitle("当前布局 · 跟随设置")
            .setItems(groups.map { it.value + if(KeyboardSetOperations.follows(id, it.key)) " · 跟随整套" else " · 含独立设置" }.toTypedArray()) { _, i ->
                val group = groups[i].key
                MaterialAlertDialogBuilder(activity).setTitle(groups[i].value)
                    .setPositiveButton("恢复跟随整套") { _, _ -> KeyboardSetDialogs.run(activity, saved) { KeyboardSetOperations.follow(id, group) } }
                    .setNeutralButton("保存为本页独立") { _, _ -> KeyboardSetDialogs.run(activity, saved) { KeyboardSetOperations.detach(id, group) } }
                    .setNegativeButton("取消", null).show()
            }.setNeutralButton("整套设置") { _, _ -> KeyboardSetActivity.start(activity, root) }.setNegativeButton("关闭", null).show()
    }
}
