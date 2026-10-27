/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import kotlinx.serialization.Serializable

/** Layout behavior is stored per keyboard rather than inherited from a YAML theme. */
@Serializable
data class KeyboardOptions(
    val horizontalGap: Int = 4,
    val heightPercent: Int = 100,
    val landscapeHeightPercent: Int = 100,
    val asciiMode: Int = 0,
    val resetAsciiMode: Boolean = false,
    val lock: Boolean = false,
    val asciiKeyboard: String = "",
    val landscapeKeyboard: String = "",
    val uppercaseLabels: Boolean = false,
    val landscapeSplitPercent: Int = 0,
) {
    fun validated(): KeyboardOptions = apply {
        require(horizontalGap in 0..32) { "横向键距须为 0–32 dp" }
        require(heightPercent in 50..200 && landscapeHeightPercent in 50..200) { "高度比例须为 50–200%" }
        require(asciiMode in 0..1) { "初始英文模式须为 0 或 1" }
        require(landscapeSplitPercent in 0..100) { "横屏分割比例须为 0–100%" }
    }
}
