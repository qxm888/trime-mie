/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.util

import android.content.Context
import android.os.Build
import android.os.UserManager

/** Never discard an explicitly device-protected context via applicationContext. */
object BootStorage {
    @androidx.annotation.ChecksSdkIntAtLeast(api=24)
    fun locked(context:Context):Boolean = Build.VERSION.SDK_INT>=24 &&
        (context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked.not()
    fun deviceProtected(context:Context):Boolean = Build.VERSION.SDK_INT>=24 && context.isDeviceProtectedStorage
    fun canScheduleWork(context:Context):Boolean = !deviceProtected(context) && !locked(context)
}

fun storageContext(context:Context):Context = when {
    BootStorage.deviceProtected(context) -> context
    BootStorage.locked(context) -> context.createDeviceProtectedStorageContext()
    else -> context.applicationContext
}
