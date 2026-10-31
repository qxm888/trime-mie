/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode

/**
 * 状态栏（工具栏）显隐的硬性规则：
 * - 候选栏模式（悬浮候选窗关闭）：不能隐藏状态栏；
 * - 悬浮窗模式（悬浮候选窗开启）：可以隐藏，隐藏时自动打开悬浮球。
 * 与“悬浮键盘”无关：悬浮键盘默认同样显示状态栏。
 */
object InputBarVisibility {
    fun candidatesFloating(): Boolean =
        AppPrefs.defaultInstance().candidates.mode.getValue() != PopupCandidatesMode.DISABLED

    fun hidden(preferHidden: Boolean) = preferHidden && candidatesFloating()
}
