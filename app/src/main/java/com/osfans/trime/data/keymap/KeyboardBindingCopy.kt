/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import kotlinx.serialization.json.JsonObject

/** Copy gesture bindings and annotations by key identity, never by row/column position. */
object KeyboardBindingCopy {
    data class Annotations(
        val labelSymbol: String = "", val hint: String = "",
        val symbolTextSize: Float = 0f, val hintTextSize: Float = 0f,
        val symbolOffsetX: Float = 0f, val symbolOffsetY: Float = 0f,
        val hintOffsetX: Float = 0f, val hintOffsetY: Float = 0f,
        val showSymbol: Boolean? = null, val showHint: Boolean? = null,
        val symbolAlign: String = "",
    ) {
        constructor(style: KeyAppearance.Style) : this(style.labelSymbol, style.hint,
            style.symbolTextSize, style.hintTextSize, style.keySymbolOffsetX, style.keySymbolOffsetY,
            style.keyHintOffsetX, style.keyHintOffsetY, style.showSymbol, style.showHint, style.symbolAlign)

        fun applyTo(style: KeyAppearance.Style) = style.copy(labelSymbol = labelSymbol, hint = hint,
            symbolTextSize = symbolTextSize, hintTextSize = hintTextSize,
            keySymbolOffsetX = symbolOffsetX, keySymbolOffsetY = symbolOffsetY,
            keyHintOffsetX = hintOffsetX, keyHintOffsetY = hintOffsetY,
            showSymbol = showSymbol, showHint = showHint, symbolAlign = symbolAlign)
    }
    data class Match(val row: Int, val column: Int, val bindings: Map<String, String>,
                     val popup: List<String>, val sendBindings: Boolean?, val annotations: Annotations = Annotations())
    data class Plan(val sourceId: String, val targetId: String, val matches: List<Match>, val skipped: Int,
                    val sourceVersion: Long)
    private val copiedAppearanceFields = setOf("labelSymbol", "hint", "symbolTextSize", "hintTextSize",
        "keySymbolOffsetX", "keySymbolOffsetY", "keyHintOffsetX", "keyHintOffsetY",
        "showSymbol", "showHint", "symbolAlign", "popup", "sendBindings")
    private fun match(key: CustomKeyboards.CustomKey, row: Int = 0, column: Int = 0) =
        Match(row, column, key.bindings.toMap(), key.appearance.popup.toList(), key.appearance.sendBindings, Annotations(key.appearance))

    /** Pin copied fields on set-managed keys, including an explicitly empty symbol or hint. */
    internal fun copyKey(target: CustomKeyboards.CustomKey, item: Match,
                         bindings: Map<String, String> = item.bindings, popup: List<String> = item.popup): CustomKeyboards.CustomKey {
        val appearance = item.annotations.applyTo(target.appearance).copy(popup = popup, sendBindings = item.sendBindings)
        return target.copy(bindings = bindings.toMutableMap(),
            setBindingOverrides = target.setBindingOverrides?.plus(target.bindings.keys + bindings.keys),
            setStyleOverrides = target.setStyleOverrides?.let { overrides ->
                JsonObject(overrides + KeyAppearance.values(appearance).filterKeys { it in copiedAppearanceFields })
            }, appearance = appearance)
    }
    private fun click(key: CustomKeyboards.CustomKey): String {
        val identity = KeyboardSets.identity(key)
        if(identity.startsWith("group:")) return identity
        val raw = key.bindings["click"].orEmpty().trim()
        val action = BindingActions.resolve(raw)
        val value = when {
            action == null -> raw
            action.command.isNotEmpty() -> "command:${action.command}:${action.option}"
            action.select.isNotEmpty() -> "keyboard:${action.select}"
            action.commit.isNotEmpty() -> action.commit
            action.send.isNotEmpty() -> action.send
            else -> raw
        }
        return if (value.length == 1 || value.lowercase() in setOf("space", "backspace", "return", "shift_l", "shift_r")) value.lowercase() else value
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
            it.size == 1 || it.map { key -> match(key) }.distinct().size == 1
        }?.firstOrNull()
        val matches = mutableListOf<Match>(); var skipped = 0
        target.rows.forEachIndexed { row, line -> line.keys.forEachIndexed keys@ { column, key ->
            // Transparent spacers are not controls, even when another layout happens to have one.
            if (key.bindings.values.none(String::isNotBlank)) return@keys
            val sourceKey = unambiguous(byClick[click(key)]) ?: unambiguous(byLabel[label(key)])
            if (sourceKey == null) skipped++ else matches += match(sourceKey, row, column)
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
                keyboard.rows[item.row].keys[item.column] = copyKey(old, item)
            }
            keyboard.importedActions = keyboard.importedActions + source.importedActions
        }) { "当前键盘已删除" }
        check(CustomKeyboards.flush()) { "保存失败，请检查数据目录" }
        return plan.matches.size
    }
}
