/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import kotlinx.serialization.json.*

/** All multi-page changes are prepared before a single atomic commit. */
object KeyboardSetOperations {
    val layoutNames = linkedMapOf("zh_26" to "26 键", "zh_9" to "9 键", "zh_18" to "18 键", "zh_14" to "14 键",
        "zh_7_1" to "七列一", "zh_7_2" to "七列二", "en" to "英文", "num" to "数字")
    private val suffix = mapOf("zh_26" to "", "zh_9" to "_9", "zh_18" to "_18", "zh_14" to "_14", "zh_7_1" to "_7_1", "zh_7_2" to "_7_2", "en" to "_en", "num" to "_num")
    private fun config(root: String, pages: MutableMap<String, CustomKeyboard>, profiles: Map<String, KeyboardSharedProfile>): KeyboardSetConfig {
        val kb = pages[root] ?: error("整套键盘已删除")
        kb.setConfig?.let { return it }
        val cfg = KeyboardSetConfig(defaults = KeyboardSets.values(KeyboardSets.resolve(root, pages, profiles)!!),
            order = KeyboardSets.family(root, pages), defaultPage = root)
        // Old pages remain independent. Merely opening this editor cannot change their appearance.
        kb.setConfig = cfg
        return cfg
    }
    fun ensure(rootId: String) {
        val pages = CustomKeyboards.rawLayouts(); val root = KeyboardSets.rootOf(rootId, pages)
        if (pages[root]?.setConfig == null) CustomKeyboards.editSets { p, profiles -> config(root, p, profiles) }
    }
    fun root(id: String) = CustomKeyboards.setRoot(id)
    fun ordered(root: String): List<String> = CustomKeyboards.setPageIds(root)
    private fun prepareNewSet(name: String, template: String, roles: List<String>): Pair<String, Map<String, CustomKeyboard>> {
        require(name.isNotBlank() && roles.isNotEmpty()) { "请输入名称并选择布局" }
        val stock = CustomKeyboards.rawLayouts(); val source = stock[template] ?: error("样式已删除")
        val root = KeyboardSets.newId()
        val selected = roles.distinct().sortedBy { if (it.startsWith("zh_")) 0 else 1 }
        val ids = selected.associateWith { if (it == selected.first()) root else root + suffix.getValue(it).ifBlank { "_26" } }
        val sourceIds = selected.associate { role -> (template + suffix.getValue(role)) to ids.getValue(role) }
        require(sourceIds.keys.all { it in stock }) { "此样式缺少所选布局，请换一个样式" }
        val actions = sourceIds.keys.flatMap { stock.getValue(it).importedActions.keys }.distinct().associateWith { root + "_action_" + it }
                val roleStyles = source.rows.flatMap { it.keys }.groupBy(KeyboardSets::role).mapValues { (_, keys) ->
            val key = keys.first(); KeyAppearance.merge(source.keyAppearance, key.appearance).copy(keyBackColor =
                KeyAppearance.merge(source.keyAppearance, key.appearance).keyBackColor.ifBlank { KeyboardSets.backgroundToken(key.resolvedBackground()) })
        }
        val normalizedStyles = roleStyles.mapValues { (_, style) -> style.copy(labelSymbol = "", hint = "", icon = "", popup = emptyList(), sendBindings = null) }
        val normal = normalizedStyles["normal"] ?: KeyAppearance.Style()
        val styles = mapOf("normal" to normal, "function" to (normalizedStyles["function"] ?: normal), "accent" to (normalizedStyles["accent"] ?: normal))
        val created = linkedMapOf<String, CustomKeyboard>()
        sourceIds.forEach { (from, to) ->
            val role = selected.first { ids[it] == to }
            val kb = KeyboardSets.remap(stock.getValue(from), sourceIds, actions)
            val toolbar = kb.toolbar.map { entry ->
                val original = stock.getValue(from).toolbar.firstOrNull { it.id == entry.id && it.action == entry.action } ?: entry
                when {
                    entry.icon.contains("tb_lang") || original.action == "Mode_switch" || entry.action.endsWith("_en") -> entry.copy(action = "Mode_switch", template = null, bindings = emptyMap())
                    entry.action.startsWith("Keyboard_") && entry.action.removePrefix("Keyboard_") !in sourceIds.values -> entry.copy(action = BindingActions.command(if(entry.action.endsWith("_layouts")) "__mie_set_layouts" else "theme"), template = null)
                    entry.action.endsWith("_layouts") -> entry.copy(action = BindingActions.command("__mie_set_layouts"), template = null)
                    else -> entry
                }
            }.toMutableList()
            fun clean(token: String): String {
                if (!token.startsWith("Keyboard_")) return token
                val target = token.removePrefix("Keyboard_")
                if (target == "default") return token
                val targetRole = ids.entries.firstOrNull { it.value == target }?.key
                if (targetRole in setOf("num", "en")) return BindingActions.command("__mie_set_page", targetRole!!)
                if (target in sourceIds.values) return token
                return if (target.contains("symbol") || target.contains("emoji")) BindingActions.command("__mie_symbols", "common")
                    else if(target.endsWith("_layouts")) BindingActions.command("__mie_set_layouts") else BindingActions.command("theme")
            }
            created[to] = kb.copy(name = if (to == root) name else name + " · " + layoutNames.getValue(role),
                secondaryOf = if (to == root) "" else root, setPageRole = role,
                setOverrides = JsonObject(emptyMap()), keyAppearance = KeyAppearance.Style(),
                setKeyStyles = styles, toolbar = toolbar,
                options = kb.options.copy(lock = role.startsWith("zh_"), asciiMode = if (role in setOf("en", "num")) 1 else 0,
                    resetAsciiMode = true, asciiKeyboard = if (role.startsWith("zh_")) ids["en"].orEmpty() else ""),
                rows = kb.rows.map { row -> row.copy(keys = row.keys.map { key ->
                    val type = KeyboardSets.role(key)
                    val local = KeyAppearance.values(key.appearance).filterKeys { it in setOf("labelSymbol", "hint", "popup", "sendBindings", "icon", "iconSize", "showSymbol", "showHint", "symbolAlign", "hintAlign") }
                    val bindings = key.bindings.mapValues { (behavior, value) ->
                        if (behavior == "click" && key.label.contains("返回")) "Keyboard_default" else clean(value)
                    }
                    key.copy(identity = KeyboardSets.identity(key), setRole = type, setStyleOverrides = JsonObject(local),
                        setBindingOverrides = emptySet(), setBaseBindings = bindings, bindings = bindings.toMutableMap())
                }.toMutableList()) }.toMutableList())
        }
        val first = created.getValue(root)
        val defaultValues = KeyboardSets.values(first.copy(keyAppearance = KeyAppearance.Style(), setKeyStyles = styles))
        // Seven-column templates retain their own dimensions instead of inheriting 260dp.
        created.replaceAll { _, page -> page.copy(setOverrides = KeyboardSets.difference(
            KeyboardSets.values(first, "geometry"), KeyboardSets.values(page, "geometry"))) }
        created[root] = first.copy(setConfig = KeyboardSetConfig(defaults = defaultValues, order = ids.values.toList(), defaultPage = root))
        return root to created
    }
    fun newSet(name: String, template: String, roles: List<String>): String {
        val (root, created) = prepareNewSet(name, template, roles)
        CustomKeyboards.editSets { pages, _ -> pages.putAll(created) }
        return root
    }
    fun newBlank(name: String): String {
        val id = KeyboardSets.newId()
        val page = CustomKeyboard(name = name, setOverrides = JsonObject(emptyMap()), setPageRole = "zh_custom",
            rows = mutableListOf(CustomKeyboards.CustomRow()), setKeyStyles = mapOf("normal" to KeyAppearance.Style(), "function" to KeyAppearance.Style(), "accent" to KeyAppearance.Style()))
        page.setConfig = KeyboardSetConfig(defaults = KeyboardSets.values(page), order = listOf(id), defaultPage = id)
        CustomKeyboards.editSets { pages, _ -> pages[id] = page }
        return id
    }
    fun addBlank(root: String, name: String): String {
        val id = KeyboardSets.newId()
        CustomKeyboards.editSets { pages, profiles ->
            val cfg = config(root, pages, profiles)
            pages[id] = CustomKeyboard(name = name, secondaryOf = root, setPageRole = "custom:$id",
                setOverrides = JsonObject(emptyMap()), rows = mutableListOf(CustomKeyboards.CustomRow()))
            pages.getValue(root).setConfig = cfg.copy(order = cfg.order + id)
        }
        return id
    }
    fun adoptBuiltin(root: String) {
        val initial = CustomKeyboards.rawLayouts()
        if(initial[root]?.setConfig != null) return
        val templates = CustomKeyboards.builtinTemplates()
        CustomKeyboards.editSets { pages, profiles -> adoptBuiltinData(root, pages, profiles, templates) }
    }
    internal fun adoptBuiltinData(root: String, pages: MutableMap<String, CustomKeyboard>, profiles: Map<String, KeyboardSharedProfile>,
                                 templates: Map<String, CustomKeyboard> = emptyMap()) {
            val base = pages.getValue(root)
            val ids = KeyboardSets.family(root, pages)
            val stockToolbar = templates[root]?.toolbar == base.toolbar
            val commonToolbar = if(stockToolbar) KeyboardSets.reusable(base, root, pages) else base
            val snapshots = ids.associateWith { id ->
                val current = KeyboardSets.resolve(id, pages, profiles)!!
                // Canonicalize only untouched stock definitions. Explicit user toolbar edits stay independent.
                if(stockToolbar && templates[id]?.toolbar == current.toolbar) current.copy(toolbar = commonToolbar.toolbar.toMutableList(),
                    importedActions = current.importedActions + commonToolbar.importedActions) else current
            }
            fun effectiveStyle(kb: CustomKeyboard, key: CustomKeyboards.CustomKey): KeyAppearance.Style {
                val style = KeyAppearance.merge(kb.keyAppearance, key.appearance)
                return style.copy(keyBackColor = style.keyBackColor.ifBlank { KeyboardSets.backgroundToken(key.resolvedBackground()) })
            }
            val styles = base.rows.flatMap { it.keys }.groupBy(KeyboardSets::role).mapValues { (_, keys) ->
                effectiveStyle(base, keys.first()).copy(labelSymbol = "", hint = "", icon = "", popup = emptyList(), sendBindings = null)
            }
            val previousConfig = base.setConfig
            val defaults = KeyboardSets.values(commonToolbar.copy(setKeyStyles = styles))
            pages.getValue(root).setConfig = KeyboardSetConfig(defaults = defaults, order = previousConfig?.order ?: ids,
                defaultPage = previousConfig?.defaultPage ?: root, profiles = previousConfig?.profiles.orEmpty())
            snapshots.forEach { (id, kb) ->
                val metadata = if(id == root) pages.getValue(root).setConfig else kb.setConfig
                pages[id] = kb.copy(setConfig = metadata, setPageRole = KeyboardSets.pageRole(id, root, kb), setKeyStyles = styles,
                    setOverrides = KeyboardSets.difference(defaults, KeyboardSets.values(kb.copy(setKeyStyles = styles))),
                    rows = kb.rows.map { row -> row.copy(keys = row.keys.map { key ->
                        val role = KeyboardSets.role(key)
                        key.copy(keyId = key.keyId.ifBlank { java.util.UUID.randomUUID().toString() }, identity = KeyboardSets.identity(key), setRole = role,
                            setStyleOverrides = if(kb.importedSkin != null || key.skin != null) null else
                                KeyboardSets.difference(KeyAppearance.values(styles[role] ?: KeyAppearance.Style()), KeyAppearance.values(effectiveStyle(kb, key))),
                            setBindingOverrides = emptySet(), setBaseBindings = key.setBaseBindings ?: key.bindings.toMap())
                    }.toMutableList()) }.toMutableList())
            }
    }
    fun copySet(sourceId: String, name: String): String {
        val sourceRoot = root(sourceId); val ids = ordered(sourceRoot)
        val newRoot = KeyboardSets.newId(); val map = ids.associateWith { if (it == sourceRoot) newRoot else KeyboardSets.newId() }
        val snapshots = CustomKeyboards.portableLayouts(ids)
        val actions = snapshots.values.flatMap { it.importedActions.keys }.distinct().associateWith { newRoot + "_action_" + it }
        val copies = snapshots.map { (id, kb) -> map.getValue(id) to KeyboardSets.remap(kb, map, actions).copy(
            name = if (id == sourceRoot) name else kb.name.replace(snapshots.getValue(sourceRoot).name, name),
            secondaryOf = if (id == sourceRoot) "" else newRoot, setOverrides = null) }.toMap().toMutableMap()
        val config = snapshots.getValue(sourceRoot).setConfig
        if(config != null) {
            val sourceBase = KeyboardSets.decode(JsonObject(KeyboardSets.encode(snapshots.getValue(sourceRoot)) + config.defaults))
            copies.getValue(newRoot).setConfig = config.copy(defaults = KeyboardSets.values(KeyboardSets.remap(sourceBase, map, actions)), profiles = emptyMap(),
                order = config.order.mapNotNull(map::get), defaultPage = map[config.defaultPage] ?: newRoot)
            snapshots.forEach { (id, kb) -> kb.setOverrides?.let { overrides ->
                copies.getValue(map.getValue(id)).setOverrides = KeyboardSets.rewriteMask(overrides, KeyboardSets.values(copies.getValue(map.getValue(id))))
            } }
        } else {
            val defaults = KeyboardSets.values(copies.getValue(newRoot))
            copies.getValue(newRoot).setConfig = KeyboardSetConfig(defaults = defaults, order = map.values.toList(), defaultPage = newRoot)
            copies.values.forEach { it.setOverrides = KeyboardSets.difference(defaults, KeyboardSets.values(it)) }
        }
        CustomKeyboards.editSets { pages, _ -> pages.putAll(copies) }
        return newRoot
    }

