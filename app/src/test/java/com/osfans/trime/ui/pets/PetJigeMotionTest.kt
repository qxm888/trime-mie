/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PetJigeMotionTest:FunSpec({
    test("shoulder strike winds back, turns sideways and returns fully to rest") {
        PetJigeMotion.attack(0f) shouldBe PetJigeMotion.Frame()
        val windup=PetJigeMotion.attack(.25f); val strike=PetJigeMotion.attack(.5f)
        (windup.x<0 && strike.x>0 && strike.lean>windup.lean && strike.width<windup.width) shouldBe true
        PetJigeMotion.attack(1f) shouldBe PetJigeMotion.Frame()
    }
})
