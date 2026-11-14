/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.ui.pets.PetFace
import com.osfans.trime.ui.pets.PetInteraction
import com.osfans.trime.ui.pets.PetPortraitView
import com.osfans.trime.ui.pets.PetPose
import com.osfans.trime.ui.pets.PetUrAim
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal data class FallingWord(val text: String, var x: Float, var y: Float,
    var speed: Float, val size: Float, var typed: Int = 0)

/** One frame clock shared by projectiles and the existing layered character artwork. */
internal class TypingGameEffects(context: Context, width: Int, height: Int) {
    private val pet = PetHouse.state.value.companion() ?: PetHouse.Pet("sheep")
    private val portrait = PetPortraitView(context).apply { halo = false; bind(pet) }
    private val attack = TypingGameAttack.forPet(pet.id)
    private val love = attack == TypingGameAttack.HEART
    private val chick = attack == TypingGameAttack.CHICK
    private val pose = PetPose()
    private val arms = PetUrAim()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var clock = 0L
    private var attackAt = -1000L
    private var side = 0f
    private var left = 0f
    private var top = 0f
    var floorY = 0f; private set
    private var originX = 0f
    private var originY = 0f
    init { resize(width, height) }
    fun resize(width: Int, height: Int) {
        side = minOf(width * .32f, height * .25f)
        left = (width - side) / 2f; top = height - side - 8f
        floorY = top + side * .08f
        originX = width / 2f; originY = top + side * .42f
    }
    private data class Shot(val target: FallingWord, val finishing: Boolean, val born: Long)
    private data class Burst(val x: Float, val y: Float, val born: Long)
    private val shots = mutableListOf<Shot>()
    private val bursts = mutableListOf<Burst>()
    var launches = 0; private set
    var impacts = 0; private set
    val heartAttacks get() = love
    val chickAttacks get() = chick
    val activeShots get() = shots.size

