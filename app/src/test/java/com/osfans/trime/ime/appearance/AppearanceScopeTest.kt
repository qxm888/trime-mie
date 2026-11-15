/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.osfans.trime.data.keymap.*
import java.io.File

class AppearanceScopeTest : FunSpec({
    val pages by lazy {
        listOf("mie_builtin_keyboards.yaml","mie_builtin_extra.yaml","mie_builtin_effects.yaml","mie_builtin_glass.yaml").map{File("src/main/res/raw",it)}
            .flatMap{CustomKeyboardYaml.decode(it.readText()).entries}.associate{it.key to it.value}
    }
    fun ids(page:String,active:String)=appearanceScopeIds(page,active,{if(it=="default" || it.isEmpty())active else it},
        {runCatching{KeyboardSets.rootOf(it,pages)}.getOrNull()})
    test("every stock family ignores glass from a different active family") {
        val live="custom_liquid_glass_live"
        pages.keys.forEach { page ->
            val root=KeyboardSets.rootOf(page,pages)
            ids(page,live).all{KeyboardSets.rootOf(it,pages)==root} shouldBe true
            if(root !in setOf(live,"custom_liquid_glass")) {
                ids(page,live).mapNotNull{pages[it]?.surfaceAppearance}.any{it.backdrop.publicWindow} shouldBe false
            }
        }
    }
    test("same-family activity and unknown system aliases still resolve") {
        ids("custom_liquid_glass_live_num","custom_liquid_glass_live_en") shouldBe listOf("custom_liquid_glass_live","custom_liquid_glass_live_en","custom_liquid_glass_live_num")
        ids("number","custom_cloudsea") shouldBe listOf("custom_cloudsea")
        ids("default","custom_cloudsea") shouldBe listOf("custom_cloudsea")
        ids("custom_cloudsea","custom_liquid_glass_live") shouldBe listOf("custom_cloudsea")
    }
})
