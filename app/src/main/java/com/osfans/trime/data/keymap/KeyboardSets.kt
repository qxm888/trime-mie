/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/** Set metadata is stored with the root, so existing backups remain self contained. */
@Serializable data class KeyboardSetConfig(
    val defaults: JsonObject = JsonObject(emptyMap()),
    val profiles: Map<String, String> = emptyMap(),
    val order: List<String> = emptyList(),
    val defaultPage: String = "",
)
@Serializable data class KeyboardSharedProfile(val id: String, val name: String, val group: String,
    val values: JsonObject, val revision: Long = 1)

/** Pure merge/navigation helpers; storage commits are performed by CustomKeyboards. */
object KeyboardSets {
    const val EDIT_PREFIX = "__mie_set:"
    val groups = linkedMapOf(
        "geometry" to "尺寸与间距",
        "background" to "背景", "appearance" to "按键外观", "fonts" to "字体",
        "toolbar" to "工具栏", "menus" to "功能菜单", "floating" to "候选窗与悬浮球", "bindings" to "常用绑定",
    )
    val fields = mapOf(
        "geometry" to setOf("keyboardHeightDp", "landscapeHeightDp", "rowSpacing", "rowMargin"),
        "background" to setOf("background", "toolbarBackground", "unifiedBackground"),
        "appearance" to setOf("surfaceAppearance", "keyAppearance", "allKeysBackground", "setKeyStyles"),
        "fonts" to setOf("typography"),
        "toolbar" to setOf("toolbar", "toolbarAlignment", "toolbarHeightDp", "importedActions"),
        "menus" to setOf("hiddenMenuItems"),
        "floating" to setOf("floatingCandidates"),
        "bindings" to setOf("setBindings", "importedActions"),
    )
    val allFields = fields.values.flatten().toSet()
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val emptyStyle by lazy { KeyAppearance.values(KeyAppearance.Style()) }
    fun encode(kb: CustomKeyboard) = json.encodeToJsonElement(CustomKeyboard.serializer(), kb).jsonObject
    fun decode(value: JsonObject) = json.decodeFromJsonElement(CustomKeyboard.serializer(), value)
    fun values(kb: CustomKeyboard, group: String? = null) = JsonObject(encode(kb).filterKeys { it in (fields[group] ?: allFields) })

    /** Object fields merge recursively; arrays, false, empty strings and explicit null replace. */
    fun overlay(base: JsonObject, patch: JsonObject): JsonObject = JsonObject(base.toMutableMap().apply {
        patch.forEach { (name, value) -> this[name] = if (value is JsonObject && value.keys == setOf("__mie_replace")) value.getValue("__mie_replace")
            else if (value is JsonObject && this[name] is JsonObject) overlay(this.getValue(name).jsonObject, value) else value }
    })
    fun difference(before: JsonObject, after: JsonObject): JsonObject = JsonObject(buildMap {
        (before.keys + after.keys).forEach { name ->
            val a = before[name]; val b = after[name] ?: JsonNull
            if (a != b) put(name, if (a is JsonObject && b is JsonObject) {
                if (b.keys.containsAll(a.keys)) difference(a, b) else JsonObject(mapOf("__mie_replace" to b))
            } else b)
        }
    })
    fun withoutGroup(value: JsonObject, group: String) = JsonObject(value.filterKeys { it !in fields.getValue(group) })

    /**
     * 从页面的独立设置里删掉 [patch] 涉及到的字段，让这些字段重新跟随整套。
     * 对象只删到子字段：整套只改了圆角，页面自己的按键背景不会被一起抹掉。
     */
    fun prune(value: JsonObject, patch: JsonObject): JsonObject {
        if (value.isEmpty() || patch.isEmpty()) return value
        val out = value.toMutableMap()
        patch.forEach { (name, changed) ->
            val current = out[name] ?: return@forEach
            when {
                changed is JsonObject && "__mie_replace" in changed -> out.remove(name)
                current is JsonObject && changed is JsonObject -> {
                    val nested = prune(current, changed)
                    if (nested.isEmpty()) out.remove(name) else out[name] = nested
                }
                else -> out.remove(name)
            }
        }
        return JsonObject(out)
    }

