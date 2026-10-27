/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.keymap.CustomBackground
import java.io.File
import java.util.UUID
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.osfans.trime.util.copyBoundedTo

/** Explicit, exclusive color/transparent/image selection for fully custom keyboards. */
object SkinPicker {
    fun unifiedBackgroundToggle(context: Context) = android.widget.CheckBox(context).apply {
        text = "状态栏与键盘共用一整块背景"
        isChecked = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.unifiedBackground.getValue()
        setPadding(24, 8, 24, 8)
        setOnCheckedChangeListener { _, checked -> com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.unifiedBackground.setValue(checked) }
    }
    private val palette = listOf(
        "#F44336", "#E91E63", "#9C27B0", "#673AB7", "#3F51B5",
        "#2196F3", "#00BCD4", "#4CAF50", "#8BC34A", "#FFEB3B",
        "#FF9800", "#FF5722", "#795548", "#9E9E9E", "#607D8B",
        "#263238", "#000000", "#FFFFFF", "#F8FAFC", "#E5E7EB",
    )

    fun show(
        activity: FragmentActivity,
        title: String,
        current: CustomBackground,
        onPicked: (CustomBackground) -> Unit,
        onPickImage: () -> Unit,
        onPickVideo: (() -> Unit)? = null,
    ) {
        val labels = mutableListOf(
            activity.getString(R.string.custom_bg_color),
            activity.getString(R.string.custom_bg_transparent),
            activity.getString(R.string.custom_bg_image),
        )
        if (onPickVideo != null) labels.add("视频（静音循环）")
        if (current.type.ordinal in labels.indices) labels[current.type.ordinal] += " ✓"
        MaterialAlertDialogBuilder(activity).apply { if (onPickVideo != null) setView(unifiedBackgroundToggle(activity)) }.setTitle(title).setItems(labels.toTypedArray()) { _, which ->
            when (which) {
                0 -> showColors(activity, title, current, onPicked)
                1 -> onPicked(CustomBackground(CustomBackground.Type.TRANSPARENT))
                2 -> onPickImage()
                3 -> onPickVideo?.invoke()
            }
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showColors(activity: FragmentActivity, title: String, current: CustomBackground, onPicked: (CustomBackground) -> Unit) {
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density + 0.5f).toInt()
        val grid = GridLayout(activity).apply { columnCount = 5; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(title)
            .setView(ScrollView(activity).apply { addView(grid) })
            .setPositiveButton(R.string.custom_kb_bg_more) { _, _ -> showCustomHex(activity, title, current, onPicked) }
            .setNegativeButton(R.string.cancel, null).create()
        palette.forEach { hex ->
            grid.addView(android.view.View(activity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(hex)); setStroke(dp(1), 0x55777777) }
                contentDescription = hex
                setOnClickListener { onPicked(CustomBackground(color = hex)); dialog.dismiss() }
            }, GridLayout.LayoutParams().apply { width = dp(40); height = dp(40); setMargins(dp(4), dp(6), dp(4), dp(6)) })
        }
        dialog.show()
    }

    private fun showCustomHex(activity: FragmentActivity, title: String, current: CustomBackground, onPicked: (CustomBackground) -> Unit) {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val layout = TextInputLayout(activity).apply { hint = activity.getString(R.string.custom_kb_bg_hex_hint); setPadding(padding, padding / 2, padding, 0) }
        val input = TextInputEditText(layout.context).apply { setSingleLine(true); setText(current.color.ifEmpty { "#3388FF" }) }
        layout.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(title).setView(layout)
            .setPositiveButton(R.string.ok, null).setNegativeButton(R.string.cancel, null).create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val hex = input.text?.toString()?.trim().orEmpty()
            if (!Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(hex)) {
                layout.error = activity.getString(R.string.custom_bg_invalid_color)
            } else {
                onPicked(CustomBackground(color = hex).normalized())
                dialog.dismiss()
            }
        }
    }

    /** Background import with bounded bytes and pixel count, followed by a main-thread result. */
    fun handleImageResult(activity: FragmentActivity, uri: Uri?, onResult: (String?) -> Unit) {
        if (uri == null) { onResult(null); return }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val folder = File(com.osfans.trime.core.data.DirectExternalStorage.resources(activity), "custom_kb_bg")
                check(folder.mkdirs() || folder.isDirectory)
                val file = File(folder, "bg_${UUID.randomUUID()}.png")
                try {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyBoundedTo(it, 32L * 1024 * 1024) }
                    } ?: error("无法读取图片")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 24_000_000) { "图片无效或超过2400万像素" }
                    file.absolutePath
                } catch (error: Throwable) { file.delete(); throw error }
            } }
            if (result.isFailure) Toast.makeText(activity, result.exceptionOrNull()?.message ?: "图片导入失败", Toast.LENGTH_LONG).show()
            onResult(result.getOrNull())
        }
    }

    fun handleVideoResult(activity: FragmentActivity, uri: Uri?, onResult: (String?) -> Unit) {
        if (uri == null) { onResult(null); return }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val folder = File(com.osfans.trime.core.data.DirectExternalStorage.resources(activity), "custom_kb_bg")
                check(folder.mkdirs() || folder.isDirectory)
                val file = File(folder, "video_${UUID.randomUUID()}.mp4")
                try {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyBoundedTo(it, 128L * 1024 * 1024) }
                    } ?: error("无法读取视频")
                    val metadata = android.media.MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(file.absolutePath)
                        val width = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val height = metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        require(width > 0 && height > 0 && width.toLong() * height <= 8_294_400) { "视频无效或超过4K，请使用1080p或更低分辨率" }
                    } finally { metadata.release() }
                    file.absolutePath
                } catch (error: Throwable) { file.delete(); throw error }
            } }
            if (result.isFailure) Toast.makeText(activity, result.exceptionOrNull()?.message ?: "视频导入失败", Toast.LENGTH_LONG).show()
            onResult(result.getOrNull())
        }
    }

}
