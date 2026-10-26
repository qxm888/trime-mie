/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

import android.content.Context
import com.osfans.trime.data.theme.ThemeManager

object VoiceSettings {
    private var context: Context? = null
    private val prefs get() = context!!.getSharedPreferences("voice_settings", Context.MODE_PRIVATE)
    fun init(ctx: Context) { context = ctx.applicationContext }
    val enabled: Boolean get() = context?.let { prefs.getBoolean("enabled", true) } ?: true
    val version: Long get() = context?.let { prefs.getLong("version", 0) } ?: 0
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("enabled", enabled).putLong("version", version + 1).commit()
        ThemeManager.requestCustomizationRefresh()
    }
}
