/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import java.io.File

class BoundedInputTest : FunSpec({
    test("limit is inclusive and decoded as UTF8") {
        val text = "同文🌸"
        text.byteInputStream().readBoundedText(text.toByteArray().size) shouldBe text
        shouldThrow<IllegalArgumentException> { text.byteInputStream().readBoundedText(text.toByteArray().size - 1) }
    }
    test("over-limit chunks are never written") {
        val out = ByteArrayOutputStream()
        shouldThrow<IllegalArgumentException> { ByteArray(20000).inputStream().copyBoundedTo(out, 8192) }
        out.size() shouldBe 8192
    }
    test("atomic writes round trip and recover an old backup") {
        val root = createTempDirectory().toFile()
        try {
            val file = File(root, "settings.json")
            file.readAtomicText() shouldBe null
            file.writeAtomicText("old")
            file.readAtomicText() shouldBe "old"
            File(file.path + ".bak").writeText("last complete")
            file.writeText("partial")
            file.readAtomicText() shouldBe "last complete"
            file.writeAtomicText("new")
            file.readAtomicText() shouldBe "new"
        } finally { root.deleteRecursively() }
    }
})
