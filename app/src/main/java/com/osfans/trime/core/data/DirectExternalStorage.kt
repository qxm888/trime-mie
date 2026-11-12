/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.osfans.trime.data.keymap.CustomKeyboards
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.FontOverrides
import java.io.File

/** Native librime needs a real filesystem path, not a SAF document URI. */
object DirectExternalStorage {
    val defaultPath: String get() = File(Environment.getExternalStorageDirectory(), "rime").absolutePath
    private val prefs get() = AppPrefs.defaultInstance().profile
    fun enabled(): Boolean = !com.osfans.trime.TrimeApplication.getInstance().isDirectBootMode && prefs.dataStorageMode.getValue() == DataStorageMode.DIRECT_EXTERNAL
    fun hasPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val directory: File get() = File(prefs.directExternalPath.getValue())

    /** 历史保存过的外部目录地址（去重、非空、按保存顺序）。 */
    fun history(): List<String> =
        prefs.directExternalPathHistory.getValue()
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun saveHistory(paths: List<String>) {
        prefs.directExternalPathHistory.setValue(paths.joinToString("\n"))
    }

    /** 把若干地址并入历史列表（去重、保留原有顺序，新增的追加在尾部）。 */
    fun addHistory(paths: List<String>) {
        val incoming = paths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (incoming.isEmpty()) return
        saveHistory((history() + incoming).distinct())
    }

    fun removeHistory(path: String) {
        saveHistory(history().filter { it != path })
    }

    fun validate(path: String): File {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val requested = File(path.trim()).canonicalFile
        require(requested.path.startsWith(root.path + File.separator) && requested != root) { "请选择共享存储中的子目录，例如 ${defaultPath}" }
        val relative = requested.relativeTo(root).path
        require(relative.split(File.separator).none { it.equals("Android", true) || it == "." || it == ".." }) { "请勿使用 Android/data 等应用专属目录" }
        return requested
    }

    /** 咩版素材目录：直接外部目录时在 `rime/.mie/<中文名>`；应用专属存储时维持 filesDir/旧名。 */
    fun resources(
        context: Context,
        kind: String,
    ): File = com.osfans.trime.core.data.MieUserDirectory.assetFolder(context, kind)

    private fun copyMissing(source: File, target: File): Int = MissingFileCopier.copy(source, target)

    /** Commit the related keys together, so a killed process cannot observe half a switch. */
    private fun commitLocation(mode: DataStorageMode, path: String, ready: Boolean) {
        check(prefs.dataStorageMode.sharedPreferences.edit()
            .putString(prefs.dataStorageMode.key, mode.name)
            .putString(prefs.directExternalPath.key, path)
            .putBoolean(prefs.directExternalReady.key, ready)
            .commit()) { "数据目录设置保存失败" }
    }

    /**
     * 只保存外部目录地址，不迁移、不复制文件，随后由 Rime 重启重新部署。
     *
     * 与 [activate] 的区别：这里不改动任何已有数据，也不跑 [MieUserDirectory.migrate]，
     * 适合「目录里已有配置、只想把地址指过去」的场景。Rime 停止期间调用。
     */
    fun savePath(context: Context, path: String) {
        check(hasPermission(context)) { "请先开启本应用的所有文件访问权限" }
        val target = validate(path)
        check(target.mkdirs() || target.isDirectory) { "无法创建外部目录" }
        val probe = File.createTempFile(".mie-access-", ".tmp", target)
        probe.delete()
        val previousPath = prefs.directExternalPath.getValue()
        val previousMode = prefs.dataStorageMode.getValue()
        val previousReady = prefs.directExternalReady.getValue()
        try {
            commitLocation(DataStorageMode.DIRECT_EXTERNAL, target.absolutePath, true)
        } catch (error: Exception) {
            runCatching { commitLocation(previousMode, previousPath, previousReady) }.onFailure(error::addSuppressed)
            throw error
        }
    }

