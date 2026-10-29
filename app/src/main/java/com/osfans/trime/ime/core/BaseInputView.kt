/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.res.Resources
import android.view.View
import android.view.WindowInsets
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splitties.dimensions.dp
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: TrimeInputMethodService,
    val rime: RimeSession,
    val scope: ThemeScope,
) : ConstraintLayout(service) {
    /** The theme config; geometry is theme-scoped, colors go through [scope.colors]. */
    val theme: Theme
        get() = scope.theme

    protected abstract fun handleRimeMessage(it: RimeMessage<*>)

    private var messageHandlerJob: Job? = null

    private fun setupRimeMessageHandler() {
        messageHandlerJob =
            service.lifecycleScope.launch {
                rime.run { messageFlow }.collect {
                    handleRimeMessage(it)
                }
            }
    }

    var handleMessages = false
        set(value) {
            field = value
            if (field) {
                if (messageHandlerJob == null) {
                    setupRimeMessageHandler()
                }
            } else {
                messageHandlerJob?.cancel()
                messageHandlerJob = null
            }
        }

    val themedContext = context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private var candidateActionMenu: PopupMenu? = null

    fun showCandidateActionMenu(idx: Int, text: String, view: View, global: Boolean, onFullDisplay: (() -> Unit)? = null) {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
        val highlightColor = scope.colors.hilitedCandidateTextColor
        val title = buildSpannedString {
            bold {
                color(highlightColor) { append(text) }
            }
        }
        service.lifecycleScope.launch {
            InputFeedbackManager.keyPressVibrate(view, longPress = true)
            val contextCode = rime.runOnReady { getRawInput() }
            val contextSchema = rime.runOnReady { selectedSchemaId() }
            fun adjust(action: String, value: String = "") {
                service.lifecycleScope.launch {
                    runCatching { rime.runOnReady {
                        if (getRawInput() != contextCode || selectedSchemaId() != contextSchema) false
                        else customizeCandidate(idx, text, global, action, value)
                    } }.onSuccess { changed ->
                        android.widget.Toast.makeText(context, if (changed) "已保存候选设置" else "候选已变化，请重新长按", android.widget.Toast.LENGTH_SHORT).show()
                    }.onFailure { android.widget.Toast.makeText(context, it.message ?: "保存失败", android.widget.Toast.LENGTH_LONG).show() }
                }
            }
            candidateActionMenu =
                PopupMenu(themedContext, view).apply {
                    menu.add(title).apply {
                        isEnabled = false
                    }
                    onFullDisplay?.let { full -> menu.add("完整显示").setOnMenuItemClickListener { full(); true } }
                    listOf("固定到第1位" to "pin1", "固定到第2位" to "pin2", "固定到第3位" to "pin3", "上移一位" to "up", "下移一位" to "down").forEach { (label, action) ->
                        menu.add(label).setOnMenuItemClickListener { adjust(action); true }
                    }
                    menu.add("造词…").setOnMenuItemClickListener {
                        context.startActivity(android.content.Intent(context, com.osfans.trime.ui.main.WordCreationActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra("schema", contextSchema).putExtra("word", text).putExtra("code", contextCode)
                            .putExtra("alphabet", rime.run { schemaCached.alphabet }))
                        true
                    }
                    menu.add("忘记这个候选 · Control+Delete").setOnMenuItemClickListener { adjust("forget"); true }
                    menu.add("删除这个候选").setOnMenuItemClickListener { adjust("delete"); true }
                    menu.add("恢复此编码默认候选").setOnMenuItemClickListener { adjust("reset"); true }
                    setOnDismissListener {
                        candidateActionMenu = null
                    }
                    show()
                }
        }
    }

    private val navBarBackground by ThemeManager.prefs.navbarBackground

    private val navBarFrameHeight: Int
        get() {
            @SuppressLint("DiscouragedApi")
            val resId = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
            return try {
                resources.getDimensionPixelSize(resId)
            } catch (_: Resources.NotFoundException) {
                dp(FALLBACK_NAVBAR_HEIGHT)
            }
        }

    private val ignoreSystemGestureInsets by AppPrefs.defaultInstance().advanced.ignoreSystemGestureInsets

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navBarBackground != ThemePrefs.NavbarBackground.FULL) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val navBars = WindowInsetsCompat.Type.navigationBars()
        val gestures = WindowInsetsCompat.Type.systemGestures() or
            WindowInsetsCompat.Type.mandatorySystemGestures()
        return if (ignoreSystemGestureInsets) {
            // check gestures insets and ignore them when it greater than zero
            val gesturesBottom = insets.getInsets(gestures).bottom
            val navBarsBottom = insets.getInsets(navBars).bottom
            if (gesturesBottom > 0) 0 else navBarsBottom
        } else {
            val insetsBottom = insets.getInsets(navBars or gestures).bottom
            if (insetsBottom > 0) max(insetsBottom, navBarFrameHeight) else insetsBottom
        }
    }

    fun dismissCandidateActionMenu() {
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // on API 35+, we must call requestApplyInsets() manually after replacing views,
        // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        handleMessages = false
        dismissCandidateActionMenu()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val FALLBACK_NAVBAR_HEIGHT = 16
    }
}
