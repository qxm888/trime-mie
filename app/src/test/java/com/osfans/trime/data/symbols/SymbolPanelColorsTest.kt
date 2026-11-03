/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.symbols
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class SymbolPanelColorsTest : FunSpec({
    test("dark image theme has readable footer and panel text without a parseable key color") {
        val p=SymbolPanelColors.resolve(-1,null,null,null)
        (SymbolPanelColors.contrast(p.text,p.key)>=4.5) shouldBe true
        (SymbolPanelColors.contrast(p.surfaceText,p.surface)>=4.5) shouldBe true
        (p.key ushr 24) shouldBe 255
    }
    test("light theme keeps readable text on white keys") {
        val p=SymbolPanelColors.resolve(0xff243748.toInt(),-1,0xfff1f3f5.toInt(),null)
        p.text shouldBe 0xff243748.toInt()
        (SymbolPanelColors.contrast(p.text,p.key)>=4.5) shouldBe true
    }
    test("different light key and dark surface get independent readable foregrounds") {
        val p=SymbolPanelColors.resolve(-1,-1,0xff111827.toInt(),null)
        (SymbolPanelColors.contrast(p.text,p.key)>=4.5) shouldBe true
        (SymbolPanelColors.contrast(p.surfaceText,p.surface)>=4.5) shouldBe true
    }
})
