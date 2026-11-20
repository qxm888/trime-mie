/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.ai

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import com.osfans.trime.data.keymap.KeyAppearance
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.KeyActionToken
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.TextKeyboard.TextKey
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ThemeAssistTest : FunSpec({
    test("extractJson accepts fenced and narrated responses") {
        val plain = ThemeAssistProtocol.extractJson("""{"summary":"ok"}""")
        plain["summary"]!!.jsonPrimitive.content shouldBe "ok"

        val fenced = ThemeAssistProtocol.extractJson(
            """
            好的，下面是结果：
            ```json
            {"summary":"深色主题","colors":{"key_back_color":"#FF000000"}}
            ```
            希望你喜欢。
            """.trimIndent(),
        )
        fenced["summary"]!!.jsonPrimitive.content shouldBe "深色主题"

        val thinking = ThemeAssistProtocol.extractJson(
            """<think>先想一想 {"summary":"wrong"}</think>结果：{"summary":"a}b","nested":{"x":1}}""",
        )
        thinking["summary"]!!.jsonPrimitive.content shouldBe "a}b"
        thinking["nested"]!!.jsonObject["x"]!!.jsonPrimitive.content shouldBe "1"
    }

    test("extractJson reports invalid or incomplete responses") {
        shouldThrow<IllegalArgumentException> { ThemeAssistProtocol.extractJson("没有 JSON") }
        shouldThrow<IllegalArgumentException> { ThemeAssistProtocol.extractJson("{\"summary\":\"cut\"") }
    }

    test("parseCustom keeps known fields and reports unusable ones") {
        val warnings = mutableListOf<String>()
        val patch = ThemeAssistProtocol.parseCustom(
            Json.parseToJsonElement(
                """
                {
                  "summary":"磨砂玻璃",
                  "keyAppearance":{"keyBackColor":"#CC111827","roundCorner":12},
                  "surface":{"keyOpacity":85,"keyboardCorners":[16,16,16,16],"gradientEnabled":true},
                  "geometry":{"keyboardHeightDp":260,"rowSpacing":6},
                  "typography":{"candidate":{"size":20,"color":"#FFF9FAFB"}},
                  "backgroundColor":"#FF101418",
                  "keys":[{"row":0,"key":1,"appearance":{"keyBackColor":"#FFF472B6"}}]
                }
                """.trimIndent(),
            ).jsonObject,
            warnings,
        )
        patch.summary shouldBe "磨砂玻璃"
        patch.keyAppearance["keyBackColor"] shouldBe "#CC111827"
        patch.surface.keyOpacity shouldBe 85
        patch.surface.keyboardCorners shouldBe listOf(16f, 16f, 16f, 16f)
        patch.geometry.keyboardHeightDp shouldBe 260
        patch.typography["candidate"]?.size shouldBe 20f
        patch.backgroundColor shouldBe "#FF101418"
        patch.keys.single().row shouldBe 0
        warnings shouldBe emptyList<String>()
    }

    test("parseTheme only accepts numeric style values") {
        val warnings = mutableListOf<String>()
        val patch = ThemeAssistProtocol.parseTheme(
            Json.parseToJsonElement(
                """
                {
                  "summary":"黑金",
                  "style":{"key_text_size":22,"round_corner":10,"oops":"many"},
                  "colors":{"key_back_color":"#FF1F2937"},
                  "keys":[{"match":"space","appearance":{"key_back_color":"#FFF472B6"}}]
                }
                """.trimIndent(),
            ).jsonObject,
            warnings,
        )
        patch.style.keys shouldBe setOf("key_text_size", "round_corner")
        patch.colors["key_back_color"] shouldBe "#FF1F2937"
        patch.keys.single().match shouldBe "space"
        warnings shouldContain "style.oops 不是数字，已忽略"
    }

    test("custom applier updates page, surface, geometry and one key") {
        val keyboard = CustomKeyboard(
            name = "测试",
            keyAppearance = KeyAppearance.Style(),
            rows = mutableListOf(
                CustomRow(
                    keys = mutableListOf(
                        CustomKey(label = "q", bindings = mutableMapOf("click" to "q")),
                        CustomKey(label = "空格", width = 30f, bindings = mutableMapOf("click" to "space")),
                    ),
                ),
            ),
        )
        val patch = CustomThemePatch(
            keyAppearance = mapOf("keyBackColor" to "#FF1F2937", "roundCorner" to "12"),
            surface = SurfacePatch(keyOpacity = 80),
            geometry = GeometryPatch(keyboardHeightDp = 280, rowSpacing = 8),
            keys = listOf(
                CustomKeyPatch(
                    row = 0,
                    key = 1,
                    appearance = mapOf("keyBackColor" to "#FFF472B6", "roundCorner" to "14"),
                ),
            ),
        )
        val report = AssistReport()
        CustomThemeApplier.apply(keyboard, patch, SurfaceAppearanceConfig(), report)

        keyboard.keyAppearance.keyBackColor shouldBe "#FF1F2937"
        keyboard.keyAppearance.roundCorner shouldBe 12f
        keyboard.surfaceAppearance?.keys?.radius shouldBe 12f
        keyboard.surfaceAppearance?.keyOpacity shouldBe 80
        keyboard.keyboardHeightDp shouldBe 280
        keyboard.rowSpacing shouldBe 8
        keyboard.rows[0].keys[0].appearance.keyBackColor shouldBe ""
        keyboard.rows[0].keys[1].appearance.keyBackColor shouldBe "#FFF472B6"
        keyboard.rows[0].keys[1].appearance.roundCorner shouldBe 14f
        keyboard.rows[0].keys[1].setStyleOverrides?.get("roundCorner")!!.jsonPrimitive.float shouldBe 14f
        report.changes.isNotEmpty() shouldBe true
        report.warnings shouldBe emptyList<String>()
    }

    test("custom applier clamps ranges and reports ignored fields") {
        val keyboard = CustomKeyboard(
            name = "测试",
            rows = mutableListOf(CustomRow(keys = mutableListOf(CustomKey(label = "q")))),
        )
        val report = AssistReport()
        CustomThemeApplier.apply(
            keyboard,
            CustomThemePatch(
                keyAppearance = mapOf("roundCorner" to "999", "notAField" to "x"),
                surface = SurfacePatch(keyOpacity = 500, blurRadius = 500, keysBackground = "red"),
            ),
            SurfaceAppearanceConfig(),
            report,
        )
        keyboard.keyAppearance.roundCorner shouldBe -1f
        keyboard.surfaceAppearance?.keyOpacity shouldBe 100
        report.warnings.any { it.contains("不支持修改") } shouldBe true
        report.warnings.any { it.contains("不透明度") && it.contains("收敛") } shouldBe true
        report.warnings.any { it.contains("背景颜色格式不正确") } shouldBe true
    }

    test("YAML applier edits style, active scheme and matching keys") {
        val theme = sampleTheme()
        val report = AssistReport()
        val updated = YamlThemeApplier.apply(
            theme,
            "默认",
            YamlThemePatch(
                style = mapOf("key_text_size" to JsonPrimitive(24), "round_corner" to JsonPrimitive(10)),
                colors = mapOf("key_back_color" to "#FFF1F3F5"),
                keys = listOf(YamlKeyPatch("space", mapOf("key_back_color" to "#FFF472B6"))),
            ),
            report,
        )
        updated.style.keyTextSize shouldBe 24f
        updated.style.roundCorner shouldBe 10f
        updated.presetColorSchemes["默认"]?.get("key_back_color") shouldBe "#FFF1F3F5"
        updated.presetKeyboards["default"]?.keys?.first()?.keyBackColor shouldBe "#FFF472B6"
        report.warnings shouldBe emptyList<String>()
        report.changes.isNotEmpty() shouldBe true
    }

    test("YAML applier keeps invalid colors and unknown keys out") {
        val theme = sampleTheme()
        val report = AssistReport()
        val updated = YamlThemeApplier.apply(
            theme,
            "默认",
            YamlThemePatch(
                style = mapOf("key_text_size" to JsonPrimitive(999), "nope" to JsonPrimitive(1)),
                colors = mapOf("key_back_color" to "red", "not_a_color_key" to "#FF000000"),
                keys = listOf(YamlKeyPatch("missing-key", mapOf("key_back_color" to "#FFF472B6"))),
            ),
            report,
        )
        updated.style.keyTextSize shouldBe 18f
        updated.presetColorSchemes["默认"]?.get("key_back_color") shouldBe "#FF111111"
        report.warnings.any { it.contains("超出范围") || it.contains("需在") } shouldBe true
        report.warnings.any { it.contains("不支持的颜色键") } shouldBe true
        report.warnings.any { it.contains("没有找到匹配") } shouldBe true
    }

    test("theme overlay merges, survives JSON and applies") {
        val first = ThemeOverlay().merge(
            "默认",
            YamlThemePatch(
                style = mapOf("key_text_size" to JsonPrimitive(24)),
                colors = mapOf("key_back_color" to "#FFF1F3F5"),
            ),
        )
        val merged = first.merge(
            "默认",
            YamlThemePatch(
                style = mapOf("round_corner" to JsonPrimitive(10)),
                colors = mapOf("key_text_color" to "#FF111827"),
                keys = listOf(YamlKeyPatch("space", mapOf("key_back_color" to "#FFF472B6"))),
            ),
        )
        val encoded = Json.encodeToString(ThemeOverlay.serializer(), merged)
        val decoded = Json.decodeFromString(ThemeOverlay.serializer(), encoded)
        val applied = YamlThemeApplier.applyOverlay(sampleTheme(), decoded)
        applied.style.keyTextSize shouldBe 24f
        applied.style.roundCorner shouldBe 10f
        applied.presetColorSchemes["默认"]?.get("key_back_color") shouldBe "#FFF1F3F5"
        applied.presetColorSchemes["默认"]?.get("key_text_color") shouldBe "#FF111827"
        applied.presetKeyboards["default"]?.keys?.first()?.keyBackColor shouldBe "#FFF472B6"
    }

    test("request points at chat/completions and carries the model") {
        val request = ThemeAssistProtocol.request(
            "https://api.example.com/v1",
            "secret",
            "test-model",
            ThemeAssistProtocol.systemPrompt(ThemeAssistMode.CUSTOM),
            ThemeAssistProtocol.userMessage("深色", JsonObject(mapOf("name" to JsonPrimitive("测试")))),
        )
        request.url.toString() shouldBe "https://api.example.com/v1/chat/completions"
        request.header("Authorization") shouldBe "Bearer secret"
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        body shouldContain "\"model\":\"test-model\""
        body shouldContain "keyBackColor"
    }

    test("system prompts list the editable fields") {
        ThemeAssistProtocol.systemPrompt(ThemeAssistMode.CUSTOM) shouldContain "keyBackColor"
        ThemeAssistProtocol.systemPrompt(ThemeAssistMode.CUSTOM) shouldContain "gradientEnabled"
        ThemeAssistProtocol.systemPrompt(ThemeAssistMode.THEME) shouldContain "key_back_color"
        ThemeAssistProtocol.systemPrompt(ThemeAssistMode.THEME) shouldContain "key_text_size"
    }
})

private fun sampleTheme(): Theme = Theme(
    name = "测试主题",
    style = GeneralStyle(keyTextSize = 18f, roundCorner = 6f, keyBorder = 1, horizontalGap = 4),
    presetColorSchemes = mapOf(
        "默认" to mapOf(
            "key_back_color" to "#FF111111",
            "key_text_color" to "#FFFFFFFF",
            "candidate_background" to "#FF000000",
        ),
    ),
    presetKeyboards = mapOf(
        "default" to TextKeyboard(
            keys = listOf(
                TextKey(label = "空格", click = KeyActionToken.Plain("space"), keyBackColor = "#FF000000"),
                TextKey(label = "回车", click = KeyActionToken.Plain("Return")),
            ),
        ),
    ),
)
