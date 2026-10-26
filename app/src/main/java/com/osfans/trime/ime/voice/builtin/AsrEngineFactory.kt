/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 按 AsrSettings.selectedVendor 构建内置引擎（对照说点啥的工厂分发，简化为单引擎）。
 */
package com.osfans.trime.ime.voice.builtin

import kotlinx.coroutines.CoroutineScope

object AsrEngineFactory {
    fun create(
        scope: CoroutineScope,
        listener: AsrListener,
        onModelLoadStart: () -> Unit = {},
    ): AsrEngine? {
        val vendor = AsrSettings.selectedVendor
        if (vendor == "none") return null
        return when (vendor) {
            "volc" -> VolcStreamAsrEngine(
                scope, listener,
                authProvider = {
                    VolcAuthValues(
                        AsrSettings.volcUseNewAuth(),
                        AsrSettings.apiKey("volc"),
                        AsrSettings.volcAppKey(),
                        AsrSettings.volcAccessKey(),
                    )
                },
                modelProvider = { AsrSettings.model("volc").ifBlank { "doubao_streaming_v2" } },
                languageProvider = { AsrSettings.language("volc") },
                ddcProvider = { AsrSettings.volcDdcEnabled() },
            )

            "openai", "siliconflow", "openrouter", "zhipu", "cohere" -> {
                val def = AsrVendors.byId(vendor)!!
                OpenAiCompatibleAsrEngine(
                    scope, listener,
                    endpointProvider = { AsrSettings.endpoint(vendor) },
                    apiKeyProvider = { AsrSettings.apiKey(vendor) },
                    modelProvider = { AsrSettings.model(vendor) },
                    languageProvider = { AsrSettings.language(vendor) },
                    bodyStyle = def.bodyStyle,
                    extraMultipartFields = def.extraMultipartFields,
                )
            }

            "gemini" -> GeminiAsrEngine(
                scope, listener,
                endpointProvider = { AsrSettings.endpoint("gemini") },
                apiKeyProvider = { AsrSettings.apiKey("gemini") },
                modelProvider = { AsrSettings.model("gemini") },
            )

            "mimo" -> MiMoAsrEngine(
                scope, listener,
                endpointProvider = { AsrSettings.endpoint("mimo") },
                apiKeyProvider = { AsrSettings.apiKey("mimo") },
                modelProvider = { AsrSettings.model("mimo") },
                languageProvider = { AsrSettings.language("mimo") },
            )

            "elevenlabs" -> ElevenLabsAsrEngine(
                scope, listener,
                apiKeyProvider = { AsrSettings.apiKey("elevenlabs") },
                modelProvider = { AsrSettings.model("elevenlabs") },
                languageProvider = { AsrSettings.language("elevenlabs") },
            )

            "soniox" -> SonioxAsrEngine(
                scope, listener,
                apiKeyProvider = { AsrSettings.apiKey("soniox") },
                languageProvider = { AsrSettings.language("soniox") },
            )

            "stepaudio" -> StepAudioAsrEngine(
                scope, listener,
                endpointProvider = { AsrSettings.endpoint("stepaudio") },
                apiKeyProvider = { AsrSettings.apiKey("stepaudio") },
                modelProvider = { AsrSettings.model("stepaudio") },
                languageProvider = { AsrSettings.language("stepaudio").ifBlank { "zh" } },
            )

            "x_asr" -> XAsrStreamEngine(scope, listener, onModelLoadStart)

            else -> if (AsrVendors.byId(vendor)?.local == true) {
                OfflineSherpaAsrEngine(scope, listener, vendor, onModelLoadStart)
            } else {
                null
            }
        }
    }
}