    fun fire(target: FallingWord, finishing: Boolean) {
        // Only partial-word cosmetic shots can be discarded. A finishing shot always
        // reaches its target, so rapid multi-character commits cannot lose a score.
        if (shots.size >= 24) shots.indexOfFirst { !it.finishing }.takeIf { it >= 0 }?.let(shots::removeAt)
        shots.add(Shot(target, finishing, clock)); attackAt = clock; launches++
    }
    fun update(dt: Long, hit: (FallingWord, Boolean) -> Unit) {
        clock += dt
        val iterator = shots.iterator()
        while (iterator.hasNext()) {
            val shot = iterator.next()
            if (clock - shot.born >= 320) {
                iterator.remove(); impacts++
                if (bursts.size == 32) bursts.removeAt(0)
                bursts.add(Burst(shot.target.x, shot.target.y - shot.target.size * .35f, clock))
                hit(shot.target, shot.finishing)
            }
        }
        bursts.removeAll { clock - it.born > 360 }
    }
    fun reset() { shots.clear(); bursts.clear(); clock = 0; attackAt = -1000; launches = 0; impacts = 0 }
    fun draw(canvas: Canvas) {
        // The ground line leaves a protected stage for the fighter below the words.
        paint.color = 0x386acde3
        canvas.drawRoundRect(16f, floorY, (left * 2 + side) - 16f, floorY + 2f, 1f, 1f, paint)
        paint.color = 0x286acde3
        canvas.drawOval(left + side * .12f, top + side * .9f, left + side * .88f, top + side * .99f, paint)
        val phase = ((clock - attackAt) / 420f).coerceIn(0f, 1f)
        val pulse = sin(phase * PI).toFloat()
        pose.reset(); pose.phase = phase; pose.amount = pulse
        pose.face = if (love) PetFace.LOVE else PetFace.FOCUSED
        pose.interaction = if (love) PetInteraction.LOVE else PetInteraction.PLAY
        pose.y = if (chick) 0f else -pulse * 5f; pose.angle = if (chick) 0f else pulse * (if (love) -4f else 3f)
        pose.squash = pulse * .025f; pose.backAngle = -pose.angle
        arms.leftActive = true; arms.rightActive = true
        arms.leftAngle = 20f + pulse * 120f; arms.rightAngle = -20f - pulse * 120f
        canvas.save(); canvas.translate(left, top)
        portrait.drawAt(canvas, side, side, pose, urAim = arms)
        canvas.restore()
        for (shot in shots) {
            val t = ((clock - shot.born) / 320f).coerceIn(0f, 1f)
            val endY = shot.target.y - shot.target.size * .35f
            val x = originX + (shot.target.x - originX) * t + sin(t * PI).toFloat() * (if (love) 12 else -8)
            val y = originY + (endY - originY) * t
            val radius = side * (if (chick) .06f else .045f)
            paint.color = when { chick -> 0xffffd04a.toInt(); love -> 0xffff7cac.toInt(); else -> 0xffa6ecff.toInt() }
            paint.alpha = 42
            canvas.drawCircle(x, y, radius * 2.1f, paint)
            paint.alpha = 255
            when {
                chick -> drawChick(canvas, x, y, radius, sin(t * PI * 4).toFloat() * 12f)
                love -> heart(canvas, x, y, radius)
                else -> canvas.drawCircle(x, y, radius, paint)
            }
            paint.color = 0xd0ffffff.toInt(); canvas.drawCircle(x - radius * .23f, y - radius * .24f, radius * .22f, paint)
        }
        for (burst in bursts) {
            val t = (clock - burst.born) / 360f
            paint.color = when { chick -> 0xffffdb71.toInt(); love -> 0xffff9bc0.toInt(); else -> 0xffc4f1ff.toInt() }
            paint.alpha = ((1 - t) * 230).toInt().coerceIn(0, 255)
            repeat(8) { i ->
                val a = i * PI / 4
                val radius = side * (.025f + .19f * t)
                val x = burst.x + cos(a).toFloat() * radius
                val y = burst.y + sin(a).toFloat() * radius
                if (love && i % 2 == 0) heart(canvas, x, y, side * .018f * (1 - t))
                else canvas.drawCircle(x, y, side * .012f * (1 - t), paint)
            }
        }
        paint.alpha = 255
    }
    /** A tiny yellow bird silhouette, with its own beak, eyes and flapping wings. */
    private fun drawChick(c:Canvas,x:Float,y:Float,r:Float,tilt:Float) {
        c.save();c.translate(x,y);c.rotate(tilt)
        paint.color=0xffffd04a.toInt();paint.alpha=255
        c.drawOval(-r*.85f,-r*.35f,r*.85f,r,paint)
        c.drawCircle(0f,-r*.45f,r*.83f,paint)
        paint.color=0xffffe58b.toInt()
        c.drawOval(-r*1.25f,-r*.05f,-r*.5f,r*.5f,paint)
        c.drawOval(r*.5f,-r*.05f,r*1.25f,r*.5f,paint)
        paint.color=0xff272125.toInt()
        c.drawCircle(-r*.3f,-r*.5f,r*.105f,paint);c.drawCircle(r*.3f,-r*.5f,r*.105f,paint)
        paint.color=0xfff3a126.toInt()
        path.reset();path.moveTo(-r*.2f,-r*.2f);path.lineTo(r*.2f,-r*.2f);path.lineTo(0f,r*.05f);path.close();c.drawPath(path,paint)
        c.drawOval(-r*.48f,r*.86f,-r*.04f,r*1.07f,paint);c.drawOval(r*.04f,r*.86f,r*.48f,r*1.07f,paint)
        c.restore()
    }
    private fun heart(canvas: Canvas, x: Float, y: Float, r: Float) {
        path.reset(); path.moveTo(x, y + r)
        path.cubicTo(x - r * 2, y - r * .2f, x - r * .7f, y - r * 1.7f, x, y - r * .45f)
        path.cubicTo(x + r * .7f, y - r * 1.7f, x + r * 2, y - r * .2f, x, y + r)
        canvas.drawPath(path, paint)
    }
}
