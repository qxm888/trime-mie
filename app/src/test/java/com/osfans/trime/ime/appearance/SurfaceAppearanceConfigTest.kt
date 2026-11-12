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

    test("keyboard side margin clamps to 0..20 dp") {
        SurfaceAppearanceConfig(keyboardSideMargin = 99f).sanitized().keyboardSideMargin shouldBe 20f
        SurfaceAppearanceConfig(keyboardSideMargin = -3f).sanitized().keyboardSideMargin shouldBe 0f
        SurfaceAppearanceConfig(keyboardSideMargin = Float.NaN).sanitized().keyboardSideMargin shouldBe 0f
        SurfaceAppearanceConfig(keyboardSideMargin = 12f).sanitized().keyboardSideMargin shouldBe 12f
    }

    test("candidate highlight radius clamps to -1..32 dp") {
        SurfaceAppearanceConfig(candidateHighlightRadius = 99f).sanitized().candidateHighlightRadius shouldBe 32f
        SurfaceAppearanceConfig(candidateHighlightRadius = -5f).sanitized().candidateHighlightRadius shouldBe -1f
        SurfaceAppearanceConfig(candidateHighlightRadius = Float.NaN).sanitized().candidateHighlightRadius shouldBe -1f
        SurfaceAppearanceConfig(candidateHighlightRadius = 12f).sanitized().candidateHighlightRadius shouldBe 12f
    }

    test("panel gradient is off by default and survives a round trip") {
        val config = SurfaceAppearanceConfig()
        config.gradient.enabled shouldBe false
        val restored = json.decodeFromString<SurfaceAppearanceConfig>(json.encodeToString(config))
        restored.gradient shouldBe config.gradient
    }

    test("panel gradient sanitizes direction, radius and sweep center") {
        val g = PanelGradient(enabled = true, direction = 99, radius = 999, sweepX = -3f, sweepY = Float.NaN).sanitized()
        g.direction shouldBe 9
        g.radius shouldBe 200
        g.sweepX shouldBe 0f
        g.sweepY shouldBe 0.5f
    }

    test("panel gradient keeps the night colors optional") {
        // 夜间留空 = 沿用日间；写了非法色值就当没写，不能把整条渐变变成黑。
        PanelGradient(startNight = "").sanitized().startNight shouldBe ""
        PanelGradient(startNight = "not-a-color").sanitized().startNight shouldBe ""
        PanelGradient(startNight = "#ffaabbcc").sanitized().startNight shouldBe "#FFAABBCC"
    }

    test("keyboard vertical margins clamp to 0..20 dp") {
        SurfaceAppearanceConfig(keyboardTopMargin = 99f).sanitized().keyboardTopMargin shouldBe 20f
        SurfaceAppearanceConfig(keyboardBottomMargin = -3f).sanitized().keyboardBottomMargin shouldBe 0f
        SurfaceAppearanceConfig(keyboardTopMargin = Float.NaN).sanitized().keyboardTopMargin shouldBe 0f
        SurfaceAppearanceConfig(keyboardBottomMargin = 12f).sanitized().keyboardBottomMargin shouldBe 12f
        // 旧配置里没有这两个字段，反序列化后必须还是 0，不能变成意外的留白。
        json.decodeFromString<SurfaceAppearanceConfig>("""{"keyboardSideMargin":8}""")
            .keyboardTopMargin shouldBe 0f
    }

    test("keyboard vertical margins survive a round trip") {
        val config = SurfaceAppearanceConfig(keyboardTopMargin = 6f, keyboardBottomMargin = 14f)
        val restored = json.decodeFromString<SurfaceAppearanceConfig>(json.encodeToString(config))
        restored.keyboardTopMargin shouldBe 6f
        restored.keyboardBottomMargin shouldBe 14f
    }

    test("panel backdrop is off by default and survives a round trip") {
        SurfaceAppearanceConfig().backdrop.enabled shouldBe false
        val config = SurfaceAppearanceConfig(
            backdrop = PanelBackdrop(enabled = true, strength = 42, imageAlpha = 70),
        )
        val restored = json.decodeFromString<SurfaceAppearanceConfig>(json.encodeToString(config))
        restored.backdrop shouldBe config.backdrop
    }

    test("panel backdrop strength clamps and maps to a pixel radius") {
        PanelBackdrop(strength = 999).sanitized().strength shouldBe 100
        PanelBackdrop(strength = -5).sanitized().strength shouldBe 0
        PanelBackdrop(strength = 100).radiusPx() shouldBe PanelBackdrop.MAX_RADIUS_PX
        PanelBackdrop(strength = 0).radiusPx() shouldBe 0
        PanelBackdrop(strength = 50).radiusPx() shouldBe PanelBackdrop.MAX_RADIUS_PX / 2
    }

    test("key blur defaults to off and clamps") {
        SurfaceAppearanceConfig().keyBlur shouldBe 0
        SurfaceAppearanceConfig(keyBlur = 999).sanitized().keyBlur shouldBe 100
        SurfaceAppearanceConfig(keyBlur = -3).sanitized().keyBlur shouldBe 0
        json.decodeFromString<SurfaceAppearanceConfig>("""{"keyOpacity":60}""").keyBlur shouldBe 0
    }

    test("key opacity defaults to opaque and clamps") {
        SurfaceAppearanceConfig().keyOpacity shouldBe 100
        SurfaceAppearanceConfig(keyOpacity = 999).sanitized().keyOpacity shouldBe 100
        SurfaceAppearanceConfig(keyOpacity = -3).sanitized().keyOpacity shouldBe 0
        // 旧配置没有这个字段，不能把老键盘的按键变成透明。
        json.decodeFromString<SurfaceAppearanceConfig>("""{"glassOpacity":30}""").keyOpacity shouldBe 100
    }

    test("panel backdrop image mix clamps to 0..100") {
        PanelBackdrop(imageAlpha = 999).sanitized().imageAlpha shouldBe 100
        PanelBackdrop(imageAlpha = -1).sanitized().imageAlpha shouldBe 0
        PanelBackdrop().imageAlpha shouldBe 55
        PanelBackdrop(imageOpacity = 999).sanitized().imageOpacity shouldBe 100
        PanelBackdrop(imageOpacity = -5).sanitized().imageOpacity shouldBe 0
        // 旧配置没有这个字段：反序列化后要落在默认值上，不能变成全透明。
        json.decodeFromString<SurfaceAppearanceConfig>("""{"backdrop":{"enabled":true}}""")
            .backdrop.imageOpacity shouldBe 70
    }
})
