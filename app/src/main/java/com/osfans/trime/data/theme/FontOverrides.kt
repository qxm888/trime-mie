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
    fun folder(): File = com.osfans.trime.core.data.DirectExternalStorage.resources(context!!, "custom_fonts")
    fun choices(): List<String> = listOf("", "system:sans-serif", "system:serif", "system:monospace") +
        folder().listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { it.absolutePath }
    const val MAX_CHAIN = 32
    /** Legacy single paths remain valid; multiple selections use a JSON array. */
    fun decode(value: String): List<String> {
        val paths = if (value.startsWith("[")) runCatching {
            val array = org.json.JSONArray(value)
            (0 until minOf(array.length(), MAX_CHAIN)).map { array.getString(it) }
        }.getOrDefault(emptyList()) else listOf(value)
        val unique = paths.filter { it.isNotBlank() }.distinct().take(MAX_CHAIN)
        // Android's CustomFallbackBuilder has one system fallback after files.
        return unique.filterNot { it.startsWith("system:") } + unique.lastOrNull { it.startsWith("system:") }.let { listOfNotNull(it) }
    }
    fun encode(paths: List<String>): String {
        val normalized = paths.filter { it.isNotBlank() }.distinct().take(MAX_CHAIN)
        return when (normalized.size) { 0 -> ""; 1 -> normalized.first(); else -> org.json.JSONArray(normalized).toString() }
    }
    private fun singleName(value: String): String = when(value) {
        "system:sans-serif" -> "系统无衬线"
        "system:serif" -> "系统衬线"
        "system:monospace" -> "系统等宽"
        else -> File(value).name
    }
    fun name(value: String): String = decode(value).takeIf { it.isNotEmpty() }
        ?.joinToString(" → ", transform = ::singleName) ?: "跟随当前主题"
    private val families = mutableMapOf<String, android.graphics.fonts.FontFamily>()
    fun invalidate() { synchronized(cache) { cacheGeneration++;cache.clear(); failed.clear(); families.clear() } }
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
            val fonts = decode(value)
            val files = fonts.filterNot { it.startsWith("system:") }
            val system = fonts.lastOrNull { it.startsWith("system:") }?.removePrefix("system:")
            if (files.isEmpty()) system?.let { Typeface.create(it, Typeface.NORMAL) }
            else if (android.os.Build.VERSION.SDK_INT >= 29 && fonts.size > 1) {
                val available = files.mapNotNull { path ->
                    synchronized(cache) { families[path] } ?: runCatching {
                        android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(File(path)).build()).build()
                    }.onFailure { timber.log.Timber.w("Unavailable fallback font skipped") }.getOrNull()?.also {
                        synchronized(cache) { if (generation == cacheGeneration) families[path] = it }
                    }
                }
                if (available.isEmpty()) system?.let { Typeface.create(it, Typeface.NORMAL) }
                else Typeface.CustomFallbackBuilder(available.first()).apply {
                    available.drop(1).forEach { addCustomFallback(it) }
                    setSystemFallback(system ?: "sans-serif")
                }.build()
            } else {
                files.firstNotNullOfOrNull { path ->
                    runCatching { Typeface.createFromFile(path) }.getOrNull()
                } ?: system?.let { Typeface.create(it, Typeface.NORMAL) }
            }
        }
        return synchronized(cache) {
            if(generation!=cacheGeneration) return@synchronized loaded.getOrNull()
            cache[value] ?: loaded.getOrNull().also { result ->
                if (result == null) { failed.add(value); timber.log.Timber.w("Invalid font skipped (cached)") }
                else cache[value] = result
            }
        }
    }
}
