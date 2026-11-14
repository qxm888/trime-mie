/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TypingGameLayoutTest : FunSpec({
    test("portrait field fills the gap between status bar and the actual keyboard") {
        val area = checkNotNull(TypingGameLayout.area(1272, 2772, 0, 140, 0, 70, 1743))
        area shouldBe TypingGameLayout.Area(0, 140, 1272, 1743)
        area.height shouldBe 1603
    }
    test("one hand and floating keyboard positions use their actual top without a minimum third screen fallback") {
        TypingGameLayout.area(1216, 2640, 0, 135, 0, 56, 1600)?.bottom shouldBe 1600
        TypingGameLayout.area(1216, 2640, 0, 135, 0, 56, 700)?.height shouldBe 565
    }
    test("landscape respects side cutouts and navigation insets") {
        TypingGameLayout.area(2772, 1272, 140, 0, 70, 0, 760) shouldBe TypingGameLayout.Area(140, 0, 2702, 760)
        TypingGameLayout.area(1216, 2640, 0, 135, 0, 56, 3000)?.bottom shouldBe 2584
    }
    test("unlaid out or fully obstructed keyboards do not create a misleading small fallback") {
        TypingGameLayout.area(1216, 2640, 0, 135, 0, 56, 0) shouldBe null
        TypingGameLayout.area(0, 2640, 0, 135, 0, 56, 1500) shouldBe null
    }
    test("a taller field gives more typing time once the normal scrolling pace is reached") {
        val shortTime = 900f / TypingGameLayout.fallSpeed(3, 900f, 3f)
        val tallTime = 1500f / TypingGameLayout.fallSpeed(3, 1500f, 3f)
        (tallTime > shortTime) shouldBe true
    }
    test("compact screens still leave ten seconds at the first level and at least five at the last") {
        (300f / TypingGameLayout.fallSpeed(1, 300f, 3f)).toInt() shouldBe 10
        (300f / TypingGameLayout.fallSpeed(50, 300f, 3f) >= 5f) shouldBe true
    }
})
