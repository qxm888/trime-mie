/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.provider

import java.io.File
import java.io.FileNotFoundException

/** Check both input documents and every destination before touching the filesystem. */
internal object DocumentPaths {
    fun within(root: File, file: File): File {
        val base = root.canonicalFile
        val target = file.canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator))
            throw FileNotFoundException("Document is outside the selected data root")
        return target
    }
    fun child(root: File, parent: File, name: String): File {
        if (name.isBlank() || name == "." || name == ".." || name.any { it == '/' || it == '\\' || it == '\u0000' })
            throw FileNotFoundException("A document name must be a single filename")
        val directory = within(root, parent)
        if (!directory.isDirectory) throw FileNotFoundException("Parent is not a directory")
        return within(root, File(directory, name))
    }
    fun mutable(root: File, file: File): File = within(root, file).also {
        if (it == root.canonicalFile) throw FileNotFoundException("The data root cannot be moved, renamed or deleted")
    }
    fun isChild(root: File, parent: File, child: File): Boolean {
        val p = within(root, parent)
        val c = within(root, child)
        return c.path.startsWith(p.path + File.separator)
    }
}
