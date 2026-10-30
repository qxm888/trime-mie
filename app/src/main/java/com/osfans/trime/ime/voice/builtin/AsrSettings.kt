/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 内置语音识别设置（配置结构对照说点啥/BiBi-Keyboard PrefsAsrVendorFields，
 * Apache-2.0）。存独立 SharedPreferences（builtin_asr），不碰主题与 Rime 数据。
 * 在线 vendor 的凭据/endpoint/模型按 vendor id 分槽（cfg.<id>.*）；本地家族的
 * 变体/线程等按家族 id 分槽（loc.<family>.*）。
 */
package com.osfans.trime.ime.voice.builtin

import android.content.Context

object AsrSettings {
    private var context: Context? = null
    private val prefs get() = context!!.getSharedPreferences("builtin_asr", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        context = com.osfans.trime.util.storageContext(ctx)
        migrateLegacyIfNeeded()
    }

    private fun str(key: String, def: String = ""): String = context?.let { prefs.getString(key, def) ?: def } ?: def
    private fun int(key: String, def: Int): Int = context?.let { prefs.getInt(key, def) } ?: def
    private fun bool(key: String, def: Boolean): Boolean = context?.let { prefs.getBoolean(key, def) } ?: def

    private fun put(key: String, value: String?) = prefs.edit().putString(key, value).apply()
    private fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    val version: Long get() = int("version", 0).toLong()

    private fun bumpVersion() = prefs.edit().putInt("version", int("version", 0) + 1).apply()

    /** mie.12 的旧字段迁移：vendor 枚举转 id 字符串，oa 与 volc 旧键转 cfg 分槽。 */
    private fun migrateLegacyIfNeeded() {
        if (bool("migrated_v2", false)) return
        val legacyVendor = str("vendor")
        val mapped = when (legacyVendor) {
            "SENSE_VOICE" -> "sensevoice"
            "VOLC" -> "volc"
            "OPENAI_COMPATIBLE" -> "openai"
            else -> null
        }
        if (mapped != null && str("selected_vendor").isEmpty()) {
            put("selected_vendor", mapped)
        }
        if (str("cfg.openai.endpoint").isEmpty() && str("oa_endpoint").isNotEmpty()) {
            put("cfg.openai.endpoint", str("oa_endpoint"))
            put("cfg.openai.api_key", str("oa_api_key"))
            put("cfg.openai.model", str("oa_model"))
            put("cfg.openai.language", str("oa_language"))
        }
        if (str("cfg.volc.api_key").isEmpty() && str("volc_api_key").isNotEmpty()) {
            put("cfg.volc.use_new_auth", if (bool("volc_use_new_auth", true)) "1" else "0")
            put("cfg.volc.api_key", str("volc_api_key"))
            put("cfg.volc.app_key", str("volc_app_key"))
            put("cfg.volc.access_key", str("volc_access_key"))
            put("cfg.volc.model", str("volc_model"))
            put("cfg.volc.language", str("volc_language"))
            put("cfg.volc.ddc", if (bool("volc_ddc", true)) "1" else "0")
        }
        prefs.edit().putBoolean("migrated_v2", true).apply()
    }

    // ---- 当前服务商 ----

    var selectedVendor: String
        get() = str("selected_vendor", "none")
        private set(value) = prefs.edit().putString("selected_vendor", value).apply()

    fun setVendor(id: String) {
        prefs.edit().putString("selected_vendor", id).apply()
        bumpVersion()
    }

    fun isLocalVendor(): Boolean = AsrVendors.byId(selectedVendor)?.local == true

    // ---- 在线 vendor 通用字段（分槽） ----

    private fun cfgKey(vendor: String, field: String) = "cfg.$vendor.$field"

    fun apiKey(vendor: String): String = str(cfgKey(vendor, "api_key"))
    fun setApiKey(vendor: String, value: String) {
        put(cfgKey(vendor, "api_key"), value); bumpVersion()
    }

    /** endpoint：用户值优先，空则回退 vendor 默认。 */
    fun endpoint(vendor: String): String {
        val custom = str(cfgKey(vendor, "endpoint"))
        return custom.ifBlank { AsrVendors.byId(vendor)?.defaultEndpoint.orEmpty() }
    }

    fun rawEndpoint(vendor: String): String = str(cfgKey(vendor, "endpoint"))

