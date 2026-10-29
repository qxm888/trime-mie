/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.util.appContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Stable action tokens keep user names separate from Rime's existing key names. */
object BindingActions {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val PREFIX = "__mie_action:"
    fun token(action: PresetKey): String = PREFIX + json.encodeToString(action)
    fun keyboard(id: String, label: String) = token(PresetKey(send = "Eisu_toggle", select = id, label = label))
    fun command(command: String, option: String = "", label: String = if(option.isEmpty()) command else "$command · ${option.take(40)}") =
        token(PresetKey(send = "FUNCTION", command = command, option = option, label = label))
    fun resolve(token: String): PresetKey? = when {
        token.startsWith(PREFIX) -> runCatching { json.decodeFromString<PresetKey>(token.removePrefix(PREFIX)) }.getOrNull()
        token.startsWith(NamedFunctions.PREFIX) -> PresetKey(send = "FUNCTION", command = "__mie_named_function",
            option = token.removePrefix(NamedFunctions.PREFIX), label = NamedFunctions.list().firstOrNull { NamedFunctions.PREFIX + it.id == token }?.name ?: "已删除功能")
        else -> null
    }
    fun label(token: String): String = resolve(token)?.let { action -> action.label.ifBlank { action.commit.ifBlank { action.text.ifBlank { action.send.ifBlank { action.command } } } }.take(80).ifBlank { null } }
        ?: com.osfans.trime.data.theme.Theme.BUILTIN_ACTIONS[token]?.label
        ?: ThemeManager.activeThemeOrNull()?.presetKeys?.get(token)?.label?.ifBlank { null } ?: token
}

object NamedFunctions {
    const val PREFIX = "__mie_function:"
    @Serializable data class Definition(val id: String, val name: String, val actions: List<String>)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs get() = appContext.getSharedPreferences("named_functions", android.content.Context.MODE_PRIVATE)
    private var cachedRaw: String? = null
    private var cached = emptyList<Definition>()
    val version get() = prefs.getLong("version", 0L)
    @Synchronized fun list(): List<Definition> {
        val raw = prefs.getString("definitions", "[]") ?: "[]"
        if (cachedRaw != raw) {
            cached = runCatching { json.decodeFromString<List<Definition>>(raw) }.getOrDefault(emptyList())
            cachedRaw = raw
        }
        return cached
    }
    fun normalize(input: String) = list().firstOrNull { it.name == input }?.let { PREFIX + it.id } ?: input
    @Synchronized fun save(definitions: List<Definition>) {
        require(definitions.size <= 128 && definitions.map { it.id }.distinct().size == definitions.size) { "功能过多或ID重复" }
        require(definitions.map { it.name }.distinct().size == definitions.size) { "功能名不能重复" }
        require(definitions.sumOf { value -> value.actions.sumOf { it.length } } <= 1024*1024) { "自定义功能内容超过1MB" }
        definitions.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "功能ID无效" }
            require(it.name.matches(Regex("[\\p{L}\\p{N}]{1,40}"))) { "功能名需为1–40个中文、英文或数字" }
            require(it.actions.isNotEmpty() && it.actions.size <= 64 && it.actions.all { token -> token.length <= 16384 && token.isNotBlank() }) { "每个功能需包含1–64个动作" }
        }
        check(prefs.edit().putString("definitions", json.encodeToString(definitions)).putLong("version", version + 1).commit()) { "功能保存失败" }
        cachedRaw = null
        ThemeManager.requestCustomizationRefresh()
    }
    fun merge(incoming: List<Definition>) {
        val current=list()
        require(incoming.none { value -> current.any { it.name==value.name && it.id!=value.id } }) { "存在同名且ID不同的自定义功能，请先重命名后再导入" }
        save(current.filterNot { old -> incoming.any { it.id==old.id } } + incoming)
    }
    val executionMutex = kotlinx.coroutines.sync.Mutex()
    /** Resolve the complete chain before dispatch, rejecting cycles without partial execution. */
    fun expanded(id: String): List<String> {
        val definitions=list().associateBy { it.id }
        val path=linkedSetOf<String>()
        val result=mutableListOf<String>()
        fun visit(key: String) {
            require(path.size<8 && path.add(key)) { "自定义功能存在循环或嵌套过深" }
            val definition=definitions[key] ?: error("自定义功能已删除")
            definition.actions.forEach { token ->
                if(token.startsWith(PREFIX)) visit(token.removePrefix(PREFIX)) else result.add(token)
                require(result.size<=256) { "组合动作不能超过256项" }
            }
            path.remove(key)
        }
        visit(id)
        return result
    }
}
