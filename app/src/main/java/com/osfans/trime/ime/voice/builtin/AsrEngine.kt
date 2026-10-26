/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 内置 ASR 引擎统一接口（移植自说点啥/BiBi-Keyboard AsrEngine，Apache-2.0）。
 * PCM 约定：16kHz、单声道、16bit LE；引擎收到的字节流即该格式。
 */
package com.osfans.trime.ime.voice.builtin

/** 引擎回调：onFinal 一定到达一次（成功或空文本），onError 后会话作废。 */
interface AsrListener {
    fun onFinal(text: String)
    fun onError(message: String)
    fun onPartial(text: String) {}
}

interface AsrEngine {
    val isRunning: Boolean

    /** 开始会话（建立连接/预热）。 */
    fun start()

    /** 推送一段 PCM（16k/16bit/单声道）。 */
    fun feed(pcm: ByteArray)

    /** 结束录音：冲刷音频并等待最终结果，经 [AsrListener.onFinal] 回调。 */
    fun stop()

    /** 放弃本次会话（不再回调 onFinal）。 */
    fun cancel()
}
