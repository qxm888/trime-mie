/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.keymap

import android.content.Context
import android.content.res.Configuration
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.core.data.DirectExternalStorage
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.*
import com.osfans.trime.data.theme.model.*
import com.osfans.trime.ime.appearance.SurfaceAppearanceConfig
import com.osfans.trime.ime.keyboard.resolveKeyboardPreset
import com.osfans.trime.ime.keyboard.KeyCode
import android.view.KeyEvent
import com.osfans.trime.util.ColorUtils
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

/** Materializes theme references before committing a new, independently editable layout group. */
class ThemeKeyboardImporter(private val context: Context) {
    data class Result(val primaryId: String, val ids: List<String>, val warnings: List<String>)

    suspend fun import(theme: Theme, sourceId: String, name: String, appearance: SurfaceAppearanceConfig,
        unified: Boolean, includeAppOverrides:Boolean=true): Result {
        require(name.isNotBlank() && name.length <= 80) { "键盘名称须为1–80个字符" }
        val directory = File(DirectExternalStorage.resources(context), "custom_kb_bg/theme-${UUID.randomUUID()}")
        val warnings = linkedSetOf<String>()
        val owner=currentCoroutineContext()
        val assets = Assets(theme, directory, warnings) { owner.ensureActive() }
        try {
            val selected = ThemeManager.prefs
            val scheme = ColorSchemeResolver.resolve(theme.presetColorSchemes, selected.normalModeColor.getValue(),
                selected.followSystemDayNight.getValue(), context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            fun raw(value: String, fallback: String = ""): String {
                val text = value.ifEmpty { fallback }
                val resolved = ColorTable.resolveRaw(text, scheme, theme.fallbackColors) ?: text
                return assets.value(resolved)
            }
            val presets = Theme.BUILTIN_ACTIONS + theme.presetKeys + mapOf(
                "VoiceInput" to PresetKey(label="内置语音",send="VOICE_ASSIST"),
                "BibiVoice" to PresetKey(label="内置语音",send="VOICE_ASSIST"))
            val ids = linkedMapOf<String,String>()
            fun include(id: String): String {
                require(id in theme.presetKeyboards) { "主题引用了不存在的键盘：$id" }
                require(ids.size < 128 || id in ids) { "主题关联布局超过128个" }
                return ids.getOrPut(id) { CustomKeyboards.ID_PREFIX + UUID.randomUUID() }
            }
            include(sourceId)
            val macros=linkedMapOf<String,PresetKey>()
            val macroNames=linkedMapOf<Pair<String,String>,String>()
            val expanding=mutableSetOf<String>()
            val macroPattern=Regex("\\{([^{}]+)\\}")
            val bar = if(includeAppOverrides) ToolbarOverrides.apply(theme.toolBar) else theme.toolBar
            fun action(value: KeyActionToken?, keyboard: String, config: TextKeyboard): String? {
                if(value==null) return null
                if(value is KeyActionToken.Plain && value.token.isEmpty()) return ""
                val original=when(value) {
                    is KeyActionToken.Inline -> PresetKey(commit=value.commit.orEmpty(),text=value.text.orEmpty(),label=value.label.orEmpty())
                    is KeyActionToken.Plain -> BindingActions.resolve(value.token) ?: presets[value.token]
                        ?: if(macroPattern.findAll(value.token).any { it.groupValues[1] in presets }) PresetKey(text=value.token) else return value.token
                }
                val text=macroPattern.replace(original.text) { match ->
                    val alias=match.groupValues[1]
                    if(alias !in presets) match.value else {
                        val key=macroNames.getOrPut(keyboard to alias) { "${ids.getValue(keyboard)}_macro_${macroNames.size}" }
                        if(key !in macros) {
                            require(expanding.size<16 && expanding.add(key) && macroNames.size<=4096) { "主题按键宏存在循环或过度嵌套" }
                            try { macros[key]=BindingActions.resolve(action(KeyActionToken.Plain(alias),keyboard,config)!!)!! }
                            finally { expanding.remove(key) }
                        }
                        "{$key}"
                    }
                }
                val preset=original.copy(text=text)
                if(KeyCode.parse(preset.send).first != KeyEvent.KEYCODE_EISU) return BindingActions.token(preset)
                val target=when(preset.select) {
                    ".default" -> "default".takeIf { it in theme.presetKeyboards } ?: sourceId
                    ".next", ".prior" -> theme.presetKeyboards.keys.toList().let { order ->
                        order.getOrNull(order.indexOf(keyboard) + if(preset.select==".next") 1 else -1) ?: keyboard }
                    ".ascii" -> config.asciiKeyboard.takeIf { it in theme.presetKeyboards } ?: ".last_lock"
                    else -> preset.select
                }
                val mapped=if(target.isNotEmpty() && !target.startsWith(".")) include(target) else target
                return BindingActions.token(preset.copy(select=mapped))
            }
            fun surface(style: com.osfans.trime.ime.appearance.SurfaceStyle) = style.copy(
                background=raw(style.background),borderColor=raw(style.borderColor))
            val frozenAppearance=appearance.copy(keys=surface(appearance.keys),toolbar=surface(appearance.toolbar),
                bar=surface(appearance.bar),barBackground=raw(appearance.barBackground))
            val typography=fonts(theme,assets,warnings,includeAppOverrides)
            val background=(if(includeAppOverrides) AppPrefs.defaultInstance().keyboard.videoBackground.getValue() else "").takeIf { it.isNotBlank() }
                ?.let { CustomBackground(CustomBackground.Type.VIDEO,image=assets.copy(File(it))) }
                ?: background(raw("keyboard_background","keyboard_back_color"))
            val layouts=linkedMapOf<String,CustomKeyboards.CustomKeyboard>()
            var processed=0
            while(processed<ids.size) {
                currentCoroutineContext().ensureActive()
                val keyboard=ids.keys.elementAt(processed++)
                val config=resolveKeyboardPreset(theme.presetKeyboards,keyboard) ?: error("无法解析键盘：$keyboard")
                require(config.keys.isNotEmpty()) { "键盘 $keyboard 没有可导入的按键" }
                require(config.keys.size<=10000) { "键盘按键数量超过10000" }
                val bindings:(TextKeyboard.TextKey)->MutableMap<String,String> = { key ->
                    linkedMapOf("click" to key.click,"longClick" to key.longClick,"doubleClick" to key.doubleClick,
                        "lazyDoubleClick" to key.lazyDoubleClick,"swipeUp" to key.swipeUp,"swipeDown" to key.swipeDown,
                        "swipeLeft" to key.swipeLeft,"swipeRight" to key.swipeRight,"composing" to key.composing,
                        "hasMenu" to key.hasMenu,"paging" to key.paging,"combo" to key.combo,"ascii" to key.ascii)
                        .mapNotNull { (behavior,token) -> action(token,keyboard,config)?.let { behavior to it } }.toMap().toMutableMap()
                        .also { if(!key.allowDefaultVoice && "longClick" !in it) it["longClick"]="" }
                }
                val rows=mutableListOf<CustomKeyboards.CustomRow>()
                var row=CustomKeyboards.CustomRow();var width=0f;var columns=0
                val maxColumns=config.columns.takeIf { it>0 } ?: Int.MAX_VALUE
                val defaultWidth=config.width.takeIf { it>0 } ?: theme.style.keyWidth.takeIf { it>0 } ?: 10f
                val rowHeight=config.height.takeIf { it>0 } ?: theme.style.keyHeight.takeIf { it>0 }?.toFloat() ?: 60f
                fun finish() { if(row.keys.isNotEmpty()) rows.add(row);row=CustomKeyboards.CustomRow();width=0f;columns=0 }
                config.keys.forEachIndexed { index,original ->
                    val key=if(includeAppOverrides) KeyBindingOverrides.applyTo(keyboard,index,original) else original
                    val weight=if(key.width==0f && key.click.orAbsent!=null) defaultWidth else key.width
                    require(weight.isFinite() && weight in 0f..100f) { "键盘 $keyboard 含无效键宽" }
                    if(key.newRow && width>0 || columns>=maxColumns || width+weight>100.01f) finish()
                    if(weight==0f) return@forEachIndexed
                    if(row.keys.isEmpty()) row.height=(key.height.takeIf { it>0 } ?: rowHeight).toInt().coerceIn(24,160)
                    val ownBindings=bindings(key)
                    val spacer=key.label.isEmpty() && ownBindings.values.all { it.isEmpty() }
                    val clickPreset=(key.click as? KeyActionToken.Plain)?.token?.let { BindingActions.resolve(it) ?: presets[it] }
                    val display=key.label.ifEmpty { clickPreset?.label.orEmpty().ifEmpty { clickPreset?.states?.firstOrNull().orEmpty() }.ifEmpty { (key.click as? KeyActionToken.Inline)?.label.orEmpty() } }
                    val style=keyStyle(key,config,theme,display,includeAppOverrides,::raw)
                    row.keys.add(CustomKeyboards.CustomKey(label=key.label,width=weight,bindings=ownBindings,
                        background=if(spacer || style.keyBackColor.startsWith("/"))
                            CustomBackground(CustomBackground.Type.TRANSPARENT) else background(style.keyBackColor),appearance=style))
                    width+=weight
                    if(!spacer) columns++
                }
                finish();require(rows.isNotEmpty()) { "键盘 $keyboard 没有可见宽度" }
                fun button(btn:ToolBar.Button,primary:Boolean):ToolbarOverrides.Entry {
                    val a=action(KeyActionToken.Plain(btn.action.ifEmpty { if(primary) "__mie_menu" else "" }),keyboard,config).orEmpty()
                    val longPress=action(KeyActionToken.Plain(btn.longPressAction),keyboard,config).orEmpty()
                    val nativeBindings=btn.bindings.mapValues { (_,token) -> action(KeyActionToken.Plain(token),keyboard,config).orEmpty() }.toMutableMap()
                    if("longClick" !in nativeBindings && longPress.isNotEmpty()) nativeBindings["longClick"]=longPress
                    val template=btn.copy(action=a,longPressAction="",
                        foreground=btn.foreground.copy(style=assets.icon(btn.foreground.style),optionStyles=btn.foreground.optionStyles.map(assets::icon),
                            normal=raw(btn.foreground.normal,"candidate_text_color"),highlight=raw(btn.foreground.highlight,"hilited_candidate_text_color")),
                        background=btn.background.copy(normal=raw(btn.background.normal),highlight=raw(btn.background.highlight,"hilited_candidate_button_color")),
                        bindings=nativeBindings,
                        appearance=freezeStyle(btn.appearance,::raw))
                    return ToolbarOverrides.Entry(action=a,enabled=!primary || bar.primaryVisible,primary=primary,
                        id=UUID.randomUUID().toString(),template=template,appearance=template.appearance,bindings=nativeBindings,effects=template.effects,iconSize=btn.foreground.iconSize)
                }
                val toolbar=(listOf(button(bar.primaryButton ?: ToolBar.Button(action="__mie_menu",foreground=ToolBar.Button.Foreground(style="•••")),true))+
                    bar.buttons.map { button(it,false) }).toMutableList()
                val sum=rows.sumOf { it.height }
                fun percent(value:Int,fallback:Int)=((value.takeIf { it>0 } ?: fallback.takeIf { it>0 } ?: sum)*100f/sum).toInt().coerceIn(50,200)
                if(config.asciiKeyboard.isNotEmpty() || config.landscapeKeyboard.isNotEmpty()) warnings.add("主题的自动关联键盘不启用；按键中的键盘跳转已转换。")
                layouts[ids.getValue(keyboard)]=CustomKeyboards.CustomKeyboard(
                    name=if(keyboard==sourceId) name.trim() else "${name.trim()} · ${config.name.ifBlank { keyboard }}".take(120),
                    rows=rows,importedActions=macros.filterKeys { it.startsWith(ids.getValue(keyboard)+"_macro_") },background=background,toolbar=toolbar,toolbarBackground=background(raw("candidate_background","back_color")),
                    toolbarAlignment=bar.alignment,rowSpacing=(config.verticalGap.takeIf { it>0 } ?: theme.style.verticalGap).coerceIn(0,32),
                    surfaceAppearance=frozenAppearance,unifiedBackground=unified,typography=typography,
                    options=KeyboardOptions(horizontalGap=(config.horizontalGap.takeIf { it>0 } ?: theme.style.horizontalGap).coerceIn(0,32),
                        heightPercent=percent(config.keyboardHeight,theme.style.keyboardHeight),landscapeHeightPercent=percent(config.keyboardHeightLand,theme.style.keyboardHeightLand),
                        asciiMode=config.asciiMode.coerceIn(0,1),resetAsciiMode=config.resetAsciiMode,lock=config.lock,
                        uppercaseLabels=config.labelTransform==TextKeyboard.LabelTransform.UPPERCASE,landscapeSplitPercent=config.landscapeSplitPercent.coerceIn(0,100)))
            }
            currentCoroutineContext().ensureActive()
            CustomKeyboards.addImported(layouts)
            return Result(ids.getValue(sourceId),layouts.keys.toList(),warnings.toList())
        } catch(error:Throwable) { directory.deleteRecursively();throw error }
    }

    private fun background(value:String):CustomBackground = if(value.startsWith("/")) CustomBackground(CustomBackground.Type.IMAGE,image=value)
        else if(value.isBlank()) CustomBackground(CustomBackground.Type.TRANSPARENT) else CustomBackground(color=value).normalized()

    private fun keyStyle(key:TextKeyboard.TextKey,kb:TextKeyboard,theme:Theme,display:String,includeAppOverrides:Boolean,raw:(String,String)->String):KeyAppearance.Style {
        fun offset(a:Float,b:Float,c:Float)=a.takeIf { it!=0f } ?: b.takeIf { it!=0f } ?: c
        val s=theme.style
        val normal=raw(key.keyBackColor,"key_back_color")
        return KeyAppearance.Style(labelSymbol=key.labelSymbol,hint=key.hint,popup=key.popup,sendBindings=key.sendBindings,
            keyTextSize=key.keyTextSize.takeIf { it>0 } ?: (FontOverrides.size("key").takeIf { includeAppOverrides && it>0 } ?: if(display.length>1 && !display.startsWith("ic@")) s.keyLongTextSize else s.keyTextSize),
            symbolTextSize=key.symbolTextSize.takeIf { it>0 } ?: (FontOverrides.size("symbol").takeIf { includeAppOverrides && it>0 } ?: s.symbolTextSize),
            hintTextSize=key.hintTextSize,
            roundCorner=key.roundCorner.takeIf { it>=0 } ?: kb.roundCorner.takeIf { it>=0 } ?: s.roundCorner,
            keyBorder=key.keyBorder.takeIf { it>=0 } ?: kb.keyBorder.takeIf { it>=0 } ?: s.keyBorder,
            keyBackColor=normal,keyTextColor=raw(key.keyTextColor,"key_text_color"),keySymbolColor=raw(key.keySymbolColor,"key_symbol_color"),
            keyBorderColor=raw(key.keyBorderColor,"key_border_color"),hilitedKeyBackColor=raw(key.hilitedKeyBackColor,"hilited_key_back_color"),
            hilitedKeyTextColor=raw(key.hilitedKeyTextColor,"hilited_key_text_color"),hilitedKeySymbolColor=raw(key.hilitedKeySymbolColor,"hilited_key_symbol_color"),
            hilitedKeyBorderColor=raw(key.hilitedKeyBorderColor,"hilited_key_border_color"),
            keyTextOffsetX=offset(key.keyTextOffsetX,kb.keyTextOffsetX,s.keyTextOffsetX),keyTextOffsetY=offset(key.keyTextOffsetY,kb.keyTextOffsetY,s.keyTextOffsetY),
            keySymbolOffsetX=offset(key.keySymbolOffsetX,kb.keySymbolOffsetX,s.keySymbolOffsetX),keySymbolOffsetY=offset(key.keySymbolOffsetY,kb.keySymbolOffsetY,s.keySymbolOffsetY),
            keyHintOffsetX=offset(key.keyHintOffsetX,kb.keyHintOffsetX,s.keyHintOffsetX),keyHintOffsetY=offset(key.keyHintOffsetY,kb.keyHintOffsetY,s.keyHintOffsetY),
            keyPressOffsetX=offset(key.keyPressOffsetX,kb.keyPressOffsetX,s.keyPressOffsetX),keyPressOffsetY=offset(key.keyPressOffsetY,kb.keyPressOffsetY,s.keyPressOffsetY))
    }
    private fun freezeStyle(style:KeyAppearance.Style,raw:(String,String)->String):KeyAppearance.Style = style.copy(
        keyTextColor=raw(style.keyTextColor,""),keySymbolColor=raw(style.keySymbolColor,""),keyBackColor=raw(style.keyBackColor,""),keyBorderColor=raw(style.keyBorderColor,""),
        hilitedKeyTextColor=raw(style.hilitedKeyTextColor,""),hilitedKeySymbolColor=raw(style.hilitedKeySymbolColor,""),hilitedKeyBackColor=raw(style.hilitedKeyBackColor,""),hilitedKeyBorderColor=raw(style.hilitedKeyBorderColor,""))
    private fun fonts(theme:Theme,assets:Assets,warnings:MutableSet<String>,includeAppOverrides:Boolean):Map<String,FontSpec> {
        val s=theme.style
        val files=mapOf("key" to s.keyFont,"candidate" to s.candidateFont,"comment" to s.commentFont,"symbol" to s.symbolFont,
            "hint" to s.symbolFont,"text" to s.textFont,"popup" to s.popupFont,"label" to s.labelFont,"toolbar" to theme.toolBar.buttonFont)
        val sizes=mapOf("key" to s.keyTextSize,"candidate" to s.candidateTextSize,"comment" to s.commentTextSize,"symbol" to s.symbolTextSize,
            "hint" to s.symbolTextSize,"text" to s.keyTextSize,"popup" to s.popupTextSize,"label" to s.labelTextSize)
        return FontOverrides.roles.keys.associateWith { role ->
            val override=if(includeAppOverrides) FontOverrides.selection(role) else ""
            val names=files[role]?.toList().orEmpty()
            if(names.size>1) warnings.add("多字体回退链使用首个可用字体，缺字由系统回退。")
            val font=if(override.startsWith("system:")) override else if(override.isNotEmpty()) assets.copy(File(override)) else
                names.map { File(DataManager.userDataDir,"fonts/$it") }.firstOrNull { it.isFile }?.let(assets::copy).orEmpty()
            FontSpec(font,FontOverrides.size(role).takeIf { includeAppOverrides && it>0 } ?: sizes[role] ?: 0f)
        }
    }
    private class Assets(val theme:Theme,val directory:File,val warnings:MutableSet<String>,val checkActive:()->Unit) {
        private val copies=mutableMapOf<String,String>()
        private var total=0L
        fun copy(file:File):String {
            checkActive()
            if(file.name.endsWith(".9.png")) warnings.add("九宫格背景图按自定义键盘的图片方式缩放，拉伸细节可能与原主题不同。")
            require(file.isFile) { "主题素材不存在：${file.name}" }
            return copies.getOrPut(file.canonicalPath) {
                require(file.length()<=128L*1024*1024 && total+file.length()<=512L*1024*1024) { "主题素材过大（单文件128MB，总计512MB）" }
                check(directory.mkdirs() || directory.isDirectory) { "无法建立素材目录" }
                val target=File(directory,"${copies.size}-${file.name}")
                file.inputStream().use { input -> target.outputStream().use { output ->
                    val buffer=ByteArray(8192);var size=0L
                    while(true) { checkActive();val n=input.read(buffer);if(n<0) break;size+=n
                        require(size<=128L*1024*1024 && total+size<=512L*1024*1024) { "主题素材过大" };output.write(buffer,0,n) }
                    total+=size
                } }
                target.absolutePath
            }
        }
        fun icon(text:String):String = if(ColorTable.isImageValue(text) || text.startsWith("/")) value(text) else text
        fun value(raw:String):String {
            if(raw.isEmpty()) return ""
            val color=runCatching { ColorUtils.parseColor(raw) }.getOrNull()
            if(color!=null) return "#%08X".format(color)
            if(raw.startsWith("/") || ColorTable.isImageValue(raw)) {
                val file=if(raw.startsWith("/")) File(raw) else File(DataManager.userDataDir,"backgrounds/${theme.style.backgroundFolder}/$raw")
                    .takeIf { it.isFile } ?: File(DataManager.userDataDir,"backgrounds/$raw")
                return copy(file)
            }
            warnings.add("无法转换的颜色引用：${raw.take(60)}，已使用透明背景。")
            return "#00000000"
        }
    }
}
