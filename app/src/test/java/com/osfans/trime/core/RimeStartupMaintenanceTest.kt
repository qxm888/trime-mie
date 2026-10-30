/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

class RimeStartupMaintenanceTest : FunSpec({
    fun setup(): Triple<File, File, File> {
        val root = createTempDirectory().toFile()
        val shared = File(root, "shared").apply { mkdir() }
        val user = File(root, "user").apply { mkdir() }
        File(user, "build").mkdir()
        File(user, "build/default.yaml").writeText("config_version: '1'")
        return Triple(root, shared, user)
    }

    fun yaml(dir: File, name: String, seconds: Long): File =
        File(dir, name).apply {
            writeText("patch: {}")
            setLastModified(seconds * 1000)
        }

    fun userYaml(user: File, lastBuildTime: Long): File =
        File(user, "user.yaml").apply {
            writeText(
                """
                var:
                  last_build_time: $lastBuildTime
                  previously_selected_schema: luna_pinyin
                """.trimIndent(),
            )
        }

    test("missing staging rebuilds only when schemas exist") {
        val (root, shared, user) = setup()
        try {
            File(user, "build/default.yaml").delete()
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe false
            yaml(user, "luna_pinyin.schema.yaml", 1_000)
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }

    test("unchanged timestamps skip maintenance") {
        val (root, shared, user) = setup()
        try {
            yaml(user, "theme.trime.yaml", 1_000)
            userYaml(user, 2_000)
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe false
        } finally {
            root.deleteRecursively()
        }
    }

    test("creating or editing a source file triggers maintenance") {
        val (root, shared, user) = setup()
        try {
            userYaml(user, 2_000)
            yaml(user, "new.trime.yaml", 2_001)
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe true
            yaml(shared, "luna_pinyin.schema.yaml", 2_001)
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }

    test("renaming or deleting does not trigger maintenance") {
        val (root, shared, user) = setup()
        try {
            val original = yaml(user, "old.trime.yaml", 1_000)
            userYaml(user, 2_000)
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe false
            original.renameTo(File(user, "renamed.trime.yaml")) shouldBe true
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe false
            File(user, "renamed.trime.yaml").delete() shouldBe true
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe false
        } finally {
            root.deleteRecursively()
        }
    }

    test("engine state files are not deployment inputs") {
        val (root, shared, user) = setup()
        try {
            yaml(user, "theme.trime.yaml", 1_000)
            userYaml(user, 2_000).setLastModified(3_000_000)
            File(user, "installation.yaml").apply {
                writeText("installation_id: test")
                setLastModified(3_000_000)
            }
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe false
        } finally {
            root.deleteRecursively()
        }
    }

    test("missing user.yaml is treated as never built") {
        val (root, shared, user) = setup()
        try {
            yaml(user, "theme.trime.yaml", 1_000)
            RimeStartupMaintenance.needsMaintenance(shared, user) shouldBe true
        } finally {
            root.deleteRecursively()
        }
    }
})
