/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.baiduskin

import android.content.Context
import android.graphics.*
import com.osfans.trime.data.keymap.CustomBackground
import com.osfans.trime.data.keymap.CustomKeyboards
import java.io.File
import java.util.UUID

/** Converts supported BDS resources into app-owned images, geometry and animation data. */
object BdsImporter {
    data class Result(val name: String, val ids: List<String>, val warnings: List<String>)
    private val pages = linkedMapOf("py_26" to "中文26键", "py_9" to "中文九宫格", "en_26" to "英文26键", "num_9" to "数字")

    fun import(context: Context, archiveFile: File, sourceName: String): Result {
        val folder = File(com.osfans.trime.core.data.DirectExternalStorage.resources(context, "baidu_skins"), UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            BdsArchive(archiveFile).use { zip ->
                val reader = Reader(zip, folder)
                val info = zip.text("Info.txt").lineSequence().filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
                val name = info["Name"].orEmpty().ifBlank { sourceName.substringBeforeLast('.') }.take(120)
                val available = pages.keys.filter { page -> listOf("light", "dark").any { "$it/port/$page.ini" in zip.entries } }
                require("py_26" in available || "py_9" in available) { "暂不支持此皮肤：缺少中文键盘布局" }
                val ids = available.associateWith { CustomKeyboards.ID_PREFIX + UUID.randomUUID().toString().replace("-", "") }
                val layouts = linkedMapOf<String, CustomKeyboards.CustomKeyboard>()
                for (page in available) {
                    val variants = linkedMapOf<String, ImportedLayout>()
                    for (tone in listOf("light", "dark")) for (direction in listOf("port", "land")) {
                        val path = "$tone/$direction/$page.ini"
                        if (path !in zip.entries) continue
                        variants["$tone/$direction"] = reader.layout(tone, direction, page, ids)
                    }
                    val base = variants["light/port"] ?: variants["dark/port"] ?: continue
                    require(base.keys.size >= 8) { "${pages[page]}布局有效按键不足" }
                    val keysByRow = base.keys.groupBy { it.box.y.toInt() }.toSortedMap()
                    val rows = keysByRow.values.map { row ->
                        CustomKeyboards.CustomRow(row.sortedBy { it.box.x }.map { key ->
                            val preview = File(folder, "preview-${ids[page]}-${key.id}.png")
                            reader.renderKey(preview, key)
                            CustomKeyboards.CustomKey(key.label, key.box.width * 100 / base.width, key.bindings.toMutableMap(),
                                background = CustomBackground(CustomBackground.Type.IMAGE, image = preview.absolutePath),
                                skinId = key.id, skin = key.style)
                        }.toMutableList(), height = 60)
                    }.toMutableList()
                    layouts[ids.getValue(page)] = CustomKeyboards.CustomKeyboard(
                        name = "$name · ${pages[page]}", rows = rows, rowSpacing = 0,
                        background = CustomBackground(CustomBackground.Type.IMAGE, image = base.background),
                        toolbarBackground = base.toolbarBackground.takeIf { it.isNotEmpty() }?.let { CustomBackground(CustomBackground.Type.IMAGE, image = it) },
                        importedSkin = ImportedSkin(info["Author"].orEmpty(), page, variants, sourceName),
                    )
                }
                require(layouts.isNotEmpty()) { "未找到可导入的布局" }
                CustomKeyboards.addImported(layouts)
                return Result(name, layouts.keys.toList(), reader.warnings.toList())
            }
        } catch (e: Exception) { folder.deleteRecursively(); throw e }
    }

