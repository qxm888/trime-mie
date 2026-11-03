/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import android.util.AtomicFile
import com.osfans.trime.R
import java.io.File
import java.util.zip.ZipInputStream

/** Immutable, original stock material pack. Run on the layout worker, never during drawing. */
internal object BuiltinPremiumAssets {
    private const val PREFIX = "内置材质/精雕一辑/"
    @Synchronized fun install(context: Context): Boolean {
        val root = CustomKeyAssets.root(context).canonicalFile
        val names = context.resources.openRawResource(R.raw.mie_premium_materials_manifest)
            .bufferedReader().use { it.readLines().filter(String::isNotBlank).toSet() }
        require(names.isNotEmpty() && names.all { it.startsWith(PREFIX) && it.endsWith(".png") })
        val marker = File(root, "内置材质/.精雕一辑-v2")
        fun file(name: String): File = File(root, name).canonicalFile.also {
            require(it.path.startsWith(root.path + File.separator)) { "内置材质路径无效" }
        }
        if (marker.isFile && names.all { file(it).isFile }) return false
        val seen = mutableSetOf<String>()
        var installed = false
        var total = 0L
        context.resources.openRawResource(R.raw.mie_premium_materials).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in names && seen.add(entry.name))
                    val target = file(entry.name)
                    // User-edited material files are retained; missing stock files are repaired.
                    val oldStockIcon = target.isFile && entry.name in BuiltinFloatingIconUpgrade.originalHashes &&
                        BuiltinFloatingIconUpgrade.shouldReplace(entry.name, java.security.MessageDigest.getInstance("SHA-256")
                            .digest(target.readBytes()).joinToString("") { "%02x".format(it) })
                    if (!target.isFile || oldStockIcon) {
                        check(target.parentFile?.isDirectory == true || target.parentFile?.mkdirs() == true)
                        val atomic = AtomicFile(target)
                        val output = atomic.startWrite()
                        try {
                            val bytes = zip.copyTo(output)
                            total += bytes
                            require(bytes <= 4 * 1024 * 1024 && total <= 32 * 1024 * 1024)
                            atomic.finishWrite(output)
                            installed = true
                        } catch (error: Exception) {
                            atomic.failWrite(output)
                            throw error
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
        check(seen == names && names.all { file(it).isFile })
        val atomic = AtomicFile(marker)
        val output = atomic.startWrite()
        try { output.write("2\n".toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
        File(root, "内置材质/.精雕一辑-v1").delete()
        return installed
    }
}
