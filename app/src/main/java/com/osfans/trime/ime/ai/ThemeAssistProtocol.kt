/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The LLM contract behind the AI theme assistant. Two modes share the transport
 * but never the schema: custom keyboards speak camelCase of [com.osfans.trime.data.keymap.KeyAppearance],
 * YAML themes speak the snake_case of the theme file.
 *
 * The model is only allowed to emit the fields listed in [CustomThemePatch] /
 * [YamlThemePatch]; everything else is ignored by the parser and reported as a
 * warning. That keeps a hallucinated `preset_keyboards` or `click` out of the
 * user's layout.
 */
enum class ThemeAssistMode { CUSTOM, THEME }

data class SurfacePatch(
    val keysBackground: String? = null,
    val keysBorderColor: String? = null,
    val keysBorderWidth: Float? = null,
    val keysRadius: Float? = null,
    val keysBlur: Boolean? = null,
    val keyOpacity: Int? = null,
    val keyBlur: Int? = null,
    val blurRadius: Int? = null,
    val glassOpacity: Int? = null,
    val blurSaturation: Int? = null,
    val keyboardCorners: List<Float>? = null,
    val keyboardBorderColor: String? = null,
    val keyboardBorderWidth: Float? = null,
    val keyboardBorderGlow: Int? = null,
    val keyboardSideMargin: Float? = null,
    val keyboardTopMargin: Float? = null,
    val keyboardBottomMargin: Float? = null,
    val candidateHighlightColor: String? = null,
    val candidateHighlightRadius: Float? = null,
    val gradientEnabled: Boolean? = null,
    val gradientStart: String? = null,
    val gradientCenter: String? = null,
    val gradientEnd: String? = null,
    val gradientDirection: Int? = null,
    val gradientRadius: Int? = null,
) {
    val isEmpty get() = this == SurfacePatch()
}

data class GeometryPatch(
    val keyboardHeightDp: Int? = null,
    val landscapeHeightDp: Int? = null,
    val rowSpacing: Int? = null,
    val rowMargin: Int? = null,
    val toolbarHeightDp: Int? = null,
    val toolbarIconSizeDp: Int? = null,
    val horizontalGap: Int? = null,
    val heightPercent: Int? = null,
    val landscapeHeightPercent: Int? = null,
    val landscapeSplitPercent: Int? = null,
) {
    val isEmpty get() = this == GeometryPatch()
}

data class TypographyPatch(val size: Float? = null, val color: String? = null)

data class CustomKeyPatch(
    val row: Int? = null,
    val key: Int? = null,
    val label: String? = null,
    val appearance: Map<String, String> = emptyMap(),
)

data class CustomThemePatch(
    val summary: String = "",
    val keyAppearance: Map<String, String> = emptyMap(),
    val surface: SurfacePatch = SurfacePatch(),
    val geometry: GeometryPatch = GeometryPatch(),
    val typography: Map<String, TypographyPatch> = emptyMap(),
    val backgroundColor: String? = null,
    val keys: List<CustomKeyPatch> = emptyList(),
) {
    val isEmpty
        get() = keyAppearance.isEmpty() && surface.isEmpty && geometry.isEmpty &&
            typography.isEmpty() && backgroundColor == null && keys.isEmpty()
}

data class YamlKeyPatch(val match: String, val appearance: Map<String, String>)

data class YamlThemePatch(
    val summary: String = "",
    val style: Map<String, JsonElement> = emptyMap(),
    val colors: Map<String, String> = emptyMap(),
    val keys: List<YamlKeyPatch> = emptyList(),
) {
    val isEmpty get() = style.isEmpty() && colors.isEmpty() && keys.isEmpty()
}

object ThemeAssistProtocol {
    private const val MAX_REQUEST = 2000

    fun systemPrompt(mode: ThemeAssistMode): String = when (mode) {
        ThemeAssistMode.CUSTOM -> CUSTOM_PROMPT
        ThemeAssistMode.THEME -> THEME_PROMPT
    }

    fun userMessage(request: String, snapshot: JsonObject): String = buildJsonObject {
        put("request", request.trim())
        put("current", snapshot)
    }.toString()

