/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class SurfaceAppearanceConfigTest : FunSpec({
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    test("old config without effects still parses") {
        val config = json.decodeFromString<SurfaceAppearanceConfig>(
            """{"keys":{"background":"#11223344"},"blurRadius":18}""",
        )
        config.keys.background shouldBe "#11223344"
        config.blurRadius shouldBe 18
        config.effects.shadow.enabled shouldBe false
        config.effects.gradient.enabled shouldBe false
    }

    test("effects survive a round trip") {
        val config = SurfaceAppearanceConfig(
            effects = KeyEffects(
                shadow = KeyShadow(enabled = true, color = "#55FF0000", blur = 12f, offsetY = 4f),
                gradient = KeyGradient(enabled = true, type = "radial", opacity = 70),
                ripple = true,
                pressScale = 3f,
            ),
        )
        val restored = json.decodeFromString<SurfaceAppearanceConfig>(json.encodeToString(config))
        restored.effects shouldBe config.effects
    }

    test("sanitize clamps extreme values") {
        val effects = KeyEffects(
            shadow = KeyShadow(blur = 999f, offsetX = -999f, spread = 99f),
            innerShadow = KeyInnerShadow(blur = -4f, offsetY = 99f),
            gradient = KeyGradient(type = "unknown", angle = -90f, opacity = 999),
            pressScale = 99f,
            pressSink = -3f,
            animationMs = 9999,
        ).sanitized()
        effects.shadow.blur shouldBe 32f
        effects.shadow.offsetX shouldBe -16f
        effects.shadow.spread shouldBe 8f
        effects.innerShadow.blur shouldBe 0f
        effects.innerShadow.offsetY shouldBe 12f
        effects.gradient.type shouldBe "linear"
        effects.gradient.angle shouldBe 270f
        effects.gradient.opacity shouldBe 100
        effects.pressScale shouldBe 10f
        effects.pressSink shouldBe 0f
        effects.animationMs shouldBe 300
    }
})
