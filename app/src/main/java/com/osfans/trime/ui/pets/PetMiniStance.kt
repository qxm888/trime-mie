/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import kotlin.math.sin

/** Board dimensions exclude the transparent side room needed for edge-key landings. */
internal object PetMiniScene {
    const val WIDTH=264f
    const val HEIGHT=156f
    const val CENTER=132f
    const val BOARD_TOP=92f
    const val BOARD_BOTTOM=152f
    const val MODEL_SIDE=80f
}

/** Sole coordinates are the transform origin: faces and waving arms cannot move the landing. */
internal class PetMiniStance {
    var leftFoot=true
    var lift=0f
    var bend=0f
    var lean=0f
    var variant=0
    fun soleX(id:String)=if(id=="xiaomie") { if(leftFoot) 58f else 72f } else if(leftFoot) 54f else 74f
    val soleY get()=125f
    fun reset() { leftFoot=true;lift=0f;bend=0f;lean=0f;variant=0 }
    fun armAngle(left:Boolean,pose:PetPose):Float {
        val sign=if(left) 1 else -1
        val swing=sin(lift*Math.PI).toFloat()
        return when(variant%3) {
            0 -> sign*(35f+lift*65f)+swing*12
            1 -> if(left==leftFoot) sign*(55f+lift*75f) else sign*(30f+lift*35f)
            else -> sign*(40f+lift*50f)+sin(pose.phase*Math.PI*6).toFloat()*lift*18
        }
    }
}

/** Mini-only standing limbs for the vector companions; their collection artwork is unchanged. */
internal class PetMiniLimbs {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    private val path=Path()
    private var cachedSkin:Int?=null
    private var fur:Shader?=null
    fun legs(canvas:Canvas,id:String,color:Int,stance:PetMiniStance) {
        val monkey=id=="monkey"
        val skin=if(monkey) 0xff3e495e.toInt() else color
        val outline=ColorUtils.blendARGB(skin,0xff423b42.toInt(),.32f)
        if(cachedSkin!=skin) { cachedSkin=skin;fur=LinearGradient(44f,96f,83f,126f,ColorUtils.blendARGB(skin,Color.WHITE,.18f),ColorUtils.blendARGB(skin,Color.BLACK,.09f),Shader.TileMode.CLAMP) }
        // Narrow standing hips replace the broad seated haunches and baked-in front paws.
        paint.style=Paint.Style.FILL;paint.color=skin;paint.shader=fur
        canvas.drawOval(43f,90f,85f,108f,paint);paint.shader=null
        repeat(2) { i ->
            val left=i==0;val x=if(left) 54f else 74f
            val supporting=left==stance.leftFoot
            val lift=if(supporting) 0f else stance.lift*8f
            val kneeX=if(supporting) x else x+(if(left) -1 else 1)*(stance.bend*3+stance.lift*3)
            path.reset();path.moveTo(x,100f);path.quadTo(kneeX,111f-stance.bend*2,kneeX,119f-lift)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=11f;paint.color=outline;canvas.drawPath(path,paint)
            paint.strokeWidth=8.5f;paint.color=skin;paint.shader=fur;canvas.drawPath(path,paint);paint.shader=null
            paint.style=Paint.Style.FILL;paint.color=outline
            canvas.drawOval(kneeX-7f,117f-lift,kneeX+7f,125f-lift,paint)
            paint.color=ColorUtils.blendARGB(skin,Color.WHITE,.14f)
            canvas.drawOval(kneeX-6f,117f-lift,kneeX+6f,123.7f-lift,paint)
            if(monkey) { paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=0xffe1c58c.toInt();canvas.drawLine(kneeX-4,120f-lift,kneeX+4,120f-lift,paint) }
        }
        paint.style=Paint.Style.FILL
    }
    fun arms(canvas:Canvas,color:Int,pose:PetPose,stance:PetMiniStance) {
        val outline=ColorUtils.blendARGB(color,0xff423b42.toInt(),.28f)
        repeat(2) { i ->
            val left=i==0
            canvas.save();canvas.translate(if(left) 43f else 85f,91f)
            val rest=stance.armAngle(left,pose)
            val angle=if(pose.interaction==PetInteraction.GREET && !left) rest+(-145f+sin(pose.phase*Math.PI*6).toFloat()*12-rest)*pose.amount else rest
            canvas.rotate(angle)
            path.reset();path.moveTo(0f,0f);path.quadTo(if(left) -2f else 2f,8f,0f,17f)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=8f;paint.color=outline;canvas.drawPath(path,paint)
            paint.strokeWidth=6f;paint.color=color;canvas.drawPath(path,paint)
            paint.style=Paint.Style.FILL;paint.color=ColorUtils.blendARGB(color,Color.WHITE,.16f)
            canvas.drawOval(-3.7f,13.5f,3.7f,19.5f,paint)
            canvas.restore()
        }
    }
}
