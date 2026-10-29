/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 语音识别服务商注册表（对照说点啥/BiBi-Keyboard AsrVendorRegistry 的常用项，
 * Apache-2.0）。未列入的说明：阿里千问（DashScope）依赖官方 Java SDK，未移植；说点啥的免费公益 Key（SiliconFlow）不复用，一律用户自带凭据。
 */
package com.osfans.trime.ime.voice.builtin

enum class AsrTag(val label: String) {
    ONLINE("在线"),
    LOCAL("本地"),
    RECOMMENDED("推荐"),
    STREAMING("流式"),
    NON_STREAMING("非流式"),
    CUSTOM("自定义"),
    CHINESE_DIALECT("中文方言"),
    ACCURATE("准确"),
}

/** 在线 OpenAI 兼容引擎的请求形态。 */
enum class OpenAiBodyStyle {
    /** multipart /v1/audio/transcriptions（OpenAI、SiliconFlow、智谱、Cohere）。 */
    MULTIPART,

    /** JSON input_audio（OpenRouter：{"model","input_audio":{"data","format"}}）。 */
    OPENROUTER_JSON,
}

data class AsrVendorDef(
    val id: String,
    val label: String,
    val tags: List<AsrTag>,
    val local: Boolean,
    /** 在线默认 endpoint；空串 = 引擎内固定。 */
    val defaultEndpoint: String = "",
    /** endpoint 是否允许用户改（自建服务）。 */
    val editableEndpoint: Boolean = false,
    val defaultModel: String = "",
    /** OpenAI 兼容请求形态（仅 openai/siliconflow/openrouter/zhipu/cohere 使用）。 */
    val bodyStyle: OpenAiBodyStyle = OpenAiBodyStyle.MULTIPART,
    /** 附加 multipart 字段（如智谱 temperature/stream=false）。 */
    val extraMultipartFields: List<Pair<String, String>> = emptyList(),
)

