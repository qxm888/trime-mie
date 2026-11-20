/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.data.learning

import android.net.Uri
import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlPath
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.Yaml as KamlYaml
import com.osfans.trime.core.data.UserDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import timber.log.Timber
import java.io.File

/**
 * 智能整句（octagram 八股文搭配模型）的接入与拆除。
 *
 * 开启时做两件事：把语言模型（.gram）放进 librime 的 user_data 目录，并给
 * 每个启用方案写 `<方案>.custom.yaml` patch——`translator/contextual_suggestions`
 * 与 `grammar/language`（octagram 按 language id 查找 `<id>.gram`）。patch 用
 * kaml 解析重建：用户已有的自定义 patch 键原样保留，仅增删本功能的两个键；
 * 代价是文件会被重新排版（手写注释会丢失，设置页已注记）。
 *
 * 关闭时只移除 patch 键，模型文件保留（用户可能再次开启）。
 * 模型文件为通用语料训练的公共数据，不含用户输入。
 */
object GrammarSetup {
    private const val CONTEXTUAL_KEY = "translator/contextual_suggestions"
    private const val GRAMMAR_KEY = "grammar/language"

    private val yaml = KamlYaml(
        configuration = YamlConfiguration(
            strictMode = false,
            anchorsAndAliases = AnchorsAndAliases.Permitted(1000u),
        ),
    )

    fun gramFile(language: String = SmartLearningSettings.grammarLanguage): File =
        File(UserDataManager.userDataDir, "$language.gram")

    /** 设置页状态显示：已就位的模型（语言 id，大小字节）。 */
    fun installedGram(): Pair<String, Long>? =
        gramFile().takeIf { it.isFile }?.let { SmartLearningSettings.grammarLanguage to it.length() }

    /**
     * 开关的落地动作。[schemaIds] 由调用方从 Rime 会话取当前启用方案。
     * 返回供 Toast 显示的摘要；调用方随后 deploy 使其生效。
     */
    suspend fun apply(enabled: Boolean, schemaIds: List<String>): String = withContext(Dispatchers.IO) {
        val schemata = schemaIds.filter { it.isNotEmpty() }
        if (schemata.isEmpty()) return@withContext "未找到启用中的方案"
        val language = SmartLearningSettings.grammarLanguage
        if (enabled && !gramFile(language).isFile) seedGram(language)
        var changed = 0
        schemata.forEach { schemaId ->
            try {
                if (updateSchemaPatch(schemaId, enabled, language)) changed++
            } catch (e: Exception) {
                Timber.w(e, "Grammar patch update failed for %s", schemaId)
            }
        }
        if (changed == 0) return@withContext "方案配置无需变更"
        (if (enabled) "已为 $changed 个方案开启智能整句" else "已从 $changed 个方案移除智能整句")
    }

    /**
     * 导入更完整的 .gram 模型（如官方 39MB 的 zh-hans-t-essay-bgw.gram）：
     * 复制到 user_data，记录 language id 并更新全部启用方案的 patch。
     */
    suspend fun importGram(uri: Uri, displayName: String?, schemaIds: List<String>): kotlin.Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayName.orEmpty()
                .ifBlank { "zh-hans-t-essay-bgc.gram" }
            require(name.endsWith(".gram")) { "请选择 .gram 语言模型文件" }
            val language = name.removeSuffix(".gram")
            require(language.isNotBlank() && !language.contains('/') && !language.contains('\\')) {
                "模型文件名不合法"
            }
            val target = File(UserDataManager.userDataDir, "$language.gram")
            com.osfans.trime.util.appContext.contentResolver.openInputStream(uri)!!.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
            }
            SmartLearningSettings.grammarLanguage = language
            apply(enabled = true, schemaIds)
            "已安装模型 $language（${target.length() / 1024 / 1024}MB）并更新方案配置"
        }.onFailure { Timber.w(it, "Gram import failed") }
    }

    /** 首次开启时把 APK 内置模型播种到 user_data。 */
    private fun seedGram(language: String) {
        val target = gramFile(language)
        runCatching {
            com.osfans.trime.util.appContext.assets.open("learning/$language.gram").use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
            }
        }.onFailure {
            // 内置模型只对应默认语言；用户导入的其它语言文件已直接落在 user_data。
            if (!target.isFile) Timber.w(it, "Seed gram failed for %s", language)
        }
    }

    private fun updateSchemaPatch(schemaId: String, enabled: Boolean, language: String): Boolean {
        val file = File(UserDataManager.userDataDir, "$schemaId.custom.yaml")
        val existing = file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        val targetKeys = setOf(CONTEXTUAL_KEY, GRAMMAR_KEY)
        val patchEntries = LinkedHashMap<YamlScalar, YamlNode>()
        if (existing != null) {
            val root = yaml.parseToYamlNode(existing) as? YamlMap
            val patch = root?.get("patch") as? YamlMap
            patch?.entries?.forEach { (key, value) ->
                if (key.content !in targetKeys) patchEntries[YamlScalar(key.content, YamlPath.root)] = value
            }
        }
        if (enabled) {
            patchEntries[YamlScalar(CONTEXTUAL_KEY, YamlPath.root)] =
                YamlScalar("true", YamlPath.root)
            patchEntries[YamlScalar(GRAMMAR_KEY, YamlPath.root)] =
                YamlScalar(language, YamlPath.root)
        } else if (patchEntries.isEmpty() && existing == null) {
            return false
        }
        if (patchEntries.isEmpty()) {
            if (existing == null) return false
            file.writeText("", Charsets.UTF_8)
            file.delete()
            return true
        }
        val patchMap = YamlMap(patchEntries, YamlPath.root)
        val text = yaml.encodeToString(
            MapSerializer(String.serializer(), YamlNode.serializer()),
            mapOf("patch" to patchMap),
        )
        file.writeText(text, Charsets.UTF_8)
        return true
    }
}
