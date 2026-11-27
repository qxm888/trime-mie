/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import android.util.AtomicFile
import com.osfans.trime.R
import java.io.File
import java.util.zip.ZipInputStream

/** Immutable, original scenic theme artwork. Run on the layout worker, never during drawing. */
internal object BuiltinScenicAssets {
    private const val PREFIX = "keyling/"
    private const val MARKER = "keyling/.内置风景-v1"

    @Synchronized fun install(context: Context): Boolean {
        val root = CustomKeyAssets.root(context).canonicalFile
        val names = context.resources.openRawResource(R.raw.mie_scenic_materials_manifest)
            .bufferedReader().use { it.readLines().filter(String::isNotBlank).toSet() }
        require(names.isNotEmpty() && names.all { it.startsWith(PREFIX) && it.endsWith(".png") }) { "内置风景素材清单无效" }
        fun file(name: String): File = File(root, name).canonicalFile.also {
            require(it.path.startsWith(root.path + File.separator)) { "内置风景素材路径无效" }
        }
        val marker = File(root, MARKER)
        if (marker.isFile && names.all { file(it).isFile }) return false
        val seen = mutableSetOf<String>()
        var installed = false
        var total = 0L
        context.resources.openRawResource(R.raw.mie_scenic_materials).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in names && seen.add(entry.name)) { "内置风景素材条目无效" }
                    val target = file(entry.name)
                    // User-edited artwork is kept; missing stock files are repaired.
                    if (!target.isFile) {
                        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true) { "无法创建内置风景素材目录" }
                        val atomic = AtomicFile(target)
                        val output = atomic.startWrite()
                        try {
                            val bytes = zip.copyTo(output)
                            total += bytes
                            require(bytes <= 4 * 1024 * 1024 && total <= 48 * 1024 * 1024) { "内置风景素材超出大小限制" }
                            atomic.finishWrite(output)
                            installed = true
                        } catch (error: Exception) {
                            atomic.failWrite(output)
                            throw error
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        check(seen == names && names.all { file(it).isFile }) { "内置风景素材不完整" }
        val atomic = AtomicFile(marker)
        val output = atomic.startWrite()
        try { output.write("1\n".toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        return installed
    }
}
