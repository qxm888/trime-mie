/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

internal enum class TypingGameAttack {
    ORB, HEART, CHICK;
    companion object {
        fun forPet(id:String)=when(id) { "xiaomie" -> HEART; "jige" -> CHICK; else -> ORB }
    }
}
