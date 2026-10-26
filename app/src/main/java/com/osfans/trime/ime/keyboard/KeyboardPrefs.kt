/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.util.isLandscape
import splitties.dimensions.dp

object KeyboardPrefs {
    private val prefs = AppPrefs.defaultInstance()

    private const val WIDE_SCREEN_WIDTH_DP = 600

    fun Context.isLandscapeMode(): Boolean = (resources.configuration.isLandscape() && isWideScreenDevice()) || when (prefs.keyboard.landscapeMode.getValue()) {
        AppPrefs.Keyboard.LandscapeMode.WIDE -> resources.configuration.isLandscape() || isWideScreen()
        AppPrefs.Keyboard.LandscapeMode.LANDSCAPE -> resources.configuration.isLandscape()
        AppPrefs.Keyboard.LandscapeMode.ALWAYS -> true
        else -> false
    }

    /**
     * 设备最小宽度（dp，与方向无关）。普通直板机约 320–480；折叠屏内屏/平板 ≥600。
     * 折叠态的折叠屏与直板机一样按直板机处理。
     */
    fun Context.isWideScreenDevice(): Boolean = resources.configuration.smallestScreenWidthDp >= WIDE_SCREEN_WIDTH_DP ||
        (android.os.Build.VERSION.SDK_INT >= 30 && packageManager.hasSystemFeature("android.sensor.hinge_angle"))

    /** 单手模式是否实际生效：横屏一律回到普通停靠键盘（偏好值不动）。 */
    fun Context.oneHandActive(): Boolean = prefs.keyboard.oneHandMode.getValue() != 0 &&
        !resources.configuration.isLandscape()

    fun Context.presentationWidthPercent(): Int = if (oneHandActive())
        prefs.keyboard.oneHandWidth.getValue() else prefs.keyboard.floatingKeyboardWidth.getValue()

    /**
     * 「有效悬浮」：悬浮开关、生效中的单手模式，或横屏自动悬浮。
     * 横屏自动悬浮只对普通直板机生效——折叠屏展开后的宽屏横屏保持停靠横屏键盘
     * （smallestScreenWidthDp ≥ 600 视为折叠屏/平板，横屏不自动悬浮）。
     */
    fun Context.isFloatingEffective(): Boolean = prefs.keyboard.floatingKeyboard.getValue() ||
        oneHandActive() ||
        (resources.configuration.isLandscape() &&
            prefs.keyboard.floatingKeyboardAutoLandscape.getValue() &&
            !isWideScreenDevice())

    /**
     * 停靠键盘的左右留白。单手模式已改为悬浮沉底实现（不再通过留白缩窄停靠键盘），
     * 单手生效时走悬浮路径（isFloatingEffective 已返回 0 to 0），这里只剩普通留白。
     */
    fun Context.oneHandPadding(theme: Theme): Pair<Int, Int> {
        val base = dp(if (isLandscapeMode()) theme.style.keyboardPaddingLand else theme.style.keyboardPadding)
        return base to base
    }

    private fun Context.isWideScreen(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels / metrics.density > WIDE_SCREEN_WIDTH_DP
    }
}
