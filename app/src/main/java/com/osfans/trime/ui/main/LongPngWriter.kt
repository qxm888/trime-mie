/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** A single PNG with one continuous zlib stream, emitted in bounded IDAT chunks. */
internal class LongPngWriter(file: File, val width: Int, private val height: Int) : Closeable {
    private val output = DataOutputStream(file.outputStream().buffered())
    private var rows = 0
    private var closed = false
    private fun chunk(type: String, data: ByteArray, length: Int = data.size) {
        val name = type.toByteArray(Charsets.US_ASCII)
        output.writeInt(length); output.write(name); output.write(data, 0, length)
        val crc = CRC32().apply { update(name); update(data, 0, length) }
        output.writeInt(crc.value.toInt())
    }
    private val chunks = object : OutputStream() {
        private val bytes = ByteArray(32768)
        private var count = 0
        override fun write(value: Int) { bytes[count++] = value.toByte(); if (count == bytes.size) flush() }
        override fun write(data: ByteArray, off: Int, len: Int) {
            var offset = off; var remaining = len
            while (remaining > 0) {
                val size = minOf(remaining, bytes.size - count)
                data.copyInto(bytes, count, offset, offset + size)
                count += size; offset += size; remaining -= size
                if (count == bytes.size) flush()
            }
        }
        override fun flush() { if (count > 0) { chunk("IDAT", bytes, count); count = 0 } }
    }
    private val compressed: DeflaterOutputStream
    private val row = ByteArray(width * 3 + 1)
    init {
        require(width > 0 && height > 0)
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeInt(width); it.writeInt(height); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
        chunk("IHDR", bytes.toByteArray())
        compressed = DeflaterOutputStream(chunks)
    }
    fun writeRow(pixels: IntArray) {
        check(!closed && rows < height && pixels.size >= width)
        row[0] = 0 // PNG filter: none; RGB is opaque because the canvas is filled first.
        for (x in 0 until width) {
            val pixel = pixels[x]; val at = x * 3 + 1
            row[at] = (pixel shr 16).toByte(); row[at + 1] = (pixel shr 8).toByte(); row[at + 2] = pixel.toByte()
        }
        compressed.write(row); rows++
    }
    override fun close() {
        if (closed) return
        closed = true
        try {
            compressed.finish(); chunks.flush()
            check(rows == height) { "长图行数不完整" }
            chunk("IEND", byteArrayOf())
        } finally { compressed.close(); output.close() }
    }
}
