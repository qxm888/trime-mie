/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.pets

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class PetRedeemCodeTest:FunSpec({
    test("jige is SSR and participates in the existing SSR pool") {
        PetCatalog.get("jige").rarity shouldBe 3
        PetCatalog.hatchPool.any { it.id=="jige" } shouldBe true
        PetCatalog.species.map { it.id }.distinct().size shouldBe PetCatalog.species.size
    }
    test("qxm111 is exclusive to jige and rejects forged species") {
        val gift=checkNotNull(PetRedeemCode.find("qxm111"))
        gift.requireAvailable(PetHouse.State(),PetCatalog.get("jige"))
        shouldThrow<IllegalStateException> { gift.requireAvailable(PetHouse.State(),PetCatalog.get("monkey")) }
        PetRedeemCode.find("qxm112") shouldBe null
    }
    test("old claims do not prevent jige and jige cannot be claimed twice") {
        val old=PetHouse.State(redeemed=true,xiaomieRedeemed=true,monkeyRedeemed=true)
        val gift=PetRedeemCode.JIGE
        gift.requireAvailable(old,PetCatalog.get("jige"))
        val claimed=gift.claim(old)
        claimed shouldBe old.copy(jigeRedeemed=true)
        shouldThrow<IllegalStateException> { gift.requireAvailable(claimed,PetCatalog.get("jige")) }
    }
    test("jige claim does not consume any previous redemption") {
        val claimed=PetRedeemCode.JIGE.claim(PetHouse.State())
        PetRedeemCode.CHOICE.used(claimed) shouldBe false
        PetRedeemCode.XIAOMIE.used(claimed) shouldBe false
        PetRedeemCode.MONKEY.used(claimed) shouldBe false
        PetRedeemCode.CHOICE.requireAvailable(claimed,PetCatalog.get("jige"))
        shouldThrow<IllegalStateException> { PetRedeemCode.CHOICE.requireAvailable(claimed,PetCatalog.get("xiaomie")) }
    }
})
