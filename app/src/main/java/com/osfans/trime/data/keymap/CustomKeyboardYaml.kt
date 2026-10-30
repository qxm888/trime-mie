/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.osfans.trime.data.baiduskin.ImportedKeyStyle
import com.osfans.trime.data.baiduskin.ImportedSkin
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomRow
import com.osfans.trime.data.theme.ThemeYaml
import com.osfans.trime.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Native Trime YAML fields are authoritative; mie fields preserve editor-only information. */
object CustomKeyboardYaml {
    private const val FORMAT = "trime-custom-keyboards-yaml"
    private val json = Json { encodeDefaults = true }
    private val metadata = Yaml(configuration = YamlConfiguration(strictMode = false, decodeEnumCaseInsensitive = true))
    private val behaviors = linkedMapOf("click" to "click", "lazyDoubleClick" to "lazy_double_click", "longClick" to "long_click", "doubleClick" to "double_click",
        "swipeUp" to "swipe_up", "swipeDown" to "swipe_down", "swipeLeft" to "swipe_left", "swipeRight" to "swipe_right",
        "composing" to "composing", "hasMenu" to "has_menu", "paging" to "paging", "combo" to "combo", "ascii" to "ascii")

    @Serializable private data class KeyboardInfo(
        val importedActions: Map<String, com.osfans.trime.data.theme.model.PresetKey> = emptyMap(),
        val surfaceAppearance: com.osfans.trime.ime.appearance.SurfaceAppearanceConfig? = null,
        val unifiedBackground: Boolean? = null,
        val typography: Map<String, FontSpec> = emptyMap(),
        val floatingCandidates: com.osfans.trime.ime.appearance.FloatingCandidateStyle? = null,
        val toolbar: List<ToolbarOverrides.Entry> = emptyList(),
        val toolbarBackground: CustomBackground? = null,
        val toolbarAlignment: String = "legacy",
        val rowAlignments: Map<Int,String> = emptyMap(),
        val allKeysBackground: String = "",
        val importedSkin: ImportedSkin? = null,
        val importedHeightPercent: Int = 100,
        val keyboardHeightDp: Int = 0,
        val landscapeHeightDp: Int = 0,
        val toolbarHeightDp: Int = 0,
        val secondaryOf: String = "",
        val exportedHeight: Int = 0,
        val exportedBackground: String = "",
        val exportedRowSpacing: Int = 4,
        val rowMargin: Int = 0,
        val keyAppearance: Map<String, String> = emptyMap(),
        val options: KeyboardOptions = KeyboardOptions(),
    )
    @Serializable private data class KeyInfo(
        val row: Int = 1, val spacer: Boolean = false,
        val skinId: String = "", val skin: ImportedKeyStyle? = null,
        val originalWidth: Float = 0f, val exportedWidth: Float = 0f, val exportedHeight: Int = 0, val exportedBackground: String = "",
        val background: CustomBackground? = null,
        val appearance: Map<String, String> = emptyMap(),
        val exportedAppearance: Map<String, String> = emptyMap(),
    )

    private fun string(value: String) = JsonPrimitive(value)
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun background(value: CustomBackground): String = when(value.type) {
        CustomBackground.Type.COLOR -> value.color
        CustomBackground.Type.TRANSPARENT -> "#00000000"
        CustomBackground.Type.IMAGE -> value.image
        CustomBackground.Type.VIDEO -> "video:" + value.image
    }
    private fun background(value: String): CustomBackground = when {
        value.startsWith("video:") -> CustomBackground(CustomBackground.Type.VIDEO, image = value.removePrefix("video:"))
        value.isBlank() -> CustomBackground(CustomBackground.Type.TRANSPARENT)
        value.startsWith('#') -> CustomBackground(CustomBackground.Type.COLOR, color = value).normalized()
        else -> CustomBackground(CustomBackground.Type.IMAGE, image = value)
    }

