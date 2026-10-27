/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import kotlinx.coroutines.launch

class KeyboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().keyboard) {

    private val videoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        com.osfans.trime.ui.main.SkinPicker.handleVideoResult(requireActivity(), uri) { path ->
            if (path != null) {
                AppPrefs.defaultInstance().keyboard.videoBackground.setValue(path)
                com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
            }
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<Preference>("keyboard_video_background")?.apply {
            summary = "静音循环；键盘隐藏时暂停。自定义键盘请在各自背景中设置。"
            setOnPreferenceClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("主题键盘视频背景")
                    .setView(com.osfans.trime.ui.main.SkinPicker.unifiedBackgroundToggle(requireContext()))
                    .setItems(arrayOf("选择本地视频", "恢复主题原背景")) { _, which ->
                        if (which == 0) videoPicker.launch("video/*") else {
                            AppPrefs.defaultInstance().keyboard.videoBackground.setValue("")
                            com.osfans.trime.data.theme.ThemeManager.requestCustomizationRefresh()
                        }
                    }.setNegativeButton(android.R.string.cancel, null).show()
                true
            }
        }
        screen.addPreference(Preference(requireContext()).apply {
            title = "按键与状态栏外观"; summary = "背景、边框、圆角、统一样式与动态模糊"
            setOnPreferenceClickListener { com.osfans.trime.ui.main.AppearancePicker.showKeyboard(requireActivity()); true }
        })
        screen.findPreference<Preference>("custom_sound_effect_name")?.apply {
            setOnPreferenceClickListener {
                lifecycleScope.launch {
                    SoundEffectPickerDialog.build(lifecycleScope, requireContext())
                        .show()
                }
                true
            }
        }
    }
}
