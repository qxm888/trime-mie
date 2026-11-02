/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.model.PresetKey

/** One preview and one store commit for a primary keyboard and its descendants. */
object KeyboardFamilyBindingCopy {
    data class Plan(val source: String, val target: String, val version: Long,
                    val updates: Map<String, CustomKeyboards.CustomKeyboard>, val keyCount: Int,
                    val skippedKeys: Int, val unmatchedTargets: List<String>, val unmatchedSources: List<String>)

    fun rootOf(id: String): String {
        var current = id; val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            val keyboard = CustomKeyboards.get(current) ?: error("键盘已删除")
            if (keyboard.secondaryOf.isBlank()) return current
            current = keyboard.secondaryOf
        }
        error("键盘分组存在循环，请先修正归属")
    }

    private fun role(id: String, root: String, keyboard: CustomKeyboards.CustomKeyboard,
                     rootName: String): String {
        if (id == root) return "main"
        if (id.startsWith(root + "_")) return "id:" + id.removePrefix(root + "_")
        return "name:" + keyboard.name.lowercase().removePrefix(rootName.lowercase().removeSuffix("键盘"))
            .replace(Regex("[\\s·•:：_\\-]"), "").removePrefix("键盘")
    }

    fun plan(sourceId: String, targetId: String): Plan {
        val version = CustomKeyboards.version
        val source = rootOf(sourceId); val target = rootOf(targetId)
        require(source != target) { "请选择其它整套键盘" }
        val sources = CustomKeyboards.familyIds(listOf(source)).associateWith { CustomKeyboards.get(it) ?: error("来源键盘已删除") }
        val targets = CustomKeyboards.familyIds(listOf(target)).associateWith { CustomKeyboards.get(it) ?: error("目标键盘已删除") }
        val sourceName = sources.getValue(source).name; val targetName = targets.getValue(target).name
        val byRole = sources.entries.groupBy { role(it.key, source, it.value, sourceName) }
        fun nameRole(kb: CustomKeyboards.CustomKeyboard, rootName: String): String {
            val name = kb.name.lowercase().removePrefix(rootName.lowercase().removeSuffix("键盘"))
                .replace(Regex("[\\s·•:：_\\-]"), "").removePrefix("键盘")
            return when {
                name in setOf("数字", "数字键盘", "number", "numbers", "numpad", "num") -> "num"
                name in setOf("英文", "英文键盘", "english", "ascii", "en") -> "en"
                name in setOf("主题", "themes", "theme", "皮肤") -> "themes"
                name in setOf("布局", "layouts", "layout") -> "layouts"
                name in setOf("键盘中心", "hub", "keyboardhub") -> "hub"
                else -> name
            }
        }
        val byName = sources.entries.filter { it.key != source }.groupBy { nameRole(it.value, sourceName) }
        val pairs = linkedMapOf<String, String>(); val missing = mutableListOf<String>()
        targets.forEach { (id, kb) ->
            val candidate = byRole[role(id, target, kb, targetName)]?.singleOrNull()
                ?: byName[nameRole(kb, targetName)]?.singleOrNull()
            if (candidate == null || candidate.key in pairs) missing += kb.name else pairs[candidate.key] = id
        }
        require(pairs[source] == target) { "找不到主键盘" }
        val allActions = linkedMapOf<String, PresetKey>()
        sources.values.forEach { keyboard -> keyboard.importedActions.forEach { (name, action) ->
            require(allActions[name]?.let { it == action } != false) { "来源整套存在同名且不同定义的主题动作：$name" }
            allActions[name] = action
        } }
        val macro = Regex("\\{([^{}]+)\\}")
        val valid = allActions.filterValues { it.select !in sources || it.select in pairs }.keys.toMutableSet()
        do {
            val before = valid.size
            valid.removeAll { name -> macro.findAll(allActions.getValue(name).text).any { it.groupValues[1] in allActions && it.groupValues[1] !in valid } }
        } while (valid.size != before)
        val navigation = pairs.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value}" }
        val signature = java.security.MessageDigest.getInstance("SHA-256").digest(
            (navigation + allActions.entries.sortedBy { it.key }.joinToString { it.key + BindingActions.token(it.value) }).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val aliases = valid.associateWith { name ->
            val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(
                (source + name + signature).toByteArray(Charsets.UTF_8))
            target + "_copy_" + bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        require(aliases.values.distinct().size == aliases.size) { "主题动作标识冲突，请重新选择" }
        fun remapAction(action: PresetKey): PresetKey? {
            if (action.select in sources && action.select !in pairs) return null
            if (macro.findAll(action.text).any { it.groupValues[1] in allActions && it.groupValues[1] !in valid }) return null
            return action.copy(select = pairs[action.select] ?: action.select,
                text = macro.replace(action.text) { match -> aliases[match.groupValues[1]]?.let { "{$it}" } ?: match.value })
        }
        val updates = linkedMapOf<String, CustomKeyboards.CustomKeyboard>()
        var count = 0; var skipped = 0
        pairs.forEach { (from, to) ->
            val page = KeyboardBindingCopy.plan(from, to)
            val dst = targets.getValue(to)
            val needed = linkedSetOf<String>()
            fun need(name: String) {
                if (name !in valid || !needed.add(name)) return
                macro.findAll(allActions.getValue(name).text).forEach { need(it.groupValues[1]) }
            }
            fun token(value: String, returning: Boolean = false): String? {
                val inline = BindingActions.resolve(value)
                val aliased = allActions[value]
                val action = inline ?: aliased
                if ((returning || action?.label == "返回") && (value == "Keyboard_$source" || action?.select == source)) {
                    if (action == null) return "Keyboard_default"
                    return remapAction(action.copy(select = ".default"))?.let(BindingActions::token)
                }
                if (value.startsWith("Keyboard_")) {
                    val id = value.removePrefix("Keyboard_")
                    // A colour chooser explicitly selecting the source colour is
                    // not a return-to-family link. Keep that intentional choice.
                    if (from.endsWith("_themes") && id == source) return value
                    if (id in pairs) return "Keyboard_" + pairs.getValue(id)
                    if (id in sources) return null
                }
                if (value in allActions) { need(value); return aliases[value] }
                if (value.startsWith("__mie_action:")) return BindingActions.resolve(value)?.let { original ->
                    remapAction(original)?.also { macro.findAll(original.text).forEach { need(it.groupValues[1]) } }?.let(BindingActions::token)
                }
                if (macro.findAll(value).any { it.groupValues[1] in allActions && it.groupValues[1] !in valid }) return null
                return macro.replace(value) { match ->
                    val name = match.groupValues[1]
                    if (name in valid) { need(name); "{${aliases.getValue(name)}}" } else match.value
                }
            }
            page.matches.forEach { item ->
                val old = dst.rows[item.row].keys[item.column]
                val bindings = item.bindings.mapNotNull { (behavior, value) ->
                    val isReturn = behavior == "click" && (old.label == "返回" || old.bindings["click"] == "Keyboard_default" ||
                        old.bindings["click"]?.let { BindingActions.resolve(it)?.select == ".default" } == true)
                    (token(value, isReturn) ?: old.bindings[behavior])?.let { behavior to it }
                }.toMap().toMutableMap()
                val popup = item.popup.mapNotNull { token(it) }
                dst.rows[item.row].keys[item.column] = old.copy(bindings = bindings,
                    setBindingOverrides = old.setBindingOverrides?.plus(old.bindings.keys + bindings.keys),
                    appearance = old.appearance.copy(popup = popup, sendBindings = item.sendBindings))
            }
            val actions = needed.associate { aliases.getValue(it) to requireNotNull(remapAction(allActions.getValue(it))) }
            require(actions.none { (name, action) -> dst.importedActions[name]?.let { it != action } == true }) {
                "${dst.name} 存在同名不同定义的主题动作，请先重命名"
            }
            dst.importedActions = dst.importedActions + actions
            if (page.matches.isNotEmpty()) updates[to] = dst
            count += page.matches.size; skipped += page.skipped
        }
        check(version == CustomKeyboards.version) { "键盘已变化，请重新选择来源" }
        return Plan(source, target, version, updates, count, skipped, missing,
            sources.filterKeys { it !in pairs }.values.map { it.name })
    }

    fun apply(plan: Plan): Int {
        require(plan.updates.isNotEmpty()) { "没有可复制的绑定" }
        CustomKeyboards.replaceBindingLayouts(plan.version, plan.updates)
        return plan.keyCount
    }
}
