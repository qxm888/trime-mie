/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

/** UI phases come from the recorder/engine lifecycle; level is actual PCM RMS. */
data class VoiceRecordingState(
    val recording: Boolean = false,
    val level: Float = 0f,
    val phase: Phase = if (recording) Phase.RECORDING else Phase.IDLE,
) {
    enum class Phase { IDLE, CONNECTING, RECORDING, PROCESSING, LOADING_MODEL }
    val active get() = phase != Phase.IDLE
}
