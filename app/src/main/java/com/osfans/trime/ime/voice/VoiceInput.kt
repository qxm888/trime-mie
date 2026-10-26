/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

import kotlinx.coroutines.Job

/**
 * 内置按住说话控制器接口，KeyView 与
 * 输入生命周期钩子只面向此接口。
 */
interface VoiceInput {
    fun enabled(): Boolean

    fun start(mock: Boolean = false)

    /** 松手：停止录音，等待/提交最终结果。 */
    fun release()

    /** 放弃本次会话（切换输入框、滑动取消等）。 */
    fun cancel(): Job?

    fun dispose()
}
