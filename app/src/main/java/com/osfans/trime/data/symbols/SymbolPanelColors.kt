/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.symbols
import kotlin.math.pow

/** Bitmap keycaps cannot be used as solid fill colors; pair their text with a readable surface. */
internal object SymbolPanelColors {
    data class Palette(val text:Int,val key:Int,val surface:Int,val border:Int,val surfaceText:Int)
    private fun luminance(c:Int):Double {
        fun channel(shift:Int):Double { val x=((c ushr shift) and 255)/255.0;return if(x<=.04045) x/12.92 else ((x+.055)/1.055).pow(2.4) }
        return .2126*channel(16)+.7152*channel(8)+.0722*channel(0)
    }
    fun contrast(a:Int,b:Int):Double { val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05) }
    fun resolve(ink:Int,key:Int?,surface:Int?,border:Int?):Palette {
        val dark=luminance(ink)>.45
        val back=surface?.takeIf { it ushr 24 ==255 } ?: if(dark) 0xff202b3b.toInt() else 0xfff1f3f5.toInt()
        val cap=key?.takeIf { it ushr 24 ==255 } ?: if(dark) 0xff2b394c.toInt() else 0xffffffff.toInt()
        fun readable(background:Int)=if(contrast(ink,background)>=4.5) ink
            else if(contrast(-1,background)>=contrast(0xff111827.toInt(),background)) -1 else 0xff111827.toInt()
        return Palette(readable(cap),cap,back,border ?: if(dark) 0xff63748a.toInt() else 0xffc7d0da.toInt(),readable(back))
    }
}
