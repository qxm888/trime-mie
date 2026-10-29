/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.os.Bundle
import android.text.InputType
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.sync.ClipboardSync
import com.osfans.trime.data.sync.LocalNetworkAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ClipboardSyncActivity : PersonalizationActivity() {
    private lateinit var url: TextInputEditText
    private lateinit var user: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var http: MaterialSwitch
    private lateinit var enabled: MaterialSwitch
    private var pendingAction: String? = null
    private var awaitingPermission = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingAction; pendingAction = null; awaitingPermission = false
        com.osfans.trime.util.DiagnosticLog.event("clipboard_sync","lan_permission_result","granted=$granted api=${android.os.Build.VERSION.SDK_INT}")
        if (granted) { if (action != null) execute(action) else ClipboardSync.retry() }
        else Toast.makeText(this,"未获得局域网权限；可在应用权限中允许附近设备后重试",Toast.LENGTH_LONG).show()
    }
    private val filePicker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) lifecycleScope.launch {
            runCatching { ClipboardSync.uploadFile(uri) }
                .onSuccess { Toast.makeText(this@ClipboardSyncActivity,it,1).show() }
                .onFailure { Toast.makeText(this@ClipboardSyncActivity,it.message ?: "上传失败",1).show() }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); page("剪贴板同步")
        pendingAction = savedInstanceState?.getString("pendingAction")
        awaitingPermission = pendingAction != null
        val c=ClipboardSync.config()
        note("对接开源 SyncClipboard v3.1.1+ 服务器、桌面客户端内置服务器或 WebDAV 文件夹。支持文本、图片、单文件双向同步，默认关闭；输入法服务运行期间每5秒检查远端。文本上限256KB，单附件32MB，更长文本或多文件可保存为文件/ZIP同步。")
        fun field(label: String,value: String,secret: Boolean=false): TextInputEditText {
            val input=TextInputEditText(this).apply { setSingleLine(); setText(value); if(secret) inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            body.addView(TextInputLayout(this).apply { hint=label; addView(input) }); return input
        }
        url=field("服务器或 WebDAV 文件夹地址",savedInstanceState?.getString("url") ?: c.url)
        user=field("用户名",savedInstanceState?.getString("user") ?: c.user)
        password=field("密码",savedInstanceState?.getString("password") ?: c.password,true)
        http=MaterialSwitch(this).apply { text="允许 HTTP 明文传输（仅可信网络）"; isChecked=savedInstanceState?.getBoolean("http",c.allowHttp) ?: c.allowHttp }; body.addView(http)
        enabled=MaterialSwitch(this).apply { text="自动同步剪贴板"; isChecked=savedInstanceState?.getBoolean("enabled",c.enabled) ?: c.enabled }; body.addView(enabled)
        val status=TextView(this); body.addView(status)
        if (LocalNetworkAccess.required(this)) {
            note("Android 17 连接局域网服务器需要局域网访问权限。保存或同步时会申请；拒绝后也可在此重新授权。")
            button("局域网访问权限") {
                if (LocalNetworkAccess.granted(this)) Toast.makeText(this,"局域网权限已允许",0).show()
                else permission.launch(LocalNetworkAccess.PERMISSION)
            }
            button("打开应用权限设置") { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName"))) }
        }
        button("保存设置并重试连接") { runAction("save") }
        button("上传当前剪贴板") { runAction("upload") }
        button("接收远端剪贴板") { runAction("download") }
        button("选择图片 / 文件上传") { runAction("file") }
        note("有共享存储权限时，接收附件保存在 rime/mie/剪贴板附件；否则暂存应用目录。写入系统剪贴板后，可到支持粘贴图片或文件的应用中粘贴。")
        button("项目与服务器配置说明") { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/Jeric-X/SyncClipboard"))) }
        lifecycleScope.launch { ClipboardSync.state.collect { status.text=it } }
    }
    private fun config()=ClipboardSync.Config(enabled.isChecked,url.text.toString(),user.text.toString(),password.text.toString(),http.isChecked)
    private fun runAction(action: String) {
        if (pendingAction != null) return
        val c=config()
        if (runCatching { if(c.url.isNotBlank() || c.enabled) ClipboardSync.validate(c) }.onFailure { url.error=it.message }.isFailure) return
        pendingAction=action
        lifecycleScope.launch {
            val needs=if(action=="save" && !c.enabled) false else withContext(Dispatchers.IO) { LocalNetworkAccess.needsPermission(this@ClipboardSyncActivity,c.url) }
            if(needs) { awaitingPermission=true;permission.launch(LocalNetworkAccess.PERMISSION) }
            else { pendingAction=null; execute(action) }
        }
    }
    private fun execute(action: String) {
        if (runCatching { ClipboardSync.save(config()) }.onFailure { url.error=it.message }.isFailure) return
        url.error=null
        if(action=="file") { filePicker.launch(arrayOf("*/*")); return }
        if(action=="save") { Toast.makeText(this,"已保存，正在重新连接",0).show(); return }
        lifecycleScope.launch {
            runCatching { if(action=="upload") ClipboardSync.uploadNow() else ClipboardSync.downloadNow() }
                .onSuccess { Toast.makeText(this@ClipboardSyncActivity,it,1).show() }
                .onFailure { Toast.makeText(this@ClipboardSyncActivity,it.message ?: "同步失败",1).show() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingAction",pendingAction.takeIf { awaitingPermission })
        val c=config();outState.putString("url",c.url);outState.putString("user",c.user);outState.putString("password",c.password)
        outState.putBoolean("http",c.allowHttp);outState.putBoolean("enabled",c.enabled)
        super.onSaveInstanceState(outState)
    }
}
