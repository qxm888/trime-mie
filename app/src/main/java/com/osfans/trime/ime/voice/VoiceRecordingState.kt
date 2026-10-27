/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

/** Microphone state only; model loading and recognition are not recording. */
data class VoiceRecordingState(val recording: Boolean = false, val level: Float = 0f)
