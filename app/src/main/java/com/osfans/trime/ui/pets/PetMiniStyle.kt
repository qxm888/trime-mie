/* SPDX-License-Identifier: GPL-3.0-or-later OR LicenseRef-Proprietary
 * 版权人（qxm888 / luminous-spirit）对本文件保留按专有条款另行授权的权利。
 * 本文件在上游同文（osfans/trime）与 CatTrime 中均不存在，系咩版独立创作。
 */
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
