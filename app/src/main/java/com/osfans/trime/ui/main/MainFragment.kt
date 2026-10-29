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
            addCategory("设置") {
                SettingsCatalog.pages.filter { it.parent.isEmpty() }.forEach { page ->
                    addPreference(page.title, page.summary, page.icon) {
                        findNavController().navigateWithAnim(NavigationRoute.SettingsPage(page.id))
                    }
                }
            }
        }
    }
    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, state: Bundle?): android.view.View {
        val content = super.onCreateView(inflater, container, state)!!
        val ctx = requireContext()
        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
        val search = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = dp(28).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(androidx.core.content.ContextCompat.getColor(ctx, com.osfans.trime.R.color.settings_surface))
            isClickable = true; isFocusable = true; contentDescription = "搜索"
            addView(android.widget.TextView(ctx).apply {
                text = "搜索"; textSize = 16f; gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, com.osfans.trime.R.color.framework_text_secondary))
                setPadding(dp(20), 0, dp(20), 0); compoundDrawablePadding = dp(12)
                val icon=androidx.core.content.ContextCompat.getDrawable(ctx,com.osfans.trime.R.drawable.ic_baseline_search_24)?.mutate()
                icon?.setTint(currentTextColor); setCompoundDrawablesRelativeWithIntrinsicBounds(icon,null,null,null)
            }, android.widget.FrameLayout.LayoutParams(-1,-1))
            setOnClickListener { findNavController().navigateWithAnim(NavigationRoute.SettingsPage("search")) }
        }
        return android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(search,android.widget.LinearLayout.LayoutParams(-1,dp(56)).apply { setMargins(dp(16),dp(12),dp(16),dp(4)) })
            addView(content,android.widget.LinearLayout.LayoutParams(-1,0,1f))
        }
    }

}
