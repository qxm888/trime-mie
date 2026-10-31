/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import com.osfans.trime.core.data.DirectExternalStorage
import java.io.File

/**
 * 咩版自有素材目录（键盘背景、图标、字体等）的路径解析。
 *
 * 自定义键盘的图标既支持绝对路径，也支持相对 `.mie/键盘背景` 的路径；
 * 只允许访问咩版素材目录内的文件，避免 YAML 越界读取。
 */
object CustomKeyAssets {
    /** 素材根目录：`.mie/键盘背景`（直接外部模式）或 `filesDir/custom_kb_bg`（应用存储）。 */
    fun root(context: Context): File = DirectExternalStorage.resources(context, "custom_kb_bg")

    /** 解析 [raw] 为可读取的绝对路径；不存在或越界返回 null。 */
    fun resolve(context: Context, raw: String): String? {
        if (raw.isBlank()) return null
        val root = root(context)
        val file = if (raw.startsWith("/")) File(raw) else File(root, raw)
        val canonical = runCatching { file.canonicalFile.path }.getOrNull() ?: return null
        val rootPath = runCatching { root.canonicalFile.path }.getOrNull() ?: return null
        val filesRoot = runCatching { File(context.filesDir, "custom_kb_bg").canonicalFile.path }.getOrNull() ?: return null
        if (!canonical.startsWith("$rootPath${File.separator}") && !canonical.startsWith("$filesRoot${File.separator}")) return null
        return canonical.takeIf { File(it).isFile }
    }
}
