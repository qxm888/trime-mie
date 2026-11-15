/* SPDX-License-Identifier: GPL-3.0-or-later
 * Provider metadata adapted from BiBi-Keyboard LlmVendor.kt (Apache-2.0),
 * BryceWG and contributors, commit 271f77786bdae6aa6b40bc9a3c76293821745c2b.
 * No upstream shared credentials or proprietary services are included.
 */
package com.osfans.trime.ime.ai

data class TextProvider(val id: String, val label: String, val endpoint: String,
    val model: String, val models: List<String>)

object TextProviders {
    val all = listOf(
        TextProvider("sf_free", "SiliconFlow（自备 Key）", "https://api.siliconflow.cn/v1", "Qwen/Qwen3-8B", listOf("Qwen/Qwen3-8B", "Qwen/Qwen3-14B", "Qwen/Qwen3-32B", "Qwen/Qwen3-30B-A3B-Instruct-2507", "Qwen/Qwen3-30B-A3B-Thinking-2507", "Qwen/Qwen3-235B-A22B-Instruct-2507", "Qwen/Qwen3-235B-A22B-Thinking-2507", "Qwen/Qwen3-Next-80B-A3B-Instruct", "Qwen/Qwen3-Next-80B-A3B-Thinking", "deepseek-ai/DeepSeek-V3.1-Terminus", "deepseek-ai/DeepSeek-V3.2", "zai-org/GLM-4.6")),
        TextProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", listOf("deepseek-chat", "deepseek-reasoner")),
        TextProvider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.6", listOf("glm-4.7", "glm-4.6", "glm-4.5", "glm-4.5-air", "glm-4.5-flash", "glm-4-plus", "glm-4-flashx")),
        TextProvider("moonshot", "Kimi", "https://api.moonshot.cn/v1", "kimi-k2-0905-preview", listOf("kimi-k2-0905-preview", "kimi-k2-thinking")),
        TextProvider("volcengine", "火山方舟", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-flash-250828", listOf("doubao-seed-1-6-lite-251015", "doubao-seed-1-6-251015", "doubao-seed-1-6-flash-250828", "deepseek-v3-1-terminus", "deepseek-v3-2-251201")),
        TextProvider("dashscope", "阿里千问", "https://maas.qianwenaiapi.com/compatible-mode/v1", "qwen3.5-plus", listOf("qwen3.5-plus", "qwen3.5-397b-a17b", "qwen3-max")),
        TextProvider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1")),
        TextProvider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", listOf("gemini-2.0-flash", "gemini-2.5-flash-lite", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-3-flash-preview", "gemini-3-pro-preview")),
        TextProvider("groq", "Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", listOf("moonshotai/kimi-k2-instruct-0905", "qwen/qwen3-32b", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "meta-llama/llama-4-maverick-17b-128e-instruct")),
        TextProvider("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b", listOf("llama3.1-8b", "llama-3.3-70b", "qwen-3-32b", "qwen-3-235b-a22b-instruct-2507", "gpt-oss-120b", "zai-glm-4.6")),
        TextProvider("fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/deepseek-v3p2", listOf("accounts/fireworks/models/deepseek-v3p2", "accounts/fireworks/models/deepseek-v3p1-terminus", "accounts/fireworks/models/kimi-k2-instruct-0905", "accounts/fireworks/models/kimi-k2-thinking")),
        TextProvider("ohmygpt", "OhMyGPT", "https://cn2us02.opapi.win/v1", "gpt-4o-mini", listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4.1", "gpt-5-mini", "gpt-5-nano", "gemini-2.0-flash", "gemini-2.5-flash-lite", "gemini-2.5-flash", "claude-haiku-4-5", "claude-sonnet-4-5")),
        TextProvider("custom", "自定义兼容服务", "", "", listOf()),
    )
    fun byId(id: String): TextProvider = all.firstOrNull { it.id == id } ?: all.first { it.id == "deepseek" }
}
