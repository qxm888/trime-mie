/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.baiduskin

import android.content.Context
import android.content.res.Configuration
import kotlinx.serialization.Serializable

@Serializable data class SkinBox(val x: Float = 0f, val y: Float = 0f, val width: Float = 0f, val height: Float = 0f)
@Serializable data class SkinImage(val file: String, val crop: List<Int>)
@Serializable data class SkinTween(
    val type: Int, val from: List<Float>, val to: List<Float>, val duration: Int = 100,
    val repeats: Int = 0, val reverse: Boolean = false, val interpolator: Int = 0,
    val pivot: List<Float> = listOf(50f, 50f), val delay: Int = 0,
)
@Serializable data class SkinLayer(
    val normal: SkinImage? = null, val pressed: SkinImage? = null,
    val animations: List<SkinTween> = emptyList(), val fit: Boolean = false,
)
@Serializable data class ImportedKeyStyle(
    val layers: List<SkinLayer> = emptyList(), val sound: String = "",
    val sourceWidth: Float = 1f, val sourceHeight: Float = 1f,
)
@Serializable data class PlacedSkinKey(
    val id: String, val box: SkinBox, val label: String, val bindings: Map<String, String>,
    val style: ImportedKeyStyle,
)
@Serializable data class ImportedLayout(
    val width: Int, val height: Int, val background: String, val toolbarBackground: String = "",
    val keys: List<PlacedSkinKey>, val preview: String = "",
)
@Serializable data class ImportedSkin(
    val author: String = "", val page: String, val variants: Map<String, ImportedLayout>,
    val sourceName: String = "",
) {
    fun layout(context: Context): ImportedLayout? {
        val config = context.resources.configuration
        val tone = if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        val direction = if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "land" else "port"
        return variants["$tone/$direction"] ?: variants["$tone/port"] ?: variants["light/port"] ?: variants.values.firstOrNull()
    }
}
