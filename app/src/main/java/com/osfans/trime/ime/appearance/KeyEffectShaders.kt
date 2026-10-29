/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.*

/** Static shaders are rebuilt only when geometry or appearance changes. */
internal class KeyEffectShaders(private val density: Float) {
    private val bounds = RectF()
    private var config: KeyEffects? = null
    val borderBounds = RectF()
    var gradient: Shader? = null; private set
    var border: Shader? = null; private set
    var gloss: Shader? = null; private set
    private var edge: Shader? = null
    private var ripple: Shader? = null
    private val edgeMatrix = Matrix()
    private val rippleMatrix = Matrix()

    fun update(area: RectF, effects: KeyEffects) {
        if (bounds == area && config == effects) return
        bounds.set(area)
        config = effects
        gradient = if (effects.gradient.enabled) KeyEffectPainter.gradientShader(area, effects.gradient) else null
        borderBounds.set(area)
        val borderInset = (effects.borderGradient.width * density).coerceAtLeast(.5f) / 2
        borderBounds.inset(borderInset, borderInset)
        border = if (effects.borderGradient.enabled) effects.borderGradient.let {
            KeyEffectPainter.gradientShader(borderBounds, KeyGradient(enabled=true, startColor=it.startColor, endColor=it.endColor, angle=it.angle))
        } else null
        gloss = if (effects.gloss && effects.glossStrength > 0) LinearGradient(area.left, area.top, area.left, area.top + area.height() * .58f,
            KeyEffectPainter.withAlpha(Color.WHITE, effects.glossStrength * 255 / 100), Color.TRANSPARENT, Shader.TileMode.CLAMP) else null
        edge = if (effects.edge3d) LinearGradient(0f, 0f, 0f, 1f, Color.TRANSPARENT,
            KeyEffectPainter.color(effects.edgeColor, 0x33000000), Shader.TileMode.CLAMP) else null
        ripple = if (effects.ripple) KeyEffectPainter.color(effects.rippleColor, 0x33FFFFFF).let { color ->
            RadialGradient(0f, 0f, 1f, intArrayOf(color, KeyEffectPainter.withAlpha(color, 0)), null, Shader.TileMode.CLAMP)
        } else null
    }

    fun edge(depth: Float): Shader? = edge?.also {
        edgeMatrix.setScale(1f, depth)
        edgeMatrix.postTranslate(0f, bounds.bottom - depth)
        it.setLocalMatrix(edgeMatrix)
    }

    fun ripple(x: Float, y: Float, radius: Float): Shader? = ripple?.also {
        rippleMatrix.setScale(radius, radius)
        rippleMatrix.postTranslate(x, y)
        it.setLocalMatrix(rippleMatrix)
    }
}
