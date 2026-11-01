/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.osfans.trime.core.data.MieUserDirectory
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.util.BootStorage
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText
import java.io.File

/** Real data lives in .mie. The compatibility symlink is created on private
 * storage, where symlinks are supported. Atomic writes always use the real file. */
object KeyboardLayoutStorage {
    private fun alias(context: Context) = File(context.filesDir, "custom_keyboards.json")
    private fun local(context: Context) = File(context.filesDir, "custom_keyboards.local.json")
    private fun linked(file: File) = runCatching { OsConstants.S_ISLNK(Os.lstat(file.path).st_mode) }.getOrDefault(false)
    @Synchronized fun resolve(context: Context): File {
        val old = alias(context)
        val fallback = local(context)
        // Recover an interrupted legacy AtomicFile write before replacing the
        // old regular file with a symlink.
        if (!linked(old) && File(old.path + ".bak").isFile) old.readAtomicText()
        if (BootStorage.deviceProtected(context) || BootStorage.locked(context) || !UserDataManager.isStorageAvailable()) {
            if (!fallback.exists() && old.isFile && !linked(old)) old.readAtomicText()?.let(fallback::writeAtomicText)
            return fallback
        }
        return runCatching {
            val kind = when (context.packageName) {
                "com.osfans.trime.mie" -> "键盘"
                "com.osfans.trime.debug.v2" -> "键盘/咩2"
                else -> "键盘/${context.packageName}"
            }
            val folder = File(MieUserDirectory.directory(context), kind).apply { check(mkdirs() || isDirectory) }
            val target = File(folder, "键盘布局.json")
            val pending = File(context.filesDir, "keyboard-layouts.pending")
            if (File(target.path + ".bak").isFile) target.readAtomicText()
            val sources = listOf(old.takeIf { !linked(it) }, fallback.takeIf { !target.exists() || pending.exists() }).filterNotNull().filter { it.isFile }
            val latest = fallback.takeIf { pending.exists() && it.isFile } ?: sources.maxByOrNull { it.lastModified() }
            if (latest != null && (!target.exists() || pending.exists() || latest.lastModified() > target.lastModified())) {
                latest.readAtomicText()?.let(target::writeAtomicText)
            }
            if (!linked(old) || runCatching { Os.readlink(old.path) }.getOrNull() != target.absolutePath) {
                val temporary = File(context.filesDir, ".keyboard-link-${java.util.UUID.randomUUID()}")
                try {
                    Os.symlink(target.absolutePath, temporary.absolutePath)
                    if (old.isFile && !linked(old)) old.readAtomicText()?.let(fallback::writeAtomicText)
                    Os.rename(temporary.absolutePath, old.absolutePath)
                } finally { temporary.delete() }
            }
            target
        }.getOrElse {
            timber.log.Timber.w(it, "Keyboard directory unavailable; using private recovery file")
            if (!fallback.exists() && old.isFile) old.readAtomicText()?.let(fallback::writeAtomicText)
            fallback
        }
    }
    fun mirror(context: Context, text: String, primary: File) {
        local(context).writeAtomicText(text)
        File(context.filesDir, "keyboard-layouts.pending").let {
            if (primary.absolutePath == local(context).absolutePath) it.createNewFile() else it.delete()
        }
    }
    fun save(context: Context, text: String): File {
        val primary = resolve(context)
        try {
            primary.writeAtomicText(text)
        } catch (error: Exception) {
            if (primary.absolutePath == local(context).absolutePath) throw error
            timber.log.Timber.w(error, "Keyboard directory write failed; preserving changes in recovery file")
            mirror(context, text, local(context))
            return local(context)
        }
        // Failure of a secondary mirror must not mark a committed primary write
        // as failed. Keep the pending marker until recovery has completed.
        runCatching { mirror(context, text, primary) }
            .onFailure { timber.log.Timber.w(it, "Could not update keyboard recovery mirror") }
        return primary
    }
}
