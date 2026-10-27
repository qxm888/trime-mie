/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.prefs.AppPrefs

/** Shared palette and image entry for defaults, individual keys and whole-keyboard overrides. */
object AppearancePicker {
    private val colors = listOf("#FFFDF9", "#FADBD8", "#EF9A9A", "#A74942", "#FFDFA3", "#DDE8CC", "#CBE6E9", "#C8D8F0", "#DAD0EC", "#FFFFFF", "#55505A", "#18151A")
    fun show(context: Context, title: String, value: String, image: Boolean, picked: (String) -> Unit) {
        fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(12)) }
        val preview = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "当前背景预览" }
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(72)))
        val input = TextInputEditText(context).apply { setSingleLine(true); setText(value) }
        body.addView(TextInputLayout(context).apply { hint = if (image) "颜色值或图片（留空继承）" else "颜色值（留空继承）"; addView(input) })
        fun update(text: String) {
            input.setText(text)
            preview.setImageDrawable(if (text.startsWith("/")) com.osfans.trime.data.theme.AssetBitmapDrawable(text)
                else GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(runCatching { Color.parseColor(text) }.getOrDefault(Color.TRANSPARENT)); setStroke(dp(1), Color.GRAY) })
        }
        update(value)
        val palette = GridLayout(context).apply { columnCount = 6 }
        colors.forEach { color ->
            palette.addView(TextView(context).apply {
                gravity = Gravity.CENTER; contentDescription = "选择颜色 $color"
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(color)); setStroke(dp(1), Color.parseColor("#808080")) }
                setOnClickListener { update(color) }
            }, GridLayout.LayoutParams().apply { width = 0; height = dp(48); columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        body.addView(palette)
        body.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "透明"; setOnClickListener { update("#00000000") } })
        val activity = context as? androidx.fragment.app.FragmentActivity
        if (image && activity != null) body.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "选择图片…"
            setOnClickListener {
                var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
                launcher = activity.activityResultRegistry.register("key-image-${java.util.UUID.randomUUID()}", ActivityResultContracts.GetContent()) { uri ->
                    launcher?.unregister()
                    SkinPicker.handleImageResult(activity, uri) { path -> if (path != null) update(path) }
                }
                launcher.launch("image/*")
            }
        })
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(ScrollView(context).apply { addView(body) })
            .setNegativeButton(android.R.string.cancel, null).setNeutralButton("恢复继承") { _, _ -> picked("") }
            .setPositiveButton("应用", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val raw = input.text.toString().trim()
            val valid = raw.isEmpty() || runCatching { Color.parseColor(raw); true }.getOrDefault(false) || (image && java.io.File(raw).isFile)
            if (valid) { picked(raw); dialog.dismiss() } else input.error = "请选择颜色或有效图片"
        } }
        dialog.show()
    }
    fun preview(raw: String): android.graphics.drawable.Drawable? = if (raw.isBlank()) null else if (raw.startsWith("/")) com.osfans.trime.data.theme.AssetBitmapDrawable(raw)
        else GradientDrawable().apply { cornerRadius = 16f; setColor(runCatching { Color.parseColor(raw) }.getOrDefault(Color.TRANSPARENT)) }
    fun showKeyboard(context: Context, changed: () -> Unit = {}) {
        SurfaceAppearanceEditor.show(context, changed)
    }
}
