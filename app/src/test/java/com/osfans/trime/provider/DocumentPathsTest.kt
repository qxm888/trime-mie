/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.provider
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory

class DocumentPathsTest : FunSpec({
    test("names cannot escape the selected root") {
        val root = createTempDirectory().toFile()
        try {
            for (name in listOf("../outside", "/absolute", "a/b", "a\\b", ".", "..", "", "bad\u0000name"))
                shouldThrow<FileNotFoundException> { DocumentPaths.child(root, root, name) }
            DocumentPaths.child(root, root, "主题.trime.yaml").parentFile shouldBe root.canonicalFile
        } finally { root.deleteRecursively() }
    }
    test("root cannot be mutated and sibling prefixes are not descendants") {
        val parent = createTempDirectory().toFile()
        try {
            val root = File(parent, "rime").apply { mkdir() }
            val child = File(root, "a").apply { mkdir() }
            shouldThrow<FileNotFoundException> { DocumentPaths.mutable(root, root) }
            DocumentPaths.isChild(parent, root, File(parent, "rime-other")) shouldBe false
            DocumentPaths.isChild(root, root, child) shouldBe true
            DocumentPaths.isChild(root, root, root) shouldBe false
            shouldThrow<FileNotFoundException> { DocumentPaths.within(root, File(root, "../other")) }
        } finally { parent.deleteRecursively() }
    }
    test("a symlink destination outside the root is rejected") {
        val parent = createTempDirectory().toFile()
        try {
            val root = File(parent, "rime").apply { mkdir() }
            val outside = File(parent, "outside").apply { mkdir() }
            java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
            shouldThrow<FileNotFoundException> { DocumentPaths.child(root, File(root, "link"), "file") }
        } finally { parent.deleteRecursively() }
    }
})
