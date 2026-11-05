/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.Const
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.formatDateTime
import kotlinx.coroutines.launch

class AboutFragment : PaddingPreferenceFragment() {

    private lateinit var versionPref: Preference

    @SuppressLint("UseKtx")
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            versionPref = Preference(requireContext()).apply {
                isIconSpaceReserved = false
                setTitle(R.string.current_version)
                summary = Const.VERSION_NAME
                setOnPreferenceClickListener {
                    onVersionClicked()
                    true
                }
            }
            addPreference(versionPref)
            // 咩版基于上游 osfans/trime 的版本：3.3.13-mie.17 → 上游基线 3.3.13。
            addPreference(R.string.upstream_trime_version, upstreamBaseVersion()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Const.UPSTREAM_TRIME_URL)))
            }
            addPreference(R.string.librime_version, BuildConfig.LIBRIME_VERSION) {
                val hash = getCommitFromVersionName(BuildConfig.LIBRIME_VERSION)
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${Const.LIBRIME_URL}/commit/$hash"),
                    ),
                )
            }
            addPreference(R.string.opencc_version, BuildConfig.OPENCC_VERSION) {
                val hash = getCommitFromVersionName(BuildConfig.OPENCC_VERSION)
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("${Const.OPENCC_URL}/commit/$hash"),
                    ),
                )
            }
            addPreference(
                Preference(requireContext()).apply {
                    isIconSpaceReserved = false
                    isCopyingEnabled = true
                    setTitle(R.string.build_info)
                    summary = requireContext().getString(
                        R.string.build_info_format,
                        BuildConfig.BUILDER,
                        BuildConfig.BUILD_COMMIT_HASH,
                        formatDateTime(BuildConfig.BUILD_TIMESTAMP),
                    )
                },
            )
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.privacy_policy) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.PRIVACY_POLICY_URL),
                        ),
                    )
                }
                addPreference(R.string.source_code, R.string.git_repo) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(BuildConfig.BUILD_GIT_REPO),
                        ),
                    )
                }
                addPreference(R.string.license, Const.LICENSE_SPDX_ID) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.LICENSE_URL),
                        ),
                    )
                }
                addPreference(
                    R.string.open_source_licenses,
                    R.string.licenses_of_third_party_libraries,
                ) {
                    findNavController().navigate(NavigationRoute.License)
                }
            }
            addCategory("") {
                isIconSpaceReserved = false
                addPreference(R.string.qq_group_1, Const.QQ_GROUP_1_NUM) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.QQ_GROUP_1_URL),
                        ),
                    )
                }
                addPreference(R.string.qq_group_2, Const.QQ_GROUP_2_NUM) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.QQ_GROUP_2_URL),
                        ),
                    )
                }
                addPreference(R.string.rime_qq_group, Const.RIME_QQ_GROUP_NUM) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.RIME_QQ_GROUP_URL),
                        ),
                    )
                }
                addPreference(R.string.telegram, Const.TELEGRAM_NAME) {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(Const.TELEGRAM_URL),
                        ),
                    )
                }
            }
        }
    }

    /** 点"当前版本"：查更新 → 发现新版后再次点击开始下载 → 下载完成拉起系统安装。 */
    private fun onVersionClicked() {
        val pref = versionPref
        val busy = "正在检查更新…"
        if (pref.summary == busy) return
        pref.summary = busy
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            when (val result = com.osfans.trime.data.update.AppUpdater.checkLatest()) {
                is com.osfans.trime.data.update.AppUpdater.CheckResult.UpToDate -> {
                    pref.summary = "已是最新版本（${result.versionName}）"
                }
                is com.osfans.trime.data.update.AppUpdater.CheckResult.Failed -> {
                    pref.summary = Const.VERSION_NAME
                    Toast.makeText(context, "检查更新失败：${result.message}", Toast.LENGTH_LONG).show()
                }
                is com.osfans.trime.data.update.AppUpdater.CheckResult.UpdateAvailable -> {
                    val release = result.release
                    MaterialAlertDialogBuilder(context)
                        .setTitle("发现新版本 ${release.tag}")
                        .setMessage(release.notes.takeIf { it.isNotBlank() }?.take(1200)
                            ?: "新版本 ${release.versionName} 可用。")
                        .setPositiveButton("下载并安装") { _, _ -> startDownload(pref, release) }
                        .setNegativeButton("以后再说", null)
                        .show()
                    pref.summary = "发现新版本 ${release.versionName} · 点击更新"
                }
            }
        }
    }

    private fun startDownload(
        pref: Preference,
        release: com.osfans.trime.data.update.AppUpdater.Release,
    ) {
        val context = requireContext()
        pref.summary = "下载中 0%"
        viewLifecycleOwner.lifecycleScope.launch {
            com.osfans.trime.data.update.AppUpdater.downloadApk(context, release) { progress ->
                lifecycleScope.launch { pref.summary = "下载中 $progress%" }
            }.fold(
                onSuccess = { apk ->
                    pref.summary = "下载完成 · 点击安装"
                    com.osfans.trime.data.update.AppUpdater.install(context, apk)
                },
                onFailure = {
                    pref.summary = "发现新版本 ${release.versionName} · 点击重试"
                    Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    companion object {
        private val DASH_G_PATTERN = Regex("^(.*-g)([0-9a-f]+)(.*)$")
        private val COMMON_PATTERN = Regex("^([^-]*)(-.*)$")

        private fun getCommitFromVersionName(versionCode: String): String {
            val dashG = DASH_G_PATTERN.find(versionCode)?.groupValues?.get(2)
            val common = COMMON_PATTERN.find(versionCode)?.groupValues?.get(1)
            return dashG ?: common ?: versionCode
        }

        /** 3.3.13-mie.17 → 3.3.13；已经没有 -mie 后缀时原样返回。 */
        private fun upstreamBaseVersion(): String =
            BuildConfig.BUILD_VERSION_NAME.substringBefore("-mie", BuildConfig.BUILD_VERSION_NAME)
    }
}
