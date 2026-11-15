/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.appearance

import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Three separable sliding boxes approximate a Gaussian in O(width * height), independent of radius. */
internal object GaussianBackdropFilter {
    fun apply(pixels: IntArray, work: IntArray, width: Int, height: Int, radius: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= pixels.size && pixels.size == work.size)
        if (radius <= 0) return
        val size = width * height
        // Average premultiplied channels. Invisible RGB must not bleed into translucent edges.
        for (i in 0 until size) {
            val c = pixels[i]; val a = c ushr 24
            fun premultiply(shift: Int) = ((c ushr shift and 255) * a + 127) / 255
            pixels[i] = (a shl 24) or (premultiply(16) shl 16) or (premultiply(8) shl 8) or premultiply(0)
        }
        val sigma = radius.coerceIn(1, 24) * 0.6 + 0.5
        var lower = floor(sqrt(4.0 * sigma * sigma + 1.0)).toInt()
        if (lower % 2 == 0) lower--
        val upper = lower + 2
        val smallCount = ((12.0 * sigma * sigma - 3 * lower * lower - 12 * lower - 9) / (-4 * lower - 4)).roundToInt().coerceIn(0, 3)
        repeat(3) { box ->
            val r = ((if (box < smallCount) lower else upper) - 1) / 2
            pass(pixels, work, width, height, r, true)
            pass(work, pixels, width, height, r, false)
        }
        for (i in 0 until size) {
            val c = pixels[i]; val a = c ushr 24
            fun unpremultiply(shift: Int) = if (a == 0) 0 else (((c ushr shift and 255) * 255 + a / 2) / a).coerceAtMost(255)
            pixels[i] = (a shl 24) or (unpremultiply(16) shl 16) or (unpremultiply(8) shl 8) or unpremultiply(0)
        }
    }

    private fun pass(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val count = radius * 2 + 1
        val round = count / 2
        for (line in 0 until lines) {
            fun index(position: Int) = if (horizontal) line * width + position.coerceIn(0, width - 1)
                else position.coerceIn(0, height - 1) * width + line
            var a = 0; var r = 0; var g = 0; var b = 0
            for (position in -radius..radius) {
                val c = input[index(position)]
                a += c ushr 24; r += c ushr 16 and 255; g += c ushr 8 and 255; b += c and 255
            }
            for (position in 0 until length) {
                output[index(position)] = ((a + round) / count shl 24) or ((r + round) / count shl 16) or
                    ((g + round) / count shl 8) or ((b + round) / count)
                val old = input[index(position - radius)]; val next = input[index(position + radius + 1)]
                a += (next ushr 24) - (old ushr 24)
                r += (next ushr 16 and 255) - (old ushr 16 and 255)
                g += (next ushr 8 and 255) - (old ushr 8 and 255)
                b += (next and 255) - (old and 255)
            }
        }
    }
}
