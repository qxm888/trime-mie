/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.os.Build
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.startActivity
import kotlinx.coroutines.launch

class ThemeSettingsFragment : PreferenceDelegateFragment(ThemeManager.prefs) {
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        findPreference<Preference>("selected_theme")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ThemePickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
        findPreference<Preference>("normal_mode_color")?.setOnPreferenceClickListener {
            lifecycleScope.launch { ColorPickerDialog.build(lifecycleScope, requireContext()).show() }
            true
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // 样式选择支持所有布局来源；传统主题内配色仅在主题模式展示。
        val standalone = CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM
        screen.addPreference(
            R.string.theme_diagnostics,
            R.string.theme_diagnostics_summary,
        ) {
            startActivity<ThemeDiagnosticsActivity>()
        }
        screen.addPreference("配色组", "新建日间 / 夜间主题组合，跟随系统切换") {
            startActivity<com.osfans.trime.ui.main.KeyboardColorGroupsActivity>()
        }
        screen.findPreference<Preference>("selected_theme")?.title = "键盘样式"
        if (standalone) {
            screen.findPreference<Preference>("normal_mode_color")?.isVisible = false
        }
        // 夜间配色的选择通过“配色”在夜间完成（写入夜间槽位），不单独暴露原始偏好行。
        screen.findPreference<Preference>("night_mode_color")?.isVisible = false
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
