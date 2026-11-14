/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.pets

import com.osfans.trime.ui.pets.PetMiniStyle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.security.MessageDigest

class EldritchCatalogTest : FunSpec({
    test("jizai is a unique SSR available to the existing hatch and choice systems") {
        val species = PetCatalog.get("jizai")
        species.name shouldBe "季灾"
        species.rarity shouldBe 3
        species.hatchable shouldBe true
        (species in PetCatalog.hatchPool) shouldBe true
        PetCatalog.species.size shouldBe 60
        PetCatalog.species.map { it.id }.distinct().size shouldBe 60
        PetRedeemCode.CHOICE.requireAvailable(PetHouse.State(), species)
        PetCatalog.get("jige").rarity shouldBe 3
        (PetCatalog.get("xiaomie") in PetCatalog.hatchPool) shouldBe false
    }
    test("only eldritch partners use tentacles, all existing partners keep stance hops") {
        PetMiniStyle.rendererFor("jizai") shouldBe PetMiniStyle.Renderer.TENTACLES
        PetCatalog.species.filterNot { it.id == "jizai" }.forEach {
            PetMiniStyle.rendererFor(it.id) shouldBe PetMiniStyle.Renderer.STANCE
        }
    }
    test("supplied jizai artwork is packaged without alteration") {
        val path = checkNotNull(EldritchCatalog.get("jizai")).asset
        val file = listOf(File("src/main/assets",path),File("app/src/main/assets",path)).first { it.isFile }
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        hash shouldBe "53e620fea7b2475b1b9f951d70f11ac90439ad7ddc2b40e0c3770c4836748a06"
        // Original PNG has an alpha channel, retaining the cutout silhouette.
        bytes[25].toInt() shouldBe 6
    }
})
