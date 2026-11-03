/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class BuiltinFloatingIconUpgradeTest:FunSpec({
    test("all five original floating icons opt into the managed upgrade") {
        BuiltinFloatingIconUpgrade.originalHashes.size shouldBe 5
        BuiltinFloatingIconUpgrade.originalHashes.forEach { (name,hash) ->
            BuiltinFloatingIconUpgrade.shouldReplace(name,hash) shouldBe true
        }
    }
    test("user-modified files and other artwork never match the upgrade") {
        BuiltinFloatingIconUpgrade.originalHashes.forEach { (name,hash) ->
            BuiltinFloatingIconUpgrade.shouldReplace(name,"0".repeat(64)) shouldBe false
            BuiltinFloatingIconUpgrade.shouldReplace(name.replace("tb_float","tb_theme"),hash) shouldBe false
        }
    }
})
