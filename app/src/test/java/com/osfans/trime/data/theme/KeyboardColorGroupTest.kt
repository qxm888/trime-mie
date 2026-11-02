/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrowAny
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class KeyboardColorGroupTest : FunSpec({
    val json = Json { ignoreUnknownKeys = true }
    val day = KeyboardStyleTarget("builtin", "custom_pink", "樱花粉")
    val night = KeyboardStyleTarget("custom", "custom_test", "夜间 · 💕")
    test("day and night select the correct complete target") {
        val group = KeyboardColorGroup(name = "日夜", day = day, night = night)
        group.target(false) shouldBe day
        group.target(true) shouldBe night
    }
    test("group JSON roundtrip preserves ids Unicode and target categories") {
        val group = KeyboardColorGroup(name = "日夜 \"A\"\n💕", day = day, night = night)
        json.decodeFromString<List<KeyboardColorGroup>>(json.encodeToString(listOf(group))) shouldBe listOf(group)
    }
    test("a fresh group list is empty") {
        json.decodeFromString<List<KeyboardColorGroup>>("[]") shouldBe emptyList()
    }
    test("forward fields do not erase compatible saved groups") {
        val group = KeyboardColorGroup(name = "日夜", day = day, night = night)
        val raw = json.encodeToString(group).dropLast(1) + ",\"future\":true}"
        json.decodeFromString<KeyboardColorGroup>(raw) shouldBe group
    }
    test("invalid source and blank theme references are rejected") {
        shouldThrowAny { KeyboardStyleTarget("invalid", "x", "x") }
        shouldThrowAny { KeyboardStyleTarget("theme", " ", "x") }
    }
})
