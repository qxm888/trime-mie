/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

/** Only replace byte-identical, app-authored v1 icons; preserve user artwork at the same path. */
internal object BuiltinFloatingIconUpgrade {
    val originalHashes = mapOf(
        "内置材质/精雕一辑/云汐·软屿/tb_float.png" to "2595cba507c2beca24540d5951756bf7ccb4237251bde566ce556caf6e26737f",
        "内置材质/精雕一辑/墨绒·格调/tb_float.png" to "c23bc4ba42db4307583b66acae9607bdc90a648aaac3c12d6b6de29e3e17a6b6",
        "内置材质/精雕一辑/深空·钛影/tb_float.png" to "999ece6b60e50d427dac48783e25d8453ccd8f5a822e11e6e4af8703389fd864",
        "内置材质/精雕一辑/竹月·青釉/tb_float.png" to "377af426b53c14d507ebd94e1485af4cc1dd2fffbae41ab3a09665dc6af8cb86",
        "内置材质/精雕一辑/霁银·柔光/tb_float.png" to "be109f03afea192e633cbd4b14e91dfa16af40f2160ada1032fa1da46386dd78",
    )
    fun shouldReplace(name:String,sha256:String) = originalHashes[name]?.equals(sha256,ignoreCase=true)==true
}
