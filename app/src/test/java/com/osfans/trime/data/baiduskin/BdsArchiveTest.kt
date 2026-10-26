/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.baiduskin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrowAny
import java.io.File
import java.util.zip.*

class BdsArchiveTest : FunSpec({
    fun archive(name: String = "Info.txt", stored: Boolean = false): File {
        val file = File.createTempFile("bds-test", ".bds")
        val bytes = "Name=玫瑰之恋".toByteArray()
        ZipOutputStream(file.outputStream()).use { out ->
            val entry = ZipEntry(name)
            if (stored) { entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.crc = CRC32().apply { update(bytes) }.value }
            out.putNextEntry(entry); out.write(bytes); out.closeEntry()
        }
        return file
    }
    fun patch(file: File, central: Boolean, byte: Int) {
        val data = file.readBytes()
        val signature = if (central) byteArrayOf(80, 75, 1, 2) else byteArrayOf(80, 75, 3, 4)
        val index = (0..data.size - 4).first { data.sliceArray(it until it + 4).contentEquals(signature) }
        data[index + (if (central) 8 else 6)] = byte.toByte(); file.writeBytes(data)
    }
    test("stored and deflated archives are decoded with UTF8") {
        for (stored in listOf(false, true)) { val f = archive(stored = stored); try { BdsArchive(f).use { it.text("Info.txt") shouldBe "Name=玫瑰之恋" } } finally { f.delete() } }
    }
    test("central-only encryption flag accepts CRC-valid plaintext") {
        val f = archive(stored = true)
        try { patch(f, true, 1); BdsArchive(f).use { it.text("Info.txt") shouldBe "Name=玫瑰之恋" } } finally { f.delete() }
    }
    test("locally encrypted resources are rejected") {
        val f = archive(stored = true)
        try { patch(f, false, 1); shouldThrowAny { BdsArchive(f).use { it.read("Info.txt") } } } finally { f.delete() }
    }
    test("traversal entry is rejected") {
        val f = archive("../escape.png")
        try { shouldThrowAny { BdsArchive(f).close() } } finally { f.delete() }
    }
    test("corrupt resource fails checksum") {
        val f = archive(stored = true)
        try { val bytes=f.readBytes(); bytes[38]=(bytes[38].toInt() xor 32).toByte();f.writeBytes(bytes);shouldThrowAny { BdsArchive(f).use { it.read("Info.txt") } } } finally { f.delete() }
    }
    test("decoy sections cannot overwrite real fields") {
        val ini = BdsIni("[KEY1]\nCENTER=q\n[KEY2\u200b]\nCENTER=z\n[KEY3]\nCENTER=e\nCENTRE=x")
        ini["KEY1"]["CENTER"] shouldBe "q"
        ini["KEY3"]["CENTER"] shouldBe "e"
        ini["KEY2"].isEmpty() shouldBe true
    }
})
