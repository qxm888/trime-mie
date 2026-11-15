/* SPDX-License-Identifier: Apache-2.0
 * Copyright BryceWG and BiBi-Keyboard contributors.
 * Adapted from BiBi-Keyboard 271f77786bdae6aa6b40bc9a3c76293821745c2b:
 * Trime provider selection, shared cancellable transport, bounded response/timeout,
 * and testable kotlinx.serialization protocol helpers.
 */
package com.osfans.trime.ime.ai

import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object OnlineTranslationService {
    suspend fun translate(id: String, text: String, source: String, target: String): String = withTimeout(50_000) {
        withContext(Dispatchers.IO) {
            val vendor = TranslationVendor.entries.firstOrNull { it.id == id } ?: error("请选择翻译服务")
            require(text.isNotBlank() && text.length <= TextProtocol.MAX_INPUT) { "请选择 1～12000 字的文本" }
            require(target in TranslationLanguages.codes && target != "auto" && source in TranslationLanguages.codes)
            if (source == target) return@withContext text
            buildString {
                for (part in TranslationProtocol.chunks(text, if (vendor == TranslationVendor.Bilibili) 2000 else 1000)) {
                    currentCoroutineContext().ensureActive()
                    if (part.isBlank()) append(part) else {
                        val start = part.indexOfFirst { !it.isWhitespace() }
                        val end = part.indexOfLast { !it.isWhitespace() } + 1
                        append(part.substring(0, start))
                        append(TranslationProtocol.parse(vendor, TextHttp.execute(TranslationProtocol.request(vendor, part.substring(start, end), source, target)), source).trim())
                        append(part.substring(end))
                    }
                }
            }
        }
    }
}

internal object TranslationProtocol {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    fun chunks(text: String, budget: Int): List<String> {
        require(budget > 0)
        val parts = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            val limit = text.offsetByCodePoints(offset, minOf(budget, text.codePointCount(offset, text.length)))
            val boundary = if (limit < text.length) (limit - 1 downTo offset + (limit - offset) / 2)
                .firstOrNull { text[it].isWhitespace() || text[it] in "。！？.!?;；" } else null
            val end = boundary?.plus(1) ?: limit
            parts += text.substring(offset, end)
            offset = end
        }
        return parts
    }
    fun request(vendor: TranslationVendor, text: String, source: String, target: String): Request {
        val from = language(vendor, source, true)
        val to = language(vendor, target)
        return when (vendor) {
            TranslationVendor.Microsoft -> {
                val url = "https://edge.microsoft.com/translate/translatetext".toHttpUrl().newBuilder()
                    .addQueryParameter("from", if (source == "auto") "" else from).addQueryParameter("to", to)
                    .addQueryParameter("isEnterpriseClient", "false").build()
                Request.Builder().url(url).post(buildJsonArray { add(escapeHtml(text)) }.toString().toRequestBody(jsonType)).build()
            }
            TranslationVendor.Tencent -> json("https://transmart.qq.com/api/imt", buildJsonObject {
                putJsonObject("header") { put("fn", "auto_translation"); put("client_key", "browser-chrome-110.0.0-Android-${UUID.randomUUID()}-${System.currentTimeMillis()}") }
                put("type", "plain"); put("model_category", "normal")
                putJsonObject("source") { putJsonArray("text_list") { add(text) }; put("lang", from) }
                putJsonObject("target") { put("lang", to) }
            })
            TranslationVendor.Volcengine -> json("https://translate.volcengine.com/crx/translate/v1/", buildJsonObject {
                put("target_language", to); put("text", text)
                if (source != "auto") put("source_language", from)
            })
            TranslationVendor.Bilibili -> json("https://index-translate.bilibili.com/v1/chat/completions", buildJsonObject {
                put("model", "Index-Translate-35B-A3B")
                putJsonArray("messages") { add(buildJsonObject {
                    put("role", "user")
                    val name = if (source == "auto") "" else languageName(source)
                    put("content", "Translate the following $name text into ${languageName(target)}. Preserve code, formulas, names and links. Output only the translation without explanation.\n\n$text")
                }) }
                put("temperature", 0); put("stream", false); put("max_tokens", 4096)
                putJsonObject("chat_template_kwargs") { put("enable_thinking", false) }
            })
            TranslationVendor.Google -> {
                val url = "https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder()
                    .addQueryParameter("client", "gtx").addQueryParameter("sl", from).addQueryParameter("tl", to).addQueryParameter("dt", "t").build()
                Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
            }
        }
    }
    fun parse(vendor: TranslationVendor, body: String, source: String): String {
        val value = Json.parseToJsonElement(body)
        val text = when (vendor) {
            TranslationVendor.Microsoft -> decodeHtml(value.jsonArray[0].jsonObject.getValue("translations").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
            TranslationVendor.Tencent -> {
                val response = value.jsonObject
                check(response.getValue("header").jsonObject.getValue("ret_code").jsonPrimitive.content == "succ") { "腾讯翻译返回错误" }
                response.getValue("auto_translation").jsonArray.joinToString("") { it.jsonPrimitive.content }
            }
            TranslationVendor.Volcengine -> {
                val response = value.jsonObject
                check(response.getValue("base_resp").jsonObject.getValue("status_code").jsonPrimitive.int == 0) { "火山翻译返回错误" }
                response.getValue("translation").jsonPrimitive.content
            }
            TranslationVendor.Bilibili -> TextProtocol.chatResult(body)
            TranslationVendor.Google -> if (source == "auto") value.jsonArray[0].jsonArray[0].jsonPrimitive.content else value.jsonArray[0].jsonPrimitive.content
        }
        check(text.isNotBlank()) { "翻译服务未返回正文" }
        return text
    }
    private fun json(url: String, body: JsonElement) = Request.Builder().url(url).header("User-Agent", "Trime-Mie")
        .post(body.toString().toRequestBody(jsonType)).build()
    fun language(vendor: TranslationVendor, tag: String, source: Boolean = false): String = when {
        vendor == TranslationVendor.Google && tag == "zh-Hans" -> "zh-CN"
        vendor == TranslationVendor.Google && tag == "zh-Hant" -> "zh-TW"
        vendor in listOf(TranslationVendor.Tencent, TranslationVendor.Volcengine) && tag == "zh-Hans" -> "zh"
        vendor in listOf(TranslationVendor.Tencent, TranslationVendor.Volcengine) && tag == "zh-Hant" && source -> "zh"
        vendor == TranslationVendor.Tencent && tag == "zh-Hant" -> "zh-tw"
        else -> tag
    }
    private fun languageName(tag: String) = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH)
    private fun escapeHtml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun decodeHtml(text: String) = text.replace(Regex("&#(?:0*39);|&#x0*27;", RegexOption.IGNORE_CASE), "'")
        .replace("&quot;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")
}