    private class Reader(private val zip: BdsArchive, private val folder: File) {
        val warnings = linkedSetOf<String>()
        private val iniCache = mutableMapOf<String, BdsIni>()
        private val resources = mutableMapOf<String, String>()
        private fun ini(path: String) = iniCache.getOrPut(path) { BdsIni(zip.text(path)) }
        private fun floats(value: String?) = value.orEmpty().split(',').mapNotNull { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }
        private fun ids(value: String?) = value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull()?.takeIf { id -> id > 0 } }.take(20)
        private fun resource(path: String): String? {
            resources[path]?.let { return it }
            if (path !in zip.entries) { warnings += "缺少资源：$path"; return null }
            val ext = path.substringAfterLast('.').lowercase()
            if (ext !in listOf("png", "ogg", "wav", "mp3")) return null
            val dest = File(folder, "asset-${resources.size}.$ext")
            dest.writeBytes(zip.read(path))
            if (ext == "png") {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(dest.path, options)
                require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 24_000_000) { "图片无效或尺寸过大：$path" }
            }
            return dest.absolutePath.also { resources[path] = it }
        }
        private fun image(prefix: String, value: String?): SkinImage? {
            val parts = value.orEmpty().split(','); if (parts.size < 2) return null
            val name = parts[0].trim(); val index = parts[1].trim().toIntOrNull() ?: return null
            require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "..") { "图片引用不合法" }
            val til = "$prefix/$name.til"; if (til !in zip.entries) return null
            val rect = floats(ini(til)["IMG$index"]["SOURCE_RECT"]).map { it.toInt() }
            if (rect.size != 4) return null
            val file = resource("$prefix/$name.png") ?: return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file, options)
            if (rect[0] < 0 || rect[1] < 0 || rect[2] <= 0 || rect[3] <= 0 || rect[0].toLong() + rect[2] > options.outWidth || rect[1].toLong() + rect[3] > options.outHeight) {
                warnings += "已跳过无效切图：$name,$index"; return null
            }
            return SkinImage(file, rect)
        }
        private fun animation(data: BdsIni, id: Int, seen: Set<Int> = emptySet(), delay: Int = 0, budget: IntArray = intArrayOf(64)): List<SkinTween> {
            if (budget[0]-- <= 0) { warnings += "组合动画节点过多，已截断"; return emptyList() }
            if (id in seen || seen.size >= 8) { warnings += "已跳过循环动画：$id"; return emptyList() }
            val value = data["ANIM$id"]
            if ("BUILD_LIST" in value) {
                var nextDelay = delay
                return ids(value["BUILD_LIST"]).flatMap { child ->
                    val tracks = animation(data, child, seen + id, nextDelay, budget)
                    if (value["BUILD_METHOD"] == "1") nextDelay = tracks.maxOfOrNull(SkinPainter::duration) ?: nextDelay
                    tracks
                }.take(32)
            }
            val type = value["TYPE"]?.toIntOrNull() ?: return emptyList()
            if (type !in listOf(0, 2, 4)) { warnings += "未支持动画类型：$type"; return emptyList() }
            val from = floats(value["FROM"]); val to = floats(value["TO"])
            if (from.isEmpty() || to.isEmpty()) return emptyList()
            return listOf(SkinTween(type, from, to, value["DURATION"]?.toIntOrNull()?.coerceIn(16, 3000) ?: 100,
                value["REPEAT_CNT"]?.toIntOrNull()?.coerceIn(0, 3) ?: 0, value["REPEAT_MODE"] == "1",
                value["INTPOL"]?.toIntOrNull() ?: 0, floats(value["PIVOT"]).ifEmpty { listOf(50f, 50f) }, delay.coerceAtMost(6000)))
        }
        private fun layer(prefix: String, css: BdsIni, anim: BdsIni, style: Int, animationStyle: Int?, fit: Boolean): SkinLayer? {
            val value = css["STYLE$style"]
            val normal = image(prefix, value["NM_IMG"]); val pressed = image(prefix, value["HL_IMG"])
            if (normal == null && pressed == null) return null
            val animationId = css["STYLE${animationStyle ?: style}"]["PRESS_ANIM"]?.toIntOrNull()
            return SkinLayer(normal, pressed, animationId?.let { animation(anim, it) }.orEmpty(), fit)
        }
        fun layout(tone: String, direction: String, page: String, links: Map<String, String>): ImportedLayout {
            val prefix = "$tone/res"; val css = ini("$prefix/default.css")
            val anim = if ("$prefix/anim.ini" in zip.entries) ini("$prefix/anim.ini") else BdsIni("")
            val gen = ini("$tone/$direction/gen.ini"); val panel = gen["PANEL"]
            val size = floats(panel["SIZE"]); require(size.size == 2 && size.all { it in 100f..4096f }) { "键盘尺寸无效" }
            val w = size[0].toInt(); val h = size[1].toInt()
            val data = ini("$tone/$direction/$page.ini")
            val backgroundLayers = ids(panel["BACK_STYLE"]).mapNotNull { layer(prefix, css, anim, it, null, true) }.toMutableList()
            val decorative = mutableListOf<Pair<SkinBox, ImportedKeyStyle>>()
            val placed = mutableListOf<PlacedSkinKey>()
            data.sections.forEach { (section, value) ->
                if (!section.matches(Regex("KEY[0-9]+"))) return@forEach
                val rect = floats(value["VIEW_RECT"]); if (rect.size != 4 || rect[2] <= 0 || rect[3] <= 0 || rect[0] < 0 || rect[1] < 0 || rect[0] >= w || rect[1] >= h) return@forEach
                val box = SkinBox(rect[0], rect[1], rect[2].coerceAtMost(w.toFloat()), minOf(rect[3], h - rect[1]))
                val layers = mutableListOf<SkinLayer>()
                val backAnimations = ids(value["BACK_ANIM_STYLE"])
                ids(value["BACK_STYLE"]).forEachIndexed { index, id -> layer(prefix, css, anim, id, backAnimations.getOrNull(index), true)?.let(layers::add) }
                val foreAnimations = ids(value["FORE_ANIM_STYLE"])
                ids(value["FORE_STYLE"]).forEachIndexed { index, id -> layer(prefix, css, anim, id, foreAnimations.getOrNull(index), false)?.let(layers::add) }
                val sound = ids(value["SOUND_STYLE"]).firstNotNullOfOrNull { css["STYLE$it"]["PRESS_SOUND_PATH"] }
                    ?.let { path -> if (path.matches(Regex("[A-Za-z0-9_.-]+"))) resource("$prefix/$path") else null }.orEmpty()
                val style = ImportedKeyStyle(layers, sound, box.width, box.height)
                val center = value["CENTER"].orEmpty()
                if (center.isBlank()) {
                    if (layers.isNotEmpty() && rect[2] >= w * 0.8f) decorative += box to style
                    return@forEach
                }
                val bindings = linkedMapOf<String, String>()
                fun bind(field: String, behavior: String) { action(value[field], page, links, value["UP"])?.let { bindings[behavior] = it } }
                bind("CENTER", "click"); bind("UP", "swipeUp"); bind("DOWN", "swipeDown"); bind("LEFT", "swipeLeft"); bind("RIGHT", "swipeRight")
                placed += PlacedSkinKey(section, box, label(center, page, value["UP"]), bindings, style)
            }
            val background = File(folder, "$tone-$direction-$page-background.png")
            save(background, w, h) { canvas ->
                SkinPainter.paint(canvas, ImportedKeyStyle(backgroundLayers, sourceWidth = w.toFloat(), sourceHeight = h.toFloat()), RectF(0f, 0f, w.toFloat(), h.toFloat()))
                decorative.distinctBy { it.second.layers }.forEach { (box, style) -> SkinPainter.paint(canvas, style, RectF(box.x, box.y, box.x + box.width, box.y + box.height)) }
            }
            val toolbar = File(folder, "$tone-$direction-$page-toolbar.png")
            val candName = gen["CAND"]["LAYOUT_NAME"].orEmpty().ifBlank { "cand1" }
            val candPath = "$tone/$direction/$candName.cnd"
            val candStyle = if (candPath in zip.entries) ini(candPath)["CAND"]["BACK_STYLE"] else "2"
            val toolbarLayers = ids(candStyle).mapNotNull { layer(prefix, css, anim, it, null, true) }
            save(toolbar, w, 135) { SkinPainter.paint(it, ImportedKeyStyle(toolbarLayers, sourceWidth = w.toFloat(), sourceHeight = 135f), RectF(0f, 0f, w.toFloat(), 135f)) }
            val preview = File(folder, "$tone-$direction-$page-preview.png")
            save(preview, w, h) { canvas ->
                SkinBitmaps.get(background.path)?.let { canvas.drawBitmap(it, null, Rect(0, 0, w, h), null) }
                placed.forEach { key -> val b = key.box; SkinPainter.paint(canvas, key.style, RectF(b.x, b.y, b.x + b.width, b.y + b.height)) }
            }
            return ImportedLayout(w, h, background.absolutePath, toolbar.absolutePath, placed, preview.absolutePath)
        }
        private fun label(value: String, page: String, up: String?): String {
            if (page == "py_9" && up?.matches(Regex("[2-9]")) == true && !value.startsWith('F')) return up
            return when(value) { "F36" -> "退格"; "F38" -> "空格"; "F39" -> "回车"; "F10" -> "Shift"; "F1" -> "符号"; "F6" -> "数字"; "F15", "F16" -> "中/EN"; "F4" -> "返回"; "F62" -> "收起"; else -> value }
        }
        private fun action(value: String?, page: String, links: Map<String, String>, up: String?): String? {
            if (value.isNullOrBlank()) return null
            if (page == "py_9" && up?.matches(Regex("[2-9]")) == true && value.length == 1 && value[0].isLetter()) return up
            fun keyboard(target: String) = links[target]?.let { "Keyboard_$it" }
            return when(value) {
                "F36" -> "BackSpace"; "F38" -> "space"; "F39" -> "Return"; "F10" -> "Shift_L"
                "F1", "F6" -> keyboard("num_9")
                "F4" -> keyboard("py_26") ?: keyboard("py_9")
                "F15" -> keyboard("py_26") ?: "Mode_switch"
                "F16" -> keyboard("en_26") ?: "Mode_switch"
                "F62" -> "Hide"; "F76", "F31" -> "SETTINGS"
                else -> if (value.matches(Regex("F[0-9]+"))) { warnings += "未映射的百度功能键：$value（可在按键编辑中重新绑定）"; null } else value
            }
        }
        fun renderKey(file: File, key: PlacedSkinKey) = save(file, key.box.width.toInt().coerceAtLeast(1), key.box.height.toInt().coerceAtLeast(1)) {
            SkinPainter.paint(it, key.style, RectF(0f, 0f, key.box.width, key.box.height))
        }
        private fun save(file: File, width: Int, height: Int, draw: (Canvas) -> Unit) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try { draw(Canvas(bitmap)); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        }
    }
}
