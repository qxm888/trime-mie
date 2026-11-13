/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.osfans.trime.data.sentences.SentenceRepository
import kotlinx.coroutines.launch

internal object SentenceManagement {
    fun show(fragment: androidx.fragment.app.Fragment) {
        fragment.lifecycleScope.launch {
            val repository = SentenceRepository.get(fragment.requireContext())
            runCatching {
                repository.awaitIdle()
                repository.counts() to repository.learned()
            }.onSuccess { (counts, entries) ->
                if (!fragment.isAdded) return@onSuccess
                MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle("常用句 ${counts.second} 条 · 待学习 ${counts.first} 条")
                    .setItems(if (entries.isEmpty()) arrayOf("暂无常用句；开启学习后，同一句7天内出现3次即可加入")
                        else entries.map { it.text }.toTypedArray()) { _, index ->
                        if (entries.isNotEmpty()) MaterialAlertDialogBuilder(fragment.requireContext())
                            .setTitle("删除这条常用句？").setMessage(entries[index].text)
                            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                                fragment.lifecycleScope.launch { repository.forget(entries[index].text); show(fragment) }
                            }.show()
                    }
                    .setNegativeButton("关闭", null)
                    .setNeutralButton("清空全部学习数据") { _, _ ->
                        MaterialAlertDialogBuilder(fragment.requireContext()).setTitle("清空全部学习数据？")
                            .setMessage("将删除常用句和未达到3次的待学习记录。")
                            .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                                fragment.lifecycleScope.launch { repository.clear(); show(fragment) }
                            }.show()
                    }.show()
            }.onFailure {
                if (fragment.isAdded) android.widget.Toast.makeText(fragment.requireContext(), "常用句数据读取失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
