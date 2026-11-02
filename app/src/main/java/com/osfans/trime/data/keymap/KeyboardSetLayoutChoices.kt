/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

object KeyboardSetLayoutChoices {
    data class Choice(val id: String, val label: String)
    fun items(root: String, pages: Map<String, CustomKeyboards.CustomKeyboard>, order: List<String>): List<Choice> {
        val helpers = setOf("en", "num", "layouts", "themes", "hub")
        return order.mapNotNull { id ->
            val page = pages[id] ?: return@mapNotNull null
            val suffix = id.removePrefix(root + "_")
            if(id != root && (suffix in helpers || suffix.startsWith("symbols") || suffix.startsWith("cat_") || suffix.startsWith("emoji"))) return@mapNotNull null
            val role = KeyboardSets.pageRole(id, root, page)
            if(role == "en" || role == "num") return@mapNotNull null
            Choice(id, KeyboardSetOperations.layoutNames[role]?.let { if(role == "zh_26") "26键 主键盘" else it }
                ?: page.name.removePrefix(pages[root]?.name.orEmpty()).trim(' ', '·').ifBlank { page.name })
        }
    }
}
