/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.data.stats.InputStats
import com.osfans.trime.util.readBoundedText
import com.osfans.trime.util.writeAtomicText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Portable records, kept separate from clipboard backups and native user dictionaries. */
class PersonalDataActivity : AppCompatActivity() {
    private var exporting = "stats"
    private var importing = "stats"
    private var busy = false
    private lateinit var body: LinearLayout
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) task("已导出") {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                when (exporting) {
                    "dict" -> {
                        val dictionaries = CandidatePreferences.dictionaryDrafts()
                        require(dictionaries.isNotEmpty()) { "还没有候选调整记录" }
                        ZipOutputStream(out).use { zip ->
                            val files = dictionaries + ("使用说明.txt" to DICTIONARY_NOTE)
                            files.forEach { (name, text) ->
                                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                            }
                        }
                    }
                    else -> out.write(exportText(exporting).toByteArray(Charsets.UTF_8))
                }
            } ?: error("无法写入目标文件")
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val kind = importing
            confirmRestore(kind) {
                task("已恢复") {
                    val raw = contentResolver.openInputStream(uri)?.use { it.readBoundedText(32 * 1024 * 1024) } ?: error("无法读取文件")
                    restore(kind, raw)
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        exporting = savedInstanceState?.getString("exporting") ?: "stats"
        importing = savedInstanceState?.getString("importing") ?: "stats"
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, com.osfans.trime.R.color.framework_background))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "统计与候选备份"; setNavigationIcon(com.osfans.trime.R.drawable.ic_baseline_arrow_back_24)
            setNavigationIconTint(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        fun card(title: String, description: String, actions: List<Pair<String, () -> Unit>>) {
            val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(12)) }
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE }
            val header = TextView(this).apply {
                text = "▸ $title"; textSize = 20f; minHeight = dp(48); gravity = android.view.Gravity.CENTER_VERTICAL
                setTypeface(typeface, android.graphics.Typeface.BOLD); isFocusable = true
                contentDescription = "$title，已折叠"
                setOnClickListener {
                    val expanded = details.visibility != android.view.View.VISIBLE
                    details.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
                    text = "${if (expanded) "▾" else "▸"} $title"; contentDescription = "$title，${if (expanded) "已展开" else "已折叠"}"
                }
            }
            content.addView(header)
            content.addView(TextView(this).apply {
                text = when (title) { "输入统计" -> "自动保存 · 跨设备恢复"; "候选调整" -> "保存应用内调序与造词"; else -> "按方案导出，接入前需核对编码" }
                textSize = 14f; setPadding(0, 0, 0, dp(8))
                setOnClickListener { header.performClick() }
            })
            details.addView(TextView(this).apply { text = description; textSize = 14f; setPadding(0, dp(8), 0, dp(12)) })
            actions.forEach { (label, action) -> details.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label; isAllCaps = false; setOnClickListener { if (!busy) action() }
            }, LinearLayout.LayoutParams(-1, -2)) }
            content.addView(details)
            body.addView(MaterialCardView(this).apply { radius = dp(20).toFloat(); cardElevation = 0f; addView(content) },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        card("输入统计", "空闲时自动保存到 Rime 目录的 ${InputStats.rimeFile().name}。换设备可复制文件后恢复，累计字数、周期统计和热力图会一起保留。", listOf(
            "导出统计文件…" to { exporting = "stats"; exportFile.launch("mie-input-stats.json") },
            "导入统计文件…" to { importing = "stats"; importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            "立即保存到 Rime 目录" to { task("已保存到 Rime 目录") {
                check(UserDataManager.isStorageAvailable()) { "请先设置并授权数据目录" }
                InputStats.saveToRime(); check(UserDataManager.sync()) { "已保存本地文件，但外部同步失败" }
            } },
            "从 Rime 目录恢复" to { confirmRestore("stats") { task("已恢复统计") {
                check(UserDataManager.sync()) { "目录同步失败" }
                InputStats.restoreFromRime()
            } } },
        ))
        card("候选调整", "JSON 保存各方案、各编码的固定位置、前后调序、隐藏和造词。原生 Control+Delete 忘词由 Rime 用户词典管理，请使用整包备份保存。恢复 JSON 会替换应用内调整，并保留恢复前备份。", listOf(
            "导出候选调整…" to { exporting = "candidates"; exportFile.launch("mie-candidate-order.json") },
            "导入候选调整…" to { importing = "candidates"; importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            "保存候选调整到 Rime 目录" to { task("已保存到 Rime 目录") {
                check(UserDataManager.isStorageAvailable()) { "请先设置并授权数据目录" }
                File(DataManager.userDataDir, candidateFileName()).writeAtomicText(CandidatePreferences.exportJson()); check(UserDataManager.sync()) { "已保存本地文件，但外部同步失败" }
            } },
            "从 Rime 目录恢复候选调整" to { confirmRestore("candidates") { task("已恢复候选调整") {
                check(UserDataManager.sync()) { "目录同步失败" }
                restore("candidates", File(DataManager.userDataDir, candidateFileName()).inputStream().use { it.readBoundedText(32 * 1024 * 1024) })
            } } },
        ))
        card("生成词典草稿", "按方案生成 .dict.yaml，按调整顺序写入递减权重。输入编码可能经过双拼、拼写变换或 Lua 处理，因此需要核对编码并手动接入方案，不能保证直接部署即还原顺序。", listOf(
            "导出各方案词典草稿 ZIP…" to {
                MaterialAlertDialogBuilder(this).setTitle("生成词典草稿").setMessage(DICTIONARY_NOTE)
                    .setNegativeButton(android.R.string.cancel, null).setPositiveButton("导出") { _, _ ->
                        exporting = "dict"; exportFile.launch("mie-candidate-dictionaries.zip")
                    }.show()
            },
        ))
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exporting", exporting); outState.putString("importing", importing); super.onSaveInstanceState(outState)
    }
    private fun candidateFileName() = if (com.osfans.trime.BuildConfig.DEBUG) "mie_candidate_order-$packageName.json" else "mie_candidate_order.json"
    private fun exportText(kind: String) = if (kind == "stats") InputStats.exportJson() else CandidatePreferences.exportJson()
    private fun restore(kind: String, raw: String) = if (kind == "stats") InputStats.importJson(raw) else CandidatePreferences.importJson(raw)
    private fun confirmRestore(kind: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(if (kind == "stats") "替换当前统计？" else "替换当前候选调整？")
            .setMessage("恢复文件将替换当前记录，不会重复累加。应用会保留恢复前备份。")
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton("恢复") { _, _ -> if (!busy) action() }.show()
    }
    private fun task(success: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess { Toast.makeText(this@PersonalDataActivity, success, Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this@PersonalDataActivity, it.message ?: "操作失败", Toast.LENGTH_LONG).show(); timber.log.Timber.e(it, "Personal data transfer failed") }
            busy = false
        }
    }
    companion object {
        private const val DICTIONARY_NOTE = "这是输入编码词典草稿。请先核对编码是否等于方案词典编码；双拼、形码和 Lua 方案可能需要转换。接入时需将草稿词典加入方案词典的 import_tables 后重新部署。权重只影响词典层，Lua 过滤和用户词频仍可能改变候选顺序。应用内隐藏等规则另存 JSON；原生忘词与用户词频请使用整包备份中的 Rime 用户词典保存。"
    }
}
