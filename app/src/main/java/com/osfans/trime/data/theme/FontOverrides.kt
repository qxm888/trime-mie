/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import android.content.Context
import android.graphics.Typeface
import java.io.File

/** App-owned font overrides; neither layout mode nor YAML files are changed. */
object FontOverrides {
    val roles = linkedMapOf("key" to "键盘按键", "candidate" to "候选文字", "comment" to "候选注释",
        "symbol" to "按键符号", "hint" to "按键注解", "popup" to "按键弹出预览", "text" to "编码 / 预编辑文字",
        "label" to "候选序号", "toolbar" to "工具栏")
    private var context: Context? = null
    private val cache = mutableMapOf<String, Typeface>()
    private val failed = mutableSetOf<String>()
    private var cacheGeneration = 0L
    @Volatile private var snapshot: Map<String, Any?> = emptyMap()
    private val prefs get() = context!!.getSharedPreferences("font_overrides", Context.MODE_PRIVATE)
    val version: Long get() = snapshot["version"] as? Long ?: 0
    fun init(ctx: Context) { context = com.osfans.trime.util.storageContext(ctx); snapshot = prefs.all.toMap() }
    fun selection(role: String): String = snapshot[role] as? String ?: ""
    fun size(role: String): Float = (snapshot["size.$role"] as? Float ?: 0f).takeIf { it.isFinite() && it in 0f..200f } ?: 0f
    fun setSize(role: String, value: Float) {
        require(role in roles && value.isFinite() && value in 0f..200f)
        check(prefs.edit().putFloat("size.$role", value).putLong("version", version + 1).commit())
        snapshot = prefs.all.toMap()
        ThemeManager.requestCustomizationRefresh()
    }
    @JvmOverloads
    fun set(role: String, value: String, refresh: Boolean = true) {
        require(role in roles)
        check(prefs.edit().putString(role, value).putLong("version", version + 1).commit()) { "字体设置保存失败" }
        snapshot = prefs.all.toMap()
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
    fun invalidate() { synchronized(cache) { cacheGeneration++;cache.clear(); failed.clear() } }
    fun typeface(role: String): Typeface? {
        return fromSelection(selection(role))
    }
    fun fromSelection(value: String): Typeface? {
        if (value.isEmpty()) return null
        val generation=synchronized(cache) {
            cache[value]?.let { return it }
            if(value in failed) return null
            cacheGeneration
        }
        // Typeface construction can map a large font. Do not hold the shared cache
        // monitor while unrelated, already-cached fonts are needed by the IME UI.
        val loaded=runCatching {
            if(value.startsWith("system:")) Typeface.create(value.removePrefix("system:"),Typeface.NORMAL)
            else Typeface.createFromFile(value)
        }
        return synchronized(cache) {
            if(generation!=cacheGeneration) return@synchronized loaded.getOrNull()
            cache[value] ?: loaded.onFailure {
                failed.add(value);timber.log.Timber.w("Invalid font skipped (cached)")
            }.getOrNull()?.also { cache[value]=it }
        }
    }
}
