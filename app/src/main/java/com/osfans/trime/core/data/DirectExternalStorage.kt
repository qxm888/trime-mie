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
    fun enabled(): Boolean = prefs.dataStorageMode.getValue() == DataStorageMode.DIRECT_EXTERNAL
    fun hasPermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val directory: File get() = File(prefs.directExternalPath.getValue())

    fun validate(path: String): File {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val requested = File(path.trim()).canonicalFile
        require(requested.path.startsWith(root.path + File.separator) && requested != root) { "请选择共享存储中的子目录，例如 ${defaultPath}" }
        val relative = requested.relativeTo(root).path
        require(relative.split(File.separator).none { it.equals("Android", true) || it == "." || it == ".." }) { "请勿使用 Android/data 等应用专属目录" }
        return requested
    }

    fun resources(context: Context): File = if (enabled()) {
        check(hasPermission(context)) { "请先授予所有文件访问权限" }
        File(directory, "mie_resources/${context.packageName}").apply { check(mkdirs() || isDirectory) }
    } else context.filesDir

    private fun copyMissing(source: File, target: File): Int = MissingFileCopier.copy(source, target)

    /** Commit the related keys together, so a killed process cannot observe half a switch. */
    private fun commitLocation(mode: DataStorageMode, path: String, ready: Boolean) {
        check(prefs.dataStorageMode.sharedPreferences.edit()
            .putString(prefs.dataStorageMode.key, mode.name)
            .putString(prefs.directExternalPath.key, path)
            .putBoolean(prefs.directExternalReady.key, ready)
            .commit()) { "数据目录设置保存失败" }
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
        // Public media are partitioned by package; both channels can share Rime files.
        val oldAssets = resources(context)
        val newAssets = File(target, "mie_resources/${context.packageName}")
        val oldRoots = listOf(oldAssets, context.filesDir).distinctBy { it.canonicalPath }
        oldRoots.forEach { oldRoot ->
            listOf("baidu_skins", "custom_kb_bg", "custom_fonts").forEach { folder ->
                count += copyMissing(File(oldRoot, folder), File(newAssets, folder))
            }
        }
        fun relocated(value: String): String {
            var result = value
            oldRoots.filter { it.canonicalFile != newAssets.canonicalFile }.forEach { oldRoot ->
                listOf("baidu_skins", "custom_kb_bg", "custom_fonts").forEach { folder ->
                    result = result.replace(oldRoot.absolutePath + "/$folder/", newAssets.absolutePath + "/$folder/")
                }
            }
            return result
        }
        val before = CustomKeyboards.exportJson()
        val after = relocated(before)
        val oldMode = prefs.dataStorageMode.getValue()
        val oldPath = prefs.directExternalPath.getValue()
        val oldReady = prefs.directExternalReady.getValue()
        val oldFonts = FontOverrides.roles.keys.associateWith(FontOverrides::selection)
        if (after != before) {
            val backup = File(context.filesDir, "custom_keyboards-before-external.json")
            if (!backup.exists()) backup.writeText(before)
        }
        try {
            if (after != before) { CustomKeyboards.importJson(after, true); check(CustomKeyboards.flush()) { "布局保存失败，未切换目录" } }
            oldFonts.forEach { (role, value) -> if (relocated(value) != value) FontOverrides.set(role, relocated(value), refresh = false) }
            commitLocation(DataStorageMode.DIRECT_EXTERNAL, target.absolutePath, true)
        } catch (error: Exception) {
            runCatching { commitLocation(oldMode, oldPath, oldReady) }.onFailure(error::addSuppressed)
            if (after != before) runCatching { CustomKeyboards.importJson(before, true); check(CustomKeyboards.flush()) }.onFailure(error::addSuppressed)
            oldFonts.forEach { (role, value) -> runCatching {
                if (FontOverrides.selection(role) != value) FontOverrides.set(role, value, refresh = false)
            }.onFailure(error::addSuppressed) }
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
        val oldAssets = resources(context)
        val before = CustomKeyboards.exportJson()
        val fonts = FontOverrides.roles.keys.associateWith(FontOverrides::selection)
        val target = UserDataManager.appUserDataDir
        val tag = java.util.UUID.randomUUID().toString()
        val backup = File(target.parentFile, "rime-before-switch-$tag")
        val hadTarget = target.exists()
        val assets = mutableListOf<Pair<File, File?>>()
        if (hadTarget) check(target.renameTo(backup)) { "无法保留原应用目录" }
        fun relocated(value: String): String {
            var text = value
            listOf("baidu_skins", "custom_kb_bg", "custom_fonts").forEach { folder ->
                text = text.replace(oldAssets.absolutePath + "/$folder/", context.filesDir.absolutePath + "/$folder/")
            }
            return text
        }
        try {
            copyMissing(directory, target)
            check(target.isDirectory) { "无法准备应用数据目录" }
            listOf("baidu_skins", "custom_kb_bg", "custom_fonts").forEach { folder ->
                val source = File(oldAssets, folder)
                if (source.isDirectory) {
                    val dest = File(context.filesDir, folder)
                    val prior = if (dest.exists()) File(context.filesDir, "$folder-before-switch-$tag").also {
                        check(dest.renameTo(it)) { "无法保留原素材目录" }
                    } else null
                    assets += dest to prior
                    copyMissing(source, dest)
                }
            }
            val after = relocated(before)
            if (after != before) { CustomKeyboards.importJson(after, true); check(CustomKeyboards.flush()) { "布局素材路径保存失败" } }
            fonts.forEach { (role, value) -> if (relocated(value) != value) FontOverrides.set(role, relocated(value), refresh = false) }
            commitLocation(mode, oldPath, oldReady)
        } catch (error: Exception) {
            // Attempt every rollback even if one write fails (e.g. the disk is full).
            runCatching { commitLocation(DataStorageMode.DIRECT_EXTERNAL, oldPath, oldReady) }.onFailure(error::addSuppressed)
            runCatching { CustomKeyboards.importJson(before, true); check(CustomKeyboards.flush()) }.onFailure(error::addSuppressed)
            fonts.forEach { (role, value) -> runCatching { if (FontOverrides.selection(role) != value) FontOverrides.set(role, value, refresh = false) }.onFailure(error::addSuppressed) }
            assets.asReversed().forEach { (dest, prior) -> runCatching {
                check(dest.deleteRecursively())
                if (prior != null) check(prior.renameTo(dest))
            }.onFailure(error::addSuppressed) }
            runCatching {
                check(target.deleteRecursively())
                if (hadTarget) check(backup.renameTo(target)) { "请恢复原应用目录：${backup.path}" }
            }.onFailure(error::addSuppressed)
            throw error
        }
    }
}
