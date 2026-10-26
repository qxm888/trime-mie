// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

import android.content.ClipData
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

@Entity(tableName = DatabaseBean.TABLE_NAME)
data class DatabaseBean(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val text: String? = null,
    val html: String? = null,
    val type: BeanType = BeanType.TEXT,
    val time: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /**
     * 手动排序索引（自由排序，dahiPLUS 移植）。默认 0，此时回落到按 [time] 降序；
     * 排序规则统一为 `pinned DESC, orderIndex ASC, time DESC`。
     */
    val orderIndex: Int = 0,
    val categoryId: String? = null,
) {
    companion object {
        const val TABLE_NAME = "t_data"

        fun fromClipData(clipData: ClipData): DatabaseBean? {
            val str = clipData.getItemAt(0).text?.toString() ?: return null
            return DatabaseBean(text = str)
        }

        fun fromInputConnection(inputConnection: InputConnection): DatabaseBean? {
            val str = inputConnection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString() ?: return null
            return DatabaseBean(text = str)
        }
    }

    enum class BeanType {
        TEXT,
        HTML,
    }

    class Converters {
        @TypeConverter
        fun beanTypeToInt(beanType: BeanType?): Int? = beanType?.ordinal

        @TypeConverter
        fun intToBeanType(ordinal: Int?): BeanType? = ordinal?.let { BeanType.entries[it] }
    }
}
