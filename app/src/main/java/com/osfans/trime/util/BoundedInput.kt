/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

fun InputStream.copyBoundedTo(output: OutputStream, limit: Long): Long {
    require(limit >= 0)
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) return total
        if (count == 0) continue
        require(count.toLong() <= limit - total) { "文件超过允许大小（${limit / 1024 / 1024}MB）" }
        output.write(buffer, 0, count)
        total += count
    }
}
fun InputStream.readBoundedText(limit: Int): String = ByteArrayOutputStream().use { output ->
    copyBoundedTo(output, limit.toLong())
    output.toString(Charsets.UTF_8.name())
}
