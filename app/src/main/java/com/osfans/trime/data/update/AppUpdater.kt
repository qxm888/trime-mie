/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：查询 GitHub 最新 Release，比较版本后下载 APK 并拉起安装。
 *
 * 查询走 api.github.com；APK 下载先直连，失败后按镜像列表重试
 * （境内直连 GitHub 资产常为 0 B/s，镜像仅加速下载、不改变内容）。
 */
object AppUpdater {
    private const val REPO = "qxm888/trime-mie"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private val DOWNLOAD_MIRRORS = arrayOf(
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
    )

    data class Release(val tag: String, val versionName: String, val apkUrl: String, val notes: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 提取版本数字段："V3.3.8-2"/"v3.3.8" → [3,3,8(,2)]。 */
    fun parseVersion(value: String): List<Int> =
        Regex("\\d+").findAll(value.trim().trimStart('v', 'V')).take(4).map { it.value.toInt() }.toList()

    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    sealed interface CheckResult {
        data class UpToDate(val versionName: String) : CheckResult
        data class UpdateAvailable(val release: Release) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    suspend fun checkLatest(): CheckResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(API_LATEST)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "trime-mie")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("GitHub 返回 ${response.code}")
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val tag = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.getString("name")
                    if (name.endsWith(".apk") && name.startsWith("trime-mie-")) {
                        apkUrl = asset.getString("browser_download_url")
                        if (name.contains("arm64")) break
                    }
                }
                Release(tag, tag.removePrefix("v"), apkUrl, json.optString("body"))
            }
        }.fold(
            onSuccess = { release ->
                val current = com.osfans.trime.util.Const.VERSION_NAME
                if (isNewer(release.versionName, current)) CheckResult.UpdateAvailable(release)
                else CheckResult.UpToDate(release.versionName)
            },
            onFailure = { CheckResult.Failed(it.message ?: "网络请求失败") },
        )
    }

    /** 下载到应用缓存；先直连，失败走镜像。返回可安装的 APK 文件。 */
    suspend fun downloadApk(context: Context, release: Release, onProgress: (Int) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            val tag = release.tag
            val target = File(context.cacheDir, "update").apply { mkdirs() }
                .resolve("trime-mie-${release.versionName}.apk")
            val urls = buildList {
                if (release.apkUrl.isNotEmpty()) add(release.apkUrl)
                DOWNLOAD_MIRRORS.forEach { mirror -> add(mirror + release.apkUrl) }
            }
            var lastError: Throwable? = null
            for (url in urls) {
                runCatching {
                    val request = Request.Builder().url(url).header("User-Agent", "trime-mie").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        val body = response.body ?: error("空响应")
                        val total = body.contentLength()
                        target.outputStream().use { out ->
                            val input = body.byteStream()
                            val buffer = ByteArray(64 * 1024)
                            var read = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                read += n
                                if (total > 0) onProgress(((read * 100 / total).toInt()).coerceIn(0, 100))
                            }
                        }
                    }
                    if (target.length() < 1_000_000L) error("下载不完整（${target.length()} 字节）")
                }.onSuccess {
                    return@withContext Result.success(target)
                }.onFailure { lastError = it }
            }
            Result.failure(lastError ?: IllegalStateException("下载失败"))
        }

    /** 拉起系统安装界面（同签名可直接覆盖升级）。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
