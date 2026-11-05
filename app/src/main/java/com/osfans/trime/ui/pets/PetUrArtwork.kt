/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.graphics.*
import com.osfans.trime.R
import com.osfans.trime.util.appContext

/** Armless base atlas and illustrated arm are decoded once; shoulders share the character transform. */
internal object PetUrArtwork {
    private val bitmap by lazy { BitmapFactory.decodeResource(appContext.resources,R.drawable.pet_xiaomie_rig_base) }
    private val standing by lazy { BitmapFactory.decodeResource(appContext.resources,R.drawable.pet_xiaomie_standing) }
    private val standingSources=arrayOf(Rect(97,11,551,613),Rect(693,11,1148,612),Rect(96,645,552,1248),Rect(692,645,1151,1248))
    private val standingMask=Path().apply { fillType=Path.FillType.EVEN_ODD;addRect(0f,0f,128f,128f,Path.Direction.CW);addRect(47f,96f,84f,128f,Path.Direction.CW) }
    private val standingTarget=RectF(18f,3f,110f,125f)
    val arm by lazy { BitmapFactory.decodeResource(appContext.resources,R.drawable.pet_xiaomie_arm) }
    private val source=Rect()
    private val target=RectF(0f,0f,128f,128f)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Register each facial cell to the same neck/shoulder origin, rather than clipping at the face.
    private val offsets=floatArrayOf(-4f,0f,-4f,-1.5f)
    fun draw(canvas:Canvas,pose:PetPose,step:PetMiniStance?=null) {
        val frame=if(pose.amount<.25f) 0 else when(pose.face) {
            PetFace.SMILE,PetFace.DELIGHTED -> 1
            PetFace.WINK,PetFace.CHEEKY,PetFace.LOVE -> 2
            PetFace.FOCUSED,PetFace.SURPRISED -> 3
            else -> 0
        }
        if(step!=null) {
            // Separate standing legs at runtime, so the free foot tucks up during each hop.
            repeat(2) { i ->
                val left=i==0;val supporting=left==step.leftFoot
                canvas.save()
                if(!supporting) { canvas.translate(0f,-step.lift*8);canvas.rotate((if(left) -1 else 1)*step.lift*12,if(left) 58f else 72f,96f) }
                canvas.clipRect(if(left) 47f else 65f,94f,if(left) 65f else 84f,128f)
                canvas.drawBitmap(standing,standingSources[frame],standingTarget,paint);canvas.restore()
            }
            canvas.save();canvas.clipPath(standingMask);canvas.drawBitmap(standing,standingSources[frame],standingTarget,paint);canvas.restore()
            return
        }
        val w=bitmap.width/2;val h=bitmap.height/2
        source.set(frame%2*w,frame/2*h,frame%2*w+w,frame/2*h+h)
        canvas.save();canvas.translate(offsets[frame],0f);canvas.drawBitmap(bitmap,source,target,paint);canvas.restore()
    }
}
