/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** A real editor owned by the game; typing never depends on a covered chat field. */
class TypingGameActivity : Activity() {
    internal lateinit var input: EditText
        private set
    private val frameListener=ViewTreeObserver.OnPreDrawListener { TypingGame.showForHost(this); true }
    private lateinit var root:FrameLayout
    private var imeBottom=0
    private var keyboardTop:Int?=null
    private val rootLocation=IntArray(2)
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TypingGame.claimHost(this)) { finish(); return }
        WindowCompat.setDecorFitsSystemWindows(window,false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        root=FrameLayout(this).apply { setBackgroundColor(Color.rgb(18,24,35)) }
        input=EditText(this).apply {
            id=View.generateViewId()
            hint="输入落字，选字后发射攻击"
            contentDescription="打字游戏输入框"
            setTextColor(Color.WHITE);setHintTextColor(0xffa7b8cd.toInt());setTextSize(17f)
            setBackgroundColor(0xff253244.toInt());setPadding(dp(16),dp(10),dp(16),dp(10))
            minHeight=dp(48);setSingleLine(true)
            inputType=InputType.TYPE_CLASS_TEXT
            imeOptions=EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            setOnClickListener { showKeyboard() }
        }
        root.addView(input,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val bottom=insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars()).bottom
            root.setPadding(bars.left,bars.top,bars.right,0)
            imeBottom=bottom
            if(!insets.isVisible(WindowInsetsCompat.Type.ime())) keyboardTop=null
            updateInputMargin()
            input.post { TypingGame.showForHost(this) }
            insets
        }
        setContentView(root);root.viewTreeObserver.addOnPreDrawListener(frameListener)
        input.requestFocus();input.postDelayed({ showKeyboard() },300)
    }
    internal fun alignEditor(top:Int) { keyboardTop=top;updateInputMargin() }
    /** Board and editor share the application window, below the IME candidate layer. */
    internal fun placePlayfield(view: View, area: TypingGameLayout.Area) {
        root.getLocationOnScreen(rootLocation)
        val left = area.left - rootLocation[0] - root.paddingLeft
        val top = area.top - rootLocation[1] - root.paddingTop
        val current = view.layoutParams as? FrameLayout.LayoutParams
        if (view.parent === root && current != null &&
            current.width == area.width && current.height == area.height &&
            current.leftMargin == left && current.topMargin == top) return
        val params = FrameLayout.LayoutParams(area.width, area.height, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = left
            topMargin = top
        }
        if (view.parent == null) root.addView(view, 0, params) else view.layoutParams = params
    }

    internal fun removePlayfield(view: View) { root.removeView(view) }

    private fun updateInputMargin() {
        root.getLocationOnScreen(rootLocation)
        val margin=keyboardTop?.let { (rootLocation[1]+root.height-it).coerceAtLeast(0) } ?: imeBottom
        (input.layoutParams as FrameLayout.LayoutParams).also {
            if(it.bottomMargin!=margin) { it.bottomMargin=margin;input.layoutParams=it }
        }
    }
    private fun showKeyboard() {
        if(!::input.isInitialized) return
        input.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT)
    }
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus && ::input.isInitialized) input.post { showKeyboard() }
    }
    override fun onStop() { super.onStop();TypingGame.hostStopped(this) }
    override fun onDestroy() {
        if(::root.isInitialized && root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(frameListener)
        TypingGame.hostStopped(this);super.onDestroy()
    }
}
