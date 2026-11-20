/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.ui.main

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.R
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.learning.LearningAggregator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 已学新词管理：查看词库学习写入用户词典的词条（词、编码、累计次数），
 * 支持删除——同时移出学习词频表并向用户词典导入负频次标记删除。
 */
class LearnedWordsActivity : AppCompatActivity() {
    private data class WordRow(val word: String, val code: String, val count: Int)

    private val adapter = WordsAdapter()
    private var all = emptyList<WordRow>()
    private lateinit var count: TextView
    private lateinit var empty: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.framework_background))
        }
        root.addView(MaterialToolbar(this).apply {
            title = "已学新词"
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(dp(20), dp(4), dp(20), dp(4))
            setTextColor(ContextCompat.getColor(context, R.color.framework_text_secondary))
            text = "词库学习按使用频次自动组词并写入用户词典。删除会同时移除学习记录与词典中的词条；长句输入需要重新部署才完全还原。"
        })
        count = TextView(this).apply {
            textSize = 13f; setPadding(dp(20), dp(4), 0, dp(8))
            setTextColor(ContextCompat.getColor(context, R.color.framework_text_secondary))
        }
        root.addView(count)
        val frame = FrameLayout(this)
        frame.addView(RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LearnedWordsActivity)
            adapter = this@LearnedWordsActivity.adapter
            setPadding(dp(16), 0, dp(16), dp(16)); clipToPadding = false
        }, FrameLayout.LayoutParams(-1, -1))
        empty = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 16f; setPadding(dp(24), dp(24), dp(24), dp(24))
            text = "还没有已学新词\n开启词库学习后，高频组合会自动出现在这里"
        }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume(); reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val words = withContext(Dispatchers.IO) {
                LearningAggregator.learnedWords().map { WordRow(it.word, it.code, it.count) }
            }
            all = words
            adapter.submitList(words)
            count.text = "${words.size} 条 · 点击词条删除"
            empty.visibility = if (words.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun delete(row: WordRow) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除「${row.word.take(30)}」？")
            .setMessage("${row.code} · 累计 ${row.count} 次\n\n会从学习记录和用户词典中一并移除，并重新部署。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    try {
                        LearningAggregator.removeLearnedWord(row.word).getOrThrow()
                        val rime = RimeDaemon.createSession("learned_words")
                        try {
                            rime.runOnReady { deploy() }
                        } finally {
                            RimeDaemon.destroySession("learned_words")
                        }
                        Toast.makeText(this@LearnedWordsActivity, "已删除并重新部署", Toast.LENGTH_SHORT).show()
                        reload()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(this@LearnedWordsActivity, e.message ?: "删除失败", Toast.LENGTH_LONG).show()
                    }
                }
            }.show()
    }

    private inner class WordsAdapter : ListAdapter<WordRow, Holder>(
        object : DiffUtil.ItemCallback<WordRow>() {
            override fun areItemsTheSame(a: WordRow, b: WordRow) = a.word == b.word
            override fun areContentsTheSame(a: WordRow, b: WordRow) = a == b
        },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val row = LinearLayout(this@LearnedWordsActivity).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(12), dp(8), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(ContextCompat.getColor(context, R.color.settings_surface))
                }
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            }
            val texts = LinearLayout(this@LearnedWordsActivity).apply { orientation = LinearLayout.VERTICAL }
            val word = TextView(this@LearnedWordsActivity).apply {
                textSize = 19f; maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.framework_text_primary))
            }
            val detail = TextView(this@LearnedWordsActivity).apply {
                textSize = 13f; setPadding(0, dp(6), 0, 0)
                setTextColor(ContextCompat.getColor(context, R.color.framework_text_secondary))
            }
            texts.addView(word); texts.addView(detail)
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            return Holder(row, word, detail)
        }

        override fun onBindViewHolder(holder: Holder, index: Int) {
            val row = getItem(index)
            holder.word.text = row.word
            holder.detail.text = "${row.code} · 累计 ${row.count} 次"
            holder.itemView.setOnClickListener { delete(row) }
        }
    }

    private class Holder(
        row: View,
        val word: TextView,
        val detail: TextView,
    ) : RecyclerView.ViewHolder(row)
}
