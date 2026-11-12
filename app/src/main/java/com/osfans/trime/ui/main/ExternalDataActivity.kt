/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.Manifest
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
    private lateinit var listContainer: LinearLayout
    private var selectedPath: String = ""
    private val legacyPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { renderPermission() }
    private val pickDirectory = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val p = treeUriToPath(uri)
            if (p.isNullOrBlank()) {
                Toast.makeText(this, "无法解析所选目录的路径，请手动输入", Toast.LENGTH_LONG).show()
            } else {
                // 选完直接进列表并选中，用户点下方「切换/保存」即可生效。
                DirectExternalStorage.addHistory(listOf(p))
                selectedPath = p
                appendToInput(p)
                renderList()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page("直接使用外部目录")
        note("方案、主题、词库直接存放在此目录，可使用文件管理器手动修改，修改后部署即可生效。")
        path = TextInputEditText(this).apply {
            setSingleLine(false)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP
            setText(savedInstanceState?.getString("path").orEmpty())
        }
        body.addView(TextInputLayout(this).apply { hint = "添加目录（每行一个）"; addView(path) })
        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        actionRow.addView(makeButton("添加到列表") { addToList() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        actionRow.addView(makeButton("文件管理器选择") { pickDirectory.launch(null) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        body.addView(actionRow)
        body.addView(TextView(this).apply {
            text = "已保存目录（点击选择，再点下方按钮切换）"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@ExternalDataActivity, com.osfans.trime.R.color.framework_text_secondary))
            setPadding(0, dp(16), 0, dp(8))
        })
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(listContainer)
        note("切换时补齐旧目录中已有的数据和素材；目标目录已有同名文件优先保留，旧目录不删除。咩版与咩2可共用Rime文件；咩版自有素材与备份统一存放在该目录的 .mie 子目录（键盘背景 / 字体 / 百度皮肤 / 统计 / 备份）。")
        status = android.widget.TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        body.addView(status)
        button("授予文件访问权限") { requestAccess() }
        button("切换并使用此目录") { activate() }
        button("保存目录（不迁移）") { savePath() }
        // 初始化：把当前生效地址并入历史，并作为默认选中项。
        val current = AppPrefs.defaultInstance().profile.directExternalPath.getValue()
        selectedPath = savedInstanceState?.getString("selected") ?: current
        DirectExternalStorage.addHistory(listOf(selectedPath))
        renderPermission()
        renderList()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("path", path.text?.toString())
        outState.putString("selected", selectedPath)
        super.onSaveInstanceState(outState)
    }
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

    /** 与 [PersonalizationActivity.button] 同样式、但不自动挂到 body，便于并排摆放。 */
    private fun makeButton(text: String, action: () -> Unit): com.google.android.material.button.MaterialButton =
        com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            this.text = text
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@ExternalDataActivity, com.osfans.trime.R.color.framework_text_primary))
            cornerRadius = dp(4)
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(56)
            insetTop = 0; insetBottom = 0
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(ContextCompat.getColor(this@ExternalDataActivity, com.osfans.trime.R.color.settings_surface))
            setOnClickListener { action() }
        }

    /** 把 SAF 目录选择器返回的 tree URI 解析成真实文件系统路径（primay 存储可直接映射）。 */
    private fun treeUriToPath(uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":", limit = 2)
        if (split.size < 2) return@runCatching null
        val type = split[0]
        val id = split[1]
        if (type == "primary") java.io.File(Environment.getExternalStorageDirectory(), id).absolutePath
        else "/storage/$type/$id"
    }.getOrNull()

    private fun appendToInput(p: String) {
        val lines = path.text?.toString().orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val merged = (lines + p).distinct().joinToString("\n")
        path.setText(merged)
        path.setSelection(merged.length)
    }

    /** 把输入框里的地址（每行一个）并入历史列表，并选中第一个。 */
    private fun addToList() {
        val lines = path.text?.toString().orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) { Toast.makeText(this, "请先输入目录地址", Toast.LENGTH_SHORT).show(); return }
        for (line in lines) {
            runCatching { DirectExternalStorage.validate(line) }.onFailure {
                path.error = it.message ?: "地址无效：$line"; return
            }
        }
        DirectExternalStorage.addHistory(lines)
        path.setText("")
        path.error = null
        selectedPath = lines.first()
        renderList()
    }

    private fun removePath(p: String, isCurrent: Boolean) {
        if (isCurrent) {
            Toast.makeText(this, "不能删除当前正在使用的目录，请先切换到其它目录", Toast.LENGTH_LONG).show()
            return
        }
        DirectExternalStorage.removeHistory(p)
        if (selectedPath == p) selectedPath = AppPrefs.defaultInstance().profile.directExternalPath.getValue()
        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val current = AppPrefs.defaultInstance().profile.directExternalPath.getValue()
        val paths = DirectExternalStorage.history()
        if (paths.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "暂无已保存的目录"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ExternalDataActivity, com.osfans.trime.R.color.framework_text_secondary))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        }
        for (p in paths) {
            val selected = p == selectedPath
            val isCurrent = p == current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(ContextCompat.getColor(this@ExternalDataActivity, if (selected) com.osfans.trime.R.color.m3_primary_container else com.osfans.trime.R.color.settings_surface))
                }
            }
            val label = TextView(this).apply {
                text = p + if (isCurrent) "（当前）" else ""
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ExternalDataActivity, if (selected) com.osfans.trime.R.color.m3_on_primary_container else com.osfans.trime.R.color.framework_text_primary))
                setPadding(dp(4), dp(12), dp(4), dp(12))
            }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val del = TextView(this).apply {
                text = "删除"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@ExternalDataActivity, com.osfans.trime.R.color.framework_text_secondary))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener { removePath(p, isCurrent) }
            }
            row.addView(del, LinearLayout.LayoutParams(-2, -2))
            row.setOnClickListener { selectedPath = p; renderList() }
            listContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
    }

    private fun currentTarget(): String {
        if (selectedPath.isBlank()) { Toast.makeText(this, "请先添加并选择一个目录", Toast.LENGTH_SHORT).show(); return "" }
        return selectedPath
    }

    private fun activate() {
        if (busy) return
        if (!DirectExternalStorage.hasPermission(this)) { requestAccess(); return }
        val target = currentTarget()
        if (target.isEmpty()) return
        runCatching { DirectExternalStorage.validate(target) }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); return }
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
                DirectExternalStorage.addHistory(listOf(target))
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

    /** 只保存目录地址、不迁移文件，保存后由 Rime 重启重新部署。 */
    private fun savePath() {
        if (busy) return
        if (!DirectExternalStorage.hasPermission(this)) { requestAccess(); return }
        val target = currentTarget()
        if (target.isEmpty()) return
        runCatching { DirectExternalStorage.validate(target) }.onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); return }
        busy = true
        val progress = MaterialAlertDialogBuilder(this).setTitle("保存目录").setMessage("正在保存并重新部署，请稍候…").setCancelable(false).show()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                RimeDaemon.changeStorage { DirectExternalStorage.savePath(this@ExternalDataActivity, target) }
            } }
            busy = false
            progress.dismiss()
            if (!isActive || isFinishing || isDestroyed) return@launch
            result.onSuccess {
                DirectExternalStorage.addHistory(listOf(target))
                ThemeManager.requestCustomizationRefresh()
                Toast.makeText(this@ExternalDataActivity, "已保存目录，正在重新部署", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure { error ->
                MaterialAlertDialogBuilder(this@ExternalDataActivity).setTitle("未能保存目录")
                    .setMessage(error.message).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }
}
