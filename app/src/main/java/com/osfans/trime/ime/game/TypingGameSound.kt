/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import com.osfans.trime.R
import java.util.concurrent.ConcurrentHashMap

/** Short, preloaded effects; bounded voices and a separate persistent game mute. */
internal class TypingGameSound(private val context: Context, enabled: Boolean) {
    @Volatile private var pool: SoundPool? = null
    private val ready = java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val streams = ArrayDeque<Int>()
    private var orb = 0; private var heart = 0; private var hit = 0
    var played = 0; private set
    private var lastLaunch = 0L; private var lastHit = 0L
    var enabled = enabled
        set(value) {
            field = value
            if (value) prepare() else { streams.forEach { pool?.stop(it) }; streams.clear() }
        }
    init { if (enabled) prepare() }
    private fun prepare() {
        if (pool != null) return
        val sound = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        pool = sound
        sound.setOnLoadCompleteListener { source, id, status -> if (source === pool && status == 0) ready.add(id) }
        orb = sound.load(context, R.raw.typing_orb, 1)
        heart = sound.load(context, R.raw.typing_heart, 1)
        hit = sound.load(context, R.raw.typing_hit, 1)
    }
    fun launch(love: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLaunch < 65) return
        lastLaunch = now; play(if (love) heart else orb, .22f)
    }
    fun impact() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHit < 65) return
        lastHit = now; play(hit, .24f)
    }
    private fun play(id: Int, volume: Float) {
        if (!enabled || id !in ready) return
        val stream = pool?.play(id, volume, volume, 1, 0, 1f) ?: return
        if (stream != 0) { played++; if (streams.size == 4) streams.removeFirst(); streams.addLast(stream) }
    }
    fun release() { pool?.release(); pool = null; ready.clear(); streams.clear() }
}
