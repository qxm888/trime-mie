/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.ToolBar
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ThemeToolbarStoreTest : FunSpec({
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val oldActions = listOf("keyboard_clipboard", "jiantai", "ziji", "fanyi")
    // The actions reported in the screenshot; no device preferences are embedded.
    val legacyJson = """{"version":7,"configured":true,"buttons":[{"action":"keyboard_clipboard"},{"action":"jiantai"},{"action":"ziji"},{"action":"fanyi"}],"hiddenMenus":{"Q":["example-menu"]}}"""
    fun button(action: String, icon: String) = ToolBar.Button(action=action, foreground=ToolBar.Button.Foreground(style=icon))
    // Q's saved source declares Clipboard_window/ic@clipboard, not these old IDs.
    val q = Theme(name="Q",fontConfigId="Q_26键数字",toolBar=ToolBar(buttons=listOf(button("Clipboard_window","ic@clipboard"))),
        presetKeys=mapOf("Clipboard_window" to PresetKey(command="clipboard_window",label="⏍")))
    val owner = Theme(name="matching legacy theme",fontConfigId="legacy-owner",
        toolBar=ToolBar(buttons=oldActions.map { button(it,"ic@clipboard") }),
        presetKeys=oldActions.associateWith { PresetKey(label="图标",command="example") })

    test("foreign legacy actions never replace Q's native toolbar or consume the migration") {
        val store=json.decodeFromString<ThemeToolbarStore>(legacyJson)
        store.adoptLegacy(q) shouldBe false
        store.profile(q).shouldBeNull()
        store.legacyOwner shouldBe ""
        store.buttons.map { it.action } shouldBe oldActions
        store.hiddenMenus["Q"] shouldBe setOf("example-menu")
    }
    test("legacy migration waits for a compatible theme and binds to that source ID once") {
        val store=json.decodeFromString<ThemeToolbarStore>(legacyJson)
        store.adoptLegacy(q) shouldBe false
        store.adoptLegacy(owner) shouldBe true
        store.legacyOwner shouldBe "legacy-owner"
        store.profile(owner)?.buttons?.map { it.action } shouldBe oldActions
        store.adoptLegacy(owner.copy(fontConfigId="another-owner")) shouldBe false
        store.adoptLegacy(q) shouldBe false
        store.profile(q).shouldBeNull()
        store.buttons.map { it.action } shouldBe oldActions
    }
    test("theme edits are independent including themes with identical display names") {
        val store=ThemeToolbarStore()
        val other=q.copy(fontConfigId="Q-other")
        store.set(q,listOf(ToolbarOverrides.Entry("Clipboard_window",icon="Q ICON")))
        store.set(other,listOf(ToolbarOverrides.Entry("Clipboard_window",icon="OTHER ICON")))
        store.profile(q)?.buttons?.single()?.icon shouldBe "Q ICON"
        store.profile(other)?.buttons?.single()?.icon shouldBe "OTHER ICON"
        store.profile(q.copy(name="renamed title")) shouldBe store.profile(q)
    }
    test("an explicitly empty toolbar remains configured and does not empty another theme") {
        val store=ThemeToolbarStore()
        store.set(q,emptyList())
        store.profile(q)?.configured shouldBe true
        store.profile(q)?.buttons shouldBe emptyList()
        store.profile(owner).shouldBeNull()
    }
    test("reset affects one theme and prevents old data from reappearing") {
        val store=json.decodeFromString<ThemeToolbarStore>(legacyJson)
        store.adoptLegacy(owner) shouldBe true
        store.set(q,listOf(ToolbarOverrides.Entry("Clipboard_window")))
        store.reset(owner)
        store.profile(owner)?.configured shouldBe false
        store.adoptLegacy(owner) shouldBe false
        store.profile(q)?.buttons?.single()?.action shouldBe "Clipboard_window"
        store.buttons.map { it.action } shouldBe oldActions
    }
    test("legacy empty configurations and autonomous shortcuts adopt only once") {
        val empty=ThemeToolbarStore(configured=true)
        empty.adoptLegacy(q) shouldBe true
        empty.profile(q)?.configured shouldBe true
        empty.adoptLegacy(owner) shouldBe false
        val portable=ThemeToolbarStore(configured=true,buttons=mutableListOf(ToolbarOverrides.Entry("Control+c",icon="复制")))
        portable.adoptLegacy(q) shouldBe true
        portable.adoptLegacy(owner) shouldBe false
    }
    test("one unresolved legacy entry rejects the whole overlay rather than partially replacing the native bar") {
        val store=ThemeToolbarStore(configured=true,buttons=mutableListOf(
            ToolbarOverrides.Entry("Clipboard_window"),ToolbarOverrides.Entry("jiantai"),
        ))
        store.adoptLegacy(q) shouldBe false
        store.profile(q).shouldBeNull()
        store.buttons.size shouldBe 2
    }
    test("save and restore retain profiles, reset tombstones, legacy backup and menu visibility") {
        val store=json.decodeFromString<ThemeToolbarStore>(legacyJson)
        store.adoptLegacy(owner) shouldBe true
        store.set(q,listOf(ToolbarOverrides.Entry("Clipboard_window",icon="自选图标")))
        store.reset(owner)
        val restored=json.decodeFromString<ThemeToolbarStore>(json.encodeToString(store))
        restored shouldBe store
        restored.adoptLegacy(owner) shouldBe false
        restored.profile(q)?.buttons?.single()?.icon shouldBe "自选图标"
    }
    test("saving takes a snapshot of the editor list") {
        val store=ThemeToolbarStore()
        val draft=mutableListOf(ToolbarOverrides.Entry("Clipboard_window"))
        store.set(q,draft);draft.clear()
        store.profile(q)?.buttons?.size shouldBe 1
    }
})
