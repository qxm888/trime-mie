/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FloatingGeometryRegressionTest : FunSpec({
    test("pinch to full width compensates the old centered card origin") {
        floatingTranslationX(0f, 120, 1200f, 1200, 24f) shouldBe -120f
    }
    test("near-full-width card can stay centered inside physical screen edges") {
        floatingTranslationX(0f, 10, 1180f, 1200, 24f) shouldBe 0f
        floatingTranslationX(1000f, 10, 1180f, 1200, 24f) shouldBe 10f
    }
    test("popup candidates remain unchanged without a floating obstruction") {
        com.osfans.trime.ime.composition.candidatesWindowTranslationY(100f, null, 30f, 5f, 0f) shouldBe 100f
    }
    test("popup candidates stay above the floating card") {
        com.osfans.trime.ime.composition.candidatesWindowTranslationY(100f, 300f, 30f, 5f, 0f) shouldBe 265f
    }
    test("popup candidates do not move above the screen") {
        com.osfans.trime.ime.composition.candidatesWindowTranslationY(100f, 10f, 30f, 5f, 0f) shouldBe 0f
    }
})
