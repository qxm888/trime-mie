/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
package com.osfans.trime.ui.pets

/** A wind-up, shoulder strike and return. Endpoints are neutral for seamless retriggering. */
internal object PetJigeMotion {
    data class Frame(val x:Float=0f,val lean:Float=0f,val width:Float=1f,val tuck:Float=0f)
    fun attack(phase:Float):Frame {
        if(phase<=0f || phase>=1f) return Frame()
        val t=phase
        return when {
            t<.25f -> { val p=t/.25f;Frame(-4*p,-6*p,1-.1f*p,24*p) }
            t<.5f -> { val p=(t-.25f)/.25f;Frame(-4+14*p,-6+21*p,.9f-.15f*p,24+22*p) }
            else -> { val p=(t-.5f)/.5f;Frame(10*(1-p),15*(1-p),.75f+.25f*p,46*(1-p)) }
        }
    }
}
