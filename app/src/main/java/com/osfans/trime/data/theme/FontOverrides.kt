/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import android.content.Context
import android.graphics.Typeface
import java.io.File

/** App-owned font overrides; neither layout mode nor YAML files are changed. */
object FontOverrides {
    val roles = linkedMapOf("key" to "键盘按键", "candidate" to "候选文字", "comment" to "候选注释",
        "symbol" to "按键提示 / 符号", "popup" to "按键弹出预览", "text" to "编码 / 预编辑文字",
        "label" to "候选序号", "toolbar" to "工具栏")
    private var context: Context? = null
    private val cache = mutableMapOf<String, Typeface>()
    private val prefs get() = context!!.getSharedPreferences("font_overrides", Context.MODE_PRIVATE)
    val version: Long get() = context?.let { prefs.getLong("version", 0) } ?: 0
    fun init(ctx: Context) { context = ctx.applicationContext }
    fun selection(role: String): String = context?.let { prefs.getString(role, "").orEmpty() }.orEmpty()
    @JvmOverloads
    fun set(role: String, value: String, refresh: Boolean = true) {
        require(role in roles)
        check(prefs.edit().putString(role, value).putLong("version", version + 1).commit()) { "字体设置保存失败" }
        synchronized(cache) { cache.clear() }
        if (refresh) ThemeManager.requestCustomizationRefresh()
    }
    fun folder(): File = File(com.osfans.trime.core.data.DirectExternalStorage.resources(context!!), "custom_fonts").apply { mkdirs() }
    fun choices(): List<String> = listOf("", "system:sans-serif", "system:serif", "system:monospace") +
        folder().listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { it.absolutePath }
    fun name(value: String): String = when(value) {
        "" -> "跟随当前主题"
        "system:sans-serif" -> "系统无衬线"
        "system:serif" -> "系统衬线"
        "system:monospace" -> "系统等宽"
        else -> File(value).name
    }
    fun invalidate() { synchronized(cache) { cache.clear() } }
    fun typeface(role: String): Typeface? {
        val value = selection(role)
        if (value.isEmpty()) return null
        return synchronized(cache) {
            cache[value] ?: runCatching {
                if (value.startsWith("system:")) Typeface.create(value.removePrefix("system:"), Typeface.NORMAL)
                else Typeface.createFromFile(value)
            }.getOrNull()?.also { cache[value] = it }
        }
    }
}
