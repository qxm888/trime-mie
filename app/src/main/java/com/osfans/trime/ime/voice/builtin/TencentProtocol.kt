/* SPDX-License-Identifier: GPL-3.0-or-later
 * Adapted from BryceWG/BiBi-Keyboard Tencent engines (Apache-2.0), PR #134/#135.
 * Tencent protocol: https://cloud.tencent.com/document/product/1093/48982 and /52097
 */
package com.osfans.trime.ime.voice.builtin

import android.util.Base64
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object TencentProtocol {
    data class Credentials(val appId: String, val secretId: String, val secretKey: String) {
        fun valid() = appId.matches(Regex("[0-9]{1,32}")) && secretId.isNotBlank() && secretKey.isNotBlank()
    }
    private fun encode(value: String) = URLEncoder.encode(value,"UTF-8")
    private fun query(values: Map<String,String>) = values.toSortedMap().entries.joinToString("&") { (key,value) -> "$key=${encode(value)}" }
    private fun signature(text: String,key: String): String {
        val mac=Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8),"HmacSHA1"))
        return Base64.encodeToString(mac.doFinal(text.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    fun streamUrl(credentials: Credentials, model: String, now: Long, voiceId: String, nonce: Int): String {
        val query=query(mapOf("engine_model_type" to model,"expired" to (now+86400).toString(),"needvad" to "1",
            "nonce" to nonce.toString(),"secretid" to credentials.secretId,"timestamp" to now.toString(),
            "voice_format" to "1","voice_id" to voiceId))
        val path="asr.cloud.tencent.com/asr/v2/${credentials.appId}?$query"
        return "wss://$path&signature=${encode(signature(path,credentials.secretKey))}"
    }
    fun flashRequest(credentials: Credentials,model: String,now: Long): Pair<String,String> {
        val query=query(mapOf("engine_type" to model,"secretid" to credentials.secretId,"timestamp" to now.toString(),"voice_format" to "wav"))
        val path="asr.cloud.tencent.com/asr/flash/v1/${credentials.appId}?$query"
        return "https://$path" to signature("POST$path",credentials.secretKey)
    }
    /** Both official frame shapes carry absolute sentence IDs: replace, never append duplicates. */
    class Transcript {
        private val parts=java.util.TreeMap<Int,String>()
        @Synchronized fun accept(message: JSONObject): String {
            message.optJSONObject("sentences")?.let { sentence ->
                val index=sentence.optInt("sentence_id",0)
                if (sentence.has("sentence")) parts[index]=sentence.optString("sentence")
            }
            message.optJSONObject("result")?.let { result ->
                if (result.has("voice_text_str")) {
                    val text=result.optString("voice_text_str")
                    if (message.optInt("final")==1 && !result.has("index") && text.isNotBlank()) {
                        parts.clear(); parts[0]=text
                    } else if (text.isNotEmpty() || result.optInt("slice_type")==2) {
                        parts[result.optInt("index",0)]=text
                    }
                }
            }
            return parts.values.joinToString("")
        }
    }
}
