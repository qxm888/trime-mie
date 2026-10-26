/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.baiduskin

import android.media.AudioAttributes
import android.media.SoundPool

/** Uses the existing sound-enabled and volume preferences, not an independent audio switch. */
object BdsSound {
    var currentPath = ""
    private var pool: SoundPool? = null
    private val ids = mutableMapOf<String, Int>()
    private val ready = mutableSetOf<Int>()
    private val pending = mutableMapOf<Int, Float>()
    @Synchronized fun play(path: String, volume: Float) {
        if (volume <= 0 || path.isEmpty()) return
        val player = pool ?: SoundPool.Builder().setMaxStreams(8).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build().also { created ->
                pool = created
                created.setOnLoadCompleteListener { p, id, status -> synchronized(this) {
                    if (status == 0) { ready += id; pending.remove(id)?.let { p.play(id, it, it, 1, 0, 1f) } }
                    else pending.remove(id)
                } }
            }
        val id = ids[path] ?: runCatching { player.load(path, 1) }.getOrDefault(0).also { if (it != 0) ids[path] = it }
        if (id == 0) return
        if (id in ready) player.play(id, volume, volume, 1, 0, 1f) else pending[id] = volume
    }
    @Synchronized fun release() { pool?.release(); pool = null; ids.clear(); ready.clear(); pending.clear(); currentPath = "" }
}
