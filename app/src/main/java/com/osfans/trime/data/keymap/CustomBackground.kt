/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import kotlinx.serialization.Serializable

/** Exactly one background source. Unused fields are cleared before storage. */
@Serializable
data class CustomBackground(
    val type: Type = Type.COLOR,
    val color: String = "",
    val image: String = "",
) {
    @Serializable enum class Type { COLOR, TRANSPARENT, IMAGE, VIDEO }

    fun normalized(defaultColor: String = KEY_COLOR): CustomBackground = when (type) {
        Type.TRANSPARENT -> CustomBackground(Type.TRANSPARENT)
        Type.VIDEO -> if (image.isBlank()) CustomBackground(Type.TRANSPARENT) else CustomBackground(Type.VIDEO, image = image)
        Type.IMAGE -> if (image.isBlank()) CustomBackground(Type.TRANSPARENT) else CustomBackground(Type.IMAGE, image = image)
        Type.COLOR -> {
            val hex = color.uppercase()
            when {
                hex == "TRANSPARENT" || (hex.length == 9 && hex.startsWith("#00")) -> CustomBackground(Type.TRANSPARENT)
                HEX.matches(hex) -> CustomBackground(Type.COLOR, color = hex)
                else -> CustomBackground(Type.COLOR, color = defaultColor)
            }
        }
    }

    fun drawable(context: Context, targetWidth: Int): Drawable {
        val bg = normalized()
        return when (bg.type) {
            Type.TRANSPARENT -> ColorDrawable(Color.TRANSPARENT)
            Type.COLOR -> ColorDrawable(Color.parseColor(bg.color))
            Type.VIDEO -> ColorDrawable(Color.BLACK)
            Type.IMAGE -> com.osfans.trime.data.theme.AssetBitmapDrawable(bg.image)
        }
    }

    fun textColor(fallback: CustomBackground? = null): String {
        if (type == Type.TRANSPARENT && fallback != null) return fallback.textColor()
        if (type != Type.COLOR) return "#FF111827"
        val rgb = color.removePrefix("#").takeLast(6).toLongOrNull(16) ?: return "#FF111827"
        val lightness = ((rgb shr 16) and 255) * 299 + ((rgb shr 8) and 255) * 587 + (rgb and 255) * 114
        return if (lightness < 140000) "#FFFFFFFF" else "#FF111827"
    }

    companion object {
        const val KEY_COLOR = "#FFF8FAFC"
        const val KEYBOARD_COLOR = "#FFE5E7EB"
        private val HEX = Regex("#[0-9A-F]{6}([0-9A-F]{2})?")
        fun fromLegacy(color: String, image: String, defaultColor: String): CustomBackground = when {
            image.isNotBlank() -> CustomBackground(Type.IMAGE, image = image)
            color.isNotBlank() -> CustomBackground(Type.COLOR, color = color).normalized(defaultColor)
            else -> CustomBackground(Type.COLOR, color = defaultColor)
        }
    }
}
