/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main.settings

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import androidx.preference.PreferenceScreen
import com.osfans.trime.util.addPreference
import com.osfans.trime.ui.main.SettingsPageFragment

class KeyboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.removeAll()
        screen.addPreference("键盘设置", "布局、工具栏、悬浮、反馈与手势") { SettingsPageFragment.launch(requireContext(), "keyboard") }
    }
}
