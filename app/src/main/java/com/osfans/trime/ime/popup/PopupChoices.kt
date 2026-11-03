/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.popup
import kotlin.math.ceil
import kotlin.math.floor

/** Layout and labels shared by legacy theme and custom-keyboard popup menus. */
internal object PopupChoices {
    fun rows(count:Int)=ceil(count/5.0).toInt().coerceAtLeast(1)
    fun columns(count:Int)=ceil(count.toDouble()/rows(count)).toInt().coerceAtLeast(1)
    fun rowAt(y:Float,keyHeight:Int,rows:Int)=rows-1-floor(y/keyHeight.coerceAtLeast(1)).toInt()
    fun compactActionLabel(label: String): String {
        // A short action label is one grapheme, including emoji variation/skin/ZWJ/flag tails.
        var end=Character.charCount(label.codePointAt(0));var joinNext=false
        val first=label.codePointAt(0)
        var regional=first in 0x1F1E6..0x1F1FF
        while(end<label.length) {
            val cp=label.codePointAt(end)
            val mark=Character.getType(cp) in setOf(Character.NON_SPACING_MARK.toInt(),Character.COMBINING_SPACING_MARK.toInt(),Character.ENCLOSING_MARK.toInt())
            val extend=joinNext || cp==0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || mark || (regional && cp in 0x1F1E6..0x1F1FF)
            if(!extend) break
            end+=Character.charCount(cp);joinNext=cp==0x200D;regional=false
        }
        return label.substring(0,end)
    }

}
