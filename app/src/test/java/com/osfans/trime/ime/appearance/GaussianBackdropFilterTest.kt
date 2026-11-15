/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GaussianBackdropFilterTest : FunSpec({
    fun filtered(input: IntArray, width: Int, radius: Int): IntArray = input.copyOf().also {
        GaussianBackdropFilter.apply(it, IntArray(it.size), width, it.size / width, radius)
    }
    test("uniform colors retain brightness and alpha at image borders") {
        listOf(0xff000000.toInt(), 0xffffffff.toInt(), 0xffff0000.toInt(), 0x80ff0000.toInt()).forEach { color ->
            listOf(1, 8, 24).forEach { radius -> filtered(IntArray(45) { color }, 9, radius).toSet() shouldBe setOf(color) }
        }
    }
    test("invisible RGB does not leak into transparent Gaussian edges") {
        val source = IntArray(65 * 9) { if (it % 65 < 32) 0xffff0000.toInt() else 0x000000ff }
        val result = filtered(source, 65, 9)
        result.filter { it ushr 24 > 0 }.all { it and 0x00ffffff == 0x00ff0000 } shouldBe true
        result.filter { it ushr 24 == 0 }.all { it == 0 } shouldBe true
    }
    test("blurred step has an even monotonic transition without dark edge halos") {
        val result = filtered(IntArray(65 * 5) { if (it % 65 < 32) 0xff000000.toInt() else 0xffffffff.toInt() }, 65, 8)
        val row = (0 until 65).map { result[65 * 2 + it] and 255 }
        row.zipWithNext().all { (a, b) -> a <= b } shouldBe true
        row.first() shouldBe 0; row.last() shouldBe 255
        result.all { it ushr 24 == 255 } shouldBe true
    }
    test("single row and column use the same Gaussian kernel") {
        val source = IntArray(31) { if (it == 15) -1 else 0xff000000.toInt() }
        filtered(source, 31, 4).toList() shouldBe filtered(source, 1, 4).toList()
    }
    test("zero radius preserves original pixels") {
        val source = intArrayOf(0, 0x12345678, -1)
        filtered(source, 3, 0).toList() shouldBe source.toList()
    }
    test("local glass and dynamic refresh settings are compatible with old saved layouts") {
        val old = Json.decodeFromString<SurfaceAppearanceConfig>("""{"backdrop":{"enabled":true}}""")
        old.backdrop.systemBlur shouldBe true
        old.blurFrameRate shouldBe 10
        val local = old.copy(backdrop = old.backdrop.copy(systemBlur = false), blurFrameRate = 24)
        Json.decodeFromString<SurfaceAppearanceConfig>(Json.encodeToString(local)) shouldBe local
        local.sanitized().backdrop.systemBlur shouldBe false
        local.copy(blurFrameRate = 999).sanitized().blurFrameRate shouldBe 24
        local.copy(blurFrameRate = -1).sanitized().blurFrameRate shouldBe 5
    }
})
