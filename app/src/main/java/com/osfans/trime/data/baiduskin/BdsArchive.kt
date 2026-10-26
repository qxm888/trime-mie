/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.baiduskin

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Bounded ZIP reader. Some BDS writers mark only the central directory as encrypted;
 * actual encryption is rejected, and every decoded entry must pass its ZIP CRC. */
class BdsArchive(file: File) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    data class Entry(val name: String, val method: Int, val crc: Long, val packed: Int, val size: Int, val offset: Long)
    val entries: Map<String, Entry>
    init {
        try {
            require(input.length() in 22..(64L * 1024 * 1024)) { "皮肤文件大小无效或超过64MB" }
            val length = minOf(input.length(), 65557).toInt()
            val tail = ByteArray(length); input.seek(input.length() - length); input.readFully(tail)
            val end = (length - 22 downTo 0).firstOrNull { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == length }
                ?: error("不是有效的BDS/ZIP皮肤文件")
            require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) { "不支持分卷皮肤" }
            val count = u16(tail, end + 10); require(count in 1..5000) { "皮肤文件数量超限" }
            val central = u32(tail, end + 16); require(central < input.length())
            input.seek(central)
            val found = linkedMapOf<String, Entry>(); var total = 0L
            repeat(count) {
                val header = ByteArray(46); input.readFully(header); require(u32(header, 0) == 0x02014b50L) { "皮肤目录损坏" }
                val nameBytes = ByteArray(u16(header, 28)); input.readFully(nameBytes)
                val name = nameBytes.toString(Charsets.UTF_8).replace('\\', '/')
                require(!name.startsWith('/') && name.split('/').none { it == ".." } && !name.contains('\u0000') && name.length < 512) { "皮肤包含不安全路径" }
                val size = u32(header, 24); val packed = u32(header, 20)
                require(size <= 32L * 1024 * 1024 && packed <= 64L * 1024 * 1024) { "皮肤资源过大" }
                total += size; require(total <= 128L * 1024 * 1024) { "皮肤解压大小超限" }
                require(name !in found) { "皮肤包含重复文件名" }
                found[name] = Entry(name, u16(header, 10), u32(header, 16), packed.toInt(), size.toInt(), u32(header, 42))
                input.skipBytes(u16(header, 30) + u16(header, 32))
            }
            entries = found
        } catch (e: Exception) { input.close(); throw e }
    }
    fun read(name: String): ByteArray {
        val entry = entries[name] ?: error("皮肤缺少资源：$name")
        require(entry.offset + 30 < input.length()) { "资源偏移无效" }
        input.seek(entry.offset); val local = ByteArray(30); input.readFully(local)
        require(u32(local, 0) == 0x04034b50L) { "资源头损坏" }
        require(u16(local, 6) and 1 == 0) { "此皮肤实际加密，暂不支持" }
        require(u16(local, 8) == entry.method) { "资源压缩方法不一致" }
        input.skipBytes(u16(local, 26) + u16(local, 28))
        require(input.filePointer + entry.packed <= input.length())
        val packed = ByteArray(entry.packed); input.readFully(packed)
        val decoded = when (entry.method) {
            0 -> packed
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(packed); val output = ByteArray(entry.size); var offset = 0
                    while (!inflater.finished() && offset < output.size) {
                        val n = inflater.inflate(output, offset, output.size - offset)
                        require(n > 0) { "皮肤压缩流损坏" }; offset += n
                    }
                    require(inflater.finished() && offset == entry.size) { "皮肤解压大小不匹配" }; output
                } finally { inflater.end() }
            }
            else -> error("不支持的皮肤压缩格式：${entry.method}")
        }
        require(decoded.size == entry.size && CRC32().apply { update(decoded) }.value == entry.crc) { "资源校验失败：$name" }
        return decoded
    }
    fun text(name: String): String = read(name).toString(Charsets.UTF_8).removePrefix("\uFEFF")
    override fun close() = input.close()
    companion object {
        private fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        private fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, index -> value or ((bytes[offset + index].toLong() and 255) shl (index * 8)) }
    }
}

/** Exact ASCII field names ignore obfuscated decoy fields without guessing corrections. */
class BdsIni(text: String) {
    val sections = linkedMapOf<String, MutableMap<String, String>>()
    init {
        var section = "GLOBAL"
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith('[')) {
                section = if (line.matches(Regex("\\[[A-Za-z0-9_]+]"))) line.substring(1, line.length - 1) else ""
                if (section.isNotEmpty()) sections.getOrPut(section) { linkedMapOf() }
            } else if ('=' in line && !line.startsWith(';') && !line.startsWith('#')) {
                val key = line.substringBefore('=').trim()
                if (section.isNotEmpty() && key.matches(Regex("[A-Z0-9_]+"))) sections.getOrPut(section) { linkedMapOf() }[key] = line.substringAfter('=').trim()
            }
        }
    }
    operator fun get(section: String): Map<String, String> = sections[section].orEmpty()
}
