// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens immediately; directory/name reads and font loading happen after showing. */
object ThemePickerDialog {
    suspend fun build(scope: LifecycleCoroutineScope, context: Context,
                      afterConfirm: (suspend () -> Unit)? = null): AlertDialog {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val themeId = View.generateViewId()
        val customId = View.generateViewId()
        val modes = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL; gravity = Gravity.START
            addView(RadioButton(context).apply { id = themeId; text = "主题布局" })
            addView(RadioButton(context).apply { id = customId; text = "自定义布局" })
            check(if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) customId else themeId)
        }
        val title = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(8))
            addView(modes)
            addView(TextView(context).apply { text = "键盘样式"; textSize = 20f; setPadding(0, dp(8), 0, 0) })
        }
        val list = ListView(context).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        val progress = ProgressBar(context)
        val empty = TextView(context).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val content = FrameLayout(context).apply {
            val height = minOf(dp(340), (context.resources.displayMetrics.heightPixels * .48f).toInt())
            layoutParams = android.view.ViewGroup.LayoutParams(-1, height)
            addView(list, FrameLayout.LayoutParams(-1, height))
            addView(empty, FrameLayout.LayoutParams(-1, height))
            addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        }
        val dialog = AlertDialog.Builder(context).setCustomTitle(title).setView(content)
            .setNegativeButton(android.R.string.cancel, null).create()
        data class Choice(val id: String, val label: String)
        var choices = emptyList<Choice>()
        var loadJob: Job? = null
        var switchJob: Job? = null
        var restoringMode = false
        fun load() {
            loadJob?.cancel()
            val custom = modes.checkedRadioButtonId == customId
            progress.visibility = View.VISIBLE; empty.visibility = View.GONE; list.isEnabled = false
            loadJob = scope.launch {
                try {
                    choices = withContext(Dispatchers.IO) {
                        if (custom) CustomKeyboards.list().filter { it.secondaryOf.isEmpty() }.map { Choice(it.id, it.name) }
                        else ThemeManager.getAllThemes().map { Choice(it.configId, it.name ?: it.configId) }
                    }
                    list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, choices.map { it.label })
                    val selected = if (custom) CustomKeyboards.get(CustomKeyboards.activeId)?.secondaryOf?.takeIf { it.isNotBlank() } ?: CustomKeyboards.activeId else ThemeManager.prefs.selectedTheme.getValue()
                    list.setItemChecked(choices.indexOfFirst { it.id == selected }, true)
                    empty.text = if (custom) "暂无自定义布局" else "暂无可用主题"
                    empty.visibility = if (choices.isEmpty()) View.VISIBLE else View.GONE
                    progress.visibility = View.GONE; list.isEnabled = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    progress.visibility = View.GONE; empty.text = "读取失败，请重新打开"; empty.visibility = View.VISIBLE
                    timber.log.Timber.w(error, "Could not list keyboard styles")
                }
            }
        }
        modes.setOnCheckedChangeListener { _, id ->
            if (restoringMode) return@setOnCheckedChangeListener
            switchJob?.cancel()
            val targetMode = if (id == customId) CustomKeyboards.MODE_CUSTOM else CustomKeyboards.MODE_THEME
            load()
            switchJob = scope.launch {
                try {
                    afterConfirm?.invoke()
                    ThemeManager.selectTheme(ThemeManager.prefs.selectedTheme.getValue(), layoutMode = targetMode)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    timber.log.Timber.w(error, "Layout mode switch failed"); context.toast("布局切换失败")
                    restoringMode = true
                    modes.check(if (CustomKeyboards.mode == CustomKeyboards.MODE_CUSTOM) customId else themeId)
                    restoringMode = false
                    load()
                }
            }
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val item = choices.getOrNull(position) ?: return@setOnItemClickListener
            val custom = modes.checkedRadioButtonId == customId
            val previousActive = CustomKeyboards.activeId
            list.isEnabled = false; modes.isEnabled = false; progress.visibility = View.VISIBLE
            modes.getChildAt(0).isEnabled = false; modes.getChildAt(1).isEnabled = false
            switchJob?.cancel()
            switchJob = scope.launch {
                try {
                    afterConfirm?.invoke()
                    if (custom) CustomKeyboards.activeId = item.id
                    val resolved = ThemeManager.selectTheme(if (custom) ThemeManager.prefs.selectedTheme.getValue() else item.id,
                        layoutMode = if (custom) CustomKeyboards.MODE_CUSTOM else CustomKeyboards.MODE_THEME)
                    dialog.dismiss()
                    if (!custom && resolved != item.id) context.toast("主题暂不可用，已使用默认主题；如缺少编译文件，请手动部署")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (custom && CustomKeyboards.activeId == item.id) CustomKeyboards.activeId = previousActive
                    timber.log.Timber.w(error, "Keyboard style switch failed")
                    context.toast("切换失败，请检查主题文件")
                    progress.visibility = View.GONE; list.isEnabled = true
                    modes.isEnabled = true; modes.getChildAt(0).isEnabled = true; modes.getChildAt(1).isEnabled = true
                }
            }
        }
        dialog.setOnShowListener { load() }
        dialog.setOnDismissListener { loadJob?.cancel() }
        // The IME host installs its own onDismiss listener. Detach still stops
        // listing so a dismissed overlay cannot retain its Context and views.
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { loadJob?.cancel() }
        })
        return dialog
    }
}