    fun consolidate(root: String) = CustomKeyboards.editSets { pages, profiles ->
        config(root, pages, profiles)
        adoptBuiltinData(root, pages, profiles)
    }
    fun follow(pageId: String, group: String) = CustomKeyboards.editSets { pages, profiles ->
        val root = KeyboardSets.rootOf(pageId, pages); val cfg = config(root, pages, profiles)
        val page = pages.getValue(pageId)
        val previous = page.setOverrides ?: KeyboardSets.values(page)
        page.setOverrides = KeyboardSets.withoutGroup(previous, group)
    }
    fun detach(pageId: String, group: String) = CustomKeyboards.editSets { pages, profiles ->
        val root = KeyboardSets.rootOf(pageId, pages); val cfg = config(root, pages, profiles)
        val page = pages.getValue(pageId); val effective = KeyboardSets.resolve(pageId, pages, profiles)!!
        val previous = page.setOverrides ?: KeyboardSets.values(page)
        page.setOverrides = KeyboardSets.overlay(previous, KeyboardSets.values(effective, group))
    }
    fun follows(pageId: String, group: String): Boolean = CustomKeyboards.rawLayouts()[pageId]?.setOverrides?.keys?.none { it in KeyboardSets.fields.getValue(group) } == true
    fun copySettings(sourceId: String, targetId: String, groups: Set<String>, overwrite: Boolean) {
        val source = root(sourceId); val target = root(targetId)
        require(source != target)
        CustomKeyboards.editSets { pages, profiles ->
            val cfg = config(target, pages, profiles)
            val sourceValues = KeyboardSets.reusable(KeyboardSets.editor(source, pages, profiles)!!, source, pages)
            var defaults = KeyboardSets.defaults(cfg, profiles)
            groups.forEach { group ->
                val patch = KeyboardSets.values(sourceValues, group)
                val dependencies = defaults["importedActions"]?.jsonObject.orEmpty() + patch["importedActions"]?.jsonObject.orEmpty()
                defaults = JsonObject(defaults.filterKeys { it !in KeyboardSets.fields.getValue(group) } + patch + ("importedActions" to JsonObject(dependencies)))
            }
            pages.getValue(target).setConfig = cfg.copy(defaults = defaults, profiles = cfg.profiles - groups)
            if (overwrite) KeyboardSets.family(target, pages).forEach { id ->
                val kb = pages.getValue(id); var overrides = kb.setOverrides ?: KeyboardSets.values(kb)
                groups.forEach { overrides = KeyboardSets.withoutGroup(overrides, it) }; kb.setOverrides = overrides
            }
        }
    }
    fun saveProfile(root: String, group: String, name: String, update: String? = null): String {
        val id = update ?: "profile_" + java.util.UUID.randomUUID()
        CustomKeyboards.editSets { pages, profiles ->
            val cfg = config(root, pages, profiles)
            val current = KeyboardSets.reusable(KeyboardSets.editor(root, pages, profiles)!!, root, pages)
            profiles[id] = KeyboardSharedProfile(id, name, group, KeyboardSets.values(current, group), (profiles[id]?.revision ?: 0) + 1)
            pages.getValue(root).setConfig = cfg.copy(profiles = cfg.profiles + (group to id))
        }
        return id
    }
    fun useProfile(root: String, group: String, id: String?) = CustomKeyboards.editSets { pages, profiles ->
        val cfg = config(root, pages, profiles)
        require(id == null || profiles[id]?.group == group) { "共用方案不存在" }
        pages.getValue(root).setConfig = cfg.copy(defaults = KeyboardSets.defaults(cfg, profiles),
            profiles = if (id == null) cfg.profiles - group else cfg.profiles + (group to id))
    }
    fun profileUsers(id: String): List<String> = CustomKeyboards.rawLayouts().values.filter { it.setConfig?.profiles?.containsValue(id) == true }.map { it.name }
    fun renameProfile(id: String, name: String) = CustomKeyboards.editSets { _, profiles -> profiles[id]?.let { profiles[id] = it.copy(name = name) } }
    fun deleteProfile(id: String) = CustomKeyboards.editSets { pages, profiles ->
        pages.values.forEach { kb -> kb.setConfig?.takeIf { id in it.profiles.values }?.let { cfg ->
            kb.setConfig = cfg.copy(defaults = KeyboardSets.defaults(cfg, profiles), profiles = cfg.profiles.filterValues { it != id })
        } }; profiles.remove(id)
    }
    fun move(root: String, id: String, delta: Int) = CustomKeyboards.editSets { pages, profiles ->
        val cfg = config(root, pages, profiles); val order = (cfg.order + KeyboardSets.family(root, pages)).distinct().filter { it in pages }.toMutableList()
        val index = order.indexOf(id); val to = (index + delta).coerceIn(0, order.lastIndex)
        if (index >= 0) { order.removeAt(index); order.add(to, id) }
        pages.getValue(root).setConfig = cfg.copy(order = order)
    }
    fun setDefault(root: String, id: String) = CustomKeyboards.editSets { pages, profiles ->
        require(id in KeyboardSets.family(root, pages))
        val page = pages.getValue(id)
        require(page.setPageRole.startsWith("zh_") || page.options.lock) { "请选择中文主布局" }
        pages.getValue(root).setConfig = config(root, pages, profiles).copy(defaultPage = id)
    }
    fun addLayout(root: String, role: String, template: String) {
        require(role in layoutNames)
        val (temp, prepared) = prepareNewSet("临时布局", template, listOf(role))
        val snapshot = KeyboardSets.resolve(temp, prepared, emptyMap())!!
        val id = KeyboardSets.newId()
        CustomKeyboards.editSets { pages, profiles ->
            val cfg = config(root, pages, profiles)
            val family = KeyboardSets.family(root, pages)
            val en = family.firstOrNull { pages[it]?.setPageRole == "en" }.orEmpty()
            val source = snapshot.copy(options = snapshot.options.copy(asciiKeyboard = if(role.startsWith("zh_")) en else ""))
            pages[id] = KeyboardSets.remap(source, mapOf(temp to id), emptyMap()).copy(
                name = pages.getValue(root).name + " · " + layoutNames.getValue(role), secondaryOf = root,
                setPageRole = role, setOverrides = if(role.startsWith("zh_7_")) KeyboardSets.difference(
                    JsonObject(KeyboardSets.defaults(cfg, profiles).filterKeys { it in KeyboardSets.fields.getValue("geometry") }),
                    KeyboardSets.values(source, "geometry")) else JsonObject(emptyMap()))
            pages.getValue(root).setConfig = cfg.copy(order = cfg.order + id)
            if (role == "en") family.forEach { pageId -> val kb = pages.getValue(pageId)
                if ((kb.setPageRole.startsWith("zh_") || kb.options.lock) && kb.options.asciiKeyboard.isBlank()) kb.options = kb.options.copy(asciiKeyboard = id) }
        }
    }
}