    fun style(value: JsonObject): KeyAppearance.Style = json.decodeFromJsonElement(KeyAppearance.Style.serializer(), value)

    /**
     * 圆角与边框宽度各是「一条参数、两处存储」：按键默认外观的 roundCorner / keyBorder，
     * 和全局外观 · 按键的 surface.radius / surface.borderWidth。KeyView 取
     * `surface.radius ?: k.roundCorner`、`surface.borderWidth ?: k.keyBorder`，
     * 所以推送时要两处写成同一个数，否则页面会按另一处的旧值渲染。
     * 键为 surface 上的字段名，值为 null 表示回到继承（清掉覆盖）。
     */
    private val shapeTwins = listOf("roundCorner" to "radius", "keyBorder" to "borderWidth")

    /** 本次推送改到的形状参数；空表示这一项没动。 */
    fun pushedShape(patch: JsonObject, after: CustomKeyboard): Map<String, Float?> = buildMap {
        val keyAppearance = patch["keyAppearance"] as? JsonObject
        val surfaceKeys = (patch["surfaceAppearance"] as? JsonObject)?.get("keys") as? JsonObject
        if (keyAppearance != null) {
            if ("roundCorner" in keyAppearance) put("radius", after.keyAppearance.roundCorner.takeIf { it >= 0f })
            if ("keyBorder" in keyAppearance) put("borderWidth", after.keyAppearance.keyBorder.takeIf { it >= 0 }?.toFloat())
        } else if (surfaceKeys != null) {
            if ("radius" in surfaceKeys) put("radius", after.surfaceAppearance?.keys?.radius)
            if ("borderWidth" in surfaceKeys) put("borderWidth", after.surfaceAppearance?.keys?.borderWidth)
        }
    }

    /** 只清页面里这些形状参数的存储，值不参与推送（仅供 prune 用）。 */
    fun shapeProbe(shape: Map<String, Float?>): JsonObject {
        val keyFields = shapeTwins.filter { it.second in shape }.associate { (key, _) -> key to JsonPrimitive(0) }
        val surfaceFields = shape.filterKeys { name -> shapeTwins.any { it.second == name } }.mapValues { JsonPrimitive(0) }
        return JsonObject(mapOf(
            "keyAppearance" to JsonObject(keyFields),
            "surfaceAppearance" to JsonObject(mapOf("keys" to JsonObject(surfaceFields)))))
    }

    /** 表面层的形状字段名换回按键默认外观里的名字，用来清掉各键自己那份旧值。 */
    fun keyShapeFields(shape: Map<String, Float?>): Set<String> =
        shapeTwins.filter { it.second in shape }.map { it.first }.toSet()

