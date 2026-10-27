/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.model.TextKeyboard.TextKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Only native appearance fields are editable here; actions and geometry have their own editors. */
object KeyAppearance {
    // Theme bean serializers are read-only. The editor owns a round-trip model.
    @Serializable
    data class Style(
        val labelSymbol: String = "", val hint: String = "",
        val keyTextSize: Float = 0f, val symbolTextSize: Float = 0f,
        val roundCorner: Float = -1f, val keyBorder: Int = -1,
        val keyTextColor: String = "", val keySymbolColor: String = "",
        val keyBackColor: String = "", val keyBorderColor: String = "",
        val hilitedKeyTextColor: String = "", val hilitedKeySymbolColor: String = "",
        val hilitedKeyBackColor: String = "", val hilitedKeyBorderColor: String = "",
        val keyTextOffsetX: Float = 0f, val keyTextOffsetY: Float = 0f,
        val keySymbolOffsetX: Float = 0f, val keySymbolOffsetY: Float = 0f,
        val keyHintOffsetX: Float = 0f, val keyHintOffsetY: Float = 0f,
        val keyPressOffsetX: Float = 0f, val keyPressOffsetY: Float = 0f,
        val popup: List<String> = emptyList(), val sendBindings: Boolean? = null,
    ) {
        fun toTextKey() = TextKey(labelSymbol = labelSymbol, hint = hint,
            keyTextSize = keyTextSize, symbolTextSize = symbolTextSize, roundCorner = roundCorner, keyBorder = keyBorder,
            keyTextColor = keyTextColor, keySymbolColor = keySymbolColor, keyBackColor = keyBackColor, keyBorderColor = keyBorderColor,
            hilitedKeyTextColor = hilitedKeyTextColor, hilitedKeySymbolColor = hilitedKeySymbolColor,
            hilitedKeyBackColor = hilitedKeyBackColor, hilitedKeyBorderColor = hilitedKeyBorderColor,
            keyTextOffsetX = keyTextOffsetX, keyTextOffsetY = keyTextOffsetY,
            keySymbolOffsetX = keySymbolOffsetX, keySymbolOffsetY = keySymbolOffsetY,
            keyHintOffsetX = keyHintOffsetX, keyHintOffsetY = keyHintOffsetY,
            keyPressOffsetX = keyPressOffsetX, keyPressOffsetY = keyPressOffsetY, popup = popup, sendBindings = sendBindings ?: true)
    }
    val fields = linkedMapOf(
        "labelSymbol" to "符号文字", "hint" to "提示文字",
        "keyTextSize" to "主文字字号 dp（0 继承）", "symbolTextSize" to "符号字号 dp（0 继承）",
        "roundCorner" to "圆角 dp（-1 继承）", "keyBorder" to "边框宽度 dp（-1 继承）",
        "keyTextColor" to "文字颜色", "keySymbolColor" to "符号颜色",
        "keyBackColor" to "背景颜色或图片", "keyBorderColor" to "边框颜色",
        "hilitedKeyTextColor" to "按下文字颜色", "hilitedKeySymbolColor" to "按下符号颜色",
        "hilitedKeyBackColor" to "按下背景颜色或图片", "hilitedKeyBorderColor" to "按下边框颜色",
        "keyTextOffsetX" to "文字横向偏移 dp", "keyTextOffsetY" to "文字纵向偏移 dp",
        "keySymbolOffsetX" to "符号横向偏移 dp", "keySymbolOffsetY" to "符号纵向偏移 dp",
        "keyHintOffsetX" to "提示横向偏移 dp", "keyHintOffsetY" to "提示纵向偏移 dp",
        "keyPressOffsetX" to "按下横向偏移 dp", "keyPressOffsetY" to "按下纵向偏移 dp",
        "popup" to "弹出备选（每行一个）", "sendBindings" to "发送手势绑定",
    )
    private val json = Json { encodeDefaults = true }
    fun values(style: Style): JsonObject = json.encodeToJsonElement(Style.serializer(), style).jsonObject
    fun textValues(style: Style): Map<String, String> = values(style).filterKeys { it in fields }.mapValues { (_, value) ->
        if (value is JsonArray) value.joinToString("\n") { it.jsonPrimitive.content } else if (value == JsonNull) "" else value.jsonPrimitive.content
    }
    fun yamlName(name: String): String = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    fun merge(defaults: Style, key: Style): Style {
        val base = values(defaults).toMutableMap()
        val empty = values(Style())
        values(key).filter { (name, value) -> name in fields && value != empty[name] }.forEach { (name, value) -> base[name] = value }
        return json.decodeFromJsonElement(Style.serializer(), JsonObject(base))
    }
    fun parse(values: Map<String, String>): Style {
        val prototype = this.values(Style())
        val result = prototype.toMutableMap()
        values.forEach { (name, text) ->
            require(name in fields)
            val old = prototype.getValue(name)
            result[name] = when {
                name == "sendBindings" -> if (text.isBlank()) JsonNull else JsonPrimitive(text.toBooleanStrict())
                name == "popup" -> JsonArray(text.lines().filter { it.isNotEmpty() }.map(::JsonPrimitive))
                old is JsonPrimitive && old.booleanOrNull != null -> JsonPrimitive(text.toBooleanStrict())
                old is JsonPrimitive && old.isString -> JsonPrimitive(text)
                else -> {
                    val value = text.toFloatOrNull()
                    require(value != null && value.isFinite() && value in -500f..500f) { "${fields[name]}：请输入 -500 至 500 的数字" }
                    if (name == "keyBorder") {
                        require(value % 1f == 0f && value in -1f..32f) { "边框宽度须为 -1 至 32 的整数" }
                        JsonPrimitive(value.toInt())
                    } else {
                        if (name.endsWith("Size")) require(value in 0f..200f) { "字号须为 0 至 200" }
                        if (name == "roundCorner") require(value in -1f..200f) { "圆角须为 -1 至 200" }
                        JsonPrimitive(value)
                    }
                }
            }
        }
        return json.decodeFromJsonElement(Style.serializer(), JsonObject(result))
    }
    /** Preserve readable contrast and visible press feedback for solid custom keys. */
    fun pressedColor(raw: String): String {
        val hex = raw.removePrefix("#")
        val color = hex.toLongOrNull(16)?.takeIf { hex.length == 6 || hex.length == 8 } ?: return "#33FFFFFF"
        val alpha = (if (hex.length == 8) (color shr 24).toInt() else 255).coerceAtLeast(48)
        val channels = listOf(16, 8, 0).map { ((color shr it) and 255).toInt() }
        val light = channels.sum() >= 420
        val mixed = channels.map { if (light) it * 88 / 100 else it + (255 - it) * 15 / 100 }
        return "#%02X%02X%02X%02X".format(alpha, mixed[0], mixed[1], mixed[2])
    }
}
