/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()
    override fun onStart() { super.onStart(); viewModel.enableTopOptionsMenu() }
    override fun onStop() { viewModel.disableTopOptionsMenu(); super.onStop() }
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference("搜索设置", "搜索语音、视频、模糊、单手、候选、同步…") {
                findNavController().navigateWithAnim(NavigationRoute.SettingsPage("search"))
            }
            addCategory("设置") {
                SettingsCatalog.pages.filter { it.parent.isEmpty() }.forEach { page ->
                    addPreference(page.title, page.summary, page.icon) {
                        findNavController().navigateWithAnim(NavigationRoute.SettingsPage(page.id))
                    }
                }
            }
        }
    }
}
