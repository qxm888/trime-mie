/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.text.InputType
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.sync.ClipboardSync
import kotlinx.coroutines.launch

class ClipboardSyncActivity : PersonalizationActivity() {
    private val filePicker=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) lifecycleScope.launch {
            runCatching { ClipboardSync.uploadFile(uri) }
                .onSuccess { Toast.makeText(this@ClipboardSyncActivity,it,1).show() }
                .onFailure { Toast.makeText(this@ClipboardSyncActivity,it.message ?: "上传失败",1).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); page("剪贴板同步")
        val c=ClipboardSync.config()
        note("对接开源 SyncClipboard v3.1.1+ 服务器、桌面客户端内置服务器或 WebDAV 文件夹。支持文本、图片、单文件双向同步，默认关闭；输入法服务运行期间每5秒检查远端。文本上限256KB，单附件32MB，更长文本或多文件可保存为文件/ZIP同步。")
        fun field(label: String,value: String,secret: Boolean=false): TextInputEditText {
            val input=TextInputEditText(this).apply { setSingleLine(); setText(value); if(secret) inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            body.addView(TextInputLayout(this).apply { hint=label; addView(input) }); return input
        }
        val url=field("服务器或 WebDAV 文件夹地址",c.url)
        val user=field("用户名",c.user)
        val password=field("密码",c.password,true)
        val http=MaterialSwitch(this).apply { text="允许 HTTP 明文传输（仅可信网络）"; isChecked=c.allowHttp }; body.addView(http)
        val enabled=MaterialSwitch(this).apply { text="自动同步剪贴板"; isChecked=c.enabled }; body.addView(enabled)
        val status=TextView(this); body.addView(status)
        fun save(): Boolean = runCatching { ClipboardSync.save(ClipboardSync.Config(enabled.isChecked,url.text.toString(),user.text.toString(),password.text.toString(),http.isChecked)) }
            .onFailure { url.error=it.message }.isSuccess
        button("保存设置") { if(save()) Toast.makeText(this,"已保存",0).show() }
        fun transfer(upload: Boolean) { if(!save()) return; lifecycleScope.launch {
            runCatching { if(upload) ClipboardSync.uploadNow() else ClipboardSync.downloadNow() }
                .onSuccess { Toast.makeText(this@ClipboardSyncActivity,it,1).show() }
                .onFailure { Toast.makeText(this@ClipboardSyncActivity,it.message ?: "同步失败",1).show() }
        } }
        button("上传当前剪贴板") { transfer(true) }; button("接收远端剪贴板") { transfer(false) }
        button("选择图片 / 文件上传") { if(save()) filePicker.launch(arrayOf("*/*")) }
        note("有共享存储权限时，接收附件保存在 rime/mie/剪贴板附件；否则暂存应用目录。写入系统剪贴板后，可到支持粘贴图片或文件的应用中粘贴。")
        button("项目与服务器配置说明") { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/Jeric-X/SyncClipboard"))) }
        lifecycleScope.launch { ClipboardSync.state.collect { status.text=it } }
    }
}
