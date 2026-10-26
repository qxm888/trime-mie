/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard
import com.osfans.trime.data.prefs.AppPrefs.Keyboard.OneHandSide
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class KeyboardSizingTest : FunSpec({
    test("floating text follows the smaller dimension and docked text stays unchanged") {
        floatingKeyTextScale(true, 40, 80) shouldBe .4f
        floatingKeyTextScale(true, 80, 60) shouldBe .6f
        floatingKeyTextScale(true, 100, 100) shouldBe 1f
        floatingKeyTextScale(false, 40, 40) shouldBe 1f
    }
    test("left preference cycles left and normal including recovery from right") {
        listOf(0, 1, 2).map { nextOneHandMode(it, OneHandSide.LEFT) } shouldBe listOf(1, 0, 1)
    }
    test("right preference cycles right and normal including recovery from left") {
        listOf(0, 1, 2).map { nextOneHandMode(it, OneHandSide.RIGHT) } shouldBe listOf(2, 2, 0)
    }
    test("both preference preserves the existing three-state cycle") {
        listOf(0, 1, 2).map { nextOneHandMode(it, OneHandSide.BOTH) } shouldBe listOf(1, 2, 0)
    }
})