object AsrVendors {
    val VOLC = AsrVendorDef(
        "volc", "豆包语音（火山）",
        listOf(AsrTag.ONLINE, AsrTag.RECOMMENDED, AsrTag.STREAMING, AsrTag.NON_STREAMING, AsrTag.CHINESE_DIALECT, AsrTag.ACCURATE),
        local = false,
    )
    val TENCENT = AsrVendorDef("tencent", "腾讯云语音",
        listOf(AsrTag.ONLINE,AsrTag.STREAMING,AsrTag.NON_STREAMING,AsrTag.CHINESE_DIALECT),
        local=false,defaultModel="16k_zh")
    val OPENAI = AsrVendorDef(
        "openai", "OpenAI",
        listOf(AsrTag.ONLINE, AsrTag.STREAMING, AsrTag.NON_STREAMING, AsrTag.CUSTOM),
        local = false,
        defaultEndpoint = "https://api.openai.com/v1/audio/transcriptions",
        editableEndpoint = true,
        defaultModel = "gpt-4o-mini-transcribe",
    )
    val SILICONFLOW = AsrVendorDef(
        "siliconflow", "SiliconFlow",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING, AsrTag.CUSTOM),
        local = false,
        defaultEndpoint = "https://api.siliconflow.cn/v1/audio/transcriptions",
        editableEndpoint = true,
        defaultModel = "FunAudioLLM/SenseVoiceSmall",
    )
    val OPENROUTER = AsrVendorDef(
        "openrouter", "OpenRouter",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING, AsrTag.CUSTOM),
        local = false,
        defaultEndpoint = "https://openrouter.ai/api/v1/audio/transcriptions",
        editableEndpoint = true,
        defaultModel = "qwen/qwen3-asr-flash-2026-02-10",
        bodyStyle = OpenAiBodyStyle.OPENROUTER_JSON,
    )
    val ZHIPU = AsrVendorDef(
        "zhipu", "智谱 GLM",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING, AsrTag.CHINESE_DIALECT),
        local = false,
        defaultEndpoint = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions",
        defaultModel = "glm-asr-2512",
        extraMultipartFields = listOf("temperature" to "0.95", "stream" to "false"),
    )
    val COHERE = AsrVendorDef(
        "cohere", "Cohere",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING),
        local = false,
        defaultEndpoint = "https://api.cohere.com/v2/audio/transcriptions",
        defaultModel = "cohere-transcribe-03-2026",
    )
    val GEMINI = AsrVendorDef(
        "gemini", "Google Gemini",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING, AsrTag.CUSTOM),
        local = false,
        defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta",
        editableEndpoint = true,
        defaultModel = "gemini-2.5-flash",
    )
    val MIMO = AsrVendorDef(
        "mimo", "小米 MiMo",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING, AsrTag.CUSTOM),
        local = false,
        defaultEndpoint = "https://api.xiaomimimo.com/v1/chat/completions",
        editableEndpoint = true,
        defaultModel = "mimo-v2.5-asr",
    )
    val ELEVENLABS = AsrVendorDef(
        "elevenlabs", "ElevenLabs",
        listOf(AsrTag.ONLINE, AsrTag.STREAMING, AsrTag.NON_STREAMING),
        local = false,
        defaultModel = "scribe_v1",
    )
    val SONIOX = AsrVendorDef(
        "soniox", "Soniox",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING, AsrTag.ACCURATE),
        local = false,
        defaultModel = "stt-async-v5",
    )
    val STEPAUDIO = AsrVendorDef(
        "stepaudio", "StepAudio",
        listOf(AsrTag.ONLINE, AsrTag.NON_STREAMING, AsrTag.CHINESE_DIALECT, AsrTag.ACCURATE),
        local = false,
        defaultEndpoint = "https://api.stepfun.com/v1/audio/asr/sse",
        editableEndpoint = true,
        defaultModel = "stepaudio-2.5-asr",
    )

    val ONLINE_ALL = listOf(VOLC, TENCENT, OPENAI, SILICONFLOW, OPENROUTER, ZHIPU, COHERE, GEMINI, MIMO, ELEVENLABS, SONIOX, STEPAUDIO)

    /** 本地家族 id 与 LocalAsrModels.family(key) 一一对应。 */
    val LOCAL_ALL: List<AsrVendorDef> = listOf(
        AsrVendorDef("sensevoice", "SenseVoice", listOf(AsrTag.LOCAL, AsrTag.NON_STREAMING), local = true),
        AsrVendorDef("qwen3_asr", "Qwen3-ASR", listOf(AsrTag.LOCAL, AsrTag.RECOMMENDED, AsrTag.NON_STREAMING, AsrTag.CHINESE_DIALECT, AsrTag.ACCURATE), local = true),
        AsrVendorDef("funasr_nano", "FunASR Nano", listOf(AsrTag.LOCAL, AsrTag.NON_STREAMING, AsrTag.CHINESE_DIALECT), local = true),
        AsrVendorDef("parakeet", "Parakeet", listOf(AsrTag.LOCAL, AsrTag.NON_STREAMING), local = true),
        AsrVendorDef("firered_asr", "FireRedASR V2", listOf(AsrTag.LOCAL, AsrTag.NON_STREAMING, AsrTag.CHINESE_DIALECT), local = true),
        AsrVendorDef("x_asr", "X-ASR", listOf(AsrTag.LOCAL, AsrTag.RECOMMENDED, AsrTag.STREAMING, AsrTag.ACCURATE), local = true),
    )

    val ALL: List<AsrVendorDef> = ONLINE_ALL + LOCAL_ALL

    fun byId(id: String): AsrVendorDef? = ALL.firstOrNull { it.id == id }

    fun labelOf(id: String): String = when (id) {
        "none" -> "请选择识别引擎"
        else -> byId(id)?.label ?: id
    }

    fun tagsLabel(def: AsrVendorDef): String = def.tags.joinToString(" ") { it.label }
}
