/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.ToolbarOverrides
import com.osfans.trime.data.keymap.KeyAppearance
import java.util.UUID

/** Draft editor shared by theme overlays and a custom layout's toolbar. */
object ToolbarButtonEditor {
    fun show(activity: FragmentActivity, entry: ToolbarOverrides.Entry, standalone: Boolean, layoutId: String? = null, save: (ToolbarOverrides.Entry) -> Unit) {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        var draft = entry
        val bindings = entry.bindings.toMutableMap().apply { put("click", entry.action) }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(16)) }
        val label = TextInputEditText(activity).apply { setText(entry.icon); setSingleLine() }
        body.addView(TextInputLayout(activity).apply { hint = "显示文字、表情或图片路径（空为默认）"; addView(label) })
        val iconSize = TextInputEditText(activity).apply { setText(entry.iconSize.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        body.addView(TextInputLayout(activity).apply { hint = "图片图标大小 dp（0 自动填充，8–96，上限为按钮大小）"; addView(iconSize) })
        val width = TextInputEditText(activity).apply { setText(entry.width.toString()); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val extras = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
        extras.addView(TextInputLayout(activity).apply { hint = "按钮宽度 dp（0 跟随默认，24–160）"; addView(width) })
        fun button(text: String, parent: LinearLayout = body, action: () -> Unit) = MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text; isAllCaps = false; setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        var imageResult: ((String) -> Unit)? = null
        val imagePicker = activity.activityResultRegistry.register("toolbar-image-${UUID.randomUUID()}", ActivityResultContracts.GetContent()) { uri ->
            SkinPicker.handleImageResult(activity, uri) { path -> if (path != null) imageResult?.invoke(path) }
        }
        button("选择按钮图标图片…") { imageResult = { label.setText(it) }; imagePicker.launch("image/*") }
        val behaviorNames = linkedMapOf("click" to "点击", "longClick" to "长按", "doubleClick" to "双击", "swipeUp" to "上滑", "swipeDown" to "下滑", "swipeLeft" to "左滑", "swipeRight" to "右滑")
        behaviorNames.forEach { (key, name) ->
            lateinit var view: MaterialButton
            fun caption(): String {
                val token = bindings[key].orEmpty()
                val label = com.osfans.trime.data.keymap.BindingActions.label(token)
                return "$name · ${label.ifBlank { token.ifBlank { "未绑定" } }}"
            }
            view = button(caption(), if (key == "click") body else extras) {
                KeyTokenPicker.show(activity, "$name 绑定功能", !bindings[key].isNullOrEmpty(), standalone) { token ->
                    if (token == null) bindings.remove(key) else bindings[key] = token
                    view.text = caption()
                }
            }
        }
        val fields = setOf("keyTextSize", "roundCorner", "keyBorder", "keyTextColor", "keyBackColor", "keyBorderColor", "hilitedKeyTextColor", "hilitedKeyBackColor", "hilitedKeyBorderColor")
        button("按钮外观 · 字号、背景、边框与按下颜色") {
            KeyAppearanceEditor.show(activity, "状态栏按钮外观", draft.appearance, fields) { draft = draft.copy(appearance = it) }
        }
        button("按钮特效 · 阴影、玻璃、渐变与按压") {
            SurfaceAppearanceEditor.show(activity,standalone,initialEffects=draft.effects ?: com.osfans.trime.ime.appearance.SurfaceAppearance.current(standalone,layoutId).effectiveToolbarEffects,
                onEffects={ draft=draft.copy(effects=it) })
        }
        button("特效恢复跟随工具栏") { draft=draft.copy(effects=null) }
        body.addView(android.widget.TextView(activity).apply {
            text = "▸ 更多手势与尺寸"; textSize = 16f; minHeight = dp(48); setPadding(dp(8), dp(14), dp(8), dp(14)); isFocusable = true
            setOnClickListener {
                val expanded = extras.visibility != android.view.View.VISIBLE
                extras.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
                text = if (expanded) "▾ 更多手势与尺寸" else "▸ 更多手势与尺寸"
                contentDescription = if (expanded) "更多手势与尺寸，已展开" else "更多手势与尺寸，已折叠"
            }
        })
        body.addView(extras)
        val dialog = MaterialAlertDialogBuilder(activity).setTitle(if (entry.id.isEmpty()) "添加状态栏按钮" else "编辑状态栏按钮")
            .setView(ScrollView(activity).apply { addView(body) }).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("保存", null).create()
        dialog.setOnDismissListener { imageResult = null; imagePicker.unregister() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val action = bindings["click"].orEmpty()
                val size = width.text.toString().toIntOrNull()
                val imageSize = iconSize.text.toString().toIntOrNull()
                if (imageSize == null || imageSize != 0 && imageSize !in 8..96) { iconSize.error = "请输入0或8–96"; return@setOnClickListener }
                if (action.isBlank()) { Toast.makeText(activity, "请先设置点击功能", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (ToolbarOverrides.isModeAction(action)) { Toast.makeText(activity, "这是已停用的旧动作，请从功能列表重新选择", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                if (size == null || size != 0 && size !in 24..160) { width.error = "请输入0或24–160"; return@setOnClickListener }
                save(draft.copy(action = action, icon = label.text.toString().trim(), id = entry.id.ifEmpty { UUID.randomUUID().toString() },
                    bindings = bindings.filterKeys { it != "click" }, width = size, iconSize = imageSize))
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