    fun chatBody(model: String, system: String, user: String): String = buildJsonObject {
        put("model", model)
        put("stream", false)
        put("temperature", 0.2)
        put("max_tokens", 1800)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            add(buildJsonObject { put("role", "user"); put("content", user) })
        }
    }.toString()

    /**
     * Models sometimes wrap JSON in a fenced block or add a sentence before it.
     * Find the first balanced object, respecting strings and escapes, instead of
     * trusting the whole response to be a document.
     */
    fun extractJson(content: String): JsonObject {
        val text = content.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
        val start = text.indexOf('{')
        require(start >= 0) { "AI 没有返回 JSON，请重试或换一个模型" }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { Json.parseToJsonElement(text.substring(start, index + 1)).jsonObject }
                            .getOrElse { throw IllegalArgumentException("AI 返回的 JSON 无法解析，请重试或换一个模型") }
                    }
                }
            }
        }
        throw IllegalArgumentException("AI 返回的 JSON 不完整，请重试或换一个模型")
    }

    fun parseCustom(root: JsonObject, warnings: MutableList<String> = mutableListOf()): CustomThemePatch {
        val keyAppearance = mutableMapOf<String, String>()
        root.obj("keyAppearance")?.forEach { (name, value) ->
            val text = value.textValue()
            if (text == null) warnings += "keyAppearance.$name 的格式不可用，已忽略" else keyAppearance[name] = text
        }
        val typography = root.obj("typography").orEmptyJson().mapNotNull { (role, value) ->
            val obj = value as? JsonObject ?: run { warnings += "typography.$role 不是对象，已忽略"; return@mapNotNull null }
            val size = obj.number("size", "typography.$role.size", warnings)?.toFloat()
            val color = obj.string("color", "typography.$role.color", warnings)
            if (size == null && color == null) null else role to TypographyPatch(size, color)
        }.toMap()
        val keys = root.array("keys").orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: run { warnings += "keys 里有一项不是对象，已忽略"; return@mapNotNull null }
            val appearance = mutableMapOf<String, String>()
            obj.obj("appearance")?.forEach { (name, value) ->
                val text = value.textValue()
                if (text == null) warnings += "单键 appearance.$name 的格式不可用，已忽略" else appearance[name] = text
            }
            if (appearance.isEmpty()) {
                warnings += "单键修改没有外观字段，已忽略"
                null
            } else CustomKeyPatch(
                row = obj.integer("row", "keys.row", warnings),
                key = obj.integer("key", "keys.key", warnings),
                label = obj.string("label", "keys.label", warnings),
                appearance = appearance,
            )
        }
        return CustomThemePatch(
            summary = root.string("summary").orEmpty(),
            keyAppearance = keyAppearance,
            surface = parseSurface(root.obj("surface"), warnings),
            geometry = parseGeometry(root.obj("geometry"), warnings),
            typography = typography,
            backgroundColor = root.string("backgroundColor") ?: root.string("background_color"),
            keys = keys,
        )
    }

    fun parseTheme(root: JsonObject, warnings: MutableList<String> = mutableListOf()): YamlThemePatch {
        val style = mutableMapOf<String, JsonElement>()
        root.obj("style")?.forEach { (name, value) ->
            if (value is JsonPrimitive && value.content.toDoubleOrNull() != null) style[name] = value
            else warnings += "style.$name 不是数字，已忽略"
        }
        val colors = mutableMapOf<String, String>()
        root.obj("colors")?.forEach { (name, value) ->
            val text = value.textValue()
            if (text == null) warnings += "colors.$name 的格式不可用，已忽略" else colors[name] = text
        }
        val keys = root.array("keys").orEmpty().mapNotNull { element ->
            val obj = element as? JsonObject ?: run { warnings += "keys 里有一项不是对象，已忽略"; return@mapNotNull null }
            val match = obj.string("match") ?: obj.string("label") ?: obj.string("binding")
            if (match.isNullOrBlank()) {
                warnings += "单键修改缺少 match，已忽略"
                return@mapNotNull null
            }
            val appearance = mutableMapOf<String, String>()
            (obj.obj("appearance") ?: obj.obj("fields"))?.forEach { (name, value) ->
                val text = value.textValue()
                if (text == null) warnings += "单键 appearance.$name 的格式不可用，已忽略" else appearance[name] = text
            }
            if (appearance.isEmpty()) null else YamlKeyPatch(match, appearance)
        }
        return YamlThemePatch(
            summary = root.string("summary").orEmpty(),
            style = style,
            colors = colors,
            keys = keys,
        )
    }

    private fun parseSurface(obj: JsonObject?, warnings: MutableList<String>): SurfacePatch {
        if (obj == null) return SurfacePatch()
        fun number(name: String) = obj.number(name, "surface.$name", warnings)?.toFloat()
        fun int(name: String) = obj.integer(name, "surface.$name", warnings)
        fun bool(name: String) = obj.bool(name, "surface.$name", warnings)
        fun color(name: String) = obj.string(name, "surface.$name", warnings)
        val corners = obj.array("keyboardCorners")?.mapNotNull { it.textValue()?.toFloatOrNull() }
        if (corners != null && corners.size != 4) warnings += "surface.keyboardCorners 需要 4 个数字，已忽略"
        return SurfacePatch(
            keysBackground = color("keysBackground"),
            keysBorderColor = color("keysBorderColor"),
            keysBorderWidth = number("keysBorderWidth"),
            keysRadius = number("keysRadius"),
            keysBlur = bool("keysBlur"),
            keyOpacity = int("keyOpacity"),
            keyBlur = int("keyBlur"),
            blurRadius = int("blurRadius"),
            glassOpacity = int("glassOpacity"),
            blurSaturation = int("blurSaturation"),
            keyboardCorners = corners?.takeIf { it.size == 4 },
            keyboardBorderColor = color("keyboardBorderColor"),
            keyboardBorderWidth = number("keyboardBorderWidth"),
            keyboardBorderGlow = int("keyboardBorderGlow"),
            keyboardSideMargin = number("keyboardSideMargin"),
            keyboardTopMargin = number("keyboardTopMargin"),
            keyboardBottomMargin = number("keyboardBottomMargin"),
            candidateHighlightColor = color("candidateHighlightColor"),
            candidateHighlightRadius = number("candidateHighlightRadius"),
            gradientEnabled = bool("gradientEnabled"),
            gradientStart = color("gradientStart"),
            gradientCenter = color("gradientCenter"),
            gradientEnd = color("gradientEnd"),
            gradientDirection = int("gradientDirection"),
            gradientRadius = int("gradientRadius"),
        )
    }

    private fun parseGeometry(obj: JsonObject?, warnings: MutableList<String>): GeometryPatch {
        if (obj == null) return GeometryPatch()
        fun int(name: String) = obj.integer(name, "geometry.$name", warnings)
        return GeometryPatch(
            keyboardHeightDp = int("keyboardHeightDp"),
            landscapeHeightDp = int("landscapeHeightDp"),
            rowSpacing = int("rowSpacing"),
            rowMargin = int("rowMargin"),
            toolbarHeightDp = int("toolbarHeightDp"),
            toolbarIconSizeDp = int("toolbarIconSizeDp"),
            horizontalGap = int("horizontalGap"),
            heightPercent = int("heightPercent"),
            landscapeHeightPercent = int("landscapeHeightPercent"),
            landscapeSplitPercent = int("landscapeSplitPercent"),
        )
    }

    /** Body for the OpenAI-compatible endpoint used by [ThemeAssistService]. */
    internal fun requestBody(model: String, system: String, user: String) =
        chatBody(model, system, user).toRequestBody("application/json; charset=utf-8".toMediaType())

    internal fun request(endpoint: String, apiKey: String, model: String, system: String, user: String): Request =
        Request.Builder().url(TextProtocol.chatEndpoint(endpoint))
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody(model, system, user))
            .build()

    private const val CUSTOM_PROMPT = """你是一位键盘外观设计助手。用户会用自然语言描述想要的键盘主题，你只负责生成外观修改参数。
硬性规则：
1. 只输出一个 JSON 对象，不要 Markdown 代码块、解释、前后缀或思考过程。
2. 只能修改下面列出的字段；不要改按键文字、布局、绑定、字体文件、图片或视频。
3. 颜色用 #AARRGGBB 或 #RRGGBB，alpha 在前；不要用颜色名、rgb() 或渐变字符串。
4. 数值必须在范围内；超出范围的请求在 summary 里说明，并只输出能做的部分。
5. 保证文字与背景有足够对比度：深色背景用浅色字，浅色背景用深色字。
6. 用户没要求的字段不要输出；不确定就不要改。summary 用一句简短中文说明改了什么。

输出结构（所有字段可选）：
{
  "summary": "...",
  "keyAppearance": { "keyBackColor": "#FF1F2937", "keyTextColor": "#FFF9FAFB", "roundCorner": 12, "keyBorder": 0, "keyTextSize": 22 },
  "surface": { "keyOpacity": 85, "keyBlur": 20, "glassOpacity": 30, "keysBackground": "#CC111827", "keysRadius": 12, "keyboardCorners": [16,16,16,16], "gradientEnabled": true, "gradientStart": "#FF6C7BD9", "gradientCenter": "#FFB173C9", "gradientEnd": "#FFF08A7A", "gradientDirection": 7, "gradientRadius": 120 },
  "geometry": { "keyboardHeightDp": 260, "rowSpacing": 6, "rowMargin": 8, "horizontalGap": 4 },
  "typography": { "candidate": {"size": 20, "color": "#FFF9FAFB"}, "comment": {"size": 12, "color": "#FF9CA3AF"} },
  "backgroundColor": "#FF101418",
  "keys": [ { "row": 3, "key": 6, "appearance": { "keyBackColor": "#FFF472B6", "roundCorner": 14 } } ]
}

字段说明：
- keyAppearance（整键盘按键默认外观，camelCase）：
  颜色：keyTextColor, keySymbolColor, keyBackColor, keyBorderColor, hilitedKeyTextColor, hilitedKeySymbolColor, hilitedKeyBackColor, hilitedKeyBorderColor, keyOnTextColor, keyOnBackColor, keyOnBorderColor, iconTint
  字号：keyTextSize 0-200, symbolTextSize 0-200, hintTextSize 0-200
  形状：roundCorner -1-200（-1 继承）, keyBorder -1-32（-1 继承）
  偏移：keyTextOffsetX/Y, keySymbolOffsetX/Y, keyHintOffsetX/Y, keyPressOffsetX/Y（-500-500）
  显示：showSymbol/showHint true 或 false；symbolAlign/hintAlign 用 left/center/right；symbolInsetX/Y、hintInsetX/Y 0-48；iconSize 0-200；iconOffsetX/Y
- surface（面板、玻璃、模糊、渐变）：
  keysBackground / keysBorderColor（颜色），keysBorderWidth 0-32，keysRadius 0-64，keysBlur true/false，
  keyOpacity 0-100，keyBlur 0-100，blurRadius 2-32，glassOpacity 0-100，blurSaturation 100-200，
  keyboardCorners 依次是左上、右上、右下、左下，0-64，
  keyboardBorderColor，keyboardBorderWidth 0-5，keyboardBorderGlow 0-100，
  keyboardSideMargin / keyboardTopMargin / keyboardBottomMargin 0-20，
  candidateHighlightColor，candidateHighlightRadius -1-32，
  gradientEnabled，gradientStart/Center/End（颜色），gradientDirection 0-9，gradientRadius 10-200
- geometry：keyboardHeightDp 100-800，landscapeHeightDp 100-800，rowSpacing 0-32，rowMargin 0-48，
  toolbarHeightDp 0-120，toolbarIconSizeDp 0-96，horizontalGap 0-32，
  heightPercent 50-200，landscapeHeightPercent 50-200，landscapeSplitPercent 0-100
- typography（自定义键盘候选/注释等文字）：candidate, candidateHl, comment, label, popup, text, toolbar；
  每项 {"size": 0-200, "color": 颜色}；candidateHl 只支持 color。
- backgroundColor：键盘背景纯色；写 "transparent" 表示透明。
- keys（单键微调）：用 current.keys 里给出的 row/key 坐标；appearance 字段同 keyAppearance。"""

    private const val THEME_PROMPT = """你是一位同文输入法主题设计助手。用户会用自然语言描述想要的键盘主题，你只负责生成外观修改参数。
硬性规则：
1. 只输出一个 JSON 对象，不要 Markdown 代码块、解释、前后缀或思考过程。
2. 只能修改下面列出的 style / colors / 单键 appearance；不要改布局、绑定、按键文字、字体文件、图片或视频。
3. 颜色用 #AARRGGBB 或 #RRGGBB，alpha 在前；不要用颜色名、rgb() 或渐变字符串。
4. 数值必须在范围内；超出范围的请求在 summary 里说明，并只输出能做的部分。
5. 保证文字与背景有足够对比度：深色背景用浅色字，浅色背景用深色字。
6. 用户没要求的字段不要输出；不确定就不要改。summary 用一句简短中文说明改了什么。
7. colors 修改的是当前配色方案；style 修改对所有配色方案生效。

输出结构（所有字段可选）：
{
  "summary": "...",
  "style": { "key_text_size": 22, "round_corner": 12, "key_border": 0, "keyboard_height": 260, "horizontal_gap": 6 },
  "colors": { "key_back_color": "#FF1F2937", "key_text_color": "#FFF9FAFB", "key_border_color": "#00000000", "keyboard_back_color": "#FF101418", "candidate_background": "#FF151A22", "candidate_text_color": "#FFF9FAFB", "hilited_candidate_back_color": "#FF3B82F6" },
  "keys": [ { "match": "space", "appearance": { "key_back_color": "#FFF472B6", "round_corner": 14 } } ]
}

字段说明：
- style（snake_case）：
  keyboard_height 0-2000, keyboard_height_land 0-2000, key_height 0-400, key_width 0-100,
  horizontal_gap 0-64, vertical_gap 0-64, round_corner 0-64, key_border 0-32,
  key_text_size 0-200, key_long_text_size 0-200, symbol_text_size 0-200, label_text_size 0-200,
  candidate_text_size 0-200, candidate_view_height 0-200, candidate_corner_radius 0-64,
  candidate_border_round 0-64, candidate_padding 0-100, candidate_spacing 0-64,
  comment_text_size 0-200, comment_height 0-100, popup_text_size 0-200, popup_key_height 0-200,
  popup_width 0-1000, popup_height 0-1000, shadow_radius 0-64,
  keyboard_padding 0-100, keyboard_padding_left 0-100, keyboard_padding_right 0-100,
  keyboard_padding_bottom 0-100, keyboard_padding_land 0-100, keyboard_padding_land_bottom 0-100
- colors：只能用这些标准颜色键：
  back_color, border_color, candidate_background, candidate_border_color, candidate_separator_color,
  candidate_text_color, comment_text_color, hilited_back_color, hilited_candidate_back_color,
  hilited_candidate_button_color, hilited_candidate_text_color, hilited_comment_text_color,
  hilited_key_back_color, hilited_key_border_color, hilited_key_text_color, hilited_key_symbol_color,
  hilited_label_color, hilited_off_key_back_color, hilited_off_key_border_color, hilited_off_key_text_color,
  hilited_off_key_symbol_color, hilited_on_key_back_color, hilited_on_key_border_color,
  hilited_on_key_text_color, hilited_on_key_symbol_color, hilited_popup_back_color,
  hilited_popup_text_color, hilited_text_color, key_back_color, key_border_color, key_text_color,
  key_symbol_color, keyboard_back_color, keyboard_background, label_color,
  liquid_keyboard_background, long_text_back_color, long_text_color, off_key_back_color,
  off_key_border_color, off_key_text_color, off_key_symbol_color, on_key_back_color,
  on_key_border_color, on_key_text_color, on_key_symbol_color, popup_back_color, popup_text_color,
  root_background, shadow_color, text_back_color, text_color
- keys（单键微调）：match 填 current.keys 里的 label 或 binding；appearance 可用：
  key_back_color, key_text_color, key_border_color, key_symbol_color,
  hilited_key_back_color, hilited_key_text_color, hilited_key_border_color, hilited_key_symbol_color,
  key_text_size 0-200, symbol_text_size 0-200, hint_text_size 0-200, round_corner 0-64, key_border 0-32"""
}