    fun setEndpoint(vendor: String, value: String) {
        put(cfgKey(vendor, "endpoint"), value); bumpVersion()
    }

    fun model(vendor: String): String {
        val custom = str(cfgKey(vendor, "model"))
        return custom.ifBlank { AsrVendors.byId(vendor)?.defaultModel.orEmpty() }
    }

    fun rawModel(vendor: String): String = str(cfgKey(vendor, "model"))

    fun setModel(vendor: String, value: String) {
        put(cfgKey(vendor, "model"), value); bumpVersion()
    }

    fun language(vendor: String): String = str(cfgKey(vendor, "language"))

    fun setLanguage(vendor: String, value: String) {
        put(cfgKey(vendor, "language"), value); bumpVersion()
    }

    fun tencentField(field: String): String = str(cfgKey("tencent",field))
    fun setTencentField(field: String, value: String) {
        require(field in setOf("app_id","secret_id","secret_key","category"))
        put(cfgKey("tencent",field),value); bumpVersion()
    }

    // ---- 火山（豆包语音）专有 ----

    fun volcUseNewAuth(): Boolean = str(cfgKey("volc", "use_new_auth"), "1") != "0"
    fun setVolcUseNewAuth(value: Boolean) {
        put(cfgKey("volc", "use_new_auth"), if (value) "1" else "0"); bumpVersion()
    }

    fun volcAppKey(): String = str(cfgKey("volc", "app_key"))
    fun setVolcAppKey(value: String) {
        put(cfgKey("volc", "app_key"), value); bumpVersion()
    }

    fun volcAccessKey(): String = str(cfgKey("volc", "access_key"))
    fun setVolcAccessKey(value: String) {
        put(cfgKey("volc", "access_key"), value); bumpVersion()
    }

    fun volcDdcEnabled(): Boolean = str(cfgKey("volc", "ddc"), "1") != "0"
    fun setVolcDdcEnabled(value: Boolean) {
        put(cfgKey("volc", "ddc"), if (value) "1" else "0"); bumpVersion()
    }

    fun volcHasCredential(): Boolean = if (volcUseNewAuth()) {
        apiKey("volc").isNotBlank()
    } else {
        volcAppKey().isNotBlank() && volcAccessKey().isNotBlank()
    }

    // ---- 本地家族字段（分槽） ----

    private fun locKey(family: String, field: String) = "loc.$family.$field"

    fun localVariant(family: String): String = str(locKey(family, "variant"))
    fun setLocalVariant(family: String, value: String) {
        put(locKey(family, "variant"), value); bumpVersion()
    }

    fun localThreads(family: String): Int = int(locKey(family, "threads"), 2)
    fun setLocalThreads(family: String, value: Int) {
        putInt(locKey(family, "threads"), value); bumpVersion()
    }

    fun localLanguage(family: String): String = str(locKey(family, "language"))
    fun setLocalLanguage(family: String, value: String) {
        put(locKey(family, "language"), value); bumpVersion()
    }

    fun localItn(family: String): Boolean = bool(locKey(family, "itn"), true)
    fun setLocalItn(family: String, value: Boolean) {
        prefs.edit().putBoolean(locKey(family, "itn"), value).apply(); bumpVersion()
    }

    /** <0 常驻内存；0 即用即卸；>0 N 分钟后自动卸载。 */
    fun localKeepAliveMinutes(family: String): Int = int(locKey(family, "keep_alive"), -1)
    fun setLocalKeepAliveMinutes(family: String, value: Int) {
        putInt(locKey(family, "keep_alive"), value); bumpVersion()
    }

    // ---- 下载源 ----

    /** 0 = GitHub 官方；1-3 = 镜像。 */
    var downloadSource: Int
        get() = int("download_source", 0)
        set(value) {
            putInt("download_source", value)
        }

    // ---- 凭据检查 ----

    fun hasCredential(): Boolean = when (selectedVendor) {
        "none" -> true
        "volc" -> volcHasCredential()
        "tencent" -> TencentProtocol.Credentials(tencentField("app_id").trim(),tencentField("secret_id").trim(),tencentField("secret_key").trim()).valid()
        else -> {
            val def = AsrVendors.byId(selectedVendor) ?: return true
            if (def.local) {
                context?.let { LocalAsrModels.installedVariant(it, def.id) != null } ?: false
            } else {
                apiKey(selectedVendor).isNotBlank()
            }
        }
    }
}