    /** 把同一层的两处存储写成同一个值；null 表示回到继承。 */
    fun syncShape(values: JsonObject, shape: Map<String, Float?>): JsonObject {
        val keyAppearance = (values["keyAppearance"] as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        val surface = values["surfaceAppearance"] as? JsonObject
        val keys = (surface?.get("keys") as? JsonObject ?: JsonObject(emptyMap())).toMutableMap()
        shapeTwins.forEach { (keyField, surfaceField) ->
            if (surfaceField !in shape) return@forEach
            val value = shape[surfaceField]
            // keyBorder 是 Int，写 -1.0 这样的浮点字面量会解码失败。
            keyAppearance[keyField] = if (keyField == "keyBorder") JsonPrimitive((value ?: -1f).toInt())
            else JsonPrimitive(value ?: -1f)
            if (value == null) keys.remove(surfaceField) else keys[surfaceField] = JsonPrimitive(value.coerceIn(0f, 64f))
        }
        // 这一层本来就没有表面外观时不硬造一份：只有圆角的空配置会把它原本的按键背景、模糊等设置挡掉。
        if (surface == null) return JsonObject(values + ("keyAppearance" to JsonObject(keyAppearance)))
        return JsonObject(values +
            ("keyAppearance" to JsonObject(keyAppearance)) +
            ("surfaceAppearance" to JsonObject(surface + ("keys" to JsonObject(keys)))))
    }

    fun rootOf(id: String, pages: Map<String, CustomKeyboard>): String {
        var current = id.removePrefix(EDIT_PREFIX); val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val kb = pages[current] ?: error("键盘已删除")
            if (kb.secondaryOf.isEmpty()) return current
            current = kb.secondaryOf
        }
        error("键盘归属存在循环")
    }
    fun family(root: String, pages: Map<String, CustomKeyboard>): List<String> {
        val ids = linkedSetOf(root)
        do { val count = ids.size; pages.forEach { (id, kb) -> if (kb.secondaryOf in ids) ids.add(id) } } while (ids.size != count)
        return ids.filter { it in pages }
    }
    fun defaults(config: KeyboardSetConfig, profiles: Map<String, KeyboardSharedProfile>): JsonObject {
        var result = JsonObject(config.defaults.filterKeys { it in allFields })
        config.profiles.forEach { (group, id) -> profiles[id]?.takeIf { it.group == group }?.let {
            val incoming = JsonObject(it.values.filterKeys { name -> name in fields[group].orEmpty() })
            val dependencies = result["importedActions"]?.jsonObject.orEmpty() + incoming["importedActions"]?.jsonObject.orEmpty()
            result = JsonObject(result.filterKeys { it !in fields[group].orEmpty() } + incoming + ("importedActions" to JsonObject(dependencies)))
        } }
        return result
    }
    fun resolve(id: String, pages: Map<String, CustomKeyboard>, profiles: Map<String, KeyboardSharedProfile>): CustomKeyboard? {
        val result = resolveOwn(id, pages, profiles) ?: return null
        val root = runCatching { rootOf(id, pages) }.getOrNull() ?: return result
        if (pageRole(id, root, result) != "en") return result
        val primary = family(root, pages).firstOrNull { pageRole(it, root, pages.getValue(it)) == "zh_26" } ?: return result
        if (primary == id) return result
        val chinese = resolveOwn(primary, pages, profiles) ?: return result
        val keys = chinese.rows.flatMap { it.keys }.groupBy(::identity)
        return result.copy(importedActions = chinese.importedActions + result.importedActions,
            rows = result.rows.map { row -> row.copy(keys = row.keys.map { key ->
                val identity = identity(key)
                if (!identity.startsWith("char:") && !identity.startsWith("key:")) return@map key
                val source = keys[identity]?.singleOrNull()
                val base = key.primaryBaseBindings ?: key.bindings.toMap()
                val inherited = source?.bindings.orEmpty().filterKeys { it !in setOf("click", "ascii") && it !in key.primaryBindingOverrides }
                val bindings = (base + inherited).toMutableMap()
                key.primaryBindingOverrides.forEach { behavior ->
                    if(behavior.startsWith('@')) return@forEach
                    if (behavior in key.bindings) bindings[behavior] = key.bindings.getValue(behavior) else bindings.remove(behavior)
                }
                // English click/navigation and its own ASCII transition always stay local.
                listOf("click", "ascii").forEach { behavior -> key.bindings[behavior]?.let { bindings[behavior] = it } }
                val basePopup = if(key.primaryBaseBindings == null) key.appearance.popup else key.primaryBasePopup.orEmpty()
                val baseSend = if(key.primaryBaseBindings == null) key.appearance.sendBindings else key.primaryBaseSendBindings
                key.copy(bindings = bindings, primaryBaseBindings = base, primaryBasePopup = basePopup, primaryBaseSendBindings = baseSend,
                    appearance = key.appearance.copy(
                        popup = if("@popup" in key.primaryBindingOverrides) key.appearance.popup else source?.appearance?.popup ?: basePopup,
                        sendBindings = if("@sendBindings" in key.primaryBindingOverrides) key.appearance.sendBindings else if(source != null) source.appearance.sendBindings else baseSend))
            }.toMutableList()) }.toMutableList())
    }

