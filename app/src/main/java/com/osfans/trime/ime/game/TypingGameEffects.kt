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

internal data class FallingWord(val text: String, val x: Float, var y: Float,
    val speed: Float, val size: Float, var typed: Int = 0)

/** One frame clock shared by projectiles and the existing layered character artwork. */
internal class TypingGameEffects(context: Context, width: Int, height: Int) {
    private val pet = PetHouse.state.value.companion() ?: PetHouse.Pet("sheep")
    private val portrait = PetPortraitView(context).apply { halo = false; bind(pet) }
    private val love = pet.id == "xiaomie"
    private val pose = PetPose()
    private val arms = PetUrAim()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var clock = 0L
    private var attackAt = -1000L
    private val side = minOf(width * .32f, height * .25f)
    private val left = (width - side) / 2f
    private val top = height - side - 8f
    val floorY = top + side * .08f
    private val originX = width / 2f
    private val originY = top + side * .42f
    private data class Shot(val target: FallingWord, val finishing: Boolean, val born: Long)
    private data class Burst(val x: Float, val y: Float, val born: Long)
    private val shots = mutableListOf<Shot>()
    private val bursts = mutableListOf<Burst>()
    var launches = 0; private set
    var impacts = 0; private set
    val heartAttacks get() = love
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
        pose.y = -pulse * 5f; pose.angle = pulse * (if (love) -4f else 3f)
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
            val radius = side * .045f
            paint.color = if (love) 0xffff7cac.toInt() else 0xffa6ecff.toInt()
            paint.alpha = 42
            canvas.drawCircle(x, y, radius * 2.1f, paint)
            paint.alpha = 255
            if (love) heart(canvas, x, y, radius) else canvas.drawCircle(x, y, radius, paint)
            paint.color = 0xd0ffffff.toInt(); canvas.drawCircle(x - radius * .23f, y - radius * .24f, radius * .22f, paint)
        }
        for (burst in bursts) {
            val t = (clock - burst.born) / 360f
            paint.color = if (love) 0xffff9bc0.toInt() else 0xffc4f1ff.toInt()
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
    private fun heart(canvas: Canvas, x: Float, y: Float, r: Float) {
        path.reset(); path.moveTo(x, y + r)
        path.cubicTo(x - r * 2, y - r * .2f, x - r * .7f, y - r * 1.7f, x, y - r * .45f)
        path.cubicTo(x + r * .7f, y - r * 1.7f, x + r * 2, y - r * .2f, x, y + r)
        canvas.drawPath(path, paint)
    }
}
