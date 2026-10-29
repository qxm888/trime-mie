/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.core.data

import android.content.Context
import java.io.File

object MieUserDirectory {
    fun directory(context: Context): File {
        check(DirectExternalStorage.hasPermission(context)) { "请先在数据位置设置中授予共享存储访问权限" }
        val prefs=androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val root=if(prefs.getString("profile_data_storage_mode","DIRECT_EXTERNAL")=="DIRECT_EXTERNAL")
            File(prefs.getString("profile_direct_external_path",DirectExternalStorage.defaultPath)!!)
        else File(DirectExternalStorage.defaultPath)
        return File(root,"mie").apply { check(mkdirs() || isDirectory) { "无法访问 rime/mie 目录" } }
    }
}