object ThemeAssistService {
    suspend fun generate(
        config: TextModelConfig,
        mode: ThemeAssistMode,
        request: String,
        snapshot: JsonObject,
    ): JsonObject {
        config.validate(allowLocalHttp = com.osfans.trime.BuildConfig.DEBUG)
        require(request.isNotBlank()) { "请先描述你想要的主题" }
        require(request.length <= 2000) { "需求太长了，请精简到 2000 字以内" }
        val body = TextHttp.execute(
            ThemeAssistProtocol.request(
                config.endpoint,
                config.apiKey,
                config.model,
                ThemeAssistProtocol.systemPrompt(mode),
                ThemeAssistProtocol.userMessage(request, snapshot),
            ),
        )
        return ThemeAssistProtocol.extractJson(TextProtocol.chatResult(body))
    }
}

// ---------------------------------------------------------------- JSON helpers

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.string(name: String, path: String? = null, warnings: MutableList<String>? = null): String? {
    val value = this[name] ?: return null
    val text = value.textValue()
    if (text == null) {
        if (path != null) warnings?.add("$path 不是字符串，已忽略")
        return null
    }
    return text
}

private fun JsonObject.number(name: String, path: String, warnings: MutableList<String>): Double? {
    val value = this[name] ?: return null
    val text = value.textValue() ?: run {
        warnings += "$path 不是数字，已忽略"
        return null
    }
    return text.toDoubleOrNull() ?: run {
        warnings += "$path 不是数字，已忽略"
        null
    }
}

private fun JsonObject.integer(name: String, path: String, warnings: MutableList<String>): Int? {
    val value = number(name, path, warnings) ?: return null
    val int = value.toInt()
    if (value != int.toDouble()) {
        warnings += "$path 需要整数，已忽略"
        return null
    }
    return int
}

private fun JsonObject.bool(name: String, path: String, warnings: MutableList<String>): Boolean? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive ?: run {
        warnings += "$path 不是布尔值，已忽略"
        return null
    }
    val result = primitive.booleanOrNull ?: when (primitive.contentOrNull?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    if (result == null) warnings += "$path 不是布尔值，已忽略"
    return result
}

private fun JsonElement.textValue(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    else -> null
}

private fun JsonObject?.orEmptyJson(): JsonObject = this ?: JsonObject(emptyMap())
