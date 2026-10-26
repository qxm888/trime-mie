/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

/**
 * 让输入法自身的按键输入进入剪贴板/收藏夹的搜索框（dahiPLUS 移植）。
 *
 * 输入法窗口内的 EditText 无法直接接收同文自身键盘提交的文本（提交会送往宿主应用），
 * 因此在搜索框激活时，通过本桥把输入法（Rime）提交的文本重定向到搜索框。
 * 非激活状态下 [acceptText]/[acceptBackspace] 恒返回 false，不拦截任何输入，
 * 对原版行为零影响。
 */
object ClipboardSearchBridge {
    @Volatile
    var active: Boolean = false
        private set

    private var onText: ((String) -> Unit)? = null
    private var onBackspace: (() -> Unit)? = null
    private var onExit: (() -> Unit)? = null

    fun activate(
        onText: (String) -> Unit,
        onBackspace: () -> Unit,
        onExit: (() -> Unit)? = null,
    ) {
        this.onExit = onExit
        this.onText = onText
        this.onBackspace = onBackspace
        active = true
    }

    fun exit(): Boolean {
        if (!active) return false
        val callback = onExit ?: return false
        callback()
        return true
    }

    fun reset() {
        active = false
        onText = null
        onBackspace = null
        onExit = null
    }

    /** 若搜索框激活，则把提交的文本写入搜索框并返回 true（调用方不应再提交给宿主）。 */
    fun acceptText(text: String): Boolean {
        if (!active) return false
        val handler = onText ?: return false
        handler(text)
        return true
    }

    /** 若搜索框激活，则删除搜索框末位字符并返回 true。 */
    fun acceptBackspace(): Boolean {
        if (!active) return false
        val handler = onBackspace ?: return false
        handler()
        return true
    }
}
