/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.content.Context
import android.graphics.*
import androidx.appcompat.content.res.AppCompatResources
import com.osfans.trime.R
import kotlin.math.sin

/** All arm coordinates are in the same 128-unit model space as the character. */
internal object PetUrRig {
    data class Profile(val leftX:Float,val rightX:Float,val shoulderY:Float,val length:Float)
    val girl=Profile(53f,75.5f,69.5f,22.74f)
    val monkey=Profile(43f,85f,91f,26f)
    fun profile(id:String):Profile?=when(id) { "xiaomie" -> girl;"monkey" -> monkey;else -> null }
    val standingGirl=Profile(54.5f,75.5f,59.5f,22.74f)
}
internal class PetUrAim {
    var leftAngle=20f;var rightAngle=-20f
    var leftActive=false;var rightActive=false
    fun reset() { leftAngle=20f;rightAngle=-20f;leftActive=false;rightActive=false }
}

/** Complete illustrated sleeves, wrist and human/primate fingers; never scaled to reach a key. */
internal class PetUrArms(context:Context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source=Rect(245,280,568,1490)
    private val girlBounds=RectF(-2.98f,-.35f,3.24f,22.95f)
    private val monkey by lazy { AppCompatResources.getDrawable(context,R.drawable.pet_monkey_arm)?.mutate()?.also { it.setBounds(-8,-3,8,27) } }
    fun draw(canvas:Canvas,id:String,pose:PetPose,aim:PetUrAim?,standing:Boolean=false) {
        val rig=if(standing && id=="xiaomie") PetUrRig.standingGirl else PetUrRig.profile(id) ?: return
        repeat(2) { i ->
            val left=i==0
            val wave=sin(pose.phase*Math.PI*4).toFloat()
            val rest=if(left) 20f else -20f
            val gesture=when(pose.interaction) {
                PetInteraction.GREET -> if(left) rest else -150f+wave*12
                PetInteraction.LOVE -> if(left) -40f else 40f
                PetInteraction.PLAY -> (if(left) -30 else 30)+wave*12
                PetInteraction.YAWN,PetInteraction.LEVEL_UP -> if(left) 115f else -115f
                PetInteraction.EAT -> if(left) -65f else 65f
                else -> rest
            }
            val active=if(left) aim?.leftActive==true else aim?.rightActive==true
            val angle=if(active) (if(left) aim!!.leftAngle else aim!!.rightAngle) else rest+(gesture-rest)*pose.amount
            canvas.save();canvas.translate(if(left) rig.leftX else rig.rightX,rig.shoulderY)
            canvas.rotate(angle)
            if(id=="xiaomie") {
                // The source finger axis is slightly bent; compensate before mirroring.
                canvas.rotate(if(left) 3.28f else -3.28f)
                if(!left) canvas.scale(-1f,1f)
                canvas.drawBitmap(PetUrArtwork.arm,source,girlBounds,paint)
            } else {
                if(!left) canvas.scale(-1f,1f)
                monkey?.draw(canvas)
            }
            canvas.restore()
        }
    }
}
