/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.osfans.trime.core.data.MieUserDirectory
import com.osfans.trime.data.backup.FullSettingsBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FullBackupActivity : PersonalizationActivity() {
    private var busy=false
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) confirmImport { contentResolver.openInputStream(uri) ?: error("无法读取备份") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); page("整包备份与恢复")
        note("备份应用设置、主题按键绑定、自定义键盘与功能、字体、图片/视频背景、Rime 配置与用户词典、统计和候选记录。存放在用户目录 rime/mie/备份/，卸载后可保留。剪贴板历史请使用原有历史导出功能。")
        runCatching { MieUserDirectory.directory(this) }.onSuccess { note("目录：$it/备份") }.onFailure {
            note(it.message.orEmpty()); button("授权用户目录") { startActivity(android.content.Intent(this,ExternalDataActivity::class.java)) }
        }
        val previous=getSharedPreferences("settings_restore_result",MODE_PRIVATE).getString("message","").orEmpty()
        if(previous.isNotBlank()) note(previous)
        val models=MaterialSwitch(this).apply { text="备份包含本地语音模型（可能较大）" }; body.addView(models)
        val secrets=MaterialSwitch(this).apply { text="备份包含语音密钥与同步密码" }; body.addView(secrets)
        note("备份为未加密 ZIP；默认不包含服务密钥。导入会覆盖对应设置和同名资源，保留本机目录权限；剪贴板自动同步需重新开启。")
        button("备份到 rime/mie") {
            if(!busy) { busy=true; lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { FullSettingsBackup.create(this@FullBackupActivity,models.isChecked,secrets.isChecked) } }
                    .onSuccess { MaterialAlertDialogBuilder(this@FullBackupActivity).setTitle("备份完成").setMessage(it.absolutePath).setPositiveButton("确定",null).show() }
                    .onFailure { Toast.makeText(this@FullBackupActivity,it.message ?: "备份失败",1).show() }
                busy=false
            } }
        }
        button("从 rime/mie 选择备份恢复") {
            val files=runCatching { File(MieUserDirectory.directory(this),"备份").listFiles().orEmpty().filter { it.extension=="zip" }.sortedByDescending { it.lastModified() } }.getOrElse { emptyList() }
            if(files.isEmpty()) Toast.makeText(this,"没有找到备份，可从文件选择器导入",1).show()
            else MaterialAlertDialogBuilder(this).setTitle("选择备份").setItems(files.map { it.name }.toTypedArray()) { _,i -> confirmImport { files[i].inputStream() } }.show()
        }
        button("从文件导入恢复") { if(!busy) picker.launch(arrayOf("application/zip","application/octet-stream","*/*")) }
    }
    private fun confirmImport(input: ()->java.io.InputStream) {
        if(busy) return
        MaterialAlertDialogBuilder(this).setTitle("恢复整包设置")
            .setMessage("校验通过后将自动重启应用，覆盖备份中对应的设置和同名资源。其他设备的目录权限不会带入。")
            .setPositiveButton("恢复并重启") { _,_ -> busy=true; lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { FullSettingsBackup.prepare(this@FullBackupActivity,input()) } }
                    .onSuccess {
                        withContext(Dispatchers.IO) {
                            com.osfans.trime.data.keymap.CustomKeyboards.flush()
                            com.osfans.trime.data.keymap.ToolbarOverrides.flush()
                            com.osfans.trime.data.keymap.KeyBindingOverrides.flush()
                            com.osfans.trime.data.stats.InputStats.flush()
                            com.osfans.trime.daemon.RimeDaemon.stopRime()
                        }
                        startActivity(android.content.Intent(this@FullBackupActivity,RestartAfterRestoreActivity::class.java).putExtra("pid",android.os.Process.myPid()))
                    }
                    .onFailure { Toast.makeText(this@FullBackupActivity,it.message ?: "备份校验失败，当前设置未改变",1).show() }
                busy=false
            } }.setNegativeButton("取消",null).show()
    }
}
