/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import com.osfans.trime.data.pets.PetCatalog
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PetMiniStanceTest:FunSpec({
    test("all registered species have two valid sole anchors below the torso") {
        PetCatalog.species.forEach { species ->
            val step=PetMiniStance();val left=step.soleX(species.id);step.leftFoot=false
            (left in 40f..64f) shouldBe true
            (step.soleX(species.id) in 65f..84f) shouldBe true
            (left<step.soleX(species.id)) shouldBe true
            step.soleY shouldBe 125f
        }
    }
    test("compact board has six units below the key grid and no UR palm rest") {
        PetMiniScene.BOARD_BOTTOM-146f shouldBe 6f
        PetMiniScene.HEIGHT-PetMiniScene.BOARD_BOTTOM shouldBe 4f
        PetMiniScene.HEIGHT shouldBe 156f
    }
    test("reset clears every stance component") {
        val step=PetMiniStance().apply { leftFoot=false;lift=1f;bend=.7f;lean=6f;variant=2 }
        step.reset();step.leftFoot shouldBe true;step.lift shouldBe 0f;step.bend shouldBe 0f;step.lean shouldBe 0f;step.variant shouldBe 0
    }
    test("balance gestures stay finite and anatomically bounded across the entire hop") {
        val pose=PetPose();val step=PetMiniStance()
        repeat(3) { variant -> step.variant=variant
            repeat(101) { t -> step.lift=t/100f;pose.phase=t/100f
                listOf(true,false).forEach { left ->
                    val angle=step.armAngle(left,pose)
                    angle.isFinite() shouldBe true;(angle in -160f..160f) shouldBe true
                }
            }
        }
    }
    test("three gesture variants give different airborne poses") {
        val pose=PetPose();val step=PetMiniStance().apply { lift=.6f }
        (0..2).map { step.variant=it;step.armAngle(true,pose) to step.armAngle(false,pose) }.toSet().size shouldBe 3
    }
    test("standing limbs use the character body palette instead of glowing UI accents") {
        PetMiniPalette.body("panther",0xff6fe3e1.toInt()) shouldBe 0xff353e56.toInt()
        PetMiniPalette.body("monkey",0xffe05560.toInt()) shouldBe 0xff3e495e.toInt()
        PetMiniPalette.body("future-species",123) shouldBe 123
    }
})
