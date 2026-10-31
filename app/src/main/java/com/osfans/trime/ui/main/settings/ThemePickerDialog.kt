// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ThemePickerDialog {
    suspend fun build(
        scope: LifecycleCoroutineScope,
        context: Context,
        afterConfirm: (suspend () -> Unit)? = null,
    ): AlertDialog {
        val allThemes =
            withContext(Dispatchers.IO) {
                ThemeManager.getAllThemes()
            }
        val selectedTheme by ThemeManager.prefs.selectedTheme
        val selectedIndex = allThemes.indexOfFirst { it.configId == selectedTheme }
        return AlertDialog
            .Builder(context)
            .apply {
                setTitle(R.string.selected_theme)
                if (allThemes.isEmpty()) {
                    setMessage(R.string.no_theme_to_select)
                } else {
                    setSingleChoiceItems(
                        allThemes.map { it.name ?: it.configId }.toTypedArray(),
                        selectedIndex,
                    ) { dialog, which ->
                        scope.launch {
                            afterConfirm?.invoke()
                            val newItem = allThemes[which]
                            UserDataManager.sync()
                            // 用户手动切换主题时重编译一次，让主题源文件的改动立即生效。
                            val resolvedThemeId = ThemeManager.selectTheme(newItem.configId, recompileTheme = true)
                            dialog.dismiss()
                            if (resolvedThemeId != newItem.configId) {
                                // Chosen theme unavailable: report the one actually in effect.
                                val fallbackName =
                                    allThemes.firstOrNull { it.configId == resolvedThemeId }?.name
                                        ?: resolvedThemeId
                                context.toast(
                                    context.getString(
                                        R.string.theme_unavailable_fallback,
                                        newItem.name,
                                        fallbackName,
                                    ),
                                    Toast.LENGTH_LONG,
                                )
                            }
                        }
                    }
                }
                setNegativeButton(android.R.string.cancel, null)
            }.create()
    }
}
