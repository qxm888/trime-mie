/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

/** Copy gesture bindings by key identity, never by row/column position. */
object KeyboardBindingCopy {
    data class Match(val row: Int, val column: Int, val bindings: Map<String, String>,
                     val popup: List<String>, val sendBindings: Boolean?)
    data class Plan(val sourceId: String, val targetId: String, val matches: List<Match>, val skipped: Int,
                    val sourceVersion: Long)
    private fun click(key: CustomKeyboards.CustomKey): String = key.bindings["click"].orEmpty().trim().let {
        if (it.length == 1 || it.lowercase() in setOf("space", "backspace", "return", "shift_l", "shift_r")) it.lowercase() else it
    }
    private fun label(key: CustomKeyboards.CustomKey) = key.label.trim().lowercase()
    fun plan(sourceId: String, targetId: String): Plan {
        require(sourceId != targetId) { "请选择其它键盘" }
        val version = CustomKeyboards.version
        val source = CustomKeyboards.get(sourceId) ?: error("来源键盘已删除")
        val target = CustomKeyboards.get(targetId) ?: error("当前键盘已删除")
        val keys = source.rows.flatMap { it.keys }.filter { it.bindings.values.any(String::isNotBlank) }
        val byClick = keys.filter { click(it).isNotBlank() }.groupBy(::click)
        val byLabel = keys.filter { label(it).isNotBlank() }.groupBy(::label)
        fun unambiguous(values: List<CustomKeyboards.CustomKey>?): CustomKeyboards.CustomKey? = values?.takeIf {
            it.size == 1 || it.map { key -> key.bindings }.distinct().size == 1
        }?.firstOrNull()
        val matches = mutableListOf<Match>(); var skipped = 0
        target.rows.forEachIndexed { row, line -> line.keys.forEachIndexed keys@ { column, key ->
            // Transparent spacers are not controls, even when another layout happens to have one.
            if (key.bindings.values.none(String::isNotBlank)) return@keys
            val match = unambiguous(byClick[click(key)]) ?: unambiguous(byLabel[label(key)])
            if (match == null) skipped++ else matches += Match(row, column, match.bindings.toMap(), match.appearance.popup.toList(), match.appearance.sendBindings)
        } }
        check(version == CustomKeyboards.version) { "键盘已变化，请重新选择来源" }
        return Plan(sourceId, targetId, matches, skipped, version)
    }
    fun apply(plan: Plan): Int {
        require(plan.sourceVersion == CustomKeyboards.version) { "键盘已变化，请重新选择来源" }
        val source = CustomKeyboards.get(plan.sourceId) ?: error("来源键盘已删除")
        val target = CustomKeyboards.get(plan.targetId) ?: error("当前键盘已删除")
        require(source.importedActions.none { (key, value) -> target.importedActions[key]?.let { it != value } == true }) {
            "两个键盘存在同名但不同定义的主题动作，请先重命名这些动作"
        }
        check(CustomKeyboards.mutate(plan.targetId) { keyboard ->
            require(plan.sourceVersion == CustomKeyboards.version) { "键盘已变化，请重新选择来源" }
            plan.matches.forEach { item ->
                val old = keyboard.rows[item.row].keys[item.column]
                keyboard.rows[item.row].keys[item.column] = old.copy(bindings = item.bindings.toMutableMap(),
                    appearance = old.appearance.copy(popup = item.popup, sendBindings = item.sendBindings))
            }
            keyboard.importedActions = keyboard.importedActions + source.importedActions
        }) { "当前键盘已删除" }
        check(CustomKeyboards.flush()) { "保存失败，请检查数据目录" }
        return plan.matches.size
    }
}
