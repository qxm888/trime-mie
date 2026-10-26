/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.ime.voice.builtin.AsrContextHolder
import com.osfans.trime.ime.voice.builtin.AsrSettings
import com.osfans.trime.ime.voice.builtin.AsrVendors
import com.osfans.trime.ime.voice.builtin.LocalAsrModels
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import com.osfans.trime.ime.voice.builtin.ModelInstallPhase
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Call

class VoiceSettingsActivity : PersonalizationActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null
    private var operationStatus = "准备模型…"
    private var downloadCall: Call? = null
    private var progressButtonReference: android.widget.Button? = null
    private var sourceDialog: Dialog? = null

    private val permission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "麦克风权限已开启" else "需要麦克风权限才能按住说话", Toast.LENGTH_LONG).show()
        render()
    }

    private val zipImporter = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("语音输入")
        render()
        // Finish a previously downloaded model automatically when returning to this page.
        val family = AsrSettings.selectedVendor
        val definition = LocalAsrModels.family(family)
        if (definition != null && LocalAsrModels.installedVariant(this, family) == null) {
            val variant = AsrSettings.localVariant(family).ifBlank { definition.variants.first().key }
            if (LocalAsrModels.hasDownloadedArchive(this, family, variant)) {
                startDownload(family, variant, AsrSettings.downloadSource)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadCall?.cancel()
        scope.cancel()
    }

    private fun render() {
        body.removeAllViews()
        progressButtonReference = if (downloadJob?.isActive == true) {
            button("$operationStatus · 点此取消") { downloadCall?.cancel(); downloadJob?.cancel() }
        } else null

        body.addView(MaterialSwitch(this).apply {
            text = "启用语音输入（长按空格说话）"
            isChecked = com.osfans.trime.ime.voice.VoiceSettings.enabled
            setOnCheckedChangeListener { _, checked -> com.osfans.trime.ime.voice.VoiceSettings.setEnabled(checked) }
        })

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        button(if (granted) "麦克风权限已开启" else "授予麦克风权限") {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }

        val vendorId = AsrSettings.selectedVendor
        note("识别服务商：${AsrVendors.labelOf(vendorId)}")
        button("语音识别服务商") { pickVendor() }

        val def = AsrVendors.byId(vendorId)
        when {
            vendorId == "none" -> {
                note("尚未选择识别引擎。请选择在线服务并配置自己的凭据，或下载 / 导入本地离线模型。")
            }
            def?.local == true -> renderLocal(def.id)
            def != null -> renderOnline(def.id)
        }

        note("长按空格开始录音，松开结束，识别结果直接上屏，不切换输入法。也可在按键编辑里把长按绑定到“内置语音”等语音键；已有自定义长按绑定优先。")
    }

    // ---------- 服务商选择 ----------

    private fun pickVendor() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun section(title: String) {
            container.addView(TextView(this).apply {
                text = title
                textSize = 13f
                alpha = 0.7f
                setPadding(dp(24), dp(12), dp(24), dp(4))
            })
        }
        fun row(id: String, label: String, tags: String) {
            container.addView(TextView(this).apply {
                text = if (tags.isBlank()) label else "$label  $tags"
                textSize = 16f
                setPadding(dp(24), dp(10), dp(24), dp(10))
                if (id == AsrSettings.selectedVendor) setTextColor(0xFF7C4DFF.toInt())
                setOnClickListener {
                    AsrSettings.setVendor(id)
                    render()
                }
            })
        }
        section("通用")
        row("none", AsrVendors.labelOf("none"), "")
        section("在线（用户自己的 API Key）")
        AsrVendors.ONLINE_ALL.forEach { row(it.id, it.label, AsrVendors.tagsLabel(it)) }
        section("本地离线（同文内置识别，模型可下载 / 可导入）")
        AsrVendors.LOCAL_ALL.forEach { row(it.id, it.label, AsrVendors.tagsLabel(it)) }

        val scroll = androidx.core.widget.NestedScrollView(this)
        scroll.addView(container)
        MaterialAlertDialogBuilder(this)
            .setTitle("语音识别服务商")
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 在线 vendor 配置 ----------

    private fun renderOnline(vendor: String) {
        val def = AsrVendors.byId(vendor)!!
        when (vendor) {
            "volc" -> {
                note("使用你自己的火山引擎账号：控制台开通语音技术「流式语音识别大模型」。16kHz PCM 流式上传，实时返回结果。")
                choiceRow(
                    "鉴权方式",
                    listOf("new" to "新鉴权（X-Api-Key）", "legacy" to "旧鉴权（App Key + Access Key）"),
                    if (AsrSettings.volcUseNewAuth()) "new" else "legacy",
                ) {
                    AsrSettings.setVolcUseNewAuth(it == "new")
                    render()
                }
                if (AsrSettings.volcUseNewAuth()) {
                    secretRow("X-Api-Key", AsrSettings.apiKey("volc")) { AsrSettings.setApiKey("volc", it) }
                } else {
                    secretRow("App Key", AsrSettings.volcAppKey()) { AsrSettings.setVolcAppKey(it) }
                    secretRow("Access Key", AsrSettings.volcAccessKey()) { AsrSettings.setVolcAccessKey(it) }
                }
                choiceRow(
                    "识别模型",
                    listOf(
                        "doubao_streaming_v2" to "豆包大模型流式 v2（推荐）",
                        "doubao_streaming_v1" to "豆包大模型流式 v1",
                    ),
                    AsrSettings.model("volc"),
                ) { AsrSettings.setModel("volc", it) }
                choiceRow(
                    "识别语言",
                    listOf("zh-CN" to "中文（含英文）", "en-US" to "英语", "ja-JP" to "日语", "" to "不指定"),
                    AsrSettings.language("volc"),
                ) { AsrSettings.setLanguage("volc", it) }
                body.addView(MaterialSwitch(this).apply {
                    text = "语义顺滑（去除口头禅与重复）"
                    isChecked = AsrSettings.volcDdcEnabled()
                    setOnCheckedChangeListener { _, c -> AsrSettings.setVolcDdcEnabled(c) }
                })
            }

            "gemini" -> {
                note("Gemini generateContent 转写：音频以 WAV inline base64 提交，模型名可改（如 gemini-2.5-flash）。")
                secretRow("API Key", AsrSettings.apiKey("gemini")) { AsrSettings.setApiKey("gemini", it) }
                textRow("接口地址", AsrSettings.endpoint("gemini")) { AsrSettings.setEndpoint("gemini", it) }
                textRow("模型名", AsrSettings.model("gemini")) { AsrSettings.setModel("gemini", it) }
            }

            "mimo" -> {
                note("小米 MiMo chat/completions 音频理解；也可填自建兼容端点。")
                secretRow("API Key", AsrSettings.apiKey("mimo")) { AsrSettings.setApiKey("mimo", it) }
                textRow("接口地址", AsrSettings.endpoint("mimo")) { AsrSettings.setEndpoint("mimo", it) }
                textRow("模型名", AsrSettings.model("mimo")) { AsrSettings.setModel("mimo", it) }
                choiceRow(
                    "识别语言",
                    listOf("auto" to "自动", "zh" to "中文", "en" to "英语"),
                    AsrSettings.language("mimo").ifBlank { "auto" },
                ) { AsrSettings.setLanguage("mimo", it) }
            }

            "elevenlabs" -> {
                note("ElevenLabs Scribe 转写（松手后整段上传）。")
                secretRow("API Key", AsrSettings.apiKey("elevenlabs")) { AsrSettings.setApiKey("elevenlabs", it) }
                textRow("模型", AsrSettings.model("elevenlabs")) { AsrSettings.setModel("elevenlabs", it) }
                textRow("语言代码（留空自动，如 zh）", AsrSettings.language("elevenlabs")) { AsrSettings.setLanguage("elevenlabs", it) }
            }

            "soniox" -> {
                note("Soniox 异步转写：上传 → 建任务 → 轮询 → 取结果。")
                secretRow("API Key", AsrSettings.apiKey("soniox")) { AsrSettings.setApiKey("soniox", it) }
                textRow("语言提示（留空自动识别）", AsrSettings.language("soniox")) { AsrSettings.setLanguage("soniox", it) }
            }

            "stepaudio" -> {
                note("阶跃星辰 StepAudio SSE 接口；支持 paygo 与 coding_plan 端点（可改地址）。")
                secretRow("API Key", AsrSettings.apiKey("stepaudio")) { AsrSettings.setApiKey("stepaudio", it) }
                textRow("接口地址", AsrSettings.endpoint("stepaudio")) { AsrSettings.setEndpoint("stepaudio", it) }
                textRow("模型名", AsrSettings.model("stepaudio")) { AsrSettings.setModel("stepaudio", it) }
                choiceRow(
                    "识别语言",
                    listOf("zh" to "中文", "en" to "英语", "" to "不指定"),
                    AsrSettings.language("stepaudio").ifBlank { "zh" },
                ) { AsrSettings.setLanguage("stepaudio", it) }
            }

            else -> {
                // openai / siliconflow / openrouter / zhipu / cohere：OpenAI 兼容转写
                note(
                    when (vendor) {
                        "siliconflow" -> "SiliconFlow 语音转写（默认 SenseVoiceSmall，可换 TeleAI/TeleSpeechASR 等）。"
                        "openrouter" -> "OpenRouter 音频转写（JSON input_audio 形态）。"
                        "zhipu" -> "智谱 GLM 语音识别（glm-asr）。"
                        "cohere" -> "Cohere 音频转写。"
                        else -> "OpenAI 语音转写；任何兼容 /v1/audio/transcriptions 的服务都能用（改端点即可）。"
                    },
                )
                secretRow("API Key", AsrSettings.apiKey(vendor)) { AsrSettings.setApiKey(vendor, it) }
                if (def.editableEndpoint) {
                    textRow("接口地址", AsrSettings.endpoint(vendor)) { AsrSettings.setEndpoint(vendor, it) }
                }
                if (vendor != "zhipu") {
                    textRow("模型名", AsrSettings.model(vendor)) { AsrSettings.setModel(vendor, it) }
                }
                if (vendor == "openai" || vendor == "cohere") {
                    textRow("语言代码（留空不指定，如 zh）", AsrSettings.language(vendor)) { AsrSettings.setLanguage(vendor, it) }
                }
            }
        }
    }

    // ---------- 本地模型配置 ----------

    private fun renderLocal(family: String) {
        val familyDef = LocalAsrModels.family(family) ?: return
        val ctx = AsrContextHolder.get()
        val installed = ctx?.let { LocalAsrModels.installedVariant(it, family) }
        val current = AsrSettings.localVariant(family).ifBlank { familyDef.variants.first().key }
        note(
            "本地离线识别，模型安装到应用内部目录并做 SHA-256 校验，下载支持断点续传、可选四个下载源。" +
                "已安装：" + (installed?.let { familyDef.variant(it)?.label ?: it } ?: "无") +
                (if (downloadJob?.isActive == true) "（正在下载…）" else ""),
        )
        button("模型版本：${familyDef.variant(current)?.label ?: current}") {
            MaterialAlertDialogBuilder(this@VoiceSettingsActivity)
                .setTitle("选择模型版本")
                .setItems(familyDef.variants.map { it.label }.toTypedArray()) { _, i ->
                    AsrSettings.setLocalVariant(family, familyDef.variants[i].key)
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        if (downloadJob?.isActive == true) {
            note("模型安装进行中，进度显示在页面顶部。")
        } else {
            button("下载并自动安装模型") { pickDownloadSource(family, current) }
            button("从本地导入模型（zip）") { zipImporter.launch("*/*") }
        }
        if (installed != null) {
            button("清理已下载的本地模型") {
                LocalAsrModels.uninstall(this, family)
                Toast.makeText(this, "已删除本地模型", Toast.LENGTH_SHORT).show()
                render()
            }
        }
        numberRow("识别线程数（1-8）", AsrSettings.localThreads(family)) { AsrSettings.setLocalThreads(family, it) }
        if (family == "sensevoice" || family == "funasr_nano" || family == "qwen3_asr") {
            body.addView(MaterialSwitch(this).apply {
                text = "启用 ITN（数字智能识别）"
                isChecked = AsrSettings.localItn(family)
                setOnCheckedChangeListener { _, c -> AsrSettings.setLocalItn(family, c) }
            })
        }
        if (family == "sensevoice") {
            choiceRow(
                "识别语言",
                listOf("auto" to "自动", "zh" to "中文", "en" to "英语", "ja" to "日语", "ko" to "韩语", "yue" to "粤语"),
                AsrSettings.localLanguage(family).ifBlank { "auto" },
            ) { AsrSettings.setLocalLanguage(family, it) }
        }
        choiceRow(
            "模型卸载策略",
            listOf("-1" to "始终保持（直到参数变更）", "0" to "即用即卸（省内存）", "10" to "10 分钟后卸载", "30" to "30 分钟后卸载"),
            AsrSettings.localKeepAliveMinutes(family).toString(),
        ) { AsrSettings.setLocalKeepAliveMinutes(family, it.toIntOrNull() ?: -1) }
        button("默认下载源：${LocalAsrModels.SOURCES.getOrElse(AsrSettings.downloadSource) { LocalAsrModels.SOURCES[0] }.first}") {
            pickDefaultDownloadSource()
        }
    }

    // ---------- 下载源（测速四选一） ----------

    private fun pickDefaultDownloadSource() {
        showSourceDialog { index ->
            AsrSettings.downloadSource = index
            render()
        }
    }

    private fun pickDownloadSource(family: String, variant: String) {
        showSourceDialog { index ->
            AsrSettings.downloadSource = index
            startDownload(family, variant, index)
        }
    }

    /** 与说点啥一致的选择下载源对话框：显示各源 HTTP 响应耗时，仅供可达性参考。 */
    private fun showSourceDialog(onPick: (Int) -> Unit) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val latencyViews = mutableListOf<TextView>()
        LocalAsrModels.SOURCES.forEachIndexed { index, (label, host) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(14), dp(24), dp(14))
                isClickable = true
                isFocusable = true
            }
            val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            titleRow.addView(TextView(this@VoiceSettingsActivity).apply {
                text = label
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val latency = TextView(this@VoiceSettingsActivity).apply {
                textSize = 14f
                gravity = Gravity.END
                text = "测速中…"
            }
            titleRow.addView(latency)
            row.addView(titleRow)
            row.addView(TextView(this@VoiceSettingsActivity).apply {
                text = host
                textSize = 13f
                alpha = 0.65f
            })
            row.setOnClickListener {
                sourceDialog?.dismiss()
                onPick(index)
            }
            latencyViews.add(latency)
            container.addView(row)
        }
        container.addView(TextView(this).apply {
            text = "所示延迟为 HTTP 响应耗时，仅作可达性参考，不能反映实际下载吞吐。"
            textSize = 13f
            alpha = 0.7f
            setPadding(dp(24), dp(8), dp(24), dp(16))
        })
        val scroll = androidx.core.widget.NestedScrollView(this)
        scroll.addView(container)
        sourceDialog = MaterialAlertDialogBuilder(this)
            .setTitle("选择下载源")
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                val family = AsrSettings.selectedVendor.takeIf { LocalAsrModels.family(it) != null } ?: "sensevoice"
                val variant = AsrSettings.localVariant(family)
                LocalAsrModels.SOURCES.indices.map { i -> async {
                    i to LocalAsrModels.measureSourceLatency(i, family, variant)
                } }.awaitAll()
            }
            results.forEach { (index, latency) ->
                latencyViews.getOrNull(index)?.text = when {
                    latency == null -> "延迟：不可用"
                    else -> "延迟：$latency ms"
                }
            }
        }
    }

    private fun stageProgress(phase: ModelInstallPhase, percent: Int) {
        runOnUiThread {
            operationStatus = when (phase) {
                ModelInstallPhase.DOWNLOAD -> "正在下载 $percent%"
                ModelInstallPhase.EXTRACT -> "下载完成，正在解压模型…"
                ModelInstallPhase.VERIFY -> "正在校验模型 $percent%…"
                ModelInstallPhase.INSTALL -> "正在安装并启用模型…"
                ModelInstallPhase.READY -> "模型已安装并启用"
            }
            progressButtonReference?.text = "$operationStatus · 点此取消"
        }
    }

    private fun startDownload(family: String, variant: String, source: Int) {
        if (downloadJob?.isActive == true) return
        operationStatus = "正在准备模型…"
        downloadJob = scope.launch(start = CoroutineStart.LAZY) {
            render()
            try {
                val operation = currentCoroutineContext()
                withContext(Dispatchers.IO) {
                    LocalAsrModels.install(this@VoiceSettingsActivity, family, variant, source,
                        isActive = { operation.isActive },
                        onCallCreated = { downloadCall = it }, onStage = ::stageProgress)
                }
                com.osfans.trime.ime.voice.VoiceSettings.setEnabled(true)
                MaterialAlertDialogBuilder(this@VoiceSettingsActivity)
                    .setTitle("模型已就绪")
                    .setMessage("${AsrVendors.labelOf(family)} 已完成解压、校验和安装，并已设为当前语音引擎。\n现在可回到输入框长按空格说话。首次识别需要加载模型。")
                    .setPositiveButton(android.R.string.ok, null).show()
            } catch (e: CancellationException) {
                Toast.makeText(this@VoiceSettingsActivity, "已取消，下载进度已保留", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                operationStatus = "模型安装失败：${t.message ?: t.javaClass.simpleName}"
                MaterialAlertDialogBuilder(this@VoiceSettingsActivity).setTitle("模型安装未完成")
                    .setMessage(t.message ?: t.javaClass.simpleName).setPositiveButton(android.R.string.ok, null).show()
            } finally {
                downloadCall = null; downloadJob = null
                if (!isFinishing && !isDestroyed) render()
            }
        }
        downloadJob?.start()
    }

    private fun startImport(uri: Uri) {
        if (downloadJob?.isActive == true) return
        operationStatus = "正在读取模型包…"
        downloadJob = scope.launch(start = CoroutineStart.LAZY) {
            render()
            val operation = currentCoroutineContext()
            try {
                val detected = withContext(Dispatchers.IO) {
                    val tmp = File(noBackupFilesDir, "asr_import_${System.currentTimeMillis()}.zip")
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            tmp.outputStream().use { output ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    if (!operation.isActive) throw CancellationException()
                                    val size = input.read(buffer); if (size <= 0) break
                                    output.write(buffer, 0, size)
                                }
                            }
                        } ?: error("无法读取所选模型包")
                        LocalAsrModels.importZip(this@VoiceSettingsActivity, tmp, queryFileName(uri) ?: tmp.name,
                            isActive = { operation.isActive }, onStage = ::stageProgress)
                    } finally { tmp.delete() }
                }
                com.osfans.trime.ime.voice.VoiceSettings.setEnabled(true)
                MaterialAlertDialogBuilder(this@VoiceSettingsActivity).setTitle("模型已就绪")
                    .setMessage("${AsrVendors.labelOf(detected.first)} 已导入并设为当前语音引擎。长按空格即可使用。")
                    .setPositiveButton(android.R.string.ok, null).show()
            } catch (e: CancellationException) {
                Toast.makeText(this@VoiceSettingsActivity, "导入已取消", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                MaterialAlertDialogBuilder(this@VoiceSettingsActivity).setTitle("模型导入未完成")
                    .setMessage(t.message ?: t.javaClass.simpleName).setPositiveButton(android.R.string.ok, null).show()
            } finally {
                downloadJob = null
                if (!isFinishing && !isDestroyed) render()
            }
        }
        downloadJob?.start()
    }

    private fun queryFileName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    // ---------- 输入助手 ----------

    private fun textRow(label: String, value: String, setter: (String) -> Unit) {
        button("$label：${value.ifBlank { "未设置" }}") {
            val input = EditText(this).apply { setText(value); setSelection(text.length) }
            val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
            MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setView(wrap)
                .setPositiveButton("保存") { _, _ -> setter(input.text.toString().trim()); render() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun secretRow(label: String, value: String, setter: (String) -> Unit) =
        textRow(label, if (value.isBlank()) "" else "••••••（已设置）", setter)

    private fun numberRow(label: String, value: Int, setter: (Int) -> Unit) {
        button("$label：$value") {
            val input = EditText(this).apply { setText(value.toString()); setSelection(text.length) }
            val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
            MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setView(wrap)
                .setPositiveButton("保存") { _, _ ->
                    input.text.toString().trim().toIntOrNull()?.let(setter)
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun choiceRow(label: String, options: List<Pair<String, String>>, current: String, setter: (String) -> Unit) {
        val shown = options.firstOrNull { it.first == current }?.second ?: current
        button("$label：$shown") {
            MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setItems(options.map { it.second }.toTypedArray()) { _, i -> setter(options[i].first); render() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
