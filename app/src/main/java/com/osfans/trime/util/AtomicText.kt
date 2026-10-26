/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util

import androidx.core.util.AtomicFile
import java.io.File

/** Callers serialize access; recovery must go through openRead, including old .bak files. */
fun File.readAtomicText(): String? {
    if (!exists() && !File(path + ".bak").exists()) return null
    return AtomicFile(this).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
}
fun File.writeAtomicText(text: String) {
    check(!exists() || isFile) { "保存目标不是普通文件：$name" }
    val atomic = AtomicFile(this)
    val stream = atomic.startWrite()
    val bytes = text.toByteArray(Charsets.UTF_8)
    try {
        stream.write(bytes)
        atomic.finishWrite(stream)
        // AtomicFile logs some rename/fsync errors instead of throwing. Verify the committed
        // payload so callers never report a successful import for an uncommitted write.
        check(isFile && length() == bytes.size.toLong()) { "文件提交失败：$name" }
        atomic.openRead().use { input ->
            val buffer = ByteArray(8192)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(buffer, 0, minOf(buffer.size, bytes.size - offset))
                check(count > 0) { "文件提交不完整：$name" }
                for (i in 0 until count) check(buffer[i] == bytes[offset + i]) { "文件提交校验失败：$name" }
                offset += count
            }
            check(input.read() == -1)
        }
    } catch (error: Throwable) {
        atomic.failWrite(stream)
        throw error
    }
}
