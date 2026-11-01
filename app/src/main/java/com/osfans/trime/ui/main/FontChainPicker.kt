/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.data.theme.FontOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One ordered font selection UI shared by theme, layout and floating window. */
object FontChainPicker {
    fun show(activity: FragmentActivity, title: String, current: String,
             onImport: (() -> Unit)? = null, defaultLabel: String = "跟随主题 / 布局默认字体", onSelected: (String) -> Unit) {
        fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
        val selected = FontOverrides.decode(current).toMutableList()
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(TextView(activity).apply {
            text = "从上到下依次补缺字，可添加多个字体并调整顺序。系统字体放在末尾兜底。"
            setPadding(0, 0, 0, dp(12))
        })
        body.addView(rows)
        fun render() {
            rows.removeAllViews()
            if (selected.isEmpty()) rows.addView(TextView(activity).apply { text = defaultLabel; setPadding(0, dp(8), 0, dp(8)) })
            selected.toList().forEachIndexed { index, path ->
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(activity).apply {
                    text = "${index + 1}. ${FontOverrides.name(path)}"; maxLines = 2
                }, LinearLayout.LayoutParams(0, -2, 1f))
                fun control(label: String, enabled: Boolean, action: () -> Unit) {
                    row.addView(MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = label; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                        isEnabled = enabled; contentDescription = when(label) { "↑" -> "上移字体"; "↓" -> "下移字体"; else -> "移除字体" }
                        setOnClickListener { action(); render() }
                    }, LinearLayout.LayoutParams(dp(40), dp(48)))
                }
                control("↑", index > 0) { java.util.Collections.swap(selected, index, index - 1) }
                control("↓", index < selected.lastIndex) { java.util.Collections.swap(selected, index, index + 1) }
                control("×", true) { selected.removeAt(index) }
                rows.addView(row)
            }
        }
        body.addView(MaterialButton(activity).apply {
            text = "添加 / 选择字体"
            setOnClickListener {
                val choices = (FontOverrides.choices().filter { it.isNotEmpty() } + selected).distinct()
                val checked = choices.map { it in selected }.toBooleanArray()
                MaterialAlertDialogBuilder(activity).setTitle("选择字体")
                    .setMultiChoiceItems(choices.map(FontOverrides::name).toTypedArray(), checked) { source, i, value ->
                        checked[i] = value
                        if (value && choices[i].startsWith("system:")) choices.indices.filter { it != i && choices[it].startsWith("system:") }.forEach {
                            checked[it] = false
                            (source as androidx.appcompat.app.AlertDialog).listView.setItemChecked(it, false)
                        }
                    }
                    .setPositiveButton("确定") { _, _ ->
                        val picks = choices.filterIndexed { i, _ -> checked[i] }
                        if (picks.size > FontOverrides.MAX_CHAIN) android.widget.Toast.makeText(activity, "最多选择${FontOverrides.MAX_CHAIN}个字体", 0).show()
                        else { selected.retainAll(picks.toSet()); selected.addAll(picks.filterNot { it in selected }); render() }
                    }.setNegativeButton("取消", null).show()
            }
        })
        if (onImport != null) body.addView(MaterialButton(activity).apply { text = "导入字体文件…"; setOnClickListener { dialog.dismiss(); onImport() } })
        val scroll = ScrollView(activity).apply { addView(body) }
        dialog = MaterialAlertDialogBuilder(activity).setTitle("$title · 多字体")
            .setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ -> onSelected("") }.create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val value = FontOverrides.encode(FontOverrides.decode(FontOverrides.encode(selected)))
                dialog.getButton(-1).isEnabled = false
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { FontOverrides.fromSelection(value) }
                    if (dialog.isShowing) { onSelected(value); dialog.dismiss() }
                }
            }
        }
        render(); dialog.show()
    }
}
