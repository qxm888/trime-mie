/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }

    private fun PreferenceGroup.addDestinationPreference(
        @StringRes title: Int,
        @DrawableRes icon: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, icon = icon) {
            findNavController().navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory(getString(R.string.settings_input_section)) {
                addDestinationPreference(
                    R.string.schemata,
                    R.drawable.ic_round_view_list_24,
                    NavigationRoute.SchemaList,
                )
                addDestinationPreference(
                    R.string.user_dictionary,
                    R.drawable.ic_baseline_book_24,
                    NavigationRoute.UserDict,
                )
                addDestinationPreference(
                    R.string.profile,
                    R.drawable.ic_baseline_snippet_folder_24,
                    NavigationRoute.Profile,
                )
                addPreference(
                    R.string.input_stats,
                    R.string.input_stats_summary,
                    R.drawable.ic_baseline_bar_chart_24,
                ) {
                    InputStatsActivity.start(requireContext())
                }
            }
            addCategory(getString(R.string.settings_customize_section)) {
                addPreference(
                    R.string.custom_kb_title,
                    R.string.key_editor_summary,
                    R.drawable.ic_baseline_tune_24,
                ) {
                    CustomKeyboardActivity.start(requireContext())
                }
            }
            addCategory(getString(R.string.settings_preferences_section)) {
                isIconSpaceReserved = false
                addPreference("字体设置", "分别设置按键、候选栏、工具栏等区域字体", R.drawable.ic_baseline_tune_24) {
                    startActivity(android.content.Intent(requireContext(), FontSettingsActivity::class.java))
                }
                addPreference("语音输入", "内置在线 / 离线识别 · 按住说话，松开上屏", R.drawable.ic_baseline_tune_24) {
                    startActivity(android.content.Intent(requireContext(), VoiceSettingsActivity::class.java))
                }
                addDestinationPreference(
                    R.string.general,
                    R.drawable.ic_baseline_tune_24,
                    NavigationRoute.General,
                )
                addDestinationPreference(
                    R.string.virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24,
                    NavigationRoute.VirtualKeyboard,
                )
                addDestinationPreference(
                    R.string.candidates_window,
                    R.drawable.ic_baseline_list_alt_24,
                    NavigationRoute.CandidatesWindow,
                )
                addDestinationPreference(
                    R.string.theme,
                    R.drawable.ic_baseline_color_lens_24,
                    NavigationRoute.Theme,
                )
                addDestinationPreference(
                    R.string.clipboard,
                    R.drawable.ic_clipboard_24,
                    NavigationRoute.Clipboard,
                )
                addDestinationPreference(
                    R.string.advanced,
                    R.drawable.ic_baseline_more_horiz_24,
                    NavigationRoute.Advanced,
                )
            }
        }
    }
}
