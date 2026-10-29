/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.*
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.data.theme.AssetBitmapDrawable
import com.osfans.trime.data.theme.ThemeScope

object FloatingCandidateRenderer {
    fun base(scope: ThemeScope, style: FloatingCandidateStyle): Drawable? = style.panel.background.let { raw ->
        if(raw.isBlank()) scope.drawable("text_back_color") else if(raw.startsWith("/")) AssetBitmapDrawable(raw)
        else ColorDrawable(runCatching { Color.parseColor(raw) }.getOrDefault(Color.TRANSPARENT))
    }
    fun panel(view: View, scope: ThemeScope, style: FloatingCandidateStyle, base: Drawable?): Drawable = SurfaceDrawable(
        view, base?.constantState?.newDrawable()?.mutate() ?: base,
        style.panel.copy(background="", borderWidth=style.panel.borderWidth ?: scope.theme.window.border.toFloat()),
        defaultRadius=scope.theme.window.cornerRadius.toFloat(), inset=0f, defaultBorderColor=scope.colors.candidateBorderColor,
        effects=style.panelEffects, glassOpacity=style.glassOpacity)

    fun surface(view: View, style: SurfaceStyle, effects: KeyEffects, fallback: Int, radius: Float, glassOpacity: Int): Drawable {
        val body = SurfaceDrawable(view, ColorDrawable(fallback), style, defaultRadius=radius, inset=0f, effects=effects, glassOpacity=glassOpacity)
        if(!effects.ripple) return body
        val mask=GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=(style.radius ?: radius)*view.resources.displayMetrics.density }
        return RippleDrawable(ColorStateList.valueOf(KeyEffectPainter.color(effects.rippleColor,0x33FFFFFF)),body,mask)
    }
    fun elevation(view: View, radius: Float, elevation: Int, color: String) {
        val density=view.resources.displayMetrics.density
        view.outlineProvider=object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) { outline.setRoundRect(0,0,v.width,v.height,radius*density) }
        }
        view.elevation=elevation*density
        if(Build.VERSION.SDK_INT>=28) {
            val value=KeyEffectPainter.color(color,0x55000000)
            view.outlineAmbientShadowColor=value; view.outlineSpotShadowColor=value
        }
    }
}

/** Text remains crisp above its material; press transforms never change row measurement. */
class FloatingCandidateTextView(context: Context) : android.widget.TextView(context) {
    private var effectConfig: KeyEffects? = null
    fun applyEffects(effects: KeyEffects) {
        if(effectConfig==effects) return
        effectConfig=effects
        val s=effects.textShadow
        val d=resources.displayMetrics.density
        setShadowLayer(if(s.enabled) s.blur*d else 0f,s.offsetX*d,s.offsetY*d,KeyEffectPainter.color(s.color,0x55000000))
        updatePressTransform()
    }
    override fun drawableStateChanged() {
        super.drawableStateChanged()
        updatePressTransform()
    }
    private fun updatePressTransform() {
        val e=effectConfig ?: return
        val scale=if(isPressed) 1f-e.pressScale/100 else 1f
        val y=if(isPressed) e.pressSink*resources.displayMetrics.density else 0f
        animate().cancel()
        if(scaleX!=scale || scaleY!=scale || translationY!=y) {
            val duration=if(Build.VERSION.SDK_INT>=26 && !android.animation.ValueAnimator.areAnimatorsEnabled()) 0L else e.animationMs.toLong()
            animate().scaleX(scale).scaleY(scale).translationY(y).setDuration(duration).start()
        }
    }
    override fun onDetachedFromWindow() { animate().cancel(); scaleX=1f;scaleY=1f;translationY=0f;super.onDetachedFromWindow() }
}
