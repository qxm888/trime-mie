/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.composition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class PopupCandidateGeometryTest : FunSpec({
    test("floating card near top uses a visible side region") {
        val area = popupCandidateArea(CandidateArea(0f, 20f, 1000f, 400f), CandidateArea(300f, 25f, 700f, 390f), 100f, 80f)
        area.height shouldBe 380f
        area.width shouldBe 300f
    }
    test("ordinary floating card keeps the popup above it") {
        popupCandidateArea(CandidateArea(0f, 0f, 1000f, 700f), CandidateArea(300f, 300f, 700f, 650f), 100f, 80f).bottom shouldBe 300f
    }
    test("landscape above-keyboard space includes both edge margins") {
        popupCandidateHeightLimit(1080, 240, 30) shouldBe 210
    }
    test("portrait maximum still limits a tall list") {
        popupCandidateHeightLimit(2400, 1800, 30) shouldBe 1080
    }
    test("temporarily unknown or exhausted space never creates negative measures") {
        popupCandidateHeightLimit(1080, 0, 30) shouldBe 1
    }
    test("negative follow or above-anchor positions remain visible") {
        popupCandidatePosition(-500f, 100f, 240f, 10f) shouldBe 10f
    }
    test("oversized positions from the previous orientation fit the new viewport") {
        popupCandidatePosition(2000f, 100f, 240f, 10f) shouldBe 130f
    }
    test("nearly full-size popup reduces margins without leaving the viewport") {
        popupCandidatePosition(999f, 235f, 240f, 10f) shouldBe 2.5f
    }
})
