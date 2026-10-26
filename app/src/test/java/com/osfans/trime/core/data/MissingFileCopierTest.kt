/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

class MissingFileCopierTest : FunSpec({
    test("existing targets win and repeated copies are idempotent") {
        val root = createTempDirectory().toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val target = File(root, "target").apply { mkdir() }
            File(source, "existing").writeText("source")
            File(target, "existing").writeText("user")
            File(source, "nested").mkdir()
            File(source, "nested/word.db").writeText("complete")
            MissingFileCopier.copy(source, target) shouldBe 1
            File(target, "existing").readText() shouldBe "user"
            File(target, "nested/word.db").readText() shouldBe "complete"
            MissingFileCopier.copy(source, target) shouldBe 0
            File(source, "existing").readText() shouldBe "source"
        } finally { root.deleteRecursively() }
    }
    test("interrupted temporary files do not masquerade as completed copies") {
        val root = createTempDirectory().toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            val target = File(root, "target").apply { mkdir() }
            File(source, "word.db").writeText("完整词库".repeat(10000))
            File(source, ".mie-copy-old.tmp").writeText("abandoned")
            File(target, ".mie-copy-killed.tmp").writeText("partial")
            MissingFileCopier.copy(source, target) shouldBe 1
            File(target, "word.db").readBytes().contentEquals(File(source, "word.db").readBytes()) shouldBe true
            File(target, ".mie-copy-old.tmp").exists() shouldBe false
        } finally { root.deleteRecursively() }
    }
    test("nested destination and escaping links are rejected") {
        val root = createTempDirectory().toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            shouldThrow<IllegalArgumentException> { MissingFileCopier.copy(source, File(source, "inside")) }
            val target = File(root, "target").apply { mkdir() }
            val outside = File(root, "outside").apply { mkdir() }
            File(source, "sub").mkdir()
            File(source, "sub/file").writeText("do not escape")
            java.nio.file.Files.createSymbolicLink(File(target, "sub").toPath(), outside.toPath())
            shouldThrow<IllegalArgumentException> { MissingFileCopier.copy(source, target) }
            File(outside, "file").exists() shouldBe false
        } finally { root.deleteRecursively() }
    }
})
