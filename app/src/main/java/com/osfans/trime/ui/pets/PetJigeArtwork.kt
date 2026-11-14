/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.appcompat.content.res.AppCompatResources
import com.osfans.trime.R
import kotlin.math.PI
import kotlin.math.sin

/** The chick keeps its own wings, bird feet and face at every size. No generic paws. */
internal class PetJigeArtwork(context:Context) {
    private val body=checkNotNull(AppCompatResources.getDrawable(context,R.drawable.pet_jige_body)).mutate().apply { setBounds(0,0,128,128) }
    private val hair=checkNotNull(AppCompatResources.getDrawable(context,R.drawable.pet_jige_hair)).mutate().apply { setBounds(0,0,128,128) }
    private val wing=checkNotNull(AppCompatResources.getDrawable(context,R.drawable.pet_jige_wing)).mutate().apply { setBounds(0,0,128,128) }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    private val path=Path()
    // Fixed path data is parsed once, including the small feet and beak.
    private val leftFoot=checkNotNull(androidx.core.graphics.PathParser.createPathFromPathData("M47,109 Q49,106 57,110 L58,118 Q56,124 43,122 Q36,121 39,116 Z"))
    private val rightFoot=checkNotNull(androidx.core.graphics.PathParser.createPathFromPathData("M73,110 Q80,106 83,112 L91,118 Q92,123 80,123 L71,120 Z"))
    private val beak=checkNotNull(androidx.core.graphics.PathParser.createPathFromPathData("M64,67 Q76,55 87,66 Q89,73 76,76 Q65,75 64,67 Z"))
    private fun outlined(c:Canvas,p:Path,color:Int,width:Float=2.2f) {
        paint.style=Paint.Style.FILL;paint.color=color;c.drawPath(p,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=width;paint.color=0xff19191d.toInt();c.drawPath(p,paint);paint.style=Paint.Style.FILL
    }
    fun draw(c:Canvas,pose:PetPose,step:PetMiniStance?) {
        val bump=if(step==null && pose.interaction==PetInteraction.PLAY) PetJigeMotion.attack(pose.phase) else PetJigeMotion.Frame()
        c.save()
        // Sideways turn, wings tucked, a sharp leading shoulder, then the release.
        c.translate(bump.x,0f);c.rotate(bump.lean,64f,111f);c.scale(bump.width,1f,64f,88f)
        repeat(2) { i ->
            val left=i==0;val lifted=step!=null && step.leftFoot!=left
            c.save();if(lifted) c.translate(0f,-checkNotNull(step).lift*8f)
            if(step!=null) {
                val x=if(left) 54f else 74f
                paint.color=0xffffd04a.toInt();c.drawRoundRect(x-4,103f,x+4,119f,3f,3f,paint)
                path.reset();path.addOval(x-8f,117f,x+8f,125f,Path.Direction.CW);outlined(c,path,0xfff6a52b.toInt(),1.5f)
                paint.color=0xffc87920.toInt();paint.strokeWidth=.7f;c.drawLine(x-2,120f,x-2,124f,paint);c.drawLine(x+2,120f,x+2,124f,paint)
            } else outlined(c,if(left) leftFoot else rightFoot,0xfff6a52b.toInt())
            c.restore()
        }
        repeat(2) { i ->
            c.save();if(i==1) c.scale(-1f,1f,64f,0f)
            val wave=if(step!=null) step.armAngle(i==0,pose)*.4f else -sin(pose.phase*PI).toFloat()*pose.amount*14f
            c.rotate(wave+bump.tuck,41f,85f);wing.draw(c);c.restore()
        }
        body.draw(c)
        face(c,PetFace.CALM,1-pose.amount,pose.gaze)
        face(c,pose.face,pose.amount,pose.gaze)
        outlined(c,beak,0xfff6a52b.toInt())
        paint.color=0xff19191d.toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f
        path.reset();path.moveTo(65f,68f);path.quadTo(76f,71f,86f,67f);c.drawPath(path,paint)
        paint.color=0xffffe392.toInt();paint.strokeWidth=1.5f
        path.reset();path.moveTo(69f,64f);path.quadTo(76f,61f,81f,64f);c.drawPath(path,paint);paint.style=Paint.Style.FILL
        hair.draw(c);c.restore()
    }
    private fun face(c:Canvas,face:PetFace,opacity:Float,gaze:Float) {
        if(opacity<=.001f) return
        val alpha=(opacity.coerceIn(0f,1f)*255).toInt()
        repeat(2) { i ->
            val x=if(i==0) 61f else 83f;val y=if(i==0) 52f else 53f
            paint.style=Paint.Style.FILL;paint.color=0xfffffff4.toInt();paint.alpha=alpha
            c.drawOval(x-10,y-12,x+10,y+12,paint)
            paint.color=0xff19191d.toInt();paint.alpha=alpha;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.6f;c.drawOval(x-10,y-12,x+10,y+12,paint)
            val shut=(face==PetFace.SMILE || face==PetFace.DELIGHTED || face==PetFace.SLEEPY) || face==PetFace.WINK && i==1 || face==PetFace.CHEEKY && i==0
            if(shut) {
                path.reset();path.moveTo(x-5,y+2);path.quadTo(x,y-5,x+5,y+2);paint.strokeWidth=2f;c.drawPath(path,paint)
            } else if(face==PetFace.LOVE) {
                paint.style=Paint.Style.FILL;paint.color=0xffe96886.toInt();paint.alpha=alpha
                path.reset();path.moveTo(x,y+6);path.cubicTo(x-12,y-2,x-4,y-10,x,y-3);path.cubicTo(x+4,y-10,x+12,y-2,x,y+6);c.drawPath(path,paint)
            } else {
                val px=x+4+gaze*1.8f;val ry=if(face==PetFace.SURPRISED) 7f else 5.5f
                paint.style=Paint.Style.FILL;c.drawOval(px-3.5f,y-ry+2,px+3.5f,y+ry+2,paint)
                paint.color=0xffffffff.toInt();paint.alpha=alpha;c.drawCircle(px-1,y,1.1f,paint)
            }
        }
        paint.alpha=255;paint.style=Paint.Style.FILL
    }
}
