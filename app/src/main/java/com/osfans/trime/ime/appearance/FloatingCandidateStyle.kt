/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import android.graphics.Color
import com.osfans.trime.data.keymap.FontSpec
import kotlinx.serialization.Serializable

/** Optional per-layout overrides. A missing/disabled configuration keeps legacy behavior. */
@Serializable
data class FloatingCandidateStyle(
    val enabled: Boolean = true,
    val panel: SurfaceStyle = SurfaceStyle(radius=16f),
    val panelEffects: KeyEffects = KeyEffects(),
    val normal: SurfaceStyle = SurfaceStyle(),
    val highlighted: SurfaceStyle = SurfaceStyle(),
    val itemEffects: KeyEffects = KeyEffects(),
    val pager: SurfaceStyle = SurfaceStyle(),
    val ball: SurfaceStyle = SurfaceStyle(radius=24f),
    val fonts: Map<String, FontSpec> = emptyMap(),
    val colors: Map<String, String> = emptyMap(),
    val opacity: Int = 100,
    val paddingHorizontal: Int = 8,
    val paddingVertical: Int = 6,
    val itemPaddingHorizontal: Int = 6,
    val itemPaddingVertical: Int = 4,
    val lineSpacing: Int = 0,
    val minWidth: Int = 128,
    val maxWidthPercent: Int = 90,
    val elevation: Int = 6,
    val itemElevation: Int = 0,
    val shadowColor: String = "#55000000",
    val blurRadius: Int = 16,
    val glassOpacity: Int = 25,
    val showLabels: Boolean = true,
    val showComments: Boolean = true,
    val showPreedit: Boolean = true,
    val pagination: String = "inherit",
    val arrowSize: Int = 20,
    val ballSize: Int = 44,
    val ballLabel: String = "●",
) {
    fun sanitized() = copy(
        panel=panel.sanitized(), normal=normal.sanitized(), highlighted=highlighted.sanitized(), pager=pager.sanitized(), ball=ball.sanitized().copy(blur=false),
        panelEffects=panelEffects.sanitized().copy(shadow=KeyShadow(), textShadow=KeyTextShadow(), pressScale=0f, pressSink=0f, pressDarken=0, ripple=false),
        itemEffects=itemEffects.sanitized().copy(shadow=KeyShadow()),
        fonts=fonts.filterKeys { it in roles }.mapValues { (_, spec) -> spec.copy(size=spec.size.takeIf { it.isFinite() }?.coerceIn(0f,100f) ?: 0f) },
        colors=colors.filter { (key,value) -> key in colorRoles && (value.isBlank() || runCatching { Color.parseColor(value) }.isSuccess) },
        opacity=opacity.coerceIn(20,100), paddingHorizontal=paddingHorizontal.coerceIn(0,32), paddingVertical=paddingVertical.coerceIn(0,32),
        itemPaddingHorizontal=itemPaddingHorizontal.coerceIn(0,24), itemPaddingVertical=itemPaddingVertical.coerceIn(0,24), lineSpacing=lineSpacing.coerceIn(0,24),
        minWidth=minWidth.coerceIn(48,600), maxWidthPercent=maxWidthPercent.coerceIn(35,100), elevation=elevation.coerceIn(0,24), itemElevation=itemElevation.coerceIn(0,12),
        blurRadius=blurRadius.coerceIn(2,32), glassOpacity=glassOpacity.coerceIn(0,100), arrowSize=arrowSize.coerceIn(12,48), ballSize=ballSize.coerceIn(32,72),
        pagination=pagination.takeIf { it in setOf("inherit","top","bottom") } ?: "inherit", ballLabel=ballLabel.take(16),
    )
    fun color(role: String, fallback: Int): Int = colors[role]?.takeIf { it.isNotBlank() }?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback
    fun size(role: String, fallback: Float) = fonts[role]?.size?.takeIf { it in 1f..100f } ?: fallback
    companion object {
        val roles = linkedMapOf("candidate" to "候选文字", "comment" to "注释", "label" to "序号", "text" to "编码")
        val colorRoles = roles.keys.flatMap { listOf(it, "highlight.$it") }.toSet() + setOf("arrow", "ball")
        fun current(customMode: Boolean): FloatingCandidateStyle? = if(customMode)
            SurfaceAppearance.currentLayout()?.floatingCandidates?.takeIf { it.enabled } else null
    }
}
