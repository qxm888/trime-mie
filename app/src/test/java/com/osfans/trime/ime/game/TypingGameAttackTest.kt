/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TypingGameAttackTest:FunSpec({
    test("jige uses chicks while existing fighters retain their projectiles") {
        TypingGameAttack.forPet("jige") shouldBe TypingGameAttack.CHICK
        TypingGameAttack.forPet("xiaomie") shouldBe TypingGameAttack.HEART
        listOf("sheep","monkey","future-species").forEach { TypingGameAttack.forPet(it) shouldBe TypingGameAttack.ORB }
    }
})