    private fun resolveOwn(id: String, pages: Map<String, CustomKeyboard>, profiles: Map<String, KeyboardSharedProfile>): CustomKeyboard? {
        val raw = pages[id] ?: return null
        val root = runCatching { rootOf(id, pages) }.getOrNull() ?: return raw
        val config = pages[root]?.setConfig ?: return raw
        val base = defaults(config, profiles)
        var result = if (raw.setOverrides == null) raw else decode(JsonObject(encode(raw) + overlay(base, raw.setOverrides!!)))
        result = result.copy(importedActions = raw.importedActions + result.importedActions, rows = result.rows.map { row -> row.copy(keys = row.keys.map { key ->
            var next = key
            if (key.setStyleOverrides != null) {
                val style = result.setKeyStyles[key.setRole] ?: KeyAppearance.Style()
                // A role's accent color must win over the legacy all-key default.
                var styleValues = overlay(KeyAppearance.values(result.keyAppearance),
                    JsonObject(KeyAppearance.values(style).filter { (field, value) -> value != emptyStyle[field] }))
                (raw.setOverrides?.get("keyAppearance") as? JsonObject)?.let { styleValues = overlay(styleValues, it) }
                val resolvedStyle = json.decodeFromJsonElement(KeyAppearance.Style.serializer(), overlay(styleValues, key.setStyleOverrides))
                val background = resolvedStyle.keyBackColor.takeIf { it.isNotBlank() }?.let { token ->
                    if(token.startsWith("#")) CustomBackground(CustomBackground.Type.COLOR, color = token).normalized()
                    else CustomBackground(CustomBackground.Type.IMAGE, image = token)
                }
                next = next.copy(appearance = resolvedStyle, background = background ?: next.background)
            }
            if (key.setBindingOverrides != null) {
                val rules = result.setBindings[key.identity].orEmpty()
                val bindings = (key.setBaseBindings ?: key.bindings).toMutableMap()
                key.setBindingOverrides.forEach { behavior ->
                    if(behavior in key.bindings) bindings[behavior] = key.bindings.getValue(behavior) else bindings.remove(behavior)
                }
                next = next.copy(bindings = (bindings + rules.filterKeys { it !in key.setBindingOverrides }).toMutableMap())
            }
            next
        }.toMutableList()) }.toMutableList())
        return result
    }
    fun editor(root: String, pages: Map<String, CustomKeyboard>, profiles: Map<String, KeyboardSharedProfile>): CustomKeyboard? {
        val raw = pages[root] ?: return null
        val config = raw.setConfig ?: return raw
        return decode(JsonObject(encode(raw) + defaults(config, profiles))).copy(name = raw.name + " · 整套设置")
    }
    fun captureMutation(raw: CustomKeyboard, before: CustomKeyboard, after: CustomKeyboard): CustomKeyboard {
        val next = after.copy(setConfig = raw.setConfig)
        if (raw.setOverrides != null) next.setOverrides = overlay(raw.setOverrides!!, difference(values(before), values(after)))
        val oldKeys = before.rows.flatMap { it.keys }.filter { it.keyId.isNotBlank() }.associateBy { it.keyId }
        next.rows.forEachIndexed { rowIndex, row -> row.keys.indices.forEach keys@ { index ->
            var key = row.keys[index]
            if(key.keyId.isEmpty()) {
                var styleOverrides = JsonObject(KeyAppearance.values(key.appearance).filter { (name, value) -> value != emptyStyle[name] })
                if(key.resolvedBackground() != CustomBackground().normalized()) styleOverrides = overlay(styleOverrides,
                    JsonObject(mapOf("keyBackColor" to JsonPrimitive(backgroundToken(key.resolvedBackground())))))
                key = key.copy(keyId = UUID.randomUUID().toString(), identity = identity(key), setRole = role(key),
                    setStyleOverrides = if(raw.setOverrides != null) key.setStyleOverrides ?: styleOverrides else null,
                    setBindingOverrides = if(raw.setOverrides != null) key.setBindingOverrides ?: emptySet() else null,
                    setBaseBindings = if(raw.setOverrides != null) key.setBaseBindings ?: key.bindings.toMap() else null)
                row.keys[index] = key
            }
            val old = oldKeys[key.keyId] ?: before.rows.getOrNull(rowIndex)?.keys?.getOrNull(index)?.takeIf {
                it.keyId.isBlank() && (it.label == key.label || it.bindings["click"] == key.bindings["click"])
            } ?: return@keys
            if(old.bindings["click"] != key.bindings["click"]) key = key.copy(identity = identity(key.copy(identity = "")), setRole = role(key))
            var updated = key
            updated = updated.copy(primaryBindingOverrides = key.primaryBindingOverrides +
                (old.bindings.keys + key.bindings.keys).filter { old.bindings[it] != key.bindings[it] } +
                (if(old.appearance.popup != key.appearance.popup) setOf("@popup") else emptySet()) +
                (if(old.appearance.sendBindings != key.appearance.sendBindings) setOf("@sendBindings") else emptySet()))
            if (key.setStyleOverrides != null && key.setStyleOverrides != JsonObject(mapOf("__mie_reset" to JsonPrimitive(true)))) {
                var changes = difference(KeyAppearance.values(old.appearance), KeyAppearance.values(key.appearance))
                if (old.background != key.background) changes = overlay(changes, JsonObject(mapOf("keyBackColor" to JsonPrimitive(backgroundToken(key.resolvedBackground())))))
                updated = updated.copy(setStyleOverrides = overlay(key.setStyleOverrides, changes))
            }
            if (key.setBindingOverrides != null) updated = updated.copy(setBindingOverrides = key.setBindingOverrides +
                (old.bindings.keys + key.bindings.keys).filter { old.bindings[it] != key.bindings[it] })
            if(key.setStyleOverrides == JsonObject(mapOf("__mie_reset" to JsonPrimitive(true)))) updated = updated.copy(setStyleOverrides = JsonObject(emptyMap()))
            if(key.setBindingOverrides == setOf("__mie_reset")) updated = updated.copy(setBindingOverrides = emptySet(), primaryBindingOverrides = emptySet())
            row.keys[index] = updated
        } }
        return next
    }
    fun rewriteMask(mask: JsonObject, effective: JsonObject): JsonObject = JsonObject(mask.mapValues { (key, value) ->
        if(value is JsonObject && value.keys != setOf("__mie_replace") && effective[key] is JsonObject) rewriteMask(value, effective.getValue(key).jsonObject)
        else if(value is JsonObject && value.keys == setOf("__mie_replace")) JsonObject(mapOf("__mie_replace" to (effective[key] ?: JsonObject(emptyMap()))))
        else effective[key] ?: value
    })
    fun backgroundToken(bg: CustomBackground): String = when (bg.type) {
        CustomBackground.Type.COLOR -> bg.color
        CustomBackground.Type.TRANSPARENT -> "#00000000"
        else -> bg.image
    }
    fun identity(key: CustomKey): String {
        if (key.identity.isNotBlank()) return key.identity
        val click = key.bindings["click"].orEmpty()
        val group = key.label.trim().lowercase()
        if(click.length == 1 && group.matches(Regex("[a-z]{2,6}")) && click.lowercase() in group) return "group:$group"
        return when {
            click.length == 1 -> "char:" + click.lowercase()
            click in setOf("space", "Return", "BackSpace", "Shift_L", "Shift_R") -> "key:" + click.lowercase()
            else -> "action:" + click
        }
    }
    fun role(key: CustomKey): String = when {
        key.bindings["click"] == "Return" -> "accent"
        key.bindings["click"].orEmpty().length == 1 || key.bindings["click"] == "space" -> "normal"
        else -> "function"
    }
    fun pageRole(id: String, root: String, kb: CustomKeyboard): String = kb.setPageRole.ifBlank {
        if (id == root) "zh_26" else when (id.removePrefix(root + "_")) {
            "en" -> "en"; "num" -> "num"; "9" -> "zh_9"; "14" -> "zh_14"; "18" -> "zh_18"
            "7_1" -> "zh_7_1"; "7_2" -> "zh_7_2"; else -> "custom:$id"
        }
    }
    fun newId() = CustomKeyboards.ID_PREFIX + UUID.randomUUID().toString().replace("-", "")

