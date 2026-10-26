/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full report in ONE long image. Only a small rendering tile is held in memory. */
object StatsImageShare {
    suspend fun render(activity: FragmentActivity, content: View): File {
        check(content.width > 0 && content.height > 0) { "统计页面尚未显示，请稍后重试" }
        val folder = File(activity.cacheDir, "stats-share").apply { check(mkdirs() || isDirectory) }
        withContext(Dispatchers.IO) {
            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        }
        val scale = minOf(1f, 1080f / content.width)
        val width = (content.width * scale).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil(content.height * scale.toDouble()).toInt().coerceAtLeast(1)
        val file = File(folder, "${java.util.UUID.randomUUID()}.png")
        try {
            val writer = withContext(Dispatchers.IO) { LongPngWriter(file, width, height) }
            try {
                var offset = 0
                val pixels = IntArray(width)
                while (offset < height) {
                    val tileHeight = minOf(1024, height - offset)
                    val bitmap = Bitmap.createBitmap(width, tileHeight, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        canvas.translate(0f, -offset.toFloat())
                        canvas.scale(scale, scale)
                        content.draw(canvas)
                        withContext(Dispatchers.IO) {
                            for (y in 0 until tileHeight) {
                                bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                                writer.writeRow(pixels)
                            }
                        }
                    } finally { bitmap.recycle() }
                    offset += tileHeight
                }
            } finally { withContext(Dispatchers.IO) { writer.close() } }
            return file
        } catch (failure: Throwable) { withContext(Dispatchers.IO) { file.delete() }; throw failure }
    }
    fun share(activity: FragmentActivity, content: View) {
        activity.lifecycleScope.launch {
            runCatching {
                val file = render(activity, content)
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(activity.contentResolver, "输入统计", uri)
                }
                activity.startActivity(Intent.createChooser(intent, "分享输入统计长图"))
            }.exceptionOrNull()?.let { Toast.makeText(activity, it.message ?: "图片分享失败", Toast.LENGTH_LONG).show() }
        }
    }
}
