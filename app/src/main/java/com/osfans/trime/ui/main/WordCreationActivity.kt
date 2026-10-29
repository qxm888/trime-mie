/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.core.data.CandidatePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An ordinary Activity lets the user enter arbitrary Unicode text with their IME. */
class WordCreationActivity : AppCompatActivity() {
    private var word = ""
    private var code = ""
    private var step = 0
    private lateinit var input: TextInputEditText
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        word = state?.getString("word") ?: intent.getStringExtra("word").orEmpty()
        code = state?.getString("code") ?: intent.getStringExtra("code").orEmpty()
        step = state?.getInt("step") ?: 0
        render()
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(step==1) { code=input.text.toString(); step=0; render() } else finish() }
        })
    }
    private fun render() {
        val density=resources.displayMetrics.density
        fun dp(v:Int)=(v*density).toInt()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; fitsSystemWindows=true }
        root.addView(MaterialToolbar(this).apply { title="造词 · ${step+1}/2"; setNavigationIcon(com.osfans.trime.R.drawable.ic_baseline_arrow_back_24); setNavigationOnClickListener { if(step==1) { code=input.text.toString(); step=0; render() } else finish() } })
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(24),dp(24),dp(24)) }
        input=TextInputEditText(this).apply {
            setText(if(step==0) word else code)
            inputType=if(step==0) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            minLines=if(step==0) 3 else 1
        }
        val layout=TextInputLayout(this).apply { hint=if(step==0) "词条（支持句子、标点和emoji）" else "输入编码"; boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE; addView(input) }
        body.addView(layout)
        body.addView(android.widget.TextView(this).apply { text=if(step==0) "原候选保留。新词条保存到当前方案的候选备份中，可导出后迁移。" else "使用当前方案可输入的编码。以后输入此编码即可看到新词条。"; setPadding(0,dp(16),0,dp(16)) })
        val next=MaterialButton(this).apply { text=if(step==0) "下一步 · 设置编码" else "保存词条" }
        body.addView(next)
        next.setOnClickListener {
            if(step==0) {
                word=input.text.toString()
                if(word.isEmpty() || word.length>1024) { layout.error="请输入1–1024个字符"; return@setOnClickListener }
                step=1; render()
            } else {
                code=input.text.toString().trim()
                val alphabet=intent.getStringExtra("alphabet").orEmpty()
                if(code.isEmpty() || code.length>128 || code.any { it.isWhitespace() || alphabet.isNotEmpty() && it !in alphabet && it!='\'' }) { layout.error="请使用当前方案的有效编码字符，最多128个字符"; return@setOnClickListener }
                next.isEnabled=false
                lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { CandidatePreferences.createWord(intent.getStringExtra("schema").orEmpty(),code,word) } }
                        .onSuccess { android.widget.Toast.makeText(this@WordCreationActivity,"词条已保存",0).show(); finish() }
                        .onFailure { layout.error=it.message ?: "保存失败"; next.isEnabled=true }
                }
            }
        }
        root.addView(ScrollView(this).apply { addView(body) },LinearLayout.LayoutParams(-1,0,1f)); setContentView(root)
    }
    override fun onSaveInstanceState(out:Bundle) {
        if(step==0) word=input.text.toString() else code=input.text.toString()
        out.putString("word",word); out.putString("code",code); out.putInt("step",step); super.onSaveInstanceState(out)
    }
}
