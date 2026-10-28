/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/** Publishes only complete files. A killed copy leaves a .tmp, never a truncated dictionary. */
internal object MissingFileCopier {
    fun copyFileIfMissing(source: File, target: File) {
        if (target.exists()) return
        check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        val temporary = File.createTempFile(".mie-copy-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { output ->
                source.inputStream().use { it.copyTo(output) }; output.fd.sync()
            }
            try { Files.move(temporary.toPath(), target.toPath()) }
            catch (_: FileAlreadyExistsException) { /* Existing user config wins. */ }
        } finally { temporary.delete() }
    }

    fun copy(source: File, target: File): Int {
        if (!source.isDirectory || source.canonicalFile == target.canonicalFile) return 0
        val sourceRoot = source.canonicalFile
        val targetRoot = target.canonicalFile
        require(!targetRoot.path.startsWith(sourceRoot.path + File.separator)) { "目标目录不能位于源目录中" }
        val visited = hashSetOf<String>()
        var copied = 0
        source.walkTopDown().onEnter { dir ->
            val real = dir.canonicalPath
            (real == sourceRoot.path || real.startsWith(sourceRoot.path + File.separator)) && visited.add(real)
        }.forEach { file ->
            if (file.name.startsWith(".mie-copy-") && file.name.endsWith(".tmp")) return@forEach
            val real = file.canonicalPath
            if (real != sourceRoot.path && !real.startsWith(sourceRoot.path + File.separator)) return@forEach
            val dest = File(target, file.relativeTo(source).path)
            require(dest.canonicalPath == targetRoot.path || dest.canonicalPath.startsWith(targetRoot.path + File.separator)) { "目标目录包含指向其他位置的链接" }
            if (file.isDirectory) {
                check(dest.mkdirs() || dest.isDirectory) { "目录与文件冲突：${dest.path}" }
            } else if (file.isFile && !dest.exists()) {
                check(dest.parentFile!!.mkdirs() || dest.parentFile!!.isDirectory)
                val temporary = File.createTempFile(".mie-copy-", ".tmp", dest.parentFile)
                try {
                    val size = file.length()
                    val modified = file.lastModified()
                    FileOutputStream(temporary).use { output ->
                        val count = file.inputStream().use { it.copyTo(output) }
                        check(count == size && file.length() == size && file.lastModified() == modified) { "复制期间源文件发生变化：${file.name}" }
                        output.fd.sync()
                    }
                    temporary.setLastModified(modified)
                    // No REPLACE_EXISTING: preserve a file created by another writer meanwhile.
                    try { Files.move(temporary.toPath(), dest.toPath()); copied++ }
                    catch (_: FileAlreadyExistsException) { /* The existing target wins. */ }
                } finally { temporary.delete() }
            }
        }
        return copied
    }
}
