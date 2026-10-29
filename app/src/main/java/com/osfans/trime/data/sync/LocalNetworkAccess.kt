/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/** API 37 local network permission. Public internet and older Android remain unaffected. */
object LocalNetworkAccess {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    fun required(context: Context) = Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37
    fun granted(context: Context) = !required(context) || ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
    internal fun local(address: InetAddress): Boolean = !address.isLoopbackAddress && (
        address.isSiteLocalAddress || address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc))
    private fun localName(host: String) = host.endsWith(".local", true) || ('.' !in host && ':' !in host && host != "localhost")
    private fun literal(host: String): Boolean = ':' in host || host.matches(Regex("[0-9.]+"))
    fun checkHost(context: Context, host: String) {
        if (granted(context)) return
        if (localName(host) || (literal(host) && runCatching { local(InetAddress.getByName(host)) }.getOrDefault(false))) throw PermissionRequired()
    }
    /** Called from an IO coroutine by the settings page; never performs DNS on the UI thread. */
    fun needsPermission(context: Context, url: String): Boolean {
        if (granted(context)) return false
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        if (localName(host)) return true
        return runCatching { Dns.SYSTEM.lookup(host).any(::local) }.getOrDefault(false)
    }
    internal fun dns(context: () -> Context) = object : Dns {
        override fun lookup(host: String): List<InetAddress> {
            checkHost(context(), host)
            return Dns.SYSTEM.lookup(host).also { addresses ->
                if (!granted(context()) && addresses.any(::local)) throw PermissionRequired()
            }
        }
    }
    class PermissionRequired : UnknownHostException("缺少局域网访问权限，请在剪贴板同步页面授权后重试")
}
