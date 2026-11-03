/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.pets.PetCatalog
import com.osfans.trime.data.pets.PetHouse
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Layered original cartoons: the source artwork, face and pose share one renderer everywhere. */
class PetPortraitView(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val star=Path()
    private var back:Drawable?=null
    private var body:Drawable?=null
    private var pet=PetHouse.Pet("sheep")
    private var color=PetCatalog.get("sheep").color
    private var eyeShader:Shader=makeEyeShader(64f)
    private var frog=false
    private var beak=false
    private var muzzle=false
    private var blushY=77f
    private val player=PetReactionPlayer(this)
    var halo=true
    init { bind(pet) }
    private fun makeEyeShader(y:Float)=LinearGradient(0f,y-6,0f,y+7,intArrayOf(0xff302b35.toInt(),0xff493239.toInt(),0xff876466.toInt()),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
    fun bind(value:PetHouse.Pet) {
        val changed=pet.id!=value.id
        pet=value;val species=PetCatalog.get(value.id);color=species.color
        frog=value.id=="frog"
        beak=value.id in listOf("bird","duck","penguin","crane","phoenix")
        muzzle=value.id in listOf("pig","cow","koala")
        blushY=if(value.id=="frog" || value.id=="jellyfish") 74f else 77f
        back=AppCompatResources.getDrawable(context,PetArtwork.back(value.id))?.mutate()?.also { it.setBounds(0,0,128,128) }
        body=AppCompatResources.getDrawable(context,PetArtwork.body(value.id))?.mutate()?.also { it.setBounds(0,0,128,128) }
        eyeShader=makeEyeShader(if(value.id=="frog") 31.5f else 64f)
        if(changed) player.species(value.id)
        contentDescription="${value.nickname.ifBlank { species.name }}，${PetCatalog.rarities[species.rarity]}，${PetCatalog.level(value.exp)} 级"
        invalidate()
    }
    override fun onDraw(canvas:Canvas) { super.onDraw(canvas);drawAt(canvas,width.toFloat(),height.toFloat(),player.pose) }
    internal fun drawAt(canvas:Canvas,width:Float,height:Float,pose:PetPose) {
        val side=minOf(width,height);canvas.save();canvas.translate((width-side)/2,(height-side)/2);canvas.scale(side/128f,side/128f)
        resetPaint()
        if(halo) { paint.color=(color and 0xffffff) or 0x28000000;canvas.drawCircle(64f,65f,53f,paint) }
        if(!PetPremiumFaces.handles(pet.id)) { paint.color=0x162f3742;canvas.drawOval(30f,109f,98f,117f,paint) }
        canvas.save();canvas.translate(0f,pose.y);canvas.rotate(pose.angle,64f,87f);canvas.scale(1+pose.squash,1-pose.squash,64f,88f)
        canvas.save();if(pet.id!="frog") canvas.rotate(pose.backAngle,64f,75f);back?.draw(canvas);canvas.restore()
        body?.draw(canvas)
        face(canvas,PetFace.CALM,1-pose.amount,pose.gaze)
        if(pose.amount>.001f) face(canvas,pose.face,pose.amount,pose.gaze)
        accessories(canvas)
        canvas.restore()
        effects(canvas,pose)
        if(pet.stage>0) { resetPaint();paint.color=0xffc6a470.toInt();repeat(pet.stage) { drawStar(canvas,56f+it*8,120f,2.2f) } }
        canvas.restore()
    }
    private fun resetPaint() { paint.shader=null;paint.alpha=255;paint.style=Paint.Style.FILL;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND }
    private fun face(canvas:Canvas,face:PetFace,opacity:Float,gaze:Float) {
        if(PetPremiumFaces.handles(pet.id)) { PetPremiumFaces.draw(canvas,pet.id,face,opacity,gaze);return }
        if(opacity<=.001f) return
        val alpha=(opacity.coerceIn(0f,1f)*255).toInt()
        val eyeY=if(frog) 31.5f else 64f
        resetPaint();paint.alpha=alpha
        repeat(2) { i ->
            val x=if(frog) 46f+i*36 else 48f+i*32
            if(frog) { paint.color=0xfffffdf5.toInt();paint.alpha=alpha;canvas.drawOval(x-5.5f,23.5f,x+5.5f,36.5f,paint) }
            val wink=(face==PetFace.WINK && i==1) || (face==PetFace.CHEEKY && i==0)
            when {
                face==PetFace.LOVE -> { paint.color=0xffc98092.toInt();paint.alpha=alpha;drawHeart(canvas,x,eyeY,4.8f) }
                wink || face==PetFace.SMILE || face==PetFace.DELIGHTED || face==PetFace.SLEEPY -> {
                    paint.color=0xff49353e.toInt();paint.alpha=alpha;paint.style=Paint.Style.STROKE;paint.strokeWidth=if(frog) 1.8f else 2.1f
                    path.reset();path.moveTo(x-4,eyeY+1)
                    path.quadTo(x,eyeY+(if(face==PetFace.SLEEPY) 4 else -5),x+4,eyeY+1);canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
                }
                else -> {
                    val rx=if(frog) 2.8f else 4.5f
                    val ry=when { face==PetFace.SURPRISED -> 7.1f;face==PetFace.FOCUSED -> 4.1f;frog -> 3.8f;else -> 6.1f }
                    val gx=gaze*.9f
                    paint.shader=eyeShader;paint.alpha=alpha;canvas.drawOval(x-rx+gx,eyeY-ry,x+rx+gx,eyeY+ry,paint);paint.shader=null
                    paint.color=0xffffffff.toInt();paint.alpha=alpha;canvas.drawOval(x-2.7f+gx,eyeY-4,x-.1f+gx,eyeY-.6f,paint)
                    paint.color=0xffffefd6.toInt();paint.alpha=alpha;canvas.drawCircle(x+1.5f+gx,eyeY+2.6f,.85f,paint)
                    if(face==PetFace.FOCUSED) {
                        paint.color=0xff66505a.toInt();paint.alpha=alpha;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.2f
                        canvas.drawLine(x-5,eyeY-6,x+4,eyeY-5,paint);paint.style=Paint.Style.FILL
                    }
                }
            }
        }
        // Blush and small hatch marks stay visible against every species' skin tone.
        repeat(2) { i ->
            val x=39f+i*50
            paint.color=0xffe4acb0.toInt();paint.alpha=(alpha*.76f).toInt();canvas.drawOval(x-7,blushY-3.3f,x+7,blushY+3.3f,paint)
            paint.color=0xffffdfd7.toInt();paint.alpha=(alpha*.85f).toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f
            canvas.drawLine(x-3,blushY-1,x-2,blushY+1.2f,paint);canvas.drawLine(x+1,blushY-1,x+2,blushY+1.2f,paint);paint.style=Paint.Style.FILL
        }
        val mouthY=when { pet.id=="jellyfish" -> 74f;frog -> 78f;muzzle -> 85f;else -> 83f }
        paint.color=0xff49353e.toInt();paint.alpha=alpha
        if(pet.id=="pig" || pet.id=="cow") {
            val dx=if(pet.id=="pig") 4f else 7f
            paint.color=0xffb77f7b.toInt();paint.alpha=alpha
            canvas.drawOval(64-dx-1.7f,71f,64-dx+1.7f,76f,paint);canvas.drawOval(64+dx-1.7f,71f,64+dx+1.7f,76f,paint)
        } else if(!beak && !frog && pet.id!="jellyfish" && pet.id!="koala") {
            paint.color=0xff49353e.toInt();paint.alpha=alpha
            path.reset();path.moveTo(60.5f,76f);path.quadTo(64f,73.3f,67.5f,76f);path.quadTo(67f,79f,64f,80f);path.quadTo(61f,79f,60.5f,76f);canvas.drawPath(path,paint)
            paint.color=0xffb89499.toInt();paint.alpha=alpha;canvas.drawOval(61f,75f,64f,76.1f,paint)
        }
        paint.color=0xff49353e.toInt();paint.alpha=alpha
        if(face==PetFace.DELIGHTED || face==PetFace.SURPRISED || face==PetFace.CHEEKY || face==PetFace.SLEEPY) {
            val rx=if(face==PetFace.SURPRISED || face==PetFace.SLEEPY) 3.8f else 5.8f
            val ry=if(face==PetFace.SLEEPY) 6.7f else 5f
            canvas.drawOval(64-rx,mouthY-ry,64+rx,mouthY+ry,paint)
            paint.color=0xffe9a6b0.toInt();paint.alpha=alpha;canvas.drawOval(60.8f,mouthY+1,67.2f,mouthY+4.6f,paint)
            if(face==PetFace.CHEEKY) canvas.drawRoundRect(63f,mouthY+2,69f,mouthY+8,3f,3f,paint)
        } else {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.7f
            path.reset();path.moveTo(if(beak) 59f else 57f,if(beak) 80f else mouthY-1)
            path.quadTo(64f,if(beak) 83f else mouthY+5,if(beak) 69f else 71f,if(beak) 80f else mouthY-1);canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
        }
        resetPaint()
    }
    private fun accessories(canvas:Canvas) {
        resetPaint()
        when(pet.accessory) {
            "scarf" -> { paint.color=0xffb87886.toInt();canvas.drawRoundRect(40f,86f,88f,94f,4f,4f,paint);canvas.drawRoundRect(74f,90f,84f,109f,3f,3f,paint);paint.color=0xffd7a1ab.toInt();canvas.drawRoundRect(43f,87f,84f,89f,1f,1f,paint) }
            "star" -> { paint.color=0xffd2b777.toInt();drawStar(canvas,94f,43f,10f) }
            "crown" -> { paint.color=0xffd2b777.toInt();path.reset();path.moveTo(48f,30f);path.lineTo(45f,13f);path.lineTo(57f,20f);path.lineTo(64f,9f);path.lineTo(71f,20f);path.lineTo(83f,13f);path.lineTo(80f,30f);path.close();canvas.drawPath(path,paint);paint.color=0xffffe5a4.toInt();canvas.drawRoundRect(49f,25f,79f,28f,1f,1f,paint) }
        }
    }
    private fun effects(canvas:Canvas,pose:PetPose) {
        if(pose.amount<.01f) return
        resetPaint();paint.alpha=(pose.amount*230).toInt()
        val drift=pose.phase*10
        when {
            pose.face==PetFace.LOVE || pose.interaction==PetInteraction.PET -> {
                paint.color=0xffd890a2.toInt();paint.alpha=(pose.amount*230).toInt();drawHeart(canvas,108f,31f-drift,5f);drawHeart(canvas,20f,48f-drift*.7f,3.7f)
            }
            pose.interaction==PetInteraction.EAT -> {
                paint.color=0xffd6b17d.toInt();paint.alpha=(pose.amount*230).toInt();canvas.drawCircle(109f,35f-drift,6f,paint)
                paint.color=0xff8b6955.toInt();paint.alpha=(pose.amount*230).toInt();canvas.drawCircle(107f,33f-drift,1f,paint);canvas.drawCircle(111f,37f-drift,1f,paint)
            }
            pose.face==PetFace.DELIGHTED || pose.interaction==PetInteraction.GREET -> {
                paint.color=0xffd7b874.toInt();paint.alpha=(pose.amount*230).toInt();drawStar(canvas,111f,35f-drift,4.5f);drawStar(canvas,18f,55f-drift*.6f,3f)
            }
            pose.face==PetFace.SLEEPY -> {
                paint.color=0xffb6c4db.toInt();paint.alpha=(pose.amount*190).toInt();paint.style=Paint.Style.STROKE;paint.strokeWidth=1f
                canvas.drawCircle(108f,33f-drift,4f+pose.phase*2,paint)
            }
        };resetPaint()
    }
    private fun drawHeart(canvas:Canvas,x:Float,y:Float,r:Float) {
        path.reset();path.moveTo(x,y+r);path.cubicTo(x-r*2,y-r*.2f,x-r*.7f,y-r*1.7f,x,y-r*.45f)
        path.cubicTo(x+r*.7f,y-r*1.7f,x+r*2,y-r*.2f,x,y+r);canvas.drawPath(path,paint)
    }
    private fun drawStar(canvas:Canvas,x:Float,y:Float,r:Float) {
        star.reset();repeat(10) { i -> val a=(i*36-90)*PI/180;val radius=if(i%2==0) r else r*.44f
            val px=x+cos(a).toFloat()*radius;val py=y+sin(a).toFloat()*radius
            if(i==0) star.moveTo(px,py) else star.lineTo(px,py)
        };star.close();canvas.drawPath(star,paint)
    }
    fun react(interaction:PetInteraction=PetInteraction.PET) { player.interact(interaction) }
    override fun onDetachedFromWindow() { player.clear();super.onDetachedFromWindow() }
}
