/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

/** Decode outside the UI thread. Drawables retain their current working set across LRU eviction. */
object BitmapAssets {
    data class Image(val bitmap: Bitmap, val sourceWidth: Int, val sourceHeight: Int)
    fun interface Observer { fun onImageReady() }
    private data class Key(val path: String, val budget: Int)
    private val cache = object : LruCache<Key, Image>(32 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Image) = value.bitmap.allocationByteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<Key, MutableList<WeakReference<Observer>>>()
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<Key>())
    @Volatile var generation = 0L
        private set

    private fun budget(pixels: Int) = when {
        pixels <= 65536 -> 65536
        pixels <= 262144 -> 262144
        pixels <= 1048576 -> 1048576
        else -> 4194304
    }
    private fun decode(key: Key): Image? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(key.path, opts)
        val width = opts.outWidth; val height = opts.outHeight
        require(width > 0 && height > 0 && width.toLong() * height <= 24_000_000)
        var sample = 1
        while ((width / sample).toLong() * (height / sample) > key.budget) sample *= 2
        opts.inJustDecodeBounds = false; opts.inSampleSize = sample
        BitmapFactory.decodeFile(key.path, opts)?.let { Image(it, width, height) }
    }.getOrNull()

    /** Synchronous for import workers only. UI callers get a placeholder while a decode is queued. */
    fun load(path: String, pixels: Int = 4194304): Image? {
        val key = Key(path, budget(pixels))
        cache.get(key)?.let { return it }
        if (Looper.myLooper() == Looper.getMainLooper()) return request(path, null, pixels)
        val version = generation
        return decode(key)?.also { if (generation == version) cache.put(key, it) }
    }

    fun request(path: String, observer: Observer?, pixels: Int = 4194304): Image? {
        val key = Key(path, budget(pixels))
        cache.get(key)?.let { return it }
        if (key in failed) return null
        val version = generation
        val enqueue = synchronized(pending) {
            val list = pending[key]
            if (list != null) {
                if (observer != null && list.none { it.get() === observer }) list += WeakReference(observer)
                false
            } else {
                pending[key] = mutableListOf<WeakReference<Observer>>().apply { if (observer != null) add(WeakReference(observer)) }
                true
            }
        }
        if (enqueue) scope.launch {
            val image = decode(key)
            if (generation == version) {
                if (image != null) cache.put(key, image)
                else synchronized(failed) { if (failed.size > 256) failed.clear(); failed += key }
            }
            val observers = synchronized(pending) { pending.remove(key).orEmpty() }
            if (image != null || generation != version) main.post { observers.forEach { it.get()?.onImageReady() } }
        }
        return null
    }

    /** Explicit theme/deploy refresh picks up external files changed at the same path. */
    fun invalidate() { generation++; cache.evictAll(); failed.clear() }
    fun trim() { cache.evictAll() }
}

/** Each view owns its bounds and Paint; only immutable bitmap pixels are shared. */
class AssetBitmapDrawable(private val path: String) : Drawable(), BitmapAssets.Observer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var image: BitmapAssets.Image? = null
    private var generation = -1L
    override fun onImageReady() { invalidateSelf() }
    override fun draw(canvas: Canvas) {
        if (generation != BitmapAssets.generation) { image = null; generation = BitmapAssets.generation }
        val current = image ?: BitmapAssets.request(path, this,
            (bounds.width().toLong() * bounds.height()).coerceIn(4096L, 4194304L).toInt())?.also { image = it } ?: return
        canvas.drawBitmap(current.bitmap, null, bounds, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
