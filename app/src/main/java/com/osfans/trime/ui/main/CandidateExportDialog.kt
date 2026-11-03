/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.core.data.CandidateRecordFormats

object CandidateExportDialog {
    fun show(context: Context, createdWordsOnly: Boolean = false, selected: (CandidateRecordFormats.Format) -> Unit) {
        val formats = CandidateRecordFormats.Format.entries
        MaterialAlertDialogBuilder(context)
            .setTitle(if (createdWordsOnly) "导出全部造词" else "导出候选调整")
            .setItems(formats.map { it.title + if(createdWordsOnly) " · 字词 / 编码" else " · 字词 / 权重" }.toTypedArray()) { _, index -> selected(formats[index]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
