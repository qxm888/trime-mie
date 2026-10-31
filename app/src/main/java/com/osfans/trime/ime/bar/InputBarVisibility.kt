/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.bar

/**
 * 状态栏显隐的硬性规则：只有悬浮窗模式（含单手、横屏自动悬浮）可以隐藏状态栏，
 * 停靠的候选栏模式一律显示。
 */
object InputBarVisibility {
    fun hidden(preferHidden: Boolean, floating: Boolean) = preferHidden && floating
}
