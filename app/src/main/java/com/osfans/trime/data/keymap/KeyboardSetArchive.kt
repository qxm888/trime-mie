/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import com.osfans.trime.core.data.MieUserDirectory
import com.osfans.trime.data.keymap.CustomKeyboards.CustomKeyboard
import com.osfans.trime.data.theme.FontOverrides
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Portable set archive. Untrusted files are bounded, hashed and staged before any store mutation. */
object KeyboardSetArchive {
    @Serializable data class Asset(val name: String, val size: Long, val sha256: String)
    @Serializable data class Document(val format: String = "trime-mie-keyboard-set", val version: Int = 1,
        val roots: List<String>, val pages: Map<String, CustomKeyboard>, val functions: List<NamedFunctions.Definition> = emptyList(),
        val assets: List<Asset> = emptyList())
    private const val LIMIT = 2L * 1024 * 1024 * 1024
    private fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(65536); while(true) { val n = input.read(buffer); if(n < 0) break; sha.update(buffer, 0, n) } }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
    private val resourceFields = setOf("image", "background", "toolbarBackground", "preview", "file", "sound",
        "font", "icon", "style", "keyBackColor", "hilitedKeyBackColor", "keyOnBackColor", "allKeysBackground", "floatingBackground")
    /** Only resource fields are rebased; literal key labels and committed text keep their exact content. */
    private fun strings(value: JsonElement, field: String = "", replace: (String) -> String): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.mapValues { (name, child) -> strings(child, name, replace) })
        is JsonArray -> JsonArray(value.map { strings(it, field, replace) })
        is JsonPrimitive -> if(value.isString && field in resourceFields) {
            val raw = value.content
            val chain = if(field == "font" && raw.startsWith("[")) runCatching { KeyboardSets.json.parseToJsonElement(raw) as? JsonArray }.getOrNull() else null
            if(chain != null && chain.all { it is JsonPrimitive && it.isString }) JsonPrimitive(strings(chain, field, replace).toString())
            else JsonPrimitive(replace(raw))
        } else value
        else -> value
    }
    private fun functionTokens(value: JsonElement, transform: (String) -> String): JsonElement = when(value) {
        is JsonObject -> JsonObject(value.mapValues { (name, child) ->
            if(name == "option" && value["command"]?.jsonPrimitive?.contentOrNull == "__mie_named_function" && child is JsonPrimitive)
                JsonPrimitive(transform(NamedFunctions.PREFIX + child.content).removePrefix(NamedFunctions.PREFIX))
            else functionTokens(child, transform)
        })
        is JsonArray -> JsonArray(value.map { functionTokens(it, transform) })
        is JsonPrimitive -> if(value.isString) {
            val raw = value.content
            if(raw.startsWith("__mie_action:")) {
                val nested = KeyboardSets.json.parseToJsonElement(raw.removePrefix("__mie_action:"))
                JsonPrimitive("__mie_action:" + functionTokens(nested, transform).toString())
            } else JsonPrimitive(Regex("\\{([^{}]+)\\}").replace(transform(raw)) { match ->
                val changed = transform(match.groupValues[1]); if(changed != match.groupValues[1]) "{$changed}" else match.value
            })
        } else value
        else -> value
    }
    private fun referencedFunctions(pages: Map<String, CustomKeyboard>): List<NamedFunctions.Definition> {
        val definitions = NamedFunctions.list().associateBy { it.id }; val required = linkedSetOf<String>()
        fun scan(value: JsonElement) {
            functionTokens(value) { token -> if(token.startsWith(NamedFunctions.PREFIX)) required.add(token.removePrefix(NamedFunctions.PREFIX)); token }
        }
        pages.values.forEach { scan(KeyboardSets.encode(it)) }
        val visited = mutableSetOf<String>()
        while(true) {
            val next = required.firstOrNull { it !in visited } ?: break
            visited.add(next)
            definitions[next]?.actions?.forEach { scan(JsonPrimitive(it)) } ?: error("键盘引用了已删除的自定义功能")
            require(visited.size <= 128) { "自定义功能依赖过多" }
        }
        return required.map { definitions.getValue(it) }
    }
    fun create(context: Context, roots: List<String>): File {
        check(CustomKeyboards.flush()) { "键盘配置保存失败" }
        val version = CustomKeyboards.version
        val ids = CustomKeyboards.familyIds(roots)
        require(roots.isNotEmpty() && roots.all { it in ids })
        val snapshots = CustomKeyboards.portableLayouts(ids)
        val files = linkedMapOf<String, File>(); val references = mutableMapOf<String, String>()
        val allowed = listOf(CustomKeyAssets.root(context), File(context.filesDir, "custom_kb_bg"),
            MieUserDirectory.assetFolder(context, "baidu_skins"), File(context.filesDir, "baidu_skins"), FontOverrides.folder())
            .map { it.canonicalPath + File.separator }
        val fonts = com.osfans.trime.data.theme.DirectoryFontFallback.files.map { it.canonicalPath }.toSet()
        val doc = Document(roots = roots, pages = snapshots, functions = referencedFunctions(snapshots))
        val rewritten = strings(KeyboardSets.json.encodeToJsonElement(Document.serializer(), doc)) { raw ->
            val file = if(raw.startsWith("/")) File(raw) else if(raw.contains('.') && raw.length < 4096) {
                CustomKeyAssets.resolve(context, raw)?.let(::File)
                    ?: File(FontOverrides.folder(), raw).takeIf { it.isFile }
                    ?: com.osfans.trime.data.theme.DirectoryFontFallback.files.firstOrNull { it.name == raw }
            } else null
            if(file?.isFile == true && (allowed.any { file.canonicalPath.startsWith(it) } || file.canonicalPath in fonts)) {
                references.getOrPut(raw) {
                    val ext = file.extension.takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }.orEmpty()
                    val name = "assets/${files.size}" + if(ext.isNotEmpty()) ".$ext" else ""
                    files[name] = file; "mie-asset://$name"
                }
            } else raw
        }.jsonObject.toMutableMap()
        val inventory = files.map { (name, file) -> Asset(name, file.length(), digest(file)) }
        require(inventory.sumOf { it.size } <= LIMIT && files.size <= 4096) { "资源超过 2GB 或 4096 个文件" }
        rewritten["assets"] = KeyboardSets.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Asset.serializer()), inventory)
        check(version == CustomKeyboards.version) { "键盘已变化，请重新导出" }
        val output = File.createTempFile("keyboard-set-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("keyboard.json")); zip.write(JsonObject(rewritten).toString().toByteArray()); zip.closeEntry()
                files.forEach { (name, file) -> zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            }
            return output
        } catch(error: Exception) { output.delete(); throw error }
    }
    fun import(context: Context, archive: File): List<String> {
        val staging = File(context.cacheDir, "keyboard-set-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val destination = File(CustomKeyAssets.root(context), "整套导入/${UUID.randomUUID()}")
        var committed = false
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().asSequence().toList()
                require(entries.size <= 4097 && entries.map { it.name }.distinct().size == entries.size) { "文件过多或存在重名文件" }
                val manifest = zip.getEntry("keyboard.json") ?: error("不是整套键盘包")
                val bytes = java.io.ByteArrayOutputStream().also { output -> zip.getInputStream(manifest).use { input ->
                    val buffer = ByteArray(8192); while(true) { val n = input.read(buffer); if(n < 0) break
                        require(output.size() + n <= 32 * 1024 * 1024) { "布局配置超过32MB" }; output.write(buffer, 0, n) }
                } }.toByteArray()
                require(bytes.size <= 32 * 1024 * 1024) { "布局配置超过32MB" }
                val document = KeyboardSets.json.decodeFromString(Document.serializer(), bytes.toString(Charsets.UTF_8))
                require(document.format == "trime-mie-keyboard-set" && document.version == 1) { "不支持此键盘包格式" }
                require(document.pages.isNotEmpty() && document.pages.size <= 512 && document.roots.isNotEmpty())
                require(document.pages.keys.all { it.startsWith(CustomKeyboards.ID_PREFIX) } && document.roots.all { it in document.pages })
                document.pages.keys.forEach { KeyboardSets.rootOf(it, document.pages) }
                require(document.assets.size <= 4096 && document.assets.sumOf { it.size } <= LIMIT && document.assets.all { it.size in 0..LIMIT })
                var total = 0L
                document.assets.forEach { asset ->
                    require(asset.name.matches(Regex("assets/[0-9]+(?:\\.[A-Za-z0-9]{1,12})?"))) { "资源路径无效" }
                    val entry = zip.getEntry(asset.name) ?: error("缺少资源 ${asset.name}")
                    val file = File(staging, asset.name).apply { parentFile!!.mkdirs() }
                    var count = 0L
                    zip.getInputStream(entry).use { input -> file.outputStream().use { output -> val buf = ByteArray(65536)
                        while(true) { val n = input.read(buf); if(n < 0) break; count += n; total += n
                            require(count <= asset.size && total <= LIMIT) { "解压资源超出声明大小" }; output.write(buf, 0, n) }
                    } }
                    require(file.length() == asset.size && digest(file) == asset.sha256) { "资源校验失败 ${asset.name}" }
                }
                val assets = document.assets.associate { "mie-asset://${it.name}" to File(destination, it.name).absolutePath }
                val rebound = strings(KeyboardSets.json.encodeToJsonElement(Document.serializer(), document)) { raw ->
                    if(raw.startsWith("mie-asset://")) assets[raw] ?: error("资源引用缺失") else raw
                }
                var restored = KeyboardSets.json.decodeFromJsonElement(Document.serializer(), rebound)
                val functionIds = restored.functions.associate { it.id to ("f_" + UUID.randomUUID().toString().replace("-", "")) }
                fun functionToken(token: String): String = if(token.startsWith(NamedFunctions.PREFIX))
                    functionIds[token.removePrefix(NamedFunctions.PREFIX)]?.let { NamedFunctions.PREFIX + it } ?: token else token
                val rewrittenFunctions = functionTokens(KeyboardSets.json.encodeToJsonElement(Document.serializer(), restored), ::functionToken)
                restored = KeyboardSets.json.decodeFromJsonElement(Document.serializer(), rewrittenFunctions)
                val usedNames = NamedFunctions.list().map { it.name }.toMutableSet()
                restored = restored.copy(functions = restored.functions.map { definition ->
                    var name = definition.name
                    var count = 1
                    while(name in usedNames) { name = definition.name.take(30) + "导入" + count++ }
                    usedNames.add(name)
                    definition.copy(id = functionIds.getValue(definition.id), name = name)
                })
                val ids = restored.pages.keys.associateWith { KeyboardSets.newId() }
                val actionNames = restored.pages.values.flatMap { it.importedActions.keys }.distinct().associateWith { "import_${UUID.randomUUID().toString().replace("-", "")}" }
                val imported = restored.pages.map { (id, page) -> ids.getValue(id) to KeyboardSets.remap(page, ids, actionNames) }.toMap().toMutableMap()
                // Freeze referenced profiles in the archive; keep the original page-level inheritance masks.
                restored.roots.forEach { oldRoot ->
                    val newRoot = ids.getValue(oldRoot); val old = restored.pages.getValue(oldRoot)
                    old.setConfig?.let { cfg ->
                        val effectiveDefaults = KeyboardSets.decode(JsonObject(KeyboardSets.encode(old) + cfg.defaults))
                        val remappedDefaults = KeyboardSets.remap(effectiveDefaults, ids, actionNames)
                        imported.getValue(newRoot).setConfig = cfg.copy(defaults = KeyboardSets.values(remappedDefaults), profiles = emptyMap(),
                            order = cfg.order.mapNotNull(ids::get), defaultPage = ids[cfg.defaultPage] ?: newRoot)
                    }
                }
                // Retain every page's explicit override decisions after rewriting typed action references.
                restored.pages.forEach { (oldId, oldPage) ->
                    if(oldPage.setOverrides != null) {
                        val remapped = imported.getValue(ids.getValue(oldId))
                        remapped.setOverrides = KeyboardSets.rewriteMask(oldPage.setOverrides!!, KeyboardSets.values(remapped))
                    }
                }
                check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
                check(staging.renameTo(destination) || staging.copyRecursively(destination).also { check(it) }) { "无法保存资源" }
                val oldFunctions = NamedFunctions.list()
                try {
                    if(restored.functions.isNotEmpty()) NamedFunctions.merge(restored.functions)
                    CustomKeyboards.editSets { pages, _ -> pages.putAll(imported) }
                    committed = true
                } catch(error: Exception) { if(restored.functions.isNotEmpty()) NamedFunctions.save(oldFunctions); throw error }
                return restored.roots.map { ids.getValue(it) }
            }
        } finally {
            staging.deleteRecursively()
            if(!committed) destination.deleteRecursively()
        }
    }
}
