/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.stats

/** Bounds storage and per-commit work; no sorting/layout on the typing path. */
internal object WordCloudVocabulary {
    fun allows(type:Int,options:Int):Boolean {
        val variation=type and android.text.InputType.TYPE_MASK_VARIATION
        val password=when(type and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_TEXT -> variation in setOf(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            android.text.InputType.TYPE_CLASS_NUMBER -> variation==android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        return type!=0 && !password && options and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING==0
    }
    fun terms(text:String):List<String> = buildList {
        val chars=text.codePointCount(0,text.length)
        if(chars in 2..12) add(text)
        var offset=0;var count=0
        while(offset<text.length && count++<64) {
            val cp=text.codePointAt(offset);add(String(Character.toChars(cp)));offset+=Character.charCount(cp)
        }
    }
    fun add(counts:MutableMap<String,Long>,terms:List<String>) {
        terms.forEach { word ->
            if(word !in counts && counts.size>=512) counts.minByOrNull { it.value }?.key?.let(counts::remove)
            val old=counts[word] ?: 0L
            counts[word]=if(old==Long.MAX_VALUE) old else old+1L
        }
    }
}
