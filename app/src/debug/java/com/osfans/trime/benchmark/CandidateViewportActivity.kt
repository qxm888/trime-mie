/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark
class CandidateViewportActivity : android.app.Activity() {
    private lateinit var input: android.widget.EditText
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        input = android.widget.EditText(this).apply { hint = "候选窗横屏测试"; setSingleLine(true) }
        setContentView(input)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        input.requestFocus()
        input.post { (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }
    override fun onConfigurationChanged(config: android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        input.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.requestFocus(); imm.restartInput(input)
            imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::input.isInitialized) {
            input.requestFocus()
            input.postDelayed({
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }
}
