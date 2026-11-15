/* SPDX-License-Identifier: GPL-3.0-or-later
 * Adapted from BiBi-Keyboard FoxletTranslationService / TranslationModelCatalog
 * (Apache-2.0), BryceWG and contributors, 271f77786bdae6aa6b40bc9a3c76293821745c2b.
 * Foxlet and Mozilla components retain their licenses in assets/licenses and the AAR.
 */
package com.osfans.trime.ime.ai

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.yinvoker.foxlet.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal data class TranslationPack(val source: String, val target: String, val bytes: Long) {
    val id get() = "$source-$target"
}
internal object OfflineTranslationCatalog {
    fun parse(json: String): List<TranslationPack> = Json.parseToJsonElement(json).jsonObject.getValue("models").jsonArray.map {
        val model = it.jsonObject
        TranslationPack(model.getValue("from").jsonPrimitive.content, model.getValue("to").jsonPrimitive.content,
            model.getValue("files").jsonArray.sumOf { file -> file.jsonObject.getValue("size").jsonPrimitive.long })
    }
    fun models(context: Context) = context.assets.open("translation/foxlet-models.json").bufferedReader().use { parse(it.readText()) }
    fun route(models: List<TranslationPack>, source: String, target: String): List<TranslationPack> {
        if (source == target) return emptyList()
        models.firstOrNull { it.source == source && it.target == target }?.let { return listOf(it) }
        val first = models.firstOrNull { it.source == source && it.target == "en" } ?: return emptyList()
        val second = models.firstOrNull { it.source == "en" && it.target == target } ?: return emptyList()
        return listOf(first, second)
    }
}

internal object TextTranslations {
    suspend fun translate(config: TextPipelineConfig, text: String): String = when (config.vendor) {
        "ai" -> AiTextService.translate(config.model, text, config.source, config.target)
        "foxlet" -> {
            if (!OfflineTranslationService.supported()) throw IllegalArgumentException("离线翻译需要 Android 9 以上的 64 位 ARM 设备")
            OfflineTranslationService.translate(AiTextSettings.context, text, config.source, config.target)
        }
        else -> OnlineTranslationService.translate(config.vendor, text, config.source, config.target)
    }
}

/** No preloading, no automatic download, one inference thread, release model RAM after each request. */
internal object OfflineTranslationService {
    @androidx.annotation.ChecksSdkIntAtLeast(api = 28)
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 28 && Build.SUPPORTED_ABIS.contains("arm64-v8a") && android.os.Process.is64Bit()
    private val lock = Mutex()
    private var sdk: Foxlet? = null
    @RequiresApi(28)
    private suspend fun client(context: Context): Foxlet {
        check(supported())
        return sdk ?: try { Foxlet.create(context.applicationContext) {
            models { directory = File(context.filesDir, "translation_models") }
            translation { threading = Threading.Fixed(1); retention = ModelRetention.UntilShutdown }
        }.also { sdk = it } } catch (e: LinkageError) { throw IllegalStateException("离线翻译运行库在此设备不可用", e) }
    }
    @RequiresApi(28)
    suspend fun installed(context: Context): List<TranslationPack> = withContext(Dispatchers.IO) { lock.withLock {
        client(context).models.listInstalled().map { TranslationPack(it.pair.source, it.pair.target, it.sizeBytes) }
    } }
    @RequiresApi(28)
    suspend fun download(context: Context, packs: List<TranslationPack>, progress: (Int, Int, DownloadProgress) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            val client = client(context)
            packs.forEachIndexed { index, pack ->
                currentCoroutineContext().ensureActive()
                val descriptor = requireNotNull(client.models.findBundled(LanguagePair(pack.source, pack.target))) { "该语言包暂不可用" }
                client.models.download(descriptor, onProgress = { progress(index + 1, packs.size, it) })
            }
        }
    }
    @RequiresApi(28)
    suspend fun delete(context: Context, pack: TranslationPack) = withContext(Dispatchers.IO) { lock.withLock {
        val client = client(context)
        check(client.translator.unloadModels().allReleased)
        client.models.deleteAll(LanguagePair(pack.source, pack.target)).results.forEach { it.failure?.let { error -> throw error } }
    } }
    @RequiresApi(28)
    suspend fun translate(context: Context, text: String, source: String, target: String): String = withContext(Dispatchers.Default) {
        require(text.isNotBlank() && text.length <= TextProtocol.MAX_INPUT) { "请选择 1～12000 字的文本" }
        require(source != "auto") { "离线翻译请指定原文语言并下载语言包" }
        if (source == target) return@withContext text
        val route = OfflineTranslationCatalog.route(OfflineTranslationCatalog.models(context), source, target)
        require(route.isNotEmpty()) { "该离线语言方向暂不支持" }
        lock.withLock {
            val client = client(context)
            try {
                val models = route.map { client.models.prepare(LanguagePair(it.source, it.target), PreparePolicy.LocalOnly) }
                val result = if (models.size == 1) client.translator.translate(text, models.single())
                    else client.translator.translatePivot(text, models[0], models[1])
                currentCoroutineContext().ensureActive()
                require(result.isNotBlank()) { "离线翻译未返回正文" }
                result
            } finally { withContext(NonCancellable) { client.translator.unloadModels() } }
        }
    }
}
