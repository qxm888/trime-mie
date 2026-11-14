/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.pets

/** Shared by the dialog and save transaction so every gift has an independent claim. */
internal enum class PetRedeemCode(val code:String,val petId:String?) {
    CHOICE("qxm888",null), XIAOMIE("qxm520","xiaomie"), MONKEY("qxm555","monkey"), JIGE("qxm111","jige");
    fun used(s:PetHouse.State)=when(this) {
        CHOICE -> s.redeemed; XIAOMIE -> s.xiaomieRedeemed; MONKEY -> s.monkeyRedeemed; JIGE -> s.jigeRedeemed
    }
    fun claim(s:PetHouse.State)=when(this) {
        CHOICE -> s.copy(redeemed=true); XIAOMIE -> s.copy(xiaomieRedeemed=true)
        MONKEY -> s.copy(monkeyRedeemed=true); JIGE -> s.copy(jigeRedeemed=true)
    }
    fun requireAvailable(s:PetHouse.State,species:PetCatalog.Species) {
        check(!used(s)) { "这个兑换码已经用过了" }
        check(if(petId==null) species.rarity<4 else species.id==petId) { "此兑换码不支持这位伙伴" }
    }
    companion object { fun find(code:String)=entries.firstOrNull { it.code==code } }
}
