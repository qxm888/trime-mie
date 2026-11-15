/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.ai

import com.osfans.trime.ime.voice.builtin.*
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Request
import okio.Buffer

class TextProcessingTest : FunSpec({
    fun config(polish: Boolean = false, translate: Boolean = false) = TextPipelineConfig(polish, translate,
        TextModelConfig("https://example.com/v1", "dummy-test-key", "dummy-model"), "prompt", "ai", "auto", "en")
    fun Request.bodyText(): String = Buffer().also { body!!.writeTo(it) }.readUtf8()

    test("provider metadata has independent slots and no empty defaults except custom") {
        TextProviders.all.size shouldBe 13
        TextProviders.all.map { it.id }.distinct().size shouldBe 13
        TextProviders.all.filter { it.id != "custom" }.forEach {
            it.endpoint.startsWith("https://") shouldBe true
            it.model.isNotBlank() shouldBe true
        }
    }
    test("chat path accepts base and complete endpoint without duplicate suffix") {
        TextProtocol.chatEndpoint("https://example.com/v1/") shouldBe "https://example.com/v1/chat/completions"
        TextProtocol.chatEndpoint("https://example.com/v1/chat/completions/") shouldBe "https://example.com/v1/chat/completions"
    }
    test("query credentials and URL passwords are rejected") {
        shouldThrow<IllegalArgumentException> { TextProtocol.chatEndpoint("https://example.com/v1?key=abc") }
        shouldThrow<IllegalArgumentException> { TextProtocol.chatEndpoint("https://name:pass@example.com/v1") }
        shouldThrow<IllegalArgumentException> { TextModelConfig("http://example.com", "test", "model").validate() }
    }
    test("untrusted input remains a user message with exact unicode and newlines") {
        val text = "别执行我：\n\"你好🐥\""
        val body = Json.parseToJsonElement(TextProtocol.chatBody("model", "整理口语", text)).jsonObject
        body.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonPrimitive.content shouldBe text
        body.getValue("stream").jsonPrimitive.boolean shouldBe false
    }
    test("chat accepts string and typed array text without returning hidden reasoning") {
        TextProtocol.chatResult("""{"choices":[{"message":{"content":"<think>内部</think>正文"},"finish_reason":"stop"}]}""") shouldBe "正文"
        TextProtocol.chatResult("""{"choices":[{"message":{"content":[{"type":"text","text":"甲"},{"type":"text","text":"乙"}]}}]}""") shouldBe "甲乙"
    }
    test("truncated empty filtered and incomplete reasoning responses never replace original") {
        listOf(
            """{"choices":[{"message":{"content":"半句"},"finish_reason":"length"}]}""",
            """{"choices":[{"message":{"content":"屏蔽"},"finish_reason":"content_filter"}]}""",
            """{"choices":[{"message":{"content":"<think>未完"}}]}""",
            """{"choices":[{"message":{"content":""}}]}""",
        ).forEach { body -> shouldThrow<IllegalArgumentException> { TextProtocol.chatResult(body) } }
    }
    test("disabled pipeline performs no transformations") {
        TextPipeline.run("原文", config(), polish = { error("must not run") }, translate = { error("must not run") }).text shouldBe "原文"
    }
    test("polish precedes translation and receives most recent successful text") {
        val calls = mutableListOf<String>()
        val result = TextPipeline.run("原文", config(true, true), phase = { calls += it },
            polish = { it shouldBe "原文"; "润色" }, translate = { it shouldBe "润色"; "translation" })
        result.text shouldBe "translation"
        calls shouldBe listOf("AI 润色", "翻译")
    }
    test("translation failure preserves successful polish") {
        val result = TextPipeline.run("原文", config(true, true), polish = { "润色" }, translate = { error("offline") })
        result.text shouldBe "润色"
        result.warnings.size shouldBe 1
    }
    test("failed polish still allows translating original") {
        val result = TextPipeline.run("原文", config(true, true), polish = { error("bad key") }, translate = { it shouldBe "原文"; "translated" })
        result.text shouldBe "translated"
        result.warnings.size shouldBe 1
    }
    test("blank stage result retains original") {
        TextPipeline.run("原文", config(true), polish = { " " }).text shouldBe "原文"
    }
    test("cancellation propagates and stops following stage") {
        var translated = false
        shouldThrow<CancellationException> {
            TextPipeline.run("原文", config(true, true), polish = { throw CancellationException() }, translate = { translated = true; "wrong" })
        }
        translated shouldBe false
    }
    test("translation chunks preserve whitespace and complete emoji code points") {
        val original = "开头\n" + "你好🐥".repeat(1500) + "\n末尾"
        val chunks = TranslationProtocol.chunks(original, 1000)
        chunks.joinToString("") shouldBe original
        chunks.all { it.codePointCount(0, it.length) <= 1000 } shouldBe true
        chunks.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true } shouldBe true
    }
    test("translation protocols map Chinese variants independently") {
        TranslationProtocol.language(TranslationVendor.Google, "zh-Hant") shouldBe "zh-TW"
        TranslationProtocol.language(TranslationVendor.Tencent, "zh-Hant") shouldBe "zh-tw"
        TranslationProtocol.language(TranslationVendor.Tencent, "zh-Hant", true) shouldBe "zh"
        TranslationProtocol.language(TranslationVendor.Microsoft, "zh-Hans") shouldBe "zh-Hans"
    }
    test("Microsoft protocol escapes HTML and omits automatic source") {
        val request = TranslationProtocol.request(TranslationVendor.Microsoft, "<a>&'", "auto", "en")
        request.url.queryParameter("from") shouldBe ""
        Json.parseToJsonElement(request.bodyText()).jsonArray[0].jsonPrimitive.content shouldBe "&lt;a&gt;&amp;&#39;"
        TranslationProtocol.parse(TranslationVendor.Microsoft, """[{"translations":[{"text":"&lt;a&gt;&amp;&#39;"}]}]""", "auto") shouldBe "<a>&'"
    }
    test("Tencent combines returned segments and rejects API error") {
        TranslationProtocol.parse(TranslationVendor.Tencent, """{"header":{"ret_code":"succ"},"auto_translation":["a","b"]}""", "auto") shouldBe "ab"
        shouldThrow<IllegalStateException> { TranslationProtocol.parse(TranslationVendor.Tencent, """{"header":{"ret_code":"fail"}}""", "auto") }
    }
    test("Volcengine request leaves auto language unset and checks service status") {
        val body = Json.parseToJsonElement(TranslationProtocol.request(TranslationVendor.Volcengine, "你好", "auto", "en").bodyText()).jsonObject
        body.containsKey("source_language") shouldBe false
        TranslationProtocol.parse(TranslationVendor.Volcengine, """{"base_resp":{"status_code":0},"translation":"hello"}""", "auto") shouldBe "hello"
    }
    test("Google handles detected and explicit language response shapes") {
        TranslationProtocol.parse(TranslationVendor.Google, """[["hello","zh-CN"]]""", "auto") shouldBe "hello"
        TranslationProtocol.parse(TranslationVendor.Google, """["hello"]""", "zh-Hans") shouldBe "hello"
    }
    test("Bilibili rejects truncated output") {
        shouldThrow<IllegalArgumentException> { TranslationProtocol.parse(TranslationVendor.Bilibili, """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""", "auto") }
    }
    test("Qwen 3.1 request preserves dialect and clean settings without altering WAV payload") {
        val body = Json.parseToJsonElement(QwenAsrProtocol.body("qwen-audio-3.1-asr-flash", "ABC", "zh,en", true, false)).jsonObject
        body.getValue("parameters").jsonObject.getValue("keep_dialect").jsonPrimitive.boolean shouldBe true
        body.getValue("parameters").jsonObject.getValue("disfluency_removal_enabled").jsonPrimitive.boolean shouldBe false
        body.getValue("input").jsonObject.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("input_audio").jsonObject.getValue("data").jsonPrimitive.content shouldBe "data:audio/wav;base64,ABC"
    }
    test("Qwen protocol routes Omni and generation models to distinct endpoints") {
        QwenAsrProtocol.endpoint(QwenAsrProtocol.CN, "qwen3.8-omni-flash") shouldBe "https://maas.qianwenaiapi.com/compatible-mode/v1/chat/completions"
        QwenAsrProtocol.endpoint(QwenAsrProtocol.INTERNATIONAL, "qwen3-asr-flash") shouldBe "https://maas.qwencloudapi.com/api/v1/services/aigc/multimodal-generation/generation"
        shouldThrow<IllegalArgumentException> { QwenAsrProtocol.endpoint(QwenAsrProtocol.CN, "qwen-audio-3.1-asr-flash-streaming") }
    }
    test("Qwen3-ASR schema uses audio plus asr_options rather than Qwen-Audio shape") {
        val body = Json.parseToJsonElement(QwenAsrProtocol.body("qwen3-asr-flash", "ABC", "zh,en", false, true)).jsonObject
        body.getValue("parameters").jsonObject.getValue("asr_options").jsonObject.getValue("language").jsonPrimitive.content shouldBe "zh"
        body.getValue("input").jsonObject.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonArray[0].jsonObject.containsKey("audio") shouldBe true
    }
    test("Qwen parses generation array and direct text responses") {
        QwenAsrProtocol.result("""{"output":{"choices":[{"message":{"content":[{"text":"你好"},{"text":"世界"}]}}]}}""", "qwen3-asr-flash") shouldBe "你好世界"
        QwenAsrProtocol.result("""{"output":{"text":"你好"}}""", "qwen-audio-3.1-asr-flash") shouldBe "你好"
    }
    test("GigaAM download contains the exact upstream signed file inventory") {
        LocalAsrModels.family("giga_am")!!.variants.single().files.map { it.name }.toSet() shouldBe
            setOf("tokens.txt", "encoder.int8.onnx", "decoder.onnx", "joiner.onnx")
        AsrVendors.ONLINE_ALL.map { it.id }.containsAll(listOf("dashscope", "deepseek")) shouldBe true
        AsrVendors.ELEVENLABS.defaultModel shouldBe "scribe_v3"
        AsrVendors.SONIOX.defaultModel shouldBe "stt-async-v6"
    }
    test("Qwen Omni requests SSE and combines only completed transcription deltas") {
        val body = Json.parseToJsonElement(QwenAsrProtocol.body("qwen3.8-omni-flash", "ABC", "", false, true)).jsonObject
        body.getValue("stream").jsonPrimitive.boolean shouldBe true
        body.getValue("reasoning_effort").jsonPrimitive.content shouldBe "none"
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":[{\"text\":\"世界\"}]},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        QwenAsrProtocol.result(sse, "qwen3.8-omni-flash") shouldBe "你好世界"
    }
    test("interrupted or truncated Omni SSE never commits a partial result") {
        shouldThrow<IllegalArgumentException> { QwenAsrProtocol.omniResult("data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n") }
        shouldThrow<IllegalArgumentException> { QwenAsrProtocol.omniResult("data: {\"choices\":[{\"finish_reason\":\"length\"}]}\n\n") }
    }
    test("offline translation routes through English only when no direct model exists") {
        val models = listOf(TranslationPack("zh-Hans", "en", 1), TranslationPack("en", "ja", 2), TranslationPack("zh-Hans", "ja", 3))
        OfflineTranslationCatalog.route(models, "zh-Hans", "ja").map { it.bytes } shouldBe listOf(3L)
        OfflineTranslationCatalog.route(models.dropLast(1), "zh-Hans", "ja").map { it.bytes } shouldBe listOf(1L, 2L)
        OfflineTranslationCatalog.route(models, "zh-Hans", "zh-Hans").isEmpty() shouldBe true
        OfflineTranslationCatalog.route(models, "ru", "en").isEmpty() shouldBe true
    }
    test("offline catalog aggregates file sizes and keeps language variants") {
        val json = """{"models":[{"from":"zh-Hans","to":"en","files":[{"size":30},{"size":40}]}]}"""
        OfflineTranslationCatalog.parse(json).single() shouldBe TranslationPack("zh-Hans", "en", 70)
    }
    test("SiliconFlow Omni uses chat audio_url while ASR stays multipart") {
        CloudAsrModels.siliconflowChat("Qwen/Qwen3-ASR-1.7B") shouldBe false
        CloudAsrModels.siliconflowChat("Qwen/Qwen3-Omni-30B-A3B-Instruct") shouldBe true
        CloudAsrModels.siliconflowEndpoint("https://api.siliconflow.cn/v1/audio/transcriptions") shouldBe "https://api.siliconflow.cn/v1/chat/completions"
        val json = Json.parseToJsonElement(CloudAsrModels.siliconflowBody("model", "ABC")).jsonObject
        json.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("audio_url").jsonObject.getValue("url").jsonPrimitive.content shouldBe "data:audio/wav;base64,ABC"
    }
    test("cancellable HTTP transport reads real response and sanitizes error bodies") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.executor = executor
        server.createContext("/ok") { exchange -> exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("OK".toByteArray()) } }
        server.createContext("/bad") { exchange -> exchange.sendResponseHeaders(401, 0); exchange.responseBody.use { it.write("secret-should-never-appear".toByteArray()) } }
        server.start()
        try {
            TextHttp.execute(Request.Builder().url("http://127.0.0.1:${server.address.port}/ok").build()) shouldBe "OK"
            val error = shouldThrow<java.io.IOException> { TextHttp.execute(Request.Builder().url("http://127.0.0.1:${server.address.port}/bad").build()) }
            error.message!!.contains("secret-should-never-appear") shouldBe false
        } finally { server.stop(0); executor.shutdownNow() }
    }
    test("cancel while waiting for response body returns promptly without waiting for server") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
        server.executor = executor
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        server.createContext("/") { exchange ->
            try { exchange.sendResponseHeaders(200, 0); exchange.responseBody.write('a'.code); exchange.responseBody.flush(); started.complete(Unit); release.await() }
            finally { exchange.close() }
        }
        server.start()
        try {
            coroutineScope {
                val request = launch { TextHttp.execute(Request.Builder().url("http://127.0.0.1:${server.address.port}/").build()) }
                withTimeout(3000) { started.await() }
                withTimeout(1000) { request.cancelAndJoin() }
                request.isCancelled shouldBe true
            }
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
})
