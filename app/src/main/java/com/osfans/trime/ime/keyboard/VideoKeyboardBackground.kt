/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View

/** One muted decoder per visible keyboard; no audio focus or background playback. */
class VideoKeyboardBackground(context: Context, private val path: String) : TextureView(context), TextureView.SurfaceTextureListener {
    companion object {
        private val positions = object : LinkedHashMap<String, Int>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 32
        }
    }
    private var blurFrame: android.graphics.Bitmap? = null
    private val blurPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val blurBounds = android.graphics.Rect()
    fun drawBlurFrame(canvas: android.graphics.Canvas) {
        if (!isAvailable || width <= 0 || height <= 0) return
        val w = (width / 8).coerceIn(1, 256)
        val h = (height.toFloat() * w / width).toInt().coerceIn(1, 256)
        val frame = blurFrame?.takeIf { it.width == w && it.height == h }
            ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { blurFrame = it }
        if (getBitmap(frame) != null) {
            blurBounds.set(0, 0, width, height)
            canvas.drawBitmap(frame, null, blurBounds, blurPaint)
        }
    }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var active = true

    init { isOpaque = true; surfaceTextureListener = this; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setActive(value: Boolean) { active = value; syncPlayback() }
    private fun syncPlayback() {
        val p = player ?: return
        if (!prepared) return
        runCatching { if (active && isShown && windowVisibility == View.VISIBLE) p.start() else if (p.isPlaying) p.pause() }
    }
    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        release()
        surface = Surface(texture)
        val p = MediaPlayer()
        player = p
        try {
            p.setVolume(0f, 0f)
            p.isLooping = true
            p.setSurface(surface)
            p.setOnPreparedListener { if (player === it) {
                prepared = true; fitVideo()
                val position = synchronized(positions) { positions[path] ?: 0 }
                if (position > 0 && position < it.duration) {
                    it.setOnSeekCompleteListener { ready -> if (player === ready) syncPlayback() }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        it.seekTo(position.toLong(), MediaPlayer.SEEK_CLOSEST)
                    } else it.seekTo(position)
                } else syncPlayback()
            } }
            p.setOnErrorListener { _, _, _ -> release(); true }
            require(java.io.File(path).isFile)
            p.setDataSource(path)
            p.prepareAsync()
        } catch (_: Exception) { release() }
    }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); syncPlayback() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); syncPlayback() }
    private fun fitVideo() {
        val p = player ?: return
        if (!prepared || width <= 0 || height <= 0 || p.videoWidth <= 0 || p.videoHeight <= 0) return
        val ratio = p.videoWidth.toFloat() / p.videoHeight
        val viewRatio = width.toFloat() / height
        val sx = if (ratio > viewRatio) ratio / viewRatio else 1f
        val sy = if (ratio < viewRatio) viewRatio / ratio else 1f
        setTransform(android.graphics.Matrix().apply { setScale(sx, sy, width / 2f, height / 2f) })
    }
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) { fitVideo() }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { release(); return true }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    fun release() {
        blurFrame = null
        if (prepared) player?.let { p -> runCatching { p.currentPosition }.getOrNull()?.let { position -> synchronized(positions) { positions[path] = position } } }
        prepared = false
        player?.let { p -> player = null; p.setOnPreparedListener(null); p.setOnErrorListener(null); runCatching { p.release() } }
        surface?.release(); surface = null
    }
}
