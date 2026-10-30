/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.core.data.DirectExternalStorage
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive

/** Explicit setup before switching a running input method to a public Rime folder. */
class ExternalDataActivity : PersonalizationActivity() {
    private lateinit var path: TextInputEditText
    private var busy = false
    private lateinit var status: android.widget.TextView
    private val legacyPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { renderPermission() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("直接使用外部目录")
        note("方案、主题、词库直接存放在此目录，可使用文件管理器手动修改，修改后部署即可生效。")
        path = TextInputEditText(this).apply {
            setSingleLine(true)
            setText(savedInstanceState?.getString("path") ?: AppPrefs.defaultInstance().profile.directExternalPath.getValue())
        }
        body.addView(TextInputLayout(this).apply { hint = "外部数据目录"; addView(path) })
        note("切换时补齐旧目录中已有的数据和素材；目标目录已有同名文件优先保留，旧目录不删除。咩版与咩2可共用Rime文件；咩版自有素材与备份统一存放在该目录的 .mie 子目录（键盘背景 / 字体 / 百度皮肤 / 统计 / 备份）。")
        status = android.widget.TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        body.addView(status)
        button("授予文件访问权限") { requestAccess() }
        button("切换并使用此目录") { activate() }
        renderPermission()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("path", path.text?.toString()); super.onSaveInstanceState(outState) }
    override fun onResume() { super.onResume(); if (::path.isInitialized) renderPermission() }
    private fun renderPermission() {
        if (::status.isInitialized) status.text = if (DirectExternalStorage.hasPermission(this)) "文件访问权限：已开启" else "文件访问权限：未开启"
    }
    private fun requestAccess() {
        if (busy) return
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else legacyPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    private fun activate() {
        if (busy) return
        if (!DirectExternalStorage.hasPermission(this)) { requestAccess(); return }
        val target = path.text?.toString().orEmpty()
        runCatching { DirectExternalStorage.validate(target) }.onFailure { path.error = it.message; return }
        busy = true
        val progress = MaterialAlertDialogBuilder(this).setTitle("切换数据目录").setMessage("正在保留并迁移配置，请稍候…").setCancelable(false).show()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                RimeDaemon.changeStorage { DirectExternalStorage.activate(this@ExternalDataActivity, target) }
            } }
            busy = false
            progress.dismiss()
            if (!isActive || isFinishing || isDestroyed) return@launch
            result.onSuccess { copied ->
                ThemeManager.requestCustomizationRefresh()
                Toast.makeText(this@ExternalDataActivity, "已使用外部目录，补齐 $copied 个文件；已有文件保留", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { error ->
                MaterialAlertDialogBuilder(this@ExternalDataActivity).setTitle("未能切换目录")
                    .setMessage(error.message).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }
}
