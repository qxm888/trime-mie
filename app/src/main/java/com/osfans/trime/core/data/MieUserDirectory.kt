/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * 咩版放在 Rime 数据目录里的用户可编辑文件，统一收进 `<rime目录>/.mie`，按功能用中文目录名细分：
 * - 键盘背景 / 字体 / 百度皮肤（原 mie_resources/<包名>/ 下的素材）；
 * - 备份 / 语音模型 / 剪贴板附件（原 rime/mie/ 下）；
 * - 统计（原 rime/mie_input_stats*.json）。
 * 仅迁移 Rime 数据目录内的文件；应用私有目录（filesDir）里的软件内部文件不参与迁移。
 * 旧位置由 [migrate] 在启动 / 切换数据目录时一次性搬空。
 */
object MieUserDirectory {
    /** 素材类别：旧目录名 → 中文目录名。 */
    val ASSET_KINDS = linkedMapOf(
        "custom_kb_bg" to "键盘背景",
        "custom_fonts" to "字体",
        "baidu_skins" to "百度皮肤",
    )

    /** Rime 数据目录下的 .mie 根。 */
    fun directory(context: Context): File {
        if (!UserDataManager.isStorageAvailable() && DirectExternalStorage.enabled()) {
            check(DirectExternalStorage.hasPermission(context)) { "请先在数据位置设置中授予文件访问权限" }
        }
        return File(UserDataManager.userDataDir, ".mie")
    }

    /**
     * 素材分类目录：[kind] 用旧名（custom_kb_bg 等）。直接外部目录模式下在 rime/.mie/<中文名>；
     * 应用专属存储模式下维持旧的 filesDir/<旧名>（应用内部文件，不迁移）。
     */
    fun assetFolder(
        context: Context,
        kind: String,
    ): File {
        if (!DirectExternalStorage.enabled()) return File(context.filesDir, kind).apply { check(mkdirs() || isDirectory) }
        val name = ASSET_KINDS[kind] ?: return File(directory(context), kind)
        return File(directory(context), name).apply { check(mkdirs() || isDirectory) { "无法创建 $kind 目录" } }
    }

    /** 统计目录（rime/.mie/统计）。 */
    fun statsFolder(context: Context): File = File(directory(context), "统计").apply { check(mkdirs() || isDirectory) { "无法创建统计目录" } }

    /** 参与迁移扫描的 Rime 目录（当前数据目录 + 旧版固定的 /sdcard/rime）。 */
    private fun rimeRoots(): List<File> = listOf(UserDataManager.userDataDir, File(DirectExternalStorage.defaultPath))
        .distinctBy { it.canonicalPath }

    private fun legacyResourceDirs(context: Context, roots: List<File>): List<File> =
        roots.map { File(it, "mie_resources/${context.packageName}") }.filter { it.isDirectory }

    private fun legacyMieDirs(roots: List<File>): List<File> =
        roots.map { File(it, "mie") }.filter { it.isDirectory }

    private fun legacyStatsFiles(roots: List<File>): List<File> =
        roots.flatMap { root -> root.listFiles()?.filter { it.isFile && it.name.startsWith("mie_input_stats") }.orEmpty() }

    /** 把目录内容并入目标（同名保留目标），搬空后删除源目录；返回是否发生搬移。 */
    private fun mergeInto(
        source: File,
        target: File,
    ): Boolean {
        if (!source.isDirectory) return false
        target.mkdirs()
        var moved = false
        source.listFiles()?.forEach { file ->
            val dest = File(target, file.name)
            if (dest.exists()) return@forEach
            moved = true
            if (!file.renameTo(dest)) {
                runCatching { file.copyTo(dest, overwrite = false) }
                    .onSuccess { file.delete() }
                    .onFailure { Timber.w(it, "迁移失败：%s", file.path) }
            }
        }
        if (source.listFiles()?.isEmpty() == true) source.delete()
        return moved
    }