    fun reusable(kb: CustomKeyboard, root: String, pages: Map<String, CustomKeyboard>): CustomKeyboard {
        val members = family(root, pages).toSet()
        val aliases = kb.importedActions.keys.associateWith { if(it.startsWith(root + "_shared_")) it else root + "_shared_" + it }
        fun action(value: com.osfans.trime.data.theme.model.PresetKey): com.osfans.trime.data.theme.model.PresetKey {
            val target = value.select
            if (target in members) return com.osfans.trime.data.theme.model.PresetKey(send = "FUNCTION", command = "__mie_set_page",
                option = pageRole(target, root, pages.getValue(target)), label = value.label)
            return value.copy(text = Regex("\\{([^{}]+)\\}").replace(value.text) { match -> aliases[match.groupValues[1]]?.let { "{$it}" } ?: match.value })
        }
        fun token(value: String): String {
            if (value.startsWith("Keyboard_") && value.removePrefix("Keyboard_") in members) {
                val target = value.removePrefix("Keyboard_")
                return when {
                    target.endsWith("_layouts") || target.endsWith("_hub") -> BindingActions.command("__mie_set_layouts")
                    target.endsWith("_themes") -> BindingActions.command("theme")
                    target.contains("_symbol") || target.contains("_emoji") || target.contains("_cat_") -> BindingActions.command("__mie_symbols", "common")
                    else -> BindingActions.command("__mie_set_page", pageRole(target, root, pages.getValue(target)))
                }
            }
            aliases[value]?.let { return it }
            if(value.startsWith("__mie_action:")) return BindingActions.resolve(value)?.let { BindingActions.token(action(it)) } ?: value
            return Regex("\\{([^{}]+)\\}").replace(value) { match -> aliases[match.groupValues[1]]?.let { "{$it}" } ?: match.value }
        }
        return kb.copy(toolbar = kb.toolbar.map { entry ->
            val language = entry.icon.contains("tb_lang") || entry.action == "Mode_switch"
            entry.copy(action = if(language) "Mode_switch" else token(entry.action), template = entry.template?.copy(action = if(language) "Mode_switch" else token(entry.action)),
                bindings = entry.bindings.mapValues { token(it.value) })
        }.toMutableList(), setBindings = kb.setBindings.mapValues { (_, rules) -> rules.mapValues { token(it.value) } },
            importedActions = kb.importedActions.entries.associate { (name, value) -> aliases.getValue(name) to action(value) })
    }

