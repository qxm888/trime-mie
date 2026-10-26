/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import javax.imageio.ImageIO

class LongPngWriterTest : FunSpec({
    test("full long PNG includes every row with correct colours") {
        val file = File.createTempFile("report-", ".png")
        try {
            LongPngWriter(file, 80, 6000).use { out ->
                repeat(6000) { y -> out.writeRow(IntArray(80) { if (y < 3000) 0xffff0080.toInt() else 0xff00aaff.toInt() }) }
            }
            val decoded = ImageIO.read(file)
            decoded.width shouldBe 80
            decoded.height shouldBe 6000
            decoded.getRGB(40, 0) shouldBe 0xffff0080.toInt()
            decoded.getRGB(40, 2999) shouldBe 0xffff0080.toInt()
            decoded.getRGB(40, 3000) shouldBe 0xff00aaff.toInt()
            decoded.getRGB(40, 5999) shouldBe 0xff00aaff.toInt()
        } finally { file.delete() }
    }
})
