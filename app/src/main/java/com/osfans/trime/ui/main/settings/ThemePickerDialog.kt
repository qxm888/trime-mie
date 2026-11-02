// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.color.MaterialColors
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.keymap.KeyboardLayoutCatalog
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.KeyboardColorGroups
import com.osfans.trime.data.theme.KeyboardStyleTarget
import com.osfans.trime.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Category changes only filter the list. Apply one complete layout when a tile is selected. */
object ThemePickerDialog {
    suspend fun build(scope: LifecycleCoroutineScope, context: Context,
                      pickTarget: ((KeyboardStyleTarget) -> Unit)? = null,
                      afterConfirm: (suspend () -> Unit)? = null): AlertDialog {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val night = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, if (night) 0xFFE3B9CA.toInt() else 0xFF82465A.toInt())
        val ink = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, if (night) 0xFFE6E1E5.toInt() else Color.DKGRAY)
        val face = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, if (night) 0xFF23252E.toInt() else Color.WHITE)
        fun tabShape(checked: Boolean) = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (checked) MaterialColors.layer(face, primary, .16f) else Color.TRANSPARENT)
            setStroke(dp(1).coerceAtLeast(1), if (checked) MaterialColors.layer(face, primary, .55f) else Color.TRANSPARENT)
        }

        val categories = listOf(KeyboardLayoutCatalog.THEME to "主题布局",
            KeyboardLayoutCatalog.BUILTIN to "内置布局", KeyboardLayoutCatalog.CUSTOM to "自定义布局")
        val ids = categories.associate { it.first to View.generateViewId() }
        var source = CustomKeyboards.pickerSource
        val modes = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL; gravity = Gravity.START
            categories.forEach { (value, label) ->
                addView(RadioButton(context).apply {
                    id = ids.getValue(value); text = label; textSize = 13f; minWidth = 0
                    buttonDrawable = null; isSingleLine = true; gravity = Gravity.CENTER
                    minHeight = dp(44); setPadding(dp(2), dp(4), dp(2), dp(4))
                    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(primary, ink)))
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), tabShape(true))
                        addState(intArrayOf(), tabShape(false))
                    }
                }, RadioGroup.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            }
            check(ids.getValue(source))
        }
        var colorsPage = false
        val sectionIds = listOf(View.generateViewId(), View.generateViewId())
        val sections = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            listOf("布局", "配色").forEachIndexed { index, label ->
                addView(RadioButton(context).apply {
                    id = sectionIds[index]; text = label; textSize = 16f; buttonDrawable = null
                    gravity = Gravity.CENTER; minHeight = dp(44)
                    setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(primary, ink)))
                    background = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_checked), tabShape(true)); addState(intArrayOf(), tabShape(false))
                    }
                }, RadioGroup.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), dp(6)) })
            }
            check(sectionIds[0]); visibility = if (pickTarget == null) View.VISIBLE else View.GONE
        }
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(TextView(context).apply { text = if (pickTarget == null) "键盘样式" else "选择主题"; textSize = 20f; setTextColor(ink); setPadding(dp(4), 0, 0, dp(8)) })
            addView(sections, LinearLayout.LayoutParams(-1, -2))
            addView(modes, LinearLayout.LayoutParams(-1, -2))
        }
        val grid = GridView(context).apply {
            stretchMode = GridView.STRETCH_COLUMN_WIDTH; verticalSpacing = dp(4)
            setPadding(dp(12), dp(4), dp(12), dp(12)); clipToPadding = false
        }
        val progress = ProgressBar(context)
        val empty = TextView(context).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val height = minOf(dp(360), (context.resources.displayMetrics.heightPixels * .48f).toInt())
        val content = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(-1, height)
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -1))
            addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        val dialog = AlertDialog.Builder(context).setCustomTitle(title).setView(content)
            .setNegativeButton(android.R.string.cancel, null).create()
        data class Choice(val id: String, val label: String, val target: KeyboardStyleTarget? = null)
        var choices = emptyList<Choice>()
        var loadJob: Job? = null
        var switchJob: Job? = null
        var generation = 0
        var attached = true
        fun enabled(value: Boolean) {
            grid.isEnabled = value
            for (i in 0 until modes.childCount) modes.getChildAt(i).isEnabled = value
            for (i in 0 until sections.childCount) sections.getChildAt(i).isEnabled = value
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = value
            dialog.setCancelable(value)
        }
        fun selected(): String {
            if (colorsPage) return KeyboardColorGroups.activeId()
            if (source == KeyboardLayoutCatalog.THEME) return if (CustomKeyboards.mode == CustomKeyboards.MODE_THEME)
                ThemeManager.prefs.selectedTheme.getValue() else ""
            if (CustomKeyboards.mode != CustomKeyboards.MODE_CUSTOM) return ""
            return CustomKeyboards.get(CustomKeyboards.activeId)?.secondaryOf?.takeIf { it.isNotBlank() } ?: CustomKeyboards.activeId
        }
        fun load() {
            loadJob?.cancel()
            val request = ++generation
            val category = source
            val palette = colorsPage
            modes.visibility = if (palette) View.GONE else View.VISIBLE
            progress.visibility = View.VISIBLE; empty.visibility = View.GONE; grid.isEnabled = false
            grid.numColumns = if (!palette && category == KeyboardLayoutCatalog.THEME) 1 else 2
            loadJob = scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        if (palette) KeyboardColorGroups.list().map { Choice(it.id, "${it.name}\n日 · ${it.day.name}\n夜 · ${it.night.name}") }
                        else if (category == KeyboardLayoutCatalog.THEME)
                            ThemeManager.getAllThemes().map { Choice(it.configId, it.name.ifBlank { it.configId }, KeyboardStyleTarget(category, it.configId, it.name.ifBlank { it.configId })) }
                        else KeyboardLayoutCatalog.roots(context, category).map { Choice(it.id, it.name, KeyboardStyleTarget(category, it.id, it.name)) }
                    }
                    if (request != generation || !attached) return@launch
                    choices = loaded
                    val active = selected()
                    grid.adapter = object : BaseAdapter() {
                        override fun getCount() = loaded.size
                        override fun getItem(position: Int) = loaded[position]
                        override fun getItemId(position: Int) = position.toLong()
                        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                            val item = loaded[position]; val checked = item.id == active
                            val text = TextView(context).apply {
                                this.text = if (checked) "${item.label}  ✓" else item.label
                                textSize = if (palette) 14f else 15f; gravity = Gravity.CENTER; maxLines = if (palette) 3 else 2
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setTextColor(if (checked) primary else ink)
                                setPadding(dp(10), dp(10), dp(10), dp(10))
                                contentDescription = item.label + if (checked) "，当前" else ""
                                val shape = GradientDrawable().apply {
                                    cornerRadius = dp(14).toFloat()
                                    setColor(if (checked) MaterialColors.layer(face, primary, .10f) else face)
                                    setStroke(dp(1).coerceAtLeast(1), MaterialColors.layer(face, primary, if (checked) .6f else .18f))
                                }
                                background = RippleDrawable(ColorStateList.valueOf(MaterialColors.layer(Color.TRANSPARENT, primary, .12f)), shape, null)
                            }
                            return FrameLayout(context).apply {
                                addView(text, FrameLayout.LayoutParams(-1, dp(if (palette) 96 else 68)).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) })
                            }
                        }
                    }
                    empty.text = if (palette) "" else when (category) {
                        KeyboardLayoutCatalog.CUSTOM -> "暂无自定义布局，可在布局管理中创建或导入"
                        KeyboardLayoutCatalog.BUILTIN -> "暂无内置布局，可在布局管理中恢复内置键盘"
                        else -> "暂无可用主题"
                    }
                    empty.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
                    progress.visibility = View.GONE; grid.isEnabled = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (request != generation || !attached) return@launch
                    progress.visibility = View.GONE; empty.text = "读取失败，请重新打开"; empty.visibility = View.VISIBLE
                    timber.log.Timber.w(error, "Could not list keyboard styles")
                }
            }
        }
        sections.setOnCheckedChangeListener { _, id ->
            if (switchJob?.isActive == true) return@setOnCheckedChangeListener
            colorsPage = id == sectionIds[1]
            load()
        }
        modes.setOnCheckedChangeListener { _, id ->
            if (switchJob?.isActive == true) return@setOnCheckedChangeListener
            source = ids.entries.first { it.value == id }.key
            if (pickTarget == null) CustomKeyboards.pickerSource = source
            load()
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            if (loadJob?.isActive == true || switchJob?.isActive == true || !grid.isEnabled) return@setOnItemClickListener
            val item = choices.getOrNull(position) ?: return@setOnItemClickListener
            val target = item.target
            if (pickTarget != null && target != null) { pickTarget(target); dialog.dismiss(); return@setOnItemClickListener }
            val palette = colorsPage
            loadJob?.cancel(); enabled(false); progress.visibility = View.VISIBLE
            switchJob = scope.launch {
                try {
                    afterConfirm?.invoke()
                    if (palette) KeyboardColorGroups.activate(item.id)
                    else KeyboardColorGroups.selectLayout(requireNotNull(target))
                    if (attached) dialog.dismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    timber.log.Timber.w(error, "Keyboard style switch failed")
                    if (attached) context.toast(error.message ?: "切换失败，已保留原键盘")
                } finally {
                    if (attached && dialog.isShowing) { enabled(true); progress.visibility = View.GONE }
                }
            }
        }
        dialog.setOnShowListener { load() }
        dialog.setOnDismissListener { loadJob?.cancel() }
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attached = true }
            override fun onViewDetachedFromWindow(v: View) { attached = false; loadJob?.cancel() }
        })
        return dialog
    }
}
