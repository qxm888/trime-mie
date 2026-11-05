/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.theme

import android.content.Context
import android.graphics.Typeface
import java.io.File

/** App-owned font overrides; neither layout mode nor YAML files are changed. */
object FontOverrides {
    private const val GLOBAL_PREFIX = "theme_global."
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
    fun globalSelection(themeId: String): String = snapshot[GLOBAL_PREFIX + themeId] as? String ?: ""
    fun effectiveSelection(role: String, themeId: String): String = selection(role).ifEmpty { globalSelection(themeId) }
    fun setGlobal(themeId: String, value: String, refresh: Boolean = true) {
        require(themeId.isNotBlank()) { "请先选择一个主题" }
        check(prefs.edit().putString(GLOBAL_PREFIX + themeId, value).putLong("version", version + 1).commit()) { "全局字体保存失败" }
        snapshot = prefs.all.toMap()
        if (refresh) ThemeManager.requestCustomizationRefresh()
    }
    /** Include per-theme defaults when resource/data directories move. */
    fun relocateSelections(transform: (String) -> String) {
        val changed = snapshot.filterKeys { it in roles || it.startsWith(GLOBAL_PREFIX) }
            .mapNotNull { (key, value) -> (value as? String)?.let { old -> transform(old).takeIf { it != old }?.let { key to it } } }
        if (changed.isEmpty()) return
        val edit = prefs.edit().putLong("version", version + 1)
        changed.forEach { (key, value) -> edit.putString(key, value) }
        check(edit.commit()) { "迁移字体设置失败" }
        snapshot = prefs.all.toMap()
    }

    /** 删除字体文件后调用：把该路径从所有单项/全局字体链里清掉，并失效缓存。 */
    @Synchronized
    fun purge(path: String) {
        val edit = prefs.edit()
        var changed = false
        snapshot.keys.filter { it in roles || it.startsWith(GLOBAL_PREFIX) }.forEach { key ->
            val value = snapshot[key] as? String ?: return@forEach
            val paths = decode(value)
            if (path in paths) {
                edit.putString(key, encode(paths.filterNot { it == path }))
                changed = true
            }
        }
        if (changed) edit.putLong("version", version + 1)
        check(edit.commit()) { "清理字体引用失败" }
        snapshot = prefs.all.toMap()
        invalidate()
        if (changed) ThemeManager.requestCustomizationRefresh()
    }
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
    fun choices(): List<String> = listOf("", "system:default", "system:sans-serif", "system:serif", "system:monospace") +
        (folder().listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name } + DirectoryFontFallback.files).map { it.absolutePath }.distinct()
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
        "system:default" -> "系统当前字体"
        "system:sans-serif" -> "系统无衬线"
        "system:serif" -> "系统衬线"
        "system:monospace" -> "系统等宽"
        else -> File(value).name
    }
    fun name(value: String): String = decode(value).takeIf { it.isNotEmpty() }
        ?.joinToString(" → ", transform = ::singleName) ?: "跟随当前主题"
    private val families = mutableMapOf<String, android.graphics.fonts.FontFamily>()
    fun invalidate() { synchronized(cache) { cacheGeneration++;cache.clear(); failed.clear(); families.clear() } }
    fun typeface(role: String, themeId: String = ""): Typeface? {
        return fromSelection(selection(role)) ?: fromSelection(globalSelection(themeId))
    }
    fun fromSelection(value: String): Typeface? {
        if (value.isEmpty()) return null
        val key = value + "|" + DirectoryFontFallback.stamp
        val generation=synchronized(cache) {
            cache[key]?.let { return it }
            if(key in failed) return null
            cacheGeneration
        }
        // Typeface construction can map a large font. Do not hold the shared cache
        // monitor while unrelated, already-cached fonts are needed by the IME UI.
        val loaded=runCatching {
            val fonts = decode(value)
            val explicit = fonts.filterNot { it.startsWith("system:") }
            val files = (explicit + if (explicit.isNotEmpty()) DirectoryFontFallback.files.map { it.path } else emptyList()).distinct()
            val system = fonts.lastOrNull { it.startsWith("system:") }?.removePrefix("system:")
            if (files.isEmpty()) system?.let { if (it == "default") Typeface.DEFAULT else Typeface.create(it, Typeface.NORMAL) }
            else if (android.os.Build.VERSION.SDK_INT >= 29 && (files.size > 1 || system != null)) {
                val available = files.take(android.graphics.Typeface.CustomFallbackBuilder.getMaxCustomFallbackCount()).mapNotNull { path ->
                    synchronized(cache) { families[path] } ?: runCatching {
                        android.graphics.fonts.FontFamily.Builder(android.graphics.fonts.Font.Builder(File(path)).build()).build()
                    }.onFailure { timber.log.Timber.w("Unavailable fallback font skipped") }.getOrNull()?.also {
                        synchronized(cache) { if (generation == cacheGeneration) families[path] = it }
                    }
                }
                if (available.isEmpty()) system?.let { Typeface.create(it, Typeface.NORMAL) }
                else Typeface.CustomFallbackBuilder(available.first()).apply {
                    available.drop(1).forEach { addCustomFallback(it) }
                    setSystemFallback(system?.takeUnless { it == "default" } ?: "sans-serif")
                }.build()
            } else {
                files.firstNotNullOfOrNull { path ->
                    runCatching { Typeface.createFromFile(path) }.getOrNull()
                } ?: system?.let { Typeface.create(it, Typeface.NORMAL) }
            }
        }
        return synchronized(cache) {
            if(generation!=cacheGeneration) return@synchronized loaded.getOrNull()
            cache[key] ?: loaded.getOrNull().also { result ->
                if (result == null) { failed.add(key); timber.log.Timber.w("Invalid font skipped (cached)") }
                else cache[key] = result
            }
        }
    }
}
