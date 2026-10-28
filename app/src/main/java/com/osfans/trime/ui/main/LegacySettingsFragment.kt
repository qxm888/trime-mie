/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.navigation.fragment.findNavController
import androidx.navigation.NavDestination.Companion.hasRoute
import com.osfans.trime.ui.common.PaddingPreferenceFragment

/** Keep IME and external intents on their existing routes. */
class LegacySettingsFragment : PaddingPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
    }
    override fun onResume() {
        super.onResume()
        val nav = findNavController()
        val destination = nav.currentDestination ?: return
        val page = when {
            destination.hasRoute<NavigationRoute.General>() -> "behavior"
            destination.hasRoute<NavigationRoute.VirtualKeyboard>() -> "keyboard"
            destination.hasRoute<NavigationRoute.CandidatesWindow>() -> "candidates"
            destination.hasRoute<NavigationRoute.Clipboard>() -> "clipboard"
            else -> "advanced"
        }
        nav.navigate(NavigationRoute.SettingsPage(page)) {
            popUpTo(destination.id) { inclusive = true }
        }
    }
}
