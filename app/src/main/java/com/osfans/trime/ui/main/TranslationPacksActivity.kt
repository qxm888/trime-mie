/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.ime.ai.*
import kotlinx.coroutines.*
import java.util.Locale

class TranslationPacksActivity : PersonalizationActivity() {
    private fun name(tag: String) = Locale.forLanguageTag(tag).getDisplayName(Locale.SIMPLIFIED_CHINESE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AiTextSettings.init(applicationContext)
        page("离线翻译语言包"); render()
    }
    private fun render() {
        body.removeAllViews()
        if (!OfflineTranslationService.supported()) {
            note("离线翻译需要 Android 9 以上的 64 位 ARM 设备。此设备可使用在线翻译。"); return
        }
        val route = OfflineTranslationCatalog.route(OfflineTranslationCatalog.models(this), AiTextSettings.source(), AiTextSettings.target())
        note("使用 Mozilla / Foxlet 模型，不上传文本。先在 AI 润色与翻译中选择原文与目标语言。部分方向会通过英语中转，需要两个语言包。")
        if (route.isEmpty()) note("当前方向无需翻译或暂不支持，请返回指定原文与目标语言。")
        else {
            note("当前方向：${name(AiTextSettings.source())} → ${name(AiTextSettings.target())}，约 ${route.sumOf { it.bytes } / 1024 / 1024} MB。")
            button("下载当前方向语言包") { download(route) }
        }
        note("已安装语言包：")
        lifecycleScope.launch {
            try {
                val packs = OfflineTranslationService.installed(this@TranslationPacksActivity)
                if (packs.isEmpty()) note("尚未安装")
                packs.forEach { pack -> button("${name(pack.source)} → ${name(pack.target)} · ${pack.bytes / 1024 / 1024} MB · 删除") {
                    MaterialAlertDialogBuilder(this@TranslationPacksActivity).setTitle("删除语言包？")
                        .setMessage("${name(pack.source)} → ${name(pack.target)}")
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            lifecycleScope.launch {
                                try { OfflineTranslationService.delete(this@TranslationPacksActivity, pack); render() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { toast("删除失败") }
                            }
                        }.show()
                } }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { note("读取语言包失败，请重试或检查存储空间。") }
        }
    }
    private fun download(route: List<TranslationPack>) {
        if (!OfflineTranslationService.supported()) { toast("此设备不支持离线翻译"); return }
        val progress = MaterialAlertDialogBuilder(this).setTitle("下载离线语言包")
            .setMessage("准备下载…").setNegativeButton("取消", null).create()
        val job = lifecycleScope.launch {
            try {
                var last = 0L
                OfflineTranslationService.download(this@TranslationPacksActivity, route) { index, count, state ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last >= 200 || state.downloadedBytes == state.totalBytes) {
                        last = now
                        runOnUiThread {
                            if (progress.isShowing) progress.setMessage("语言包 $index/$count\n${state.downloadedBytes / 1024 / 1024} / ${state.totalBytes.coerceAtLeast(0) / 1024 / 1024} MB")
                        }
                    }
                }
                progress.setOnDismissListener(null); progress.dismiss(); toast("语言包已安装"); render()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                progress.setOnDismissListener(null); progress.dismiss(); toast("下载失败，请检查网络和存储空间后重试")
            }
        }
        progress.setOnDismissListener { job.cancel() }
        progress.show()
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
