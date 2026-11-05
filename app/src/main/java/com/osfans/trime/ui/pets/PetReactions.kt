/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.animation.ValueAnimator
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

internal enum class PetFace { CALM, SMILE, WINK, FOCUSED, SURPRISED, DELIGHTED, CHEEKY, LOVE, SLEEPY }
internal enum class PetGesture { NOD, TILT, EAR_TWITCH, SHIMMY, STRETCH, HOP, SWAY }
enum class PetInteraction { PET, PLAY, EAT, LOVE, GREET, YAWN, LEVEL_UP }
internal class PetPose {
    var face=PetFace.CALM
    var gesture=PetGesture.NOD
    var amount=0f;var phase=0f;var angle=0f;var y=0f;var squash=0f;var backAngle=0f;var gaze=0f
    var interaction:PetInteraction?=null
    fun reset() { face=PetFace.CALM;amount=0f;phase=0f;angle=0f;y=0f;squash=0f;backAngle=0f;gaze=0f;interaction=null }
}

/** One finite reaction track. Facial reactions stay independent from the mini keyboard footstep track. */
internal class PetReactionPlayer(private val host:View) {
    val pose=PetPose()
    private var animation:ValueAnimator?=null
    private var sequence=0
    private var lastTrigger=0L
    private var seed=0
    private var presses=0
    private val neutral=Runnable { pose.reset();host.invalidate() }
    private val rest=Runnable { interact(PetInteraction.YAWN) }
    fun species(id:String) { seed=id.hashCode().and(Int.MAX_VALUE)%997;clear() }
    fun key(code:Int,gaze:Float) {
        host.removeCallbacks(rest);pose.gaze=gaze.coerceIn(-1f,1f);presses++
        val now=SystemClock.uptimeMillis()
        // Keep the key response immediate; change faces at a comfortable visual cadence.
        if(now-lastTrigger<270) return
        lastTrigger=now
        val slot=(sequence++ + seed)%6
        val face=when(code) {
            KeyEvent.KEYCODE_ENTER -> if(slot%2==0) PetFace.DELIGHTED else PetFace.SMILE
            KeyEvent.KEYCODE_DEL -> if(slot%2==0) PetFace.FOCUSED else PetFace.SURPRISED
            KeyEvent.KEYCODE_SPACE -> if(slot%2==0) PetFace.WINK else PetFace.SMILE
            else -> when(slot) { 0 -> PetFace.WINK;1 -> PetFace.SMILE;2 -> PetFace.FOCUSED;3 -> PetFace.CHEEKY;4 -> PetFace.DELIGHTED;else -> PetFace.SURPRISED }
        }
        val gesture=PetGesture.entries[(sequence+seed)%PetGesture.entries.size]
        play(face,gesture,null,620)
    }
    fun released() { if(presses>=5) { host.removeCallbacks(rest);host.postDelayed(rest,1800) } }
    fun interact(interaction:PetInteraction) {
        host.removeCallbacks(rest)
        val slot=sequence++%3
        val face=when(interaction) {
            PetInteraction.PET -> if(slot==0) PetFace.LOVE else if(slot==1) PetFace.WINK else PetFace.SMILE
            PetInteraction.PLAY,PetInteraction.LEVEL_UP -> PetFace.DELIGHTED
            PetInteraction.EAT -> PetFace.CHEEKY
            PetInteraction.LOVE -> PetFace.LOVE
            PetInteraction.GREET -> PetFace.WINK
            PetInteraction.YAWN -> PetFace.SLEEPY
        }
        val gesture=when(interaction) {
            PetInteraction.PLAY -> PetGesture.SHIMMY;PetInteraction.EAT -> PetGesture.NOD
            PetInteraction.LOVE -> PetGesture.TILT;PetInteraction.GREET -> PetGesture.EAR_TWITCH
            PetInteraction.YAWN -> PetGesture.STRETCH;PetInteraction.LEVEL_UP -> PetGesture.HOP
            else -> PetGesture.SWAY
        }
        play(face,gesture,interaction,if(interaction==PetInteraction.YAWN) 1050 else 850)
    }
    private fun play(face:PetFace,gesture:PetGesture,interaction:PetInteraction?,duration:Long) {
        animation?.cancel();host.removeCallbacks(neutral)
        val startAngle=pose.angle;val startY=pose.y;val startSquash=pose.squash;val startBack=pose.backAngle;val startAmount=pose.amount
        pose.face=face;pose.gesture=gesture;pose.interaction=interaction
        if(Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled()) {
            pose.angle=0f;pose.y=0f;pose.squash=0f;pose.backAngle=0f;pose.amount=1f
            host.invalidate();host.postDelayed(neutral,420);return
        }
        val side=if(sequence%2==0) 1f else -1f
        animation=ValueAnimator.ofFloat(0f,1f).apply {
            this.duration=duration;interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t=it.animatedValue as Float;val wave=sin(t*PI).toFloat();val wiggle=sin(t*PI*2).toFloat()
                pose.phase=t
                val enter=(t/.18f).coerceAtMost(1f);val leave=((1-t)/.2f).coerceAtMost(1f)
                pose.amount=(startAmount*(1-enter)+enter)*leave
                pose.angle=startAngle*(1-t)+when(gesture) { PetGesture.TILT -> side*5*wave;PetGesture.SHIMMY -> side*4*wiggle;PetGesture.SWAY -> side*3.3f*wave;else -> 0f }
                pose.y=startY*(1-t)+when(gesture) { PetGesture.HOP -> -4.5f*wave;PetGesture.NOD -> 2.5f*wave;PetGesture.STRETCH -> -2*wave;else -> -wave }
                pose.squash=startSquash*(1-t)+when(gesture) { PetGesture.STRETCH -> -.045f*wave;PetGesture.HOP -> .035f*wiggle;PetGesture.NOD -> .025f*wave;else -> 0f }
                pose.backAngle=startBack*(1-t)+when(gesture) { PetGesture.EAR_TWITCH -> side*5*wiggle;PetGesture.SHIMMY -> -side*4*wiggle;else -> -pose.angle*.6f }
                host.invalidate()
            }
            start()
        }
    }
    fun clear() {
        animation?.cancel();animation=null;host.removeCallbacks(neutral);host.removeCallbacks(rest)
        presses=0;lastTrigger=0;pose.reset();host.invalidate()
    }
}
