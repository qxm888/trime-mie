/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.content.Context
import com.osfans.trime.data.pets.EldritchCatalog

internal object PetMiniStyle {
    enum class Renderer { STANCE, TENTACLES }
    fun rendererFor(id: String) = if (EldritchCatalog.handles(id)) Renderer.TENTACLES else Renderer.STANCE
    fun create(context: Context, id: String): PetMiniKeyboardBase = when (rendererFor(id)) {
        Renderer.STANCE -> PetMiniKeyboardView(context)
        Renderer.TENTACLES -> PetTentacleMiniView(context)
    }
}
