/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.pets

/** Additional SSR partners supplied with the eldritch artwork patch. */
object EldritchCatalog {
    data class Spec(
        val id:String,
        val name:String,
        val rarity:Int,
        val description:String,
        val color:Int,
        val asset:String,
        val aura:Int,
        val ghostA:Int,
        val ghostB:Int,
        val title:String="",
    )
    val specs=listOf(
        Spec(
            id="jizai",name="季灾",rarity=3,
            description="分不清哪边是真，却替你守住了每一个字。",
            color=0xff8c3b3b.toInt(),
            asset="eldritch/jizai.png",
            aura=0xff6e1f2a.toInt(),
            ghostA=0xff8e2f36.toInt(),
            ghostB=0xff2f6b74.toInt(),
            title="迷惘司命",
        ),
    )
    fun handles(id:String)=specs.any { it.id==id }
    fun get(id:String)=specs.firstOrNull { it.id==id }
}
