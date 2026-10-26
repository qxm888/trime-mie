/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Intent
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.ui.main.DataTransferActivity
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference

class ClipboardSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().clipboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        // A dependable entry to the backup screen: the share icon in the
        // clipboard bar depends on the active IME theme, this one always works.
        screen.addCategory(R.string.data_transfer) {
            isIconSpaceReserved = false
        }
        screen.addPreference(
            R.string.data_transfer,
            R.string.data_transfer_desc,
            R.drawable.ic_baseline_share_24,
        ) {
            startActivity(Intent(requireContext(), DataTransferActivity::class.java))
        }
    }
}
