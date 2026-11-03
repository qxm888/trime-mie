/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class ManualColorChoiceTest : FunSpec({
    val schemes=mapOf("day" to mapOf("name" to "day","dark_scheme" to "night"),"night" to mapOf("name" to "night","light_scheme" to "day"))
    test("nighttime manual day choice bypasses automatic dark link") {
        ColorSchemeResolver.resolve(schemes,"day",true,true,"night","day") shouldBe schemes["day"]
    }
    test("daytime manual dark choice bypasses automatic light link") {
        ColorSchemeResolver.resolve(schemes,"day",true,false,"night","night") shouldBe schemes["night"]
    }
    test("next system event clears manual choice and restores configured target") {
        ColorSchemeResolver.resolve(schemes,"day",true,true,"night",null) shouldBe schemes["night"]
        ColorSchemeResolver.resolve(schemes,"day",true,false,"night",null) shouldBe schemes["day"]
    }
    test("deleted manual target safely falls back to automatic selection") {
        ColorSchemeResolver.resolve(schemes,"day",true,true,"night","deleted") shouldBe schemes["night"]
    }
})