    fun encode(layouts: Map<String, CustomKeyboard>): String {
        require(layouts.isNotEmpty()) { "请先选择要导出的键盘" }
        val base = StandaloneKeyboardTheme.base()
        // Theme model serializers are intentionally read-only. Export ordinary scalar fields.
        val presets = linkedMapOf<String, JsonElement>()
        (com.osfans.trime.data.theme.Theme.BUILTIN_ACTIONS + base.presetKeys + layouts.values.flatMap { it.importedActions.entries }.associate { it.key to it.value }).forEach { (id, preset) ->
            val values = linkedMapOf<String, JsonElement>()
            mapOf("send" to preset.send, "command" to preset.command, "option" to preset.option, "select" to preset.select,
                "toggle" to preset.toggle, "label" to preset.label, "shift_lock" to preset.shiftLock,
                "commit" to preset.commit, "text" to preset.text).forEach { (k,v) -> if (v.isNotEmpty()) values[k] = string(v) }
            if (preset.repeatable) values["repeatable"] = JsonPrimitive(true)
            if (preset.states.isNotEmpty()) values["states"] = JsonArray(preset.states.map(::string))
            presets[id] = JsonObject(values)
        }
        val targetIds = layouts.keys + layouts.values.flatMap { keyboard -> keyboard.rows.flatMap { row -> row.keys.flatMap { it.bindings.values } } }
            .filter { it.startsWith("Keyboard_custom_") }.map { it.removePrefix("Keyboard_") }
        targetIds.forEach { id -> presets["Keyboard_$id"] = obj("send" to string("Eisu_toggle"), "select" to string(id), "label" to string(layouts[id]?.name ?: "切换键盘")) }
        val keyboardNodes = linkedMapOf<String, JsonElement>()
        // The alias lets the exported file work as a traditional theme too; it is not a second layout.
        keyboardNodes["default"] = obj("import_preset" to string(layouts.keys.first()))
        layouts.forEach { (id, kb) ->
            val keys = mutableListOf<JsonElement>()
            kb.rows.forEachIndexed { rowIndex, row ->
                ButtonAlignment.slots(row).forEach slot@{ (key,width) ->
                    if (key == null) {
                        keys += obj("width" to JsonPrimitive(width),"height" to JsonPrimitive(row.height),
                            "mie" to json.encodeToJsonElement(KeyInfo.serializer(),KeyInfo(rowIndex+1,spacer=true)))
                        return@slot
                    }
                    val effective = KeyAppearance.merge(kb.keyAppearance, key.appearance)
                    val bg = kb.allKeysBackground.ifBlank { effective.keyBackColor.ifEmpty { background(key.resolvedBackground()) } }
                    val data = linkedMapOf<String, JsonElement>("label" to string(key.label), "width" to JsonPrimitive(width),
                        "height" to JsonPrimitive(row.height), "key_back_color" to string(bg))
                    KeyAppearance.values(effective).filter { (name, value) -> name in KeyAppearance.fields && name != "keyBackColor" && value != JsonNull }.forEach { (name, value) ->
                        data[KeyAppearance.yamlName(name)] = value
                    }
                    behaviors.forEach { (behavior, yamlName) -> key.bindings[behavior]?.let { data[yamlName] = string(it) } }
                    data["mie"] = json.encodeToJsonElement(KeyInfo.serializer(), KeyInfo(rowIndex + 1, skinId = key.skinId, skin = key.skin,
                        originalWidth = key.width, exportedWidth = width, exportedHeight = row.height, exportedBackground = bg,
                        background = key.resolvedBackground(), appearance = KeyAppearance.textValues(key.appearance), exportedAppearance = KeyAppearance.textValues(effective) + ("keyBackColor" to bg)))
                    keys += JsonObject(data)
                }

            }
            val layout = CustomKeyboards.run { kb.toTextKeyboard() }
            val portraitHeight = if (kb.keyboardHeightDp > 0) kb.keyboardHeightDp
                else if (kb.importedSkin != null) 240 * kb.importedHeightPercent / 100 else layout.keyboardHeight
            val landHeight = if (kb.landscapeHeightDp > 0) kb.landscapeHeightDp
                else if (kb.importedSkin != null) 180 * kb.importedHeightPercent / 100 else layout.keyboardHeightLand
            val bg = background(kb.resolvedBackground())
            keyboardNodes[id] = obj("name" to string(kb.name), "author" to string("同文输入法咩版"),
                "width" to JsonPrimitive(10), "height" to JsonPrimitive(60), "columns" to JsonPrimitive(-1),
                "keyboard_height" to JsonPrimitive(portraitHeight), "keyboard_height_land" to JsonPrimitive(landHeight),
                "vertical_gap" to JsonPrimitive(kb.rowSpacing), "horizontal_gap" to JsonPrimitive(layout.horizontalGap), "ascii_mode" to JsonPrimitive(layout.asciiMode),
                "reset_ascii_mode" to JsonPrimitive(layout.resetAsciiMode), "lock" to JsonPrimitive(layout.lock),
                "ascii_keyboard" to string(layout.asciiKeyboard), "landscape_keyboard" to string(layout.landscapeKeyboard),
                "landscape_split_percent" to JsonPrimitive(layout.landscapeSplitPercent), "label_transform" to string(layout.labelTransform.name.lowercase()),
                "keyboard_back_color" to string(bg),
                "mie" to json.encodeToJsonElement(KeyboardInfo.serializer(), KeyboardInfo(importedActions=kb.importedActions, toolbarAlignment=kb.toolbarAlignment, rowAlignments=kb.rows.mapIndexed { index,row -> index+1 to row.alignment }.toMap(), surfaceAppearance=kb.surfaceAppearance, unifiedBackground=kb.unifiedBackground, typography=kb.typography, floatingCandidates=kb.floatingCandidates, toolbar=kb.toolbar, toolbarBackground=kb.toolbarBackground,
                    allKeysBackground = kb.allKeysBackground, importedSkin = kb.importedSkin, importedHeightPercent = kb.importedHeightPercent, keyboardHeightDp = kb.keyboardHeightDp, landscapeHeightDp = kb.landscapeHeightDp, toolbarHeightDp = kb.toolbarHeightDp, secondaryOf = kb.secondaryOf, exportedHeight = portraitHeight, exportedBackground = bg, exportedRowSpacing = kb.rowSpacing, rowMargin = kb.rowMargin, keyAppearance = KeyAppearance.textValues(kb.keyAppearance), options = kb.options)), "keys" to JsonArray(keys))
        }
        val first = layouts.values.first()
        val root = obj("name" to string(if (layouts.size == 1) first.name else "自定义键盘合集"), "config_version" to string("1.0"),
            "author" to string("同文输入法咩版"),
            "mie" to obj("format" to string(FORMAT), "version" to JsonPrimitive(1), "functions" to json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(NamedFunctions.Definition.serializer()), NamedFunctions.list())),
            "style" to obj("color_scheme" to string("default"), "keyboard_height" to JsonPrimitive(240), "keyboard_height_land" to JsonPrimitive(180),
                "key_width" to JsonPrimitive(10), "key_height" to JsonPrimitive(60), "key_text_size" to JsonPrimitive(22),
                "keyboard_back_color" to string(background(first.resolvedBackground()))),
            "preset_color_schemes" to JsonObject(base.presetColorSchemes.mapValues { (_, scheme) -> JsonObject(scheme.mapValues { string(it.value) }) }),
            "tool_bar" to obj("buttons" to JsonArray(first.toolbar.filter { it.enabled }.map { entry ->
                obj("action" to string(entry.action), "foreground" to obj("style" to string(entry.icon.ifBlank { entry.action })))
            })),
            "preset_keys" to JsonObject(presets), "preset_keyboards" to JsonObject(keyboardNodes))
        return buildString {
            append("# 同文自定义键盘：可手动修改的 .trime.yaml\n")
            append("# preset_keyboards 下只包含本次勾选的键盘；default 仅为首个键盘的别名。\n")
            append("# 修改 label/click/long_click/swipe_up 等绑定，width 为百分比，height 为行高(dp)。\n")
            append("# mie.row 从1开始，用于保留行分组；每行首个按键的 height 决定行高。\n")
            append("# 修改 keyboard_height 会按比例调整总高度；修改单行 height 后未改总高度时以行高为准。\n")
            append("# mie 保存工具栏、百度原坐标和动画；普通同文只读取标准主题字段。\n")
            append("# 咩版导回时以各键的 click/long_click 等动作为准；preset_keys 用于传统主题加载。\n")
            append("# 图片/音频按文件路径引用，YAML 不包含素材文件，跨设备须另行复制素材或重导 .bds。\n")
            append("# 百度布局修改 width/height/vertical_gap/整体背景后，导回时会转为行布局，保留按键动效。\n")
            writeYaml(root, 0)
        }.also { require(it.toByteArray(Charsets.UTF_8).size <= 10 * 1024 * 1024) { "导出内容超过10MB，请分批选择键盘" } }
    }

    /** YAML scalars use JSON quoting, which is also valid YAML and preserves '#', ':', numbers and escapes. */
    private fun StringBuilder.writeYaml(value: JsonElement, indent: Int) {
        val pad = " ".repeat(indent)
        fun scalar(v: JsonElement) = v is JsonPrimitive || v is JsonArray && v.isEmpty() || v is JsonObject && v.isEmpty()
        when(value) {
            is JsonObject -> value.forEach { (key, child) ->
                append(pad).append(if (key.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) key else string(key).toString()).append(':')
                if (scalar(child)) append(' ').append(child.toString()).append('\n') else { append('\n'); writeYaml(child, indent + 2) }
            }
            is JsonArray -> value.forEach { child ->
                append(pad).append('-')
                if (scalar(child)) append(' ').append(child.toString()).append('\n') else { append('\n'); writeYaml(child, indent + 2) }
            }
            else -> append(pad).append(value.toString()).append('\n')
        }
    }

    fun functions(text: String): List<NamedFunctions.Definition> {
        val root=ThemeYaml.parser.parseToYamlNode(text).mapping ?: return emptyList()
        val node=root.pairs["mie"]?.get("functions") ?: return emptyList()
        return metadata.decodeFromYamlNode(kotlinx.serialization.builtins.ListSerializer(NamedFunctions.Definition.serializer()),node)
    }

    fun decode(text: String): Map<String, CustomKeyboard> {
        require(text.length <= 10 * 1024 * 1024) { "布局文件超过10MB" }
        val root = ThemeYaml.parser.parseToYamlNode(text).mapping ?: error("YAML根节点必须是映射")
        require(root.pairs["mie"]?.get("format")?.string == FORMAT && root.pairs["mie"]?.get("version")?.int == 1) { "请导入自定义布局导出的YAML；传统主题请从主题设置加载" }
        val source = root.pairs["preset_keyboards"]?.mapping?.pairs ?: error("缺少 preset_keyboards")
        val result = linkedMapOf<String, CustomKeyboard>()
        source.filterKeys { it != "default" }.forEach { (id, raw) ->
            require(id.startsWith(CustomKeyboards.ID_PREFIX)) { "自定义键盘ID无效：$id" }
            val node = raw.mapping ?: error("键盘 $id 必须为映射")
            val info = node.pairs["mie"]?.let { metadata.decodeFromYamlNode(KeyboardInfo.serializer(), it) } ?: KeyboardInfo()
            val grouped = sortedMapOf<Int, CustomRow>()
            var changedGeometry = false
            val list = node.pairs["keys"]?.sequence?.items ?: error("键盘 $id 缺少 keys")
            require(list.size <= 4096) { "单个键盘按键数量超过4096" }
            list.forEach keyLoop@{ item ->
                val key = item.mapping ?: error("按键必须是映射")
                val extra = key.pairs["mie"]?.let { metadata.decodeFromYamlNode(KeyInfo.serializer(), it) } ?: KeyInfo()
                require(extra.row in 1..128) { "mie.row 行号须为1–128" }
                val height = key.pairs["height"]?.int ?: 60
                require(height in 24..160) { "行高须为24–160dp" }
                val row = grouped.getOrPut(extra.row) { CustomRow(height = height, alignment = ButtonAlignment.normalized(info.rowAlignments[extra.row] ?: "left")) }
                if (extra.spacer) return@keyLoop
                val width = key.pairs["width"]?.float ?: 10f
                require(width.isFinite() && width > 0f && width <= 100f) { "按键宽度须大于0且不超过100" }
                val bindings = mutableMapOf<String, String>()
                behaviors.forEach { (behavior, yamlName) ->
                    key.pairs[yamlName]?.let { action -> bindings[behavior] = action.string ?: if (action.isNull) "" else error("$yamlName 必须是字符串；预置功能请填入动作名") }
                }
                val bg = key.pairs["key_back_color"]?.string ?: CustomBackground.KEY_COLOR
                val appearance = KeyAppearance.textValues(KeyAppearance.Style()).toMutableMap()
                appearance.putAll(extra.appearance)
                KeyAppearance.fields.keys.forEach { name ->
                    val value = key.pairs[KeyAppearance.yamlName(name)] ?: return@forEach
                    val text = if (name == "popup") value.sequence?.items?.joinToString("\n") { it.string.orEmpty() }.orEmpty() else value.string.orEmpty()
                    if (extra.exportedAppearance[name] != text) appearance[name] = text
                }
                if (width != extra.exportedWidth || height != extra.exportedHeight) changedGeometry = true
                row.keys += CustomKey(label = key.pairs["label"]?.string.orEmpty(), width = if(width == extra.exportedWidth && extra.originalWidth.isFinite() && extra.originalWidth > 0 && extra.originalWidth <= 100) extra.originalWidth else width, bindings = bindings,
                    background = if (bg == extra.exportedBackground) extra.background ?: background(bg) else background(bg), skinId = extra.skinId, skin = extra.skin.takeIf { bg == extra.exportedBackground }, appearance = KeyAppearance.parse(appearance))
            }
            require(grouped.isNotEmpty()) { "键盘不能没有行" }
            val rows = grouped.values.toMutableList()
            val spacing = node.pairs["vertical_gap"]?.int ?: 4
            val bg = node.pairs["keyboard_back_color"]?.string ?: CustomBackground.KEYBOARD_COLOR
            var skin = info.importedSkin
            if (changedGeometry || spacing != info.exportedRowSpacing || bg != info.exportedBackground ||
                (skin?.variants?.get("light/port") ?: skin?.variants?.values?.firstOrNull())?.keys?.any { original -> rows.flatMap { it.keys }.none { it.skinId == original.id } } == true) skin = null
            val targetHeight = node.pairs["keyboard_height"]?.int ?: info.exportedHeight
            var percent = info.importedHeightPercent
            var heightDp = info.keyboardHeightDp
            var heightDpLand = info.landscapeHeightDp
            val toolbarHeight = info.toolbarHeightDp
            val secondaryOf = info.secondaryOf
            if (targetHeight != info.exportedHeight && targetHeight > 0) {
                // A manually edited keyboard_height bakes into row heights; the dp override clears.
                heightDp = 0; heightDpLand = 0
                if (skin != null) {
                    percent = (targetHeight * 100f / 240).toInt()
                    require(percent in 70..150) { "百度键盘高度须为168–360dp" }
                } else {
                    val sum = rows.sumOf { it.height }
                    rows.forEach { row -> row.height = (row.height * targetHeight.toFloat() * 100 / sum / info.options.heightPercent).toInt() }
                    require(rows.all { it.height in 24..160 }) { "总高度使行高超出24–160dp范围" }
                }
            }
            if (heightDp > 0) require(heightDp in 80..800) { "键盘高度须为 80–800 dp" }
            if (heightDpLand > 0) require(heightDpLand in 80..800) { "横屏键盘高度须为 80–800 dp" }
            require(spacing in 0..minOf(32, rows.minOf { it.height } / 2)) { "行距超过允许范围" }
            val options = info.options.copy(
                horizontalGap = node.pairs["horizontal_gap"]?.int ?: info.options.horizontalGap,
                asciiMode = node.pairs["ascii_mode"]?.int ?: info.options.asciiMode,
                resetAsciiMode = node.pairs["reset_ascii_mode"]?.boolean ?: info.options.resetAsciiMode,
                lock = node.pairs["lock"]?.boolean ?: info.options.lock,
                asciiKeyboard = node.pairs["ascii_keyboard"]?.string ?: info.options.asciiKeyboard,
                landscapeKeyboard = node.pairs["landscape_keyboard"]?.string ?: info.options.landscapeKeyboard,
                landscapeSplitPercent = node.pairs["landscape_split_percent"]?.int ?: info.options.landscapeSplitPercent,
                uppercaseLabels = node.pairs["label_transform"]?.string?.let { it.equals("uppercase", true) } ?: info.options.uppercaseLabels,
            ).validated()
            val rowMargin = info.rowMargin
            require(rowMargin in 0..48) { "行边距须为 0–48 dp" }
            result[id] = CustomKeyboard(name = node.pairs["name"]?.string ?: id, rows = rows, rowSpacing = spacing, rowMargin = rowMargin,
                importedActions=info.importedActions, toolbarAlignment=info.toolbarAlignment, surfaceAppearance=info.surfaceAppearance, unifiedBackground=info.unifiedBackground, typography=info.typography, floatingCandidates=info.floatingCandidates, background = background(bg), toolbar = info.toolbar.toMutableList(), toolbarBackground = info.toolbarBackground, allKeysBackground = info.allKeysBackground,
                importedSkin = skin, importedHeightPercent = percent, keyboardHeightDp = heightDp, landscapeHeightDp = heightDpLand, toolbarHeightDp = toolbarHeight, secondaryOf = secondaryOf, keyAppearance = KeyAppearance.parse(info.keyAppearance), options = options)
        }
        require(result.isNotEmpty()) { "未找到可导入的自定义键盘" }
        return result
    }
}