    /** Called while Rime is stopped. Preferences change only after successful preparation. */
    fun activate(context: Context, path: String): Int {
        check(hasPermission(context)) { "请先开启本应用的所有文件访问权限" }
        val target = validate(path)
        check(target.mkdirs() || target.isDirectory) { "无法创建外部目录" }
        val probe = File.createTempFile(".mie-access-", ".tmp", target)
        probe.delete()
        val previous = UserDataManager.userDataDir
        DataManager.sync()
        var count = copyMissing(previous, target)
        count += copyMissing(UserDataManager.appUserDataDir, target)
        count += copyMissing(DataManager.sharedDataDir, target)
        // 旧数据目录（切换前的直接外部目录）也纳入 .mie 迁移扫描。
        val previousPath = prefs.directExternalPath.getValue()
        val legacyRoots = listOfNotNull(
            File(previousPath).takeIf { previousPath.isNotBlank() && it.isDirectory && it.absolutePath != target.absolutePath },
        )
        val previousMode = prefs.dataStorageMode.getValue()
        val previousReady = prefs.directExternalReady.getValue()
        try {
            commitLocation(DataStorageMode.DIRECT_EXTERNAL, target.absolutePath, true)
            com.osfans.trime.core.data.MieUserDirectory.migrate(context, legacyRoots)
        } catch (error: Exception) {
            runCatching { commitLocation(previousMode, previousPath, previousReady) }.onFailure(error::addSuppressed)
            throw error
        }
        return count
    }

    /** Called while native Rime is stopped; original external files and prior app files survive. */
    fun deactivate(context: Context, mode: DataStorageMode) {
        require(mode != DataStorageMode.DIRECT_EXTERNAL && enabled())
        if (mode == DataStorageMode.EXTERNAL_SYNC)
            check(UserDataManager.hasExternalAccess()) { "请先选择并授权外部同步目录" }
        val oldPath = prefs.directExternalPath.getValue()
        val oldReady = prefs.directExternalReady.getValue()
        val oldMieRoot = File(oldPath)
        val target = UserDataManager.appUserDataDir
        val tag = java.util.UUID.randomUUID().toString()
        val backup = File(target.parentFile, "rime-before-switch-$tag")
        val hadTarget = target.exists()
        if (hadTarget) check(target.renameTo(backup)) { "无法保留原应用目录" }
        fun relocated(value: String): String {
            var text = value
            // 素材留在 rime/.mie（中文目录），应用侧引用改为新数据目录下的 .mie。
            listOf("键盘背景", "字体", "百度皮肤", "统计", "备份", "语音模型", "剪贴板附件").forEach { folder ->
                text = text.replace(File(oldMieRoot, ".mie/$folder").absolutePath + "/",
                    File(UserDataManager.appUserDataDir, ".mie/$folder").absolutePath + "/")
                text = text.replace(File(oldMieRoot, "mie_resources/${context.packageName}/$folder").absolutePath + "/",
                    File(UserDataManager.appUserDataDir, ".mie/${folder}").absolutePath + "/")
            }
            return text
        }
        try {
            copyMissing(File(oldMieRoot, ".mie"), File(UserDataManager.appUserDataDir, ".mie"))
            copyMissing(directory, target)
            check(target.isDirectory) { "无法准备应用数据目录" }
            com.osfans.trime.core.data.MieUserDirectory.migrate(context, listOfNotNull(oldMieRoot.takeIf { it.isDirectory }))
            val before = CustomKeyboards.exportJson()
            val after = relocated(before)
            if (after != before) { CustomKeyboards.importJson(after, true); check(CustomKeyboards.flush()) { "布局素材路径保存失败" } }
            FontOverrides.relocateSelections(::relocated)
            commitLocation(mode, oldPath, oldReady)
        } catch (error: Exception) {
            // Attempt every rollback even if one write fails (e.g. the disk is full).
            runCatching { commitLocation(DataStorageMode.DIRECT_EXTERNAL, oldPath, oldReady) }.onFailure(error::addSuppressed)
            runCatching {
                check(target.deleteRecursively())
                if (hadTarget) check(backup.renameTo(target)) { "请恢复原应用目录：${backup.path}" }
            }.onFailure(error::addSuppressed)
            throw error
        }
    }
}
