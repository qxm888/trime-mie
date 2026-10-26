/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 本地离线模型管理（模型规格/下载源/SHA-256 校验移植自说点啥/BiBi-Keyboard
 * AsrLocalModelSpecs + LocalModelIntegrity + ModelDownloadService，Apache-2.0）。
 * zip 从 GitHub Release 下载（四个源可选、可测速），支持从本地 zip 导入（按文件名
 * 识别家族/变体）。所有文件解压后平铺在应用内部目录 filesDir/asr_models/<家族>/<变体>/。
 */
package com.osfans.trime.ime.voice.builtin

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

enum class ModelInstallPhase { DOWNLOAD, EXTRACT, VERIFY, INSTALL, READY }
class ModelIntegrityException(message: String) : IOException(message)

class LocalModelFileSpec(val name: String, val sizeBytes: Long, val sha256: String)

class LocalVariantSpec(
    val key: String,
    val label: String,
    val zipName: String,
    val files: List<LocalModelFileSpec>,
)

class LocalFamilyDef(
    val key: String,
    val label: String,
    val variants: List<LocalVariantSpec>,
) {
    fun variant(key: String): LocalVariantSpec? = variants.firstOrNull { it.key == key }
}

object LocalAsrModels {
    private fun spec(name: String, size: Long, sha: String) = LocalModelFileSpec(name, size, sha)

