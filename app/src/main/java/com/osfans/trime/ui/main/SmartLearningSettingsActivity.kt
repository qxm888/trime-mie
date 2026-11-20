/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.learning.CommitLearningLog
import com.osfans.trime.data.learning.LearningAggregator
import com.osfans.trime.data.learning.SmartLearningSettings
import com.osfans.trime.worker.LearningWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 词库自学习设置页：开关、阈值、摘要、手动学习与清除。
 * 学习完全本地进行；总开关默认关闭，隐私说明见页内注记。
 */
class SmartLearningSettingsActivity : PersonalizationActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("词库学习"); render()
    }

    private fun toggle(title: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        body.addView(MaterialSwitch(this).apply {
            text = title; isChecked = get()
            setOnCheckedChangeListener { _, value ->
                set(value); render()
            }
        })
    }

    private fun render() {
        body.removeAllViews()
        note("输入法在本地学习你的输入习惯：常用词自动排前，高频组合自动组成新词写入用户词典。全程不联网，密码框不记录；数据都在应用私有目录，随时可以清除。")
        toggle("词库学习", { SmartLearningSettings.learningEnabled }) {
            SmartLearningSettings.learningEnabled = it
            LearningWorker.start(applicationContext)
        }
        toggle("新词自动入库（高频组合写入用户词典）", { SmartLearningSettings.newWordEnabled }) {
            SmartLearningSettings.newWordEnabled = it
        }
        toggle("积累分词语料（为整句智能排序做准备）", { SmartLearningSettings.corpusEnabled }) {
            SmartLearningSettings.corpusEnabled = it
        }
        button("新词入库阈值：连续 ${SmartLearningSettings.newWordThreshold} 次") {
            val choices = listOf(3, 5, 10, 20)
            MaterialAlertDialogBuilder(this)
                .setTitle("达到多少次观察后组成新词")
                .setItems(choices.map { "$it 次" }.toTypedArray()) { _, index ->
                    SmartLearningSettings.newWordThreshold = choices[index]; render()
                }.setNegativeButton("取消", null).show()
        }
        button("管理已学新词") { startActivity(android.content.Intent(this, LearnedWordsActivity::class.java)) }
        note("下词联想根据你的用词习惯，在上屏一个词后提示最常跟着出现的词。随使用逐渐变准；点击联想词直接上屏。")
        toggle("下词联想", { SmartLearningSettings.wordPredictionEnabled }) {
            SmartLearningSettings.wordPredictionEnabled = it
        }
        note("智能整句（实验性）：使用官方八股文搭配模型（octagram），长句和整句输入的组句排序更准。开启会给启用中的方案写入 grammar 配置并重新部署；模型是公共语料训练的通用数据，不包含你的输入。会重排方案的自定义配置文件（自定义内容保留）。")
        val gram = com.osfans.trime.data.learning.GrammarSetup.installedGram()
        note(if (gram == null) "当前语言模型：未安装（开启时会自动释放内置小模型）"
        else "当前语言模型：${gram.first}（${formatBytes(gram.second)}）")
        toggle("智能整句（实验性）", { SmartLearningSettings.smartSentenceEnabled }) {
            SmartLearningSettings.smartSentenceEnabled = it
            applyGrammar(it)
        }
        button("导入更完整的语言模型（.gram）") { pickGram.launch("*/*") }
        note(summary())
        button("立即学习一次（聚合并写入用户词典）") { learnNow() }
        button("清除学习数据") {
            MaterialAlertDialogBuilder(this)
                .setTitle("清除学习数据")
                .setMessage("将删除上屏日志、词频表与分词语料；已写入用户词典的词条请在「已学新词」管理中删除。")
                .setPositiveButton("清除") { _, _ ->
                    CommitLearningLog.clear(); LearningAggregator.clear(); render()
                }.setNegativeButton("取消", null).show()
        }
    }

    private val pickGram = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) applyGrammarImport(uri)
    }

    private fun applyGrammar(enabled: Boolean) {
        val progress = MaterialAlertDialogBuilder(this).setMessage("正在更新方案配置…").setCancelable(false).create()
        progress.show()
        lifecycleScope.launch {
            try {
                val rime = RimeDaemon.createSession("smart_learning")
                try {
                    val message = rime.runOnReady {
                        val ids = enabledSchemata().map { it.id }
                        val result = com.osfans.trime.data.learning.GrammarSetup.apply(enabled, ids)
                        deploy()
                        result
                    }
                    Toast.makeText(this@SmartLearningSettingsActivity, "$message，已重新部署", Toast.LENGTH_SHORT).show()
                } finally {
                    RimeDaemon.destroySession("smart_learning")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SmartLearningSettings.smartSentenceEnabled = !enabled
                Toast.makeText(this@SmartLearningSettingsActivity, "智能整句配置失败：${e.message.orEmpty().take(80)}", Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss(); render()
            }
        }
    }

    private fun applyGrammarImport(uri: android.net.Uri) {
        val progress = MaterialAlertDialogBuilder(this).setMessage("正在导入语言模型…").setCancelable(false).create()
        progress.show()
        lifecycleScope.launch {
            try {
                val rime = RimeDaemon.createSession("smart_learning")
                try {
                    val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                    val message = rime.runOnReady {
                        val ids = enabledSchemata().map { it.id }
                        val result = com.osfans.trime.data.learning.GrammarSetup.importGram(uri, name, ids).getOrThrow()
                        deploy()
                        result
                    }
                    Toast.makeText(this@SmartLearningSettingsActivity, "$message，已重新部署", Toast.LENGTH_LONG).show()
                } finally {
                    RimeDaemon.destroySession("smart_learning")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@SmartLearningSettingsActivity, "导入失败：${e.message.orEmpty().take(80)}", Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss(); render()
            }
        }
    }

    private fun summary(): String {
        val last = SmartLearningSettings.lastLearningTime
        val lastText = if (last == 0L) "从未运行" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(last)
        val logBytes = CommitLearningLog.sizeBytes()
        val corpusBytes = LearningAggregator.corpusSizeBytes()
        return "已学习词条：${SmartLearningSettings.learnedWordCount} 个\n" +
            "待处理日志：${if (logBytes > 0) formatBytes(logBytes) else "无"}\n" +
            "分词语料：${if (corpusBytes > 0) formatBytes(corpusBytes) else "无"}\n" +
            "上次学习：$lastText（导入 ${SmartLearningSettings.lastImportedCount} 条）" +
            SmartLearningSettings.lastLearningDetail.takeIf { it.isNotEmpty() }?.let { "\n详情：$it" }.orEmpty()
    }

    private fun learnNow() {
        if (!SmartLearningSettings.learningEnabled) {
            Toast.makeText(this, "请先开启词库学习", Toast.LENGTH_SHORT).show()
            return
        }
        val progress = MaterialAlertDialogBuilder(this).setMessage("正在学习…").setCancelable(false).create()
        progress.show()
        lifecycleScope.launch {
            try {
                val result = LearningWorker.runOnceNow()
                if (result != null) {
                    if (!CommitLearningLog.isRecentlyCommitted()) {
                        val rime = RimeDaemon.createSession("learning_settings")
                        try {
                            rime.runOnReady { deploy() }
                        } finally {
                            RimeDaemon.destroySession("learning_settings")
                        }
                    }
                    MaterialAlertDialogBuilder(this@SmartLearningSettingsActivity)
                        .setTitle("学习完成")
                        .setMessage(
                            buildString {
                                append("聚合 ${result.entries} 条，导入 ${result.imported} 条（新词 ${result.newWords}）")
                                if (result.detail.isNotEmpty()) append("\n\n${result.detail}")
                            },
                        )
                        .setPositiveButton("知道了", null)
                        .show()
                } else {
                    Toast.makeText(this@SmartLearningSettingsActivity, "暂无待学习内容，先去打几个词吧", Toast.LENGTH_SHORT).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@SmartLearningSettingsActivity, "学习失败：${e.message.orEmpty().take(80)}", Toast.LENGTH_LONG).show()
            } finally {
                progress.dismiss(); render()
            }
        }
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "%.1f KB".format(bytes / 1024f)
        else "%.1f MB".format(bytes / 1024f / 1024f)
}
