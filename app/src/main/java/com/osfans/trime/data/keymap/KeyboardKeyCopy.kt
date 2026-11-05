/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

/**
 * 两个 26 键布局之间按「字母身份」搬手势与符号：
 * A 对 A、B 对 B，不看行号列号，所以中文 26 键、英文 26 键和七列布局可以互抄。
 * 主输入（click）永远保留目标自己的，输入性质不会变。
 */
object KeyboardKeyCopy {
    private const val LETTER_COUNT = 26

    data class Match(
        val row: Int,
        val column: Int,
        val source: CustomKeyboards.CustomKey,
        val sourceStyle: KeyAppearance.Style,
    )

    data class Plan(
        val sourceName: String,
        val targetId: String,
        val targetName: String,
        val version: Long,
        val matches: List<Match>,
        val skipped: Int,
    )

    /** 键面主标签（或空标签时的主输入）是单个 a–z 字母才算字母键；一个键挤两个字母的不算。 */
    fun letterOf(key: CustomKeyboards.CustomKey): Char? {
        val label = key.label.trim()
        if (label.length == 1) return label[0].lowercaseChar().takeIf { it in 'a'..'z' }
        if (label.isNotEmpty()) return null
        val raw = key.bindings["click"].orEmpty().trim()
        return if (raw.length == 1) raw[0].lowercaseChar().takeIf { it in 'a'..'z' } else null
    }

    fun letterKeys(keyboard: CustomKeyboards.CustomKeyboard): Map<Char, CustomKeyboards.CustomKey> {
        val result = linkedMapOf<Char, CustomKeyboards.CustomKey>()
        keyboard.rows.forEach { row ->
            row.keys.forEach { key ->
                val letter = letterOf(key) ?: return@forEach
                if (!result.containsKey(letter)) result[letter] = key
            }
        }
        return result
    }

    /** 26 个独立字母键齐了才允许复制；14 键这种合键布局会被这条挡掉。 */
    fun isLetterLayout(keyboard: CustomKeyboards.CustomKeyboard): Boolean =
        letterKeys(keyboard).keys.size == LETTER_COUNT

    fun plan(sourceId: String, targetId: String): Plan {
        require(sourceId != targetId) { "请选择其它布局" }
        val source = CustomKeyboards.get(sourceId) ?: error("来源布局已删除")
        return plan(source, source.name, targetId)
    }

    fun plan(source: CustomKeyboards.CustomKeyboard, sourceName: String, targetId: String): Plan {
        val version = CustomKeyboards.version
        val target = CustomKeyboards.get(targetId) ?: error("目标布局已删除")
        require(isLetterLayout(source)) { "来源布局没有完整的 26 个字母键，不能复制" }
        val sourceKeys = letterKeys(source)
        val matches = mutableListOf<Match>()
        var skipped = 0
        target.rows.forEachIndexed { row, line ->
            line.keys.forEachIndexed key@{ column, key ->
                val letter = letterOf(key) ?: return@key
                val sourceKey = sourceKeys[letter]
                if (sourceKey == null) { skipped++; return@key }
                matches += Match(row, column, sourceKey, KeyAppearance.merge(source.keyAppearance, sourceKey.appearance))
            }
        }
        require(matches.isNotEmpty()) { "两个布局没有相同的字母键" }
        return Plan(sourceName, targetId, target.name, version, matches, skipped)
    }

    fun apply(plan: Plan): Int {
        require(plan.version == CustomKeyboards.version) { "键盘已变化，请重新选择来源" }
        check(CustomKeyboards.mutate(plan.targetId) { keyboard ->
            plan.matches.forEach { item ->
                val old = keyboard.rows[item.row].keys[item.column]
                keyboard.rows[item.row].keys[item.column] =
                    KeyboardBindingCopy.copyGestures(old, item.source, item.sourceStyle)
            }
        }) { "目标布局已删除" }
        check(CustomKeyboards.flush()) { "保存失败，请检查数据目录" }
        return plan.matches.size
    }
}