    /** Rewrite typed references and action dependencies; labels and committed text are preserved. */
    fun remap(kb: CustomKeyboard, ids: Map<String, String>, actions: Map<String, String>): CustomKeyboard {
        fun token(value: String): String {
            if (value.startsWith("Keyboard_") && value.removePrefix("Keyboard_") in ids) return "Keyboard_" + ids.getValue(value.removePrefix("Keyboard_"))
            actions[value]?.let { return it }
            if (value.startsWith("__mie_action:")) return BindingActions.resolve(value)?.let { a ->
                BindingActions.token(a.copy(select = ids[a.select] ?: a.select,
                    text = Regex("\\{([^{}]+)\\}").replace(a.text) { m -> actions[m.groupValues[1]]?.let { "{$it}" } ?: m.value }))
            } ?: value
            return Regex("\\{([^{}]+)\\}").replace(value) { m -> actions[m.groupValues[1]]?.let { "{$it}" } ?: m.value }
        }
        return kb.copy(secondaryOf = ids[kb.secondaryOf] ?: kb.secondaryOf,
            options = kb.options.copy(asciiKeyboard = ids[kb.options.asciiKeyboard] ?: kb.options.asciiKeyboard,
                landscapeKeyboard = ids[kb.options.landscapeKeyboard] ?: kb.options.landscapeKeyboard),
            rows = kb.rows.map { row -> row.copy(keys = row.keys.map { key -> key.copy(
                keyId = UUID.randomUUID().toString(), bindings = key.bindings.mapValues { token(it.value) }.toMutableMap(),
                setBaseBindings = key.setBaseBindings?.mapValues { token(it.value) },
                primaryBaseBindings = key.primaryBaseBindings?.mapValues { token(it.value) },
                primaryBasePopup = key.primaryBasePopup?.map(::token),
                appearance = key.appearance.copy(popup = key.appearance.popup.map(::token))) }.toMutableList()) }.toMutableList(),
            toolbar = kb.toolbar.map { entry -> entry.copy(action = token(entry.action), bindings = entry.bindings.mapValues { token(it.value) },
                template = entry.template?.let { it.copy(action = token(it.action)) }) }.toMutableList(),
            importedActions = kb.importedActions.entries.associate { (name, a) -> (actions[name] ?: name) to a.copy(
                select = ids[a.select] ?: a.select, text = token(a.text)) },
            setConfig = null, setOverrides = null,
            setBindings = kb.setBindings.mapValues { (_, rules) -> rules.mapValues { token(it.value) } })
    }
}