    private val SENSE_VOICE_TOKENS = spec("tokens.txt", 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")

    val FAMILIES = listOf(
        LocalFamilyDef(
            "sensevoice", "SenseVoice",
            listOf(
                LocalVariantSpec(
                    "small-int8", "标准版 int8（约240MB）",
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip",
                    listOf(SENSE_VOICE_TOKENS, spec("model.int8.onnx", 239_233_841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51")),
                ),
                LocalVariantSpec(
                    "small-full", "高精度版 fp32（约900MB）",
                    "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip",
                    listOf(SENSE_VOICE_TOKENS, spec("model.onnx", 937_617_178L, "977016bd9c79f9eb343430b5cc305e07ab64d5212dff41b0dcfa1694bee9a8cb")),
                ),
            ),
        ),
        LocalFamilyDef(
            "qwen3_asr", "Qwen3-ASR",
            listOf(
                LocalVariantSpec(
                    "qwen3-0.6b-int8", "Qwen3-ASR 0.6B Int8（806MB）",
                    "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.zip",
                    listOf(
                        spec("conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
                        spec("encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
                        spec("decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
                        spec("vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                        spec("merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
                        spec("tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
                    ),
                ),
                LocalVariantSpec(
                    "qwen3-1.7b-int8", "Qwen3-ASR 1.7B Int8（2.0GB）",
                    "sherpa-onnx-qwen3-asr-1.7B-int8-2026-08-04.zip",
                    listOf(
                        spec("conv_frontend.onnx", 48_080_441L, "3cb27a9fe94d95c938e476f2012b21aba2ec0bfceef33b0e58acd208946bafdd"),
                        spec("encoder.int8.onnx", 314_222_162L, "a5deedae034ece715de8ed204378d8c77f889af3a60c2566581135e84cced7cd"),
                        spec("decoder.int8.onnx", 2_037_091_087L, "41eafdf582b124c5fb050a59ca3331a6ec62db0d5ce35d456e9f999f7e6ad042"),
                        spec("vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                        spec("merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
                        spec("tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
                    ),
                ),
            ),
        ),
        LocalFamilyDef(
            "funasr_nano", "FunASR Nano",
            listOf(
                LocalVariantSpec(
                    "nano-int8", "FunASR Nano int8（约690MB，中英）",
                    "sherpa-onnx-funasr-nano-int8-2025-12-30.zip",
                    listOf(
                        spec("embedding.int8.onnx", 155_584_380L, "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644"),
                        spec("encoder_adaptor.int8.onnx", 237_792_748L, "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422"),
                        spec("llm.int8.onnx", 600_356_593L, "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a"),
                        spec("tokenizer.json", 11_422_654L, "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"),
                    ),
                ),
                LocalVariantSpec(
                    "mlt-int8", "FunASR MLT Nano int8（约690MB，多语种）",
                    "sherpa-onnx-funasr-mlt-nano-int8-2026-03-21.zip",
                    listOf(
                        spec("embedding.int8.onnx", 155_583_106L, "2eb6f47e9119b0e92cb38caea8d41505d446c7ee829a44f91619498a6ec7594b"),
                        spec("encoder_adaptor.int8.onnx", 237_792_749L, "0ccd6c75ffe07d724921fd0787389479c3ae8d84e60f55508f43d87c0f02bc85"),
                        spec("llm.int8.onnx", 600_356_593L, "2e54f5d0e7a7147df785a8318d8680a582678f95fb3dfbb5b9aa2d4e55e6ff6c"),
                        spec("tokenizer.json", 11_422_654L, "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"),
                    ),
                ),
            ),
        ),
        LocalFamilyDef(
            "parakeet", "Parakeet",
            listOf(
                LocalVariantSpec(
                    "0.6b-v2-int8", "0.6B V2 Int8 英语（451MB）",
                    "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.zip",
                    listOf(
                        spec("tokens.txt", 9_384L, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
                        spec("encoder.int8.onnx", 652_184_296L, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                        spec("decoder.int8.onnx", 7_257_753L, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                        spec("joiner.int8.onnx", 1_739_080L, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                    ),
                ),
                LocalVariantSpec(
                    "0.6b-v3-int8", "0.6B V3 Int8 欧洲语言（456MB）",
                    "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.zip",
                    listOf(
                        spec("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
                        spec("encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                        spec("decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                        spec("joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                    ),
                ),
            ),
        ),
        LocalFamilyDef(
            "firered_asr", "FireRedASR V2",
            listOf(
                LocalVariantSpec(
                    "ctc-int8", "中英 CTC int8（约740MB）",
                    "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.zip",
                    listOf(
                        spec("tokens.txt", 79_172L, "1bc613de2112d257e61a349c3e72d1b1a9cf19c33d3ca954197ad2171e5ea07b"),
                        spec("model.int8.onnx", 775_861_420L, "ca3dbabd82170110cc0b343c2890866d449984bc9cd92b9a18371ff80a81bb99"),
                    ),
                ),
            ),
        ),
        LocalFamilyDef(
            "x_asr", "X-ASR",
            listOf(
                LocalVariantSpec(
                    "x-asr-480ms", "中英流式 480ms（530MB）",
                    "sherpa-onnx-streaming-x-asr-480ms-zh-en.zip",
                    listOf(
                        spec("tokens.txt", 58_806L, "b818a60878b9aae978cbb8ad594acbd403d76d1af2e31ef4197c84e2dbdba27c"),
                        spec("encoder-480ms.onnx", 592_968_361L, "0c3454033d249081df124ddcd7adaf3deca07d0b999b26f2ee5d2475d37abc74"),
                        spec("decoder-480ms.onnx", 11_309_084L, "3658368d274a5d5fd39a7ac20c46bed0ad9cfea1f0feddef30d5d89797c1f499"),
                        spec("joiner-480ms.onnx", 10_260_467L, "03781c98165a2385024c9cecdd2b6b13310d81db23a62c7da420782c2915cf81"),
                    ),
                ),
            ),
        ),
    )

    /** 四个下载源（与说点啥一致）：官方 + 三个镜像前缀。 */
    val SOURCES = listOf(
        "GitHub 官方" to "https://github.com",
        "GitHub 镜像1" to "https://ghproxy.net",
        "GitHub 镜像2" to "https://hub.gitmirror.com",
        "GitHub 镜像3" to "https://fastgit.cc",
    )

    fun family(key: String): LocalFamilyDef? = FAMILIES.firstOrNull { it.key == key }

    fun zipUrl(familyKey: String, variantKey: String, source: Int): String {
        val zip = family(familyKey)?.variant(variantKey)?.zipName ?: throw IOException("未知模型变体")
        val base = "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/$zip"
        return if (source <= 0) base else SOURCES.getOrElse(source) { SOURCES[0] }.second + "/" + base
    }

    fun modelsRoot(context: Context): File = File(context.filesDir, "asr_models")

    fun dirFor(context: Context, familyKey: String, variantKey: String): File =
        File(File(modelsRoot(context), familyKey), variantKey)

    private fun verify(spec: LocalModelFileSpec, file: File, isActive: () -> Boolean = { true }): Boolean {
        if (file.length() != spec.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (!isActive()) throw CancellationException("校验已取消")
                val n = ins.read(buf); if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) } == spec.sha256
    }

    /** 变体目录是否完整（存在 + 字节数匹配；SHA 在安装时已校验）。 */
    fun variantComplete(context: Context, familyKey: String, variant: LocalVariantSpec): Boolean {
        val dir = dirFor(context, familyKey, variant.key)
        return variant.files.all { spec -> File(dir, spec.name).let { it.isFile && it.length() == spec.sizeBytes } }
    }

    fun installedVariant(context: Context, familyKey: String): String? {
        val def = family(familyKey) ?: return null
        val preferred = AsrSettings.localVariant(familyKey)
        val order = listOf(preferred) + def.variants.map { it.key }
        for (key in order.distinct()) {
            def.variant(key)?.takeIf { variantComplete(context, familyKey, it) }?.let { return it.key }
        }
        return null
    }

    /** 运行时取某家族变体的文件绝对路径表（文件名 → 路径）。 */
    fun modelPaths(context: Context, familyKey: String, variantKey: String): Map<String, String>? {
        val def = family(familyKey) ?: return null
        val variant = def.variant(variantKey) ?: return null
        if (!variantComplete(context, familyKey, variant)) return null
        val dir = dirFor(context, familyKey, variantKey)
        return variant.files.associate { it.name to File(dir, it.name).absolutePath }
    }

    /** 递归在解压目录里找指定文件（跳过 __MACOSX 与隐藏目录）。 */
    private fun findFile(root: File, name: String): File? {
        val direct = File(root, name)
        if (direct.isFile) return direct
        val children = root.listFiles() ?: return null
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name == "__MACOSX" || child.name.startsWith(".")) continue
            findFile(child, name)?.let { return it }
        }
        return null
    }

    /** Archive central-directory completeness check; installed files still require SHA-256. */
    internal fun completeArchive(zip: File, variant: LocalVariantSpec): Boolean = runCatching {
        ZipFile(zip).use { archive ->
            val entries = archive.entries().asSequence().filter { !it.isDirectory }.toList()
            variant.files.all { spec ->
                val matches = entries.filter { it.name.substringAfterLast('/') == spec.name }
                matches.size == 1 && matches.single().size == spec.sizeBytes
            }
        }
    }.getOrDefault(false)

    fun hasDownloadedArchive(context: Context, familyKey: String, variantKey: String): Boolean {
        val variant = family(familyKey)?.variant(variantKey) ?: return false
        return completeArchive(File(context.noBackupFilesDir, "asr_install/${familyKey}_${variantKey}.zip"), variant) ||
            completeArchive(File(context.cacheDir, "asr_model_${familyKey}_${variantKey}.zip"), variant)
    }

    private val installLock = Any()

    /** Download, extract, verify all files, atomically publish, then select the installed model. */
    fun install(
        context: Context,
        familyKey: String,
        variantKey: String,
        source: Int = AsrSettings.downloadSource,
        predownloadedZip: File? = null,
        isActive: () -> Boolean = { true },
        onProgress: (Int) -> Unit = {},
        onCallCreated: (Call) -> Unit = {},
        onStage: (ModelInstallPhase, Int) -> Unit = { _, _ -> },
    ): File = synchronized(installLock) {
        val variant = family(familyKey)?.variant(variantKey) ?: throw IOException("未知模型变体")
        val dest = dirFor(context, familyKey, variantKey)
        val work = File(context.noBackupFilesDir, "asr_install").apply { mkdirs() }
        val zipCache = predownloadedZip ?: File(work, "${familyKey}_${variantKey}.zip")
        val tmpDir = File(work, "extract_${familyKey}_${variantKey}")
        val staged = File(dest.parentFile, ".${variantKey}.installing")
        val previous = File(dest.parentFile, ".${variantKey}.previous")
        fun active() { if (!isActive()) throw CancellationException("模型安装已取消") }
        try {
            active()
            if (predownloadedZip == null) {
                // Adopt complete downloads from mie.14 without downloading hundreds of MB again.
                val legacy = File(context.cacheDir, "asr_model_${familyKey}_${variantKey}.zip")
                if (!zipCache.exists() && completeArchive(legacy, variant)) {
                    if (!legacy.renameTo(zipCache)) legacy.copyTo(zipCache, overwrite = true)
                }
                if (!completeArchive(zipCache, variant)) {
                    onStage(ModelInstallPhase.DOWNLOAD, 0)
                    ResumableHttpDownloader.discardIfSourceChanged(zipCache, zipUrl(familyKey, variantKey, source))
                    ResumableHttpDownloader.download(downloadClient(), zipUrl(familyKey, variantKey, source),
                        zipCache, isActive, { percent ->
                            onProgress(percent); onStage(ModelInstallPhase.DOWNLOAD, percent)
                        }, onCallCreated)
                }
            }
            active()
            if (!completeArchive(zipCache, variant)) throw ModelIntegrityException("压缩包不完整，请重新下载")
            onStage(ModelInstallPhase.EXTRACT, 0)
            tmpDir.deleteRecursively(); tmpDir.mkdirs()
            unzip(zipCache, tmpDir, isActive)
            active()
            dest.parentFile?.mkdirs()
            // Recover an interrupted replacement before cleaning an old staging directory.
            if (!dest.exists() && previous.exists()) previous.renameTo(dest)
            staged.deleteRecursively(); staged.mkdirs()
            variant.files.forEachIndexed { index, spec ->
                active()
                onStage(ModelInstallPhase.VERIFY, index * 100 / variant.files.size)
                val src = findFile(tmpDir, spec.name) ?: throw ModelIntegrityException("模型包缺少 ${spec.name}")
                if (!verify(spec, src, isActive)) throw ModelIntegrityException("${spec.name} 校验失败，请重新下载")
                if (!src.renameTo(File(staged, spec.name))) src.copyTo(File(staged, spec.name))
            }
            active()
            onStage(ModelInstallPhase.INSTALL, 0)
            previous.deleteRecursively()
            if (dest.exists() && !dest.renameTo(previous)) throw IOException("无法替换已有模型")
            if (!staged.renameTo(dest)) {
                if (previous.exists()) previous.renameTo(dest)
                throw IOException("无法发布模型目录，已保留原模型")
            }
            previous.deleteRecursively()
            AsrSettings.setLocalVariant(familyKey, variantKey)
            AsrSettings.setVendor(familyKey)
            onStage(ModelInstallPhase.READY, 100)
            if (predownloadedZip == null) {
                zipCache.delete()
                File(context.cacheDir, "asr_model_${familyKey}_${variantKey}.zip.resume").delete()
                File(context.cacheDir, "asr_extract_${familyKey}_${variantKey}").deleteRecursively()
            }
            dest
        } catch (error: ModelIntegrityException) {
            if (predownloadedZip == null) zipCache.delete()
            throw error
        } finally {
            tmpDir.deleteRecursively()
            staged.deleteRecursively()
        }
    }

    /**
     * 从本地 zip 导入：按文件名（说点啥同款全等映射表）识别家族/变体，
     * 识别后走同一条解压+校验+安装路径。
     */
    fun detectFamilyAndVariant(zipFileName: String): Pair<String, String>? {
        val base = zipFileName.substringAfterLast('/').substringBeforeLast('.').lowercase()
        return when (base) {
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17" -> "sensevoice" to "small-full"
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17" -> "sensevoice" to "small-int8"
            "sherpa-onnx-funasr-nano-int8-2025-12-30" -> "funasr_nano" to "nano-int8"
            "sherpa-onnx-funasr-mlt-nano-int8-2026-03-21" -> "funasr_nano" to "mlt-int8"
            "sherpa-onnx-qwen3-asr-0.6b-int8-2026-03-25" -> "qwen3_asr" to "qwen3-0.6b-int8"
            "sherpa-onnx-qwen3-asr-1.7b-int8-2026-08-04" -> "qwen3_asr" to "qwen3-1.7b-int8"
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" -> "parakeet" to "0.6b-v3-int8"
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8" -> "parakeet" to "0.6b-v2-int8"
            "sherpa-onnx-streaming-x-asr-480ms-zh-en",
            "sherpa-onnx-streaming-x_asr-480ms-zh_en",
            -> "x_asr" to "x-asr-480ms"
            "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25" -> "firered_asr" to "ctc-int8"
            else -> null
        }
    }

    fun importZip(context: Context, zipFile: File, zipFileName: String, isActive: () -> Boolean = { true }, onProgress: (Int) -> Unit = {}, onStage: (ModelInstallPhase, Int) -> Unit = { _, _ -> }): Pair<String, String> {
        val detected = detectFamilyAndVariant(zipFileName)
            ?: throw IOException("无法从文件名识别模型类型（需说点啥/sherpa-onnx 官方模型包命名）")
        install(context, detected.first, detected.second, predownloadedZip = zipFile,
            isActive = isActive, onProgress = onProgress, onStage = onStage)
        return detected
    }

    fun uninstall(context: Context, familyKey: String) {
        File(modelsRoot(context), familyKey).deleteRecursively()
    }

    fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 下载源可达性测速：HTTP 响应耗时（毫秒）；失败返回 null（不可用）。 */
    fun measureSourceLatency(sourceIndex: Int, family: String = "sensevoice", variant: String = "small-int8"): Long? {
        val base = SOURCES.getOrNull(sourceIndex)?.second ?: return null
        val def = family(family) ?: return null
        val selected = def.variant(variant) ?: def.variants.firstOrNull() ?: return null
        val url = zipUrl(family, selected.key, sourceIndex)
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url).head().build()
        return try {
            val start = System.nanoTime()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            }
        } catch (t: Throwable) {
            Timber.d(t, "source %s unreachable", base)
            null
        }
    }

    private fun unzip(zip: File, destDir: File, isActive: () -> Boolean) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                if (!isActive()) throw CancellationException("解压已取消")
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = File(destDir, entry.name)
                if (!out.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw IOException("模型包包含非法路径：${entry.name}")
                }
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    while (true) {
                        if (!isActive()) throw CancellationException("解压已取消")
                        val n = zis.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                    }
                }
                zis.closeEntry()
            }
        }
    }
}

/** 应用上下文持有者：引擎在 IME 进程内随处可用。 */
object AsrContextHolder {
    @Volatile private var appContext: Context? = null
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}

/**
 * HTTP 文件下载（支持 Range 断点续传）。
 * 移植自说点啥/BiBi-Keyboard ResumableHttpDownloader（Apache-2.0），仅调整包名。
 */
internal object ResumableHttpDownloader {
    private const val RESUME_METADATA_SUFFIX = ".resume"
    private val CONTENT_RANGE_REGEX =
        Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    data class ContentRange(val start: Long, val end: Long, val total: Long?)

    fun parseContentRange(header: String): ContentRange? {
        val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalRaw = match.groupValues[3]
        val total = if (totalRaw == "*") null else totalRaw.toLongOrNull() ?: return null
        if (start < 0L || end < start) return null
        if (total != null && total < 0L) return null
        return ContentRange(start = start, end = end, total = total)
    }

    fun download(
        client: OkHttpClient,
        url: String,
        destFile: File,
        isActive: () -> Boolean = { true },
        onProgress: (percent: Int) -> Unit = {},
        onCallCreated: (Call) -> Unit = {},
    ) {
        downloadInternal(client, url, destFile, allowResume = true, isActive, onProgress, onCallCreated)
    }

    fun discardIfSourceChanged(destFile: File, url: String): Boolean {
        if (!destFile.exists()) return false
        if (readResumeValidator(destFile, url) != null) return false
        deletePartial(destFile)
        return true
    }

    private fun downloadInternal(
        client: OkHttpClient,
        url: String,
        destFile: File,
        allowResume: Boolean,
        isActive: () -> Boolean,
        onProgress: (percent: Int) -> Unit,
        onCallCreated: (Call) -> Unit,
    ) {
        var existing = destFile.takeIf { it.exists() }?.length() ?: 0L
        val resumeValidator = if (allowResume && existing > 0L) readResumeValidator(destFile, url) else null
        if (existing > 0L && resumeValidator == null) {
            deletePartial(destFile)
            existing = 0L
        }
        val useRange = allowResume && existing > 0L && resumeValidator != null

        val request = Request.Builder().url(url).apply {
            if (useRange) {
                header("Range", "bytes=$existing-")
                header("If-Range", resumeValidator!!)
            }
        }.build()

        val call = client.newCall(request)
        onCallCreated(call)
        try {
            ensureActive(isActive, call)
            call.execute().use { resp ->
                when {
                    resp.code == 416 && useRange -> {
                        deletePartial(destFile)
                        downloadInternal(client, url, destFile, allowResume = false, isActive, onProgress, onCallCreated)
                    }

                    resp.code == 206 -> {
                        handlePartialContent(
                            resp, destFile, existing, client, url, allowResume,
                            resumeValidator, isActive, call, onProgress, onCallCreated,
                        )
                    }

                    resp.isSuccessful -> {
                        writeResumeValidator(destFile, url, responseValidator(resp))
                        writeBodyToFile(resp, destFile, append = false, bytesAlreadyOnDisk = 0L,
                            totalBytes = resp.body!!.contentLength().takeIf { it > 0L }, isActive, call, onProgress)
                        deleteResumeMetadata(destFile)
                    }

                    else -> throw IOException("HTTP ${resp.code}")
                }
            }
        } finally {
            if (!isActive()) call.cancel()
        }
    }

    private fun handlePartialContent(
        resp: okhttp3.Response,
        destFile: File,
        existing: Long,
        client: OkHttpClient,
        url: String,
        allowResume: Boolean,
        resumeValidator: String?,
        isActive: () -> Boolean,
        call: Call,
        onProgress: (percent: Int) -> Unit,
        onCallCreated: (Call) -> Unit,
    ) {
        val header = resp.header("Content-Range") ?: throw IOException("HTTP 206 missing Content-Range")
        val range = parseContentRange(header) ?: throw IOException("Invalid Content-Range: $header")
        val total = range.total
        val receivedValidator = responseValidator(resp)
        if (resumeValidator != null && receivedValidator != null && receivedValidator != resumeValidator) {
            if (!allowResume) throw IOException("Response validator changed during resume")
            deletePartial(destFile)
            downloadInternal(client, url, destFile, allowResume = false, isActive, onProgress, onCallCreated)
            return
        }
        val declaredLength = range.end - range.start + 1L
        if (total != null && (total <= 0L || range.end >= total)) throw IOException("Invalid Content-Range: $header")
        val bodyLength = resp.body!!.contentLength()
        if (bodyLength >= 0L && bodyLength != declaredLength) throw IOException("Content-Range length does not match response body")
        if (total != null && existing > total) {
            deletePartial(destFile)
            downloadInternal(client, url, destFile, allowResume = false, isActive, onProgress, onCallCreated)
            return
        }
        if (range.start != existing) {
            if (!allowResume) throw IOException("Content-Range start does not match requested offset")
            deletePartial(destFile)
            downloadInternal(client, url, destFile, allowResume = false, isActive, onProgress, onCallCreated)
            return
        }

        val resolvedTotal = total ?: existing + declaredLength
        writeResumeValidator(destFile, url, receivedValidator ?: resumeValidator)
        writeBodyToFile(resp, destFile, append = true, bytesAlreadyOnDisk = existing,
            totalBytes = resolvedTotal, isActive, call, onProgress)
        deleteResumeMetadata(destFile)
    }

    private fun writeBodyToFile(
        resp: okhttp3.Response,
        destFile: File,
        append: Boolean,
        bytesAlreadyOnDisk: Long,
        totalBytes: Long?,
        isActive: () -> Boolean,
        call: Call,
        onProgress: (percent: Int) -> Unit,
    ) {
        destFile.parentFile?.mkdirs()
        if (!append && destFile.exists()) {
            FileOutputStream(destFile, false).close()
        }
        if (totalBytes != null && totalBytes > 0L && bytesAlreadyOnDisk > 0L) {
            onProgress(((bytesAlreadyOnDisk * 100) / totalBytes).toInt().coerceIn(0, 100))
        }
        FileOutputStream(destFile, append).use { out ->
            var readSum = bytesAlreadyOnDisk
            var lastPercent = -1
            val buf = ByteArray(128 * 1024)
            resp.body!!.byteStream().use { ins ->
                while (true) {
                    ensureActive(isActive, call)
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    readSum += n
                    if (totalBytes != null && totalBytes > 0L) {
                        val percent = ((readSum * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                    }
                }
            }
            if (totalBytes != null && totalBytes > 0L && readSum != totalBytes) {
                throw IOException("Incomplete download: got $readSum of $totalBytes bytes")
            }
        }
    }

    private fun ensureActive(isActive: () -> Boolean, call: Call) {
        if (!isActive()) {
            call.cancel()
            throw CancellationException("Download cancelled")
        }
    }

    private fun responseValidator(resp: okhttp3.Response): String? = resp.header("ETag")
        ?.takeUnless { it.startsWith("W/", ignoreCase = true) }
        ?: resp.header("Last-Modified")

    private fun readResumeValidator(destFile: File, url: String): String? {
        val metadataFile = resumeMetadataFile(destFile).takeIf { it.isFile } ?: return null
        return try {
            val lines = metadataFile.readLines()
            lines.getOrNull(1)?.takeIf { lines.firstOrNull() == url && it.isNotBlank() }
        } catch (t: Throwable) {
            metadataFile.delete()
            Timber.w(t, "Failed to read resume validator")
            null
        }
    }

    private fun writeResumeValidator(destFile: File, url: String, validator: String?) {
        val metadataFile = resumeMetadataFile(destFile)
        if (validator == null) {
            metadataFile.delete()
            return
        }
        try {
            metadataFile.writeText("$url\n$validator")
        } catch (t: Throwable) {
            metadataFile.delete()
            Timber.w(t, "Failed to persist resume validator")
        }
    }

    private fun deletePartial(file: File) {
        deleteQuietly(file)
        deleteResumeMetadata(file)
    }

    private fun deleteResumeMetadata(file: File) = deleteQuietly(resumeMetadataFile(file))

    private fun resumeMetadataFile(file: File): File = File(file.path + RESUME_METADATA_SUFFIX)

    private fun deleteQuietly(file: File) {
        try { if (file.exists()) file.delete() } catch (t: Throwable) { Timber.w(t, "delete failed") }
    }
}
