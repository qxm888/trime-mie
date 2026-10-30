/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.prefs.PreferenceDelegateUi
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.ui.main.settings.AdvancedSettingsFragment
import com.osfans.trime.ui.main.settings.SoundEffectPickerDialog
import com.osfans.trime.ui.main.settings.theme.ThemeDiagnosticsActivity
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim
import kotlinx.coroutines.launch

/** Selected original preference delegates, with original dependencies and storage. */
class SettingsPageFragment : PaddingPreferenceFragment() {
    private val prefs = AppPrefs.defaultInstance()
    private val owners by lazy { listOf(prefs.general, prefs.keyboard, prefs.candidates, prefs.clipboard, prefs.advanced) }
    private val allUi get() = owners.flatMap { it.preferenceDelegatesUi }
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var pageId: String
    private var focusKey = ""
    private val listener = PreferenceDelegateProvider.OnChangeListener { key ->
        lifecycleScope.launch { updateDependencies(); applySideEffect(key) }
    }
    private val videoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        SkinPicker.handleVideoResult(requireActivity(), uri) { path ->
            if (path != null) { prefs.keyboard.videoBackground.setValue(path); ThemeManager.requestCustomizationRefresh() }
        }
    }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Restored fragments are created before the Activity installs its
        // programmatic graph. Read this entry's arguments, not the top entry.
        pageId = requireNotNull(requireArguments().getString("id"))
        focusKey = requireArguments().getString("focusKey").orEmpty()
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
        owners.forEach { it.registerOnChangeListener(listener) }
        if (pageId == "search") {
            viewModel.setToolbarTitle("搜索")
            showSearch(savedInstanceState?.getString("query").orEmpty())
        } else buildPage()
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = super.onCreateView(inflater, container, savedInstanceState)!!
        if (pageId != "search") return content
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val input = TextInputEditText(requireContext()).apply {
            id = View.generateViewId()
            setSingleLine(); setText(query)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            hint = "搜索"
            minimumHeight = dp(56)
            background = null
            contentDescription = "搜索"
            doAfterTextChanged { showSearch(it?.toString().orEmpty()) }
        }
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, com.osfans.trime.R.color.framework_background))
            addView(com.google.android.material.card.MaterialCardView(context).apply {
                radius = dp(28).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(context, com.osfans.trime.R.color.settings_surface))
                addView(TextInputLayout(context).apply {
                    hint = "搜索"
                    startIconDrawable = androidx.core.content.ContextCompat.getDrawable(context, com.osfans.trime.R.drawable.ic_baseline_search_24)
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
                    setPadding(dp(4), 0, dp(4), 0)
                    endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
                    addView(input)
                    isHintEnabled = false
                }, ViewGroup.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(12), dp(16), dp(8)) })
            addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }
    private var query = ""
    private fun showSearch(value: String) {
        query = value
        preferenceScreen.removeAll()
        val term = value.trim()
        if (term.isEmpty()) return
        var count = 0
        SettingsCatalog.pages.forEach { page ->
            if ((page.title + page.summary + SettingsCatalog.path(page)).contains(term, true)) {
                preferenceScreen.addPreference(page.title, SettingsCatalog.path(page)) { open(page.id) }; count++
            }
            selectedUi(page.id).forEach { ui ->
                val item = ui.createUi(requireContext())
                if ((item.title.toString() + " " + (item.summary?.toString().orEmpty())).contains(term, true)) {
                    preferenceScreen.addPreference(item.title.toString(), SettingsCatalog.path(page)) { open(page.id, ui.key) }; count++
                }
            }
        }
        if (count == 0) preferenceScreen.addPreference("没有找到相关设置", "请尝试更短的关键词，如“候选”或“声音”。")
    }
    private fun selectedUi(id: String): List<PreferenceDelegateUi<*>> = when (id) {
        "candidates" -> prefs.candidates.preferenceDelegatesUi
        "clipboard" -> prefs.clipboard.preferenceDelegatesUi
        "theme" -> ThemeManager.prefs.preferenceDelegatesUi
        "hardware" -> prefs.keyboard.preferenceDelegatesUi.filter { it.key.startsWith("hook_") }
        else -> SettingsCatalog.pages.firstOrNull { it.id == id }?.keys.orEmpty().mapNotNull { key -> allUi.firstOrNull { it.key == key } }
    }
    private fun buildPage() {
        val page = SettingsCatalog.page(pageId)
        viewModel.setToolbarTitle(page.title)
        if (pageId == "diagnostics") {
            preferenceScreen.addPreference("主题诊断", "检查当前主题与配置") { startActivity(Intent(requireContext(), ThemeDiagnosticsActivity::class.java)) }
            preferenceScreen.addPreference("应用日志", "查看运行日志与排查问题") { com.osfans.trime.util.AppUtils.launchLogActivity(requireContext()) }
        }
        if (pageId == "surface") {
            preferenceScreen.addPreference("主题键盘全局外观", "主题模式的按键与状态栏样式") { SurfaceAppearanceEditor.show(requireActivity(), false) }
            preferenceScreen.addPreference("自定义键盘外观", "选择布局，分别保存外观与字号") {
                val layouts=com.osfans.trime.data.keymap.CustomKeyboards.list()
                MaterialAlertDialogBuilder(requireContext()).setTitle("选择自定义键盘").setItems(layouts.map { it.name }.toTypedArray()) { _,index -> SurfaceAppearanceEditor.show(requireActivity(),true,layoutId=layouts[index].id) }.show()
            }
        }
        if (pageId == "theme") preferenceScreen.addPreference("主题键盘字体与字号", "按键、符号、注解、候选和工具栏分别设置") { startActivity(Intent(requireContext(), FontSettingsActivity::class.java)) }
        if (pageId == "layout") preferenceScreen.addPreference("自定义功能", "命名并组合多个动作，供按键和工具栏绑定") { NamedFunctionsEditor.show(requireActivity()) }
        if (pageId == "transfer") preferenceScreen.addPreference("整包备份与恢复", "打包全部设置与键盘资源到 rime/mie，支持一键导入") { startActivity(Intent(requireContext(), FullBackupActivity::class.java)) }
        if (pageId == "voice") preferenceScreen.addPreference("识别服务与模型", "在线服务、离线模型、下载与导入") { startActivity(Intent(requireContext(), VoiceSettingsActivity::class.java)) }
        if (pageId == "clipboard") preferenceScreen.addPreference("剪贴板同步", "SyncClipboard / WebDAV · 文本、图片和文件，默认关闭") { startActivity(Intent(requireContext(), ClipboardSyncActivity::class.java)) }
        if (pageId == "toolbar") {
            preferenceScreen.addPreference("主题键盘状态栏", "主题模式的按钮、功能绑定与独立外观") { ToolbarEditActivity.start(requireContext(), themeMode = true) }
            preferenceScreen.addPreference("自定义键盘状态栏", "选择布局，编辑它自己的状态栏按钮") {
                val layouts = com.osfans.trime.data.keymap.CustomKeyboards.list()
                if (layouts.isEmpty()) CustomKeyboardActivity.start(requireContext()) else
                    MaterialAlertDialogBuilder(requireContext()).setTitle("选择自定义键盘").setItems(layouts.map { it.name }.toTypedArray()) { _, index ->
                        ToolbarEditActivity.start(requireContext(), layouts[index].id)
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        fun group(title: String, delegates: List<PreferenceDelegateUi<*>>, collapsed: Boolean = false) {
            if (delegates.isEmpty()) return
            preferenceScreen.addCategory(title) {
                this.key = "group_${pageId}_$title"
                if (collapsed && delegates.none { it.key == focusKey }) initialExpandedChildrenCount = 0
                delegates.forEach { delegate -> addPreference(delegate.createUi(requireContext()).apply { isEnabled = delegate.isEnabled() }) }
            }
        }
        val ui = selectedUi(pageId)
        when (pageId) {
            "feedback" -> {
                group("声音", ui.filter { it.key in listOf("sound_on_keypress", "sound_volume", "custom_sound_effect_enabled", "custom_sound_effect_name") })
                group("触感", ui.filter { it.key.startsWith("vibrat") })
                group("播报与气泡", ui.filter { it.key.startsWith("speak_") || it.key == "show_key_popup" })
            }
            "candidates" -> {
                preferenceScreen.addPreference(
                    com.osfans.trime.R.string.floating_candidates_editor,
                    com.osfans.trime.R.string.floating_candidates_editor_summary,
                ) {
                    val activeId = com.osfans.trime.data.keymap.CustomKeyboards.resolveId(
                        com.osfans.trime.ime.keyboard.KeyboardWindow.lastAttachedId,
                    )
                    if (com.osfans.trime.data.keymap.CustomKeyboards.mode == com.osfans.trime.data.keymap.CustomKeyboards.MODE_CUSTOM)
                        com.osfans.trime.ui.main.FloatingCandidatesEditActivity.start(requireContext(), activeId)
                    else
                        com.osfans.trime.ui.main.FloatingCandidatesEditActivity.start(requireContext(), "")
                }
                group("窗口显示", ui.filter { it.key in listOf("show_candidates_window", "candidates_layout", "candidates_window_position", "candidate_bottom_pagination") })
                group("候选内容与行数", ui.filter { it.key in listOf("candidate_limit_lines", "candidate_auto_shrink", "candidate_full_display", "candidate_item_max_lines") })
                group("悬浮球", ui.filter { it.key in listOf("candidate_floating_ball", "candidate_ball_opacity") })
            }
            else -> group(page.title, ui, page.collapsed)
        }
        SettingsCatalog.pages.filter { it.parent == pageId }.forEach { child ->
            preferenceScreen.addPreference(child.title, child.summary) { open(child.id) }
        }
        findPreference<Preference>("keyboard_unified_background")?.title = "主题键盘：状态栏与键盘共用背景"
        findPreference<Preference>("custom_keyboard_unified_background")?.title = "自定义键盘：状态栏与键盘共用背景"
        findPreference<Preference>("keyboard_video_background")?.apply {
            summary = "全局主题背景 · 静音循环，键盘隐藏时暂停；自定义布局背景在布局编辑器中设置"
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext()).setTitle("主题键盘视频背景")
                    .setItems(arrayOf("选择本地视频", "恢复主题原背景")) { _, which ->
                        if (which == 0) videoPicker.launch("video/*") else {
                            prefs.keyboard.videoBackground.setValue(""); ThemeManager.requestCustomizationRefresh()
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show(); true
            }
        }
        findPreference<Preference>("custom_sound_effect_name")?.setOnPreferenceClickListener {
            lifecycleScope.launch { SoundEffectPickerDialog.build(lifecycleScope, requireContext()).show() }; true
        }
        findPreference<Preference>("hide_input_bar")?.summary = "悬浮键盘临时显示，回到普通键盘恢复此设置；不修改开关值。"
        findPreference<Preference>("candidate_floating_ball")?.summary = "独立开关，不受状态栏显示影响；点击悬浮球显示工具栏，长按展开键盘菜单。"
    }
    private fun open(id: String, key: String = "") {
        view?.let { current ->
            current.clearFocus()
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(current.windowToken, 0)
        }
        val route = when (id) {
            "schema" -> NavigationRoute.SchemaList
            "dictionary" -> NavigationRoute.UserDict
            "profile" -> NavigationRoute.Profile
            "theme" -> NavigationRoute.Theme
            "developer" -> NavigationRoute.Developer
            "about" -> NavigationRoute.About
            else -> null
        }
        if (route != null) { findNavController().navigateWithAnim(route); return }
        val activity = when (id) {
            "created_words" -> CreatedWordsActivity::class.java
            "voice" -> VoiceSettingsActivity::class.java
            "layout" -> CustomKeyboardActivity::class.java
            "fonts" -> FontSettingsActivity::class.java
            "stats" -> InputStatsActivity::class.java
            "transfer" -> DataTransferActivity::class.java
            "personal_data" -> PersonalDataActivity::class.java
            else -> null
        }
        if (activity != null) startActivity(Intent(requireContext(), activity)) else findNavController().navigateWithAnim(NavigationRoute.SettingsPage(id, key))
    }
    private fun updateDependencies() {
        allUi.forEach { findPreference<Preference>(it.key)?.isEnabled = it.isEnabled() }
        findPreference<Preference>("candidate_floating_ball")?.isEnabled = true
        findPreference<Preference>("candidate_ball_opacity")?.isEnabled = prefs.candidates.floatingBall.getValue()
    }
    private fun applySideEffect(key: String) {
        when (key) {
            "ui_mode" -> AppCompatDelegate.setDefaultNightMode(when (prefs.advanced.uiMode.getValue()) {
                AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            })
            "show_app_icon" -> AdvancedSettingsFragment.showAppIcon(requireContext(), prefs.advanced.showAppIcon.getValue())
        }
    }
    override fun onResume() {
        super.onResume()
        if (pageId == "voice") {
            val context = requireContext()
            findNavController().popBackStack()
            context.startActivity(Intent(context,VoiceSettingsActivity::class.java))
            return
        }
        updateDependencies()
        viewModel.setToolbarTitle(if (pageId == "search") "搜索" else SettingsCatalog.page(pageId).title)
        if (focusKey.isNotEmpty()) {
            val target = focusKey
            listView.post {
                if (view != null) {
                    scrollToPreference(target)
                    focusKey = ""
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("query", query); super.onSaveInstanceState(outState) }
    override fun onStop() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) prefs.syncToDeviceEncryptedStorage(); super.onStop() }
    override fun onDestroy() { owners.forEach { it.unregisterOnChangeListener(listener) }; super.onDestroy() }
    companion object {
        fun launch(context: android.content.Context, id: String) {
            if (id == "voice") { context.startActivity(Intent(context, VoiceSettingsActivity::class.java)); return }
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_RUN
                putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.SettingsPage(id))
            })
        }
    }
}