    /**
     * 启动 / 切换数据目录后执行一次：把 Rime 目录里旧位置的自有文件搬入 .mie。
     * [extraRimeRoots] 供切换目录时把旧数据目录也纳入扫描。幂等；旧目录搬空即删。
     * 素材与备份内部存的是绝对路径，搬移后由 [rewriteStoredPaths] 更新引用。
     */
    fun migrate(
        context: Context,
        extraRimeRoots: List<File> = emptyList(),
    ) {
        runCatching {
            val roots = (rimeRoots() + extraRimeRoots).distinctBy { it.canonicalPath }
            val replacements = mutableListOf<Pair<String, String>>()
            legacyResourceDirs(context, roots).forEach { legacyRoot ->
                ASSET_KINDS.forEach { (legacy, _) ->
                    val source = File(legacyRoot, legacy)
                    val target = assetFolder(context, legacy)
                    if (mergeInto(source, target)) {
                        replacements += source.absolutePath + "/" to target.absolutePath + "/"
                    }
                }
                if (mergeInto(legacyRoot, directory(context))) {
                    replacements += legacyRoot.absolutePath + "/" to directory(context).absolutePath + "/"
                }
            }
            // 旧 mie_resources/<包名> 与 mie_resources 根目录搬空即删。
            roots.forEach { root ->
                val resourcesRoot = File(root, "mie_resources")
                val packageDir = File(resourcesRoot, context.packageName)
                if (packageDir.isDirectory && packageDir.listFiles()?.isEmpty() == true) packageDir.delete()
                if (resourcesRoot.isDirectory && resourcesRoot.listFiles()?.isEmpty() == true) resourcesRoot.delete()
            }
            legacyMieDirs(roots).forEach { legacy ->
                val target = directory(context)
                if (mergeInto(legacy, target)) replacements += legacy.absolutePath + "/" to target.absolutePath + "/"
            }
            legacyStatsFiles(roots).forEach { file ->
                val target = File(statsFolder(context), file.name)
                if (!target.exists() && file.renameTo(target)) replacements += file.absolutePath to target.absolutePath
            }
            // .nomedia 屏蔽相册收录素材与备份。
            File(directory(context), ".nomedia").apply { if (!exists()) runCatching { createNewFile() } }
            if (replacements.isNotEmpty()) rewriteStoredPaths(context, replacements)
        }.onFailure { Timber.w(it, "MieUserDirectory: migration failed") }
    }

    /** 旧位置 → 新位置（旧 mie_resources/<包名>/<类别> 与 rime/mie → rime/.mie/…）。 */
    fun pathReplacements(context: Context): List<Pair<String, String>> {
        val roots = rimeRoots()
        return buildList {
            legacyResourceDirs(context, roots).forEach { legacyRoot ->
                ASSET_KINDS.forEach { (legacy, chinese) ->
                    add(File(legacyRoot, legacy).absolutePath + "/" to assetFolder(context, legacy).absolutePath + "/")
                }
            }
            legacyMieDirs(roots).forEach { add(it.absolutePath + "/" to directory(context).absolutePath + "/") }
        }
    }

    /** 把存量 JSON / 偏好里的旧绝对路径改写到新位置。 */
    fun rewriteStoredPaths(
        context: Context,
        replacements: List<Pair<String, String>>,
    ) {
        if (replacements.isEmpty()) return
        fun relocate(value: String): String {
            var result = value
            replacements.forEach { (from, to) -> result = result.replace(from, to) }
            return result
        }
        runCatching {
            val before = com.osfans.trime.data.keymap.CustomKeyboards.exportJson()
            val after = relocate(before)
            if (after != before) {
                com.osfans.trime.data.keymap.CustomKeyboards.importJson(after, true)
                check(com.osfans.trime.data.keymap.CustomKeyboards.flush()) { "迁移后保存布局失败" }
            }
        }.onFailure { Timber.w(it, "MieUserDirectory: rewrite custom keyboards failed") }
        runCatching {
            com.osfans.trime.data.theme.FontOverrides.roles.keys.forEach { role ->
                val value = com.osfans.trime.data.theme.FontOverrides.selection(role)
                val moved = relocate(value)
                if (moved != value) com.osfans.trime.data.theme.FontOverrides.set(role, moved, refresh = false)
            }
        }.onFailure { Timber.w(it, "MieUserDirectory: rewrite fonts failed") }
        runCatching {
            val prefs = com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard
            listOf(prefs.videoBackground, prefs.surfaceAppearance, prefs.customSurfaceAppearance, prefs.themeFloatingCandidates, prefs.customKeyBackground).forEach { pref ->
                val value = pref.getValue()
                val moved = relocate(value)
                if (moved != value) pref.setValue(moved)
            }
        }.onFailure { Timber.w(it, "MieUserDirectory: rewrite prefs failed") }
    }
}
