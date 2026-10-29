/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.theme.*
import com.osfans.trime.data.theme.model.*
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import com.osfans.trime.ui.main.CustomKeyboardActivity
import kotlinx.coroutines.runBlocking
import java.io.File

/** Generated layouts/assets only; does not activate the import or alter theme/IME selection. */
class Mie305Runner:Instrumentation() {
    override fun onCreate(args:Bundle?) { super.onCreate(args);start() }
    private fun pass(name:String)=sendStatus(0,Bundle().apply { putString("case","PASS: $name") })
    override fun onStart() {
        runOnMainSync { check(com.osfans.trime.data.prefs.AppPrefs.defaultInstance() != null) }
        val result=Bundle()
        val initialMode=CustomKeyboards.mode
        val initial=CustomKeyboards.list().map { it.id }.toSet()
        val marker="主题转换检查-${java.util.UUID.randomUUID()}"
        val file=File(targetContext.cacheDir,"$marker.png")
        val ids=mutableListOf<String>();val assets=mutableSetOf<File>()
        try {
            val image=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
            val rows=listOf(TextKeyboard.TextKey(width=25f,click=KeyActionToken.Plain("fixture_commit")),
                TextKeyboard.TextKey(width=25f),
                TextKeyboard.TextKey(width=25f,click=KeyActionToken.Inline(commit="🙂，",label="表情"),labelSymbol="上",hint="下",popup=listOf("甲","乙")),
                TextKeyboard.TextKey(width=25f,click=KeyActionToken.Plain("fixture_numbers"),swipeUp=KeyActionToken.Plain("{fixture_numbers}{Control+a}")),
                TextKeyboard.TextKey(newRow=true,width=50f,click=KeyActionToken.Plain("space")),
                TextKeyboard.TextKey(width=50f,click=KeyActionToken.Plain("fixture_ime")))
            val native=ToolBar.Button(action="fixture_commit",longPressAction="fixture_numbers",
                foreground=ToolBar.Button.Foreground(style="甲",optionStyles=listOf("甲","乙"),normal="#FF123456"),
                background=ToolBar.Button.Background(cornerRadius=12f,normal="#FF334455",highlight="#FF778899"))
            val theme=Theme(name=marker,style=GeneralStyle(keyHeight=50,keyWidth=25f,keyTextSize=21f,keyLongTextSize=13f,keyboardHeight=100),
                presetColorSchemes=mapOf("default" to (StandaloneKeyboardTheme.base().presetColorSchemes.getValue("default")+
                    mapOf("key_back_color" to file.absolutePath,"hilited_key_back_color" to "#FF112233","keyboard_background" to "#FF203040"))),
                presetKeys=mapOf("fixture_commit" to PresetKey(commit="测试🙂",text="{fixture_numbers}",label="造词"),
                    "fixture_numbers" to PresetKey(send="Eisu_toggle",select="numbers",label="数字"),
                    "fixture_back" to PresetKey(send="Eisu_toggle",select=".default",label="返回"),
                    "fixture_ime" to PresetKey(send="LANGUAGE_SWITCH",select="other_ime",label="输入法")),
                presetKeyboards=linkedMapOf("default" to TextKeyboard(importPreset="letters"),"letters" to TextKeyboard(keys=rows),
                    "numbers" to TextKeyboard(width=100f,height=50f,keys=listOf(TextKeyboard.TextKey(click=KeyActionToken.Plain("fixture_back"))))),
                toolBar=ToolBar(primaryButton=ToolBar.Button(action="__mie_menu"),buttons=listOf(native)))
            val imported=runBlocking { ThemeKeyboardImporter(targetContext).import(theme,"default",marker,SurfaceAppearanceConfig(),false,false) }
            ids+=imported.ids
            val keyboard=CustomKeyboards.get(imported.primaryId)!!
            keyboard.rows.flatMap { it.keys }.map { it.appearance.keyBackColor }.filter { it.startsWith("/") }.forEach { assets+=File(it).parentFile!! }
            check(ids.size==2 && CustomKeyboards.mode==initialMode)
            check(keyboard.rows.map { it.keys.size }==listOf(4,2) && keyboard.rows.map { it.height }==listOf(50,50))
            val keys=keyboard.rows.flatMap { it.keys }
            check(keys[1].bindings.isEmpty() && keys[1].background?.type==CustomBackground.Type.TRANSPARENT)
            check(BindingActions.resolve(keys[0].bindings.getValue("click"))?.commit=="测试🙂")
            check(BindingActions.resolve(keys[2].bindings.getValue("click"))?.commit=="🙂，")
            check(keys[2].appearance.labelSymbol=="上" && keys[2].appearance.hint=="下" && keys[2].appearance.popup==listOf("甲","乙"))
            check(BindingActions.resolve(keys[5].bindings.getValue("click"))?.select=="other_ime")
            val target=BindingActions.resolve(keys[3].bindings.getValue("click"))!!.select
            check(target in ids && target!=imported.primaryId)
            val back=CustomKeyboards.get(target)!!.rows[0].keys[0].bindings.getValue("click")
            check(BindingActions.resolve(back)?.select==imported.primaryId)
            pass("aliases, inline Unicode actions, all row/spacer geometry, symbol/hint/popup, keyboard links and non-keyboard select preserved; no activation")
            check(BindingActions.resolve(keys[3].bindings.getValue("swipeUp"))!!.text.contains("{Control+a}"))
            val macro=keyboard.importedActions.values.single()
            check(CustomKeyboards.standaloneTheme().presetKeys[keyboard.importedActions.keys.single()]==macro)
            check(macro.select==target && keyboard.importedActions.keys.single() in BindingActions.resolve(keys[0].bindings.getValue("click"))!!.text)
            val zeroGap=com.osfans.trime.ime.keyboard.Keyboard(targetContext,StandaloneKeyboardTheme.base().copy(isCustom=true),1000,TextKeyboard(horizontalGap=0),"fixture")
            check(zeroGap.horizontalGap==0)
            val copied=File(keys[0].appearance.keyBackColor)
            check(copied.isFile && copied.readBytes().contentEquals(file.readBytes()) && copied.absolutePath!=file.absolutePath)
            check(keys[0].appearance.hilitedKeyBackColor=="#FF112233" && keys[0].background?.type==CustomBackground.Type.TRANSPARENT)
            check(keyboard.toolbar[1].template?.foreground?.optionStyles==listOf("甲","乙"))
            val bar=ToolbarOverrides.applyForCustomKeyboard(StandaloneKeyboardTheme.base().toolBar,keyboard.toolbar)
            check(bar.buttons[0].background.cornerRadius==12f && bar.buttons[0].foreground.normal=="#FF123456")
            check(BindingActions.resolve(bar.buttons[0].longPressAction)?.select==target)
            val replacement=BindingActions.command("clipboard_window",label="剪贴板")
            val edited=ToolbarOverrides.applyForCustomKeyboard(StandaloneKeyboardTheme.base().toolBar,keyboard.toolbar.mapIndexed { index,entry ->
                if(index==1) entry.copy(action=replacement,bindings=emptyMap()) else entry })
            check(edited.buttons[0].action==replacement && edited.buttons[0].longPressAction.isEmpty())
            check(BindingActions.label(keys[0].bindings.getValue("click"))=="造词")
            val roundtrip=CustomKeyboardYaml.decode(CustomKeyboards.exportYaml(ids))
            check(roundtrip.getValue(imported.primaryId).toolbar[1].template==keyboard.toolbar[1].template)
            check(roundtrip.getValue(imported.primaryId).importedActions==keyboard.importedActions)
            pass("asset is an independent copy; normal/pressed backgrounds, native toolbar option styles and YAML metadata survive")
            val before=CustomKeyboards.list().map { it.id }.toSet()
            val invalid=theme.copy(presetKeyboards=mapOf("bad" to TextKeyboard(importPreset="bad")))
            check(runCatching { runBlocking { ThemeKeyboardImporter(targetContext).import(invalid,"bad",marker,SurfaceAppearanceConfig(),false,false) } }.isFailure)
            check(CustomKeyboards.list().map { it.id }.toSet()==before)
            pass("cyclic preset import fails without adding or overwriting layouts")
            val node=ThemeYaml.parser.parseToYamlNode(targetContext.assets.open("shared/trime.yaml").bufferedReader().use { it.readText() })
            val standard=ThemeLoader.decodeSource("fixture-standard",node) { null }
            val standardResult=runBlocking { ThemeKeyboardImporter(targetContext).import(standard,"default",marker+"-标准",SurfaceAppearanceConfig(),false,false) }
            ids+=standardResult.ids
            standardResult.ids.mapNotNull(CustomKeyboards::get).forEach { layout ->
                layout.typography.values.map { it.font }.filter { it.startsWith("/") }.forEach { assets+=File(it).parentFile!! }
            }
            val standardKeyboard=CustomKeyboards.get(standardResult.primaryId)!!
            check(standardKeyboard.rows.size==5 && standardKeyboard.rows.sumOf { it.keys.size }>=40 && standardResult.ids.size>=3)
            pass("shipped standard theme source expands and converts default plus number/symbol pages without changing mode")
            val activity=startActivitySync(Intent(targetContext,CustomKeyboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                var visible=false
                runOnMainSync { visible=activity.findViewById<android.view.View>(com.osfans.trime.R.id.custom_kb_import_theme).isShown }
                check(visible);pass("import action visible without changing layout mode")
            } finally { runOnMainSync { activity.finish() } }
            result.putString("result","PASS")
        } catch(error:Throwable) { result.putString("result","FAIL: ${error.message}");result.putString("trace",error.stackTraceToString()) }
        finally {
            ids.forEach(CustomKeyboards::delete);CustomKeyboards.flush();assets.forEach { it.deleteRecursively() };file.delete()
            check(CustomKeyboards.list().map { it.id }.toSet()==initial)
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
