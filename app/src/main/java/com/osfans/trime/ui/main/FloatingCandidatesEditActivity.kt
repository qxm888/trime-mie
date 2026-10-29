/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.data.keymap.*
import com.osfans.trime.data.theme.FontOverrides
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.appearance.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/** Staged edits, fixed preview and four short pages; only Save changes the chosen layout. */
class FloatingCandidatesEditActivity : PersonalizationActivity() {
    private val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
    private var id=""
    private var draft=FloatingCandidateStyle()
    private var saved: FloatingCandidateStyle?=null
    private var tab=0
    private lateinit var preview: FloatingCandidatesPreview
    private lateinit var drawers:SettingsDrawers
    private var fontRole="candidate"
    private val fontButtons=mutableMapOf<String,MaterialButton>()
    private val importer=registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if(uri!=null) lifecycleScope.launch {
            val file=File(FontOverrides.folder(),"popup-${UUID.randomUUID()}.ttf")
            val result=withContext(Dispatchers.IO) { runCatching {
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    file.outputStream().use { output ->
                        val buffer=ByteArray(8192);var total=0L
                        while(true) { val n=input.read(buffer);if(n<0) break;total+=n;require(total<=128L*1024*1024) { "字体不能超过128MB" };output.write(buffer,0,n) }
                    }
                }
                FontOverrides.fromSelection(file.absolutePath) ?: error("字体不可用")
            } }
            result.onSuccess {
                font(fontRole,(draft.fonts[fontRole] ?: FontSpec()).copy(font=file.absolutePath))
                fontButtons[fontRole]?.text="字体 · ${file.name}"
            }.onFailure { file.delete();Toast.makeText(this@FloatingCandidatesEditActivity,it.message ?: "字体导入失败",1).show() }
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        id=intent.getStringExtra("id").orEmpty()
        val keyboard=CustomKeyboards.get(id) ?: run { finish();return }
        saved=keyboard.floatingCandidates
        draft=state?.getString("draft")?.let { runCatching { json.decodeFromString<FloatingCandidateStyle>(it) }.getOrNull() } ?: saved ?: FloatingCandidateStyle()
        tab=state?.getInt("tab") ?: 0;fontRole=state?.getString("fontRole") ?: "candidate"
        page("悬浮窗编辑")
        val scroll=body.parent as ScrollView
        val root=scroll.parent as LinearLayout
        val toolbar=root.getChildAt(0) as MaterialToolbar
        toolbar.subtitle=keyboard.name
        toolbar.menu.add("保存").apply { setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);setOnMenuItemClickListener { save();true } }
        toolbar.setNavigationOnClickListener { leave() }
        preview=FloatingCandidatesPreview(this,keyboard.typography)
        root.addView(preview,1,LinearLayout.LayoutParams(-1,dp(190)))
        val tabs=TabLayout(this).apply {
            listOf("窗口","候选","文字","辅助").forEach { addTab(newTab().setText(it)) }
            getTabAt(tab)?.select()
            addOnTabSelectedListener(object:TabLayout.OnTabSelectedListener {
                override fun onTabSelected(item:TabLayout.Tab) { tab=item.position;render() }
                override fun onTabUnselected(item:TabLayout.Tab) {}
                override fun onTabReselected(item:TabLayout.Tab) {}
            })
        }
        root.addView(tabs,2,LinearLayout.LayoutParams(-1,dp(48)))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom))
            val height=dp(if(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) 96 else 190)
            if(preview.layoutParams.height!=height) preview.layoutParams=preview.layoutParams.apply { this.height=height }
            insets
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) { override fun handleOnBackPressed()=leave() })
        render();changed()
    }
    override fun onSaveInstanceState(state:Bundle) {
        state.putString("draft",json.encodeToString(FloatingCandidateStyle.serializer(),draft));state.putInt("tab",tab);state.putString("fontRole",fontRole)
        super.onSaveInstanceState(state)
    }
    private fun changed() { preview.render(draft) }
    private fun save() {
        val previous=CustomKeyboards.get(id) ?: run { Toast.makeText(this,"布局已被删除",Toast.LENGTH_LONG).show();return }
        val normalized=draft.sanitized()
        if(!CustomKeyboards.mutate(id) { it.floatingCandidates=normalized }) return
        if(CustomKeyboards.flush()) {
            draft=normalized;saved=normalized;ThemeManager.requestCustomizationRefresh()
            Toast.makeText(this,"已保存，重新拉起键盘后生效",Toast.LENGTH_SHORT).show()
        } else {
            CustomKeyboards.mutate(id) { it.floatingCandidates=previous.floatingCandidates }
            CustomKeyboards.flush()
            Toast.makeText(this,"保存失败，已保留原外观，请检查存储空间",Toast.LENGTH_LONG).show()
        }
    }
    private fun leave() {
        if(draft==saved || saved==null && draft==FloatingCandidateStyle()) { finish();return }
        MaterialAlertDialogBuilder(this).setTitle("修改尚未保存").setPositiveButton("保存并返回") { _,_ -> save();if(draft==saved) finish() }
            .setNegativeButton("放弃修改") { _,_ -> finish() }.setNeutralButton("继续编辑",null).show()
    }
    private fun render() {
        body.removeAllViews();fontButtons.clear();drawers=SettingsDrawers(body)
        when(tab) {
            0 -> windowPage()
            1 -> candidatesPage()
            2 -> textPage()
            else -> auxiliaryPage()
        }
    }
    private fun section(title:String)=drawers.section(title)
    private fun note(parent:LinearLayout,text:String) { parent.addView(TextView(this).apply { this.text=text;textSize=13f;setPadding(dp(12),dp(8),dp(12),dp(12)) }) }
    private fun toggle(parent:LinearLayout,title:String,value:Boolean,apply:(Boolean)->Unit) {
        parent.addView(SwitchMaterial(this).apply { text=title;isChecked=value;minHeight=dp(48);setPadding(dp(12),0,dp(8),0);setOnCheckedChangeListener { _,v -> apply(v);changed() } })
    }
    private fun slider(parent:LinearLayout,title:String,value:Float,min:Float,max:Float,step:Float=1f,unit:String="",apply:(Float)->Unit) {
        fun display(v:Float)=if(v%1f==0f) v.roundToInt().toString() else String.format(java.util.Locale.ROOT,"%.1f",v)
        val label=TextView(this).apply { text="$title · ${display(value)}$unit";setPadding(dp(12),dp(10),dp(12),0) }
        parent.addView(label)
        parent.addView(Slider(this).apply {
            valueFrom=min;valueTo=max;stepSize=step;this.value=(min+((value.coerceIn(min,max)-min)/step).roundToInt()*step).coerceIn(min,max)
            addOnChangeListener { _,v,user -> if(user) { label.text="$title · ${display(v)}$unit";apply(v);changed() } }
        },LinearLayout.LayoutParams(-1,dp(48)).apply { setMargins(dp(8),0,dp(8),0) })
    }
    private fun choice(parent:LinearLayout,title:String,labels:List<String>,selected:Int,apply:(Int)->Unit) {
        var current=selected.coerceIn(labels.indices)
        parent.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text="$title · ${labels[current]}";isAllCaps=false
            setOnClickListener { MaterialAlertDialogBuilder(this@FloatingCandidatesEditActivity).setTitle(title).setSingleChoiceItems(labels.toTypedArray(),current) { dialog,index ->
                current=index;apply(index);text="$title · ${labels[index]}";changed();dialog.dismiss()
            }.show() }
        })
    }
    private fun color(parent:LinearLayout,title:String,value:String,image:Boolean=false,apply:(String)->Unit) {
        fun label(v:String)="$title · ${when { v.isBlank()->"继承";v.startsWith("/")->"图片";v=="#00000000"->"透明";else->v }}"
        parent.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text=label(value);isAllCaps=false
            var current=value
            setOnClickListener { AppearancePicker.show(this@FloatingCandidatesEditActivity,title,current,image) { v -> current=v;apply(v);text=label(v);changed() } }
        })
    }
    private fun surface(title:String,read:()->SurfaceStyle,allowBlur:Boolean=true,write:(SurfaceStyle)->Unit) {
        val box=section(title)
        color(box,"背景",read().background,true) { write(read().copy(background=it)) }
        slider(box,"圆角",read().radius ?: 8f,0f,64f,unit=" dp") { write(read().copy(radius=it)) }
        slider(box,"边框粗细",read().borderWidth ?: 0f,0f,8f,step=.5f,unit=" dp") { write(read().copy(borderWidth=it)) }
        color(box,"边框颜色",read().borderColor) { write(read().copy(borderColor=it)) }
        if(allowBlur) toggle(box,"柔化悬浮窗背景",read().blur) { write(read().copy(blur=it)) }
    }
    private fun materials(title:String,read:()->KeyEffects,write:(KeyEffects)->Unit) {
        var box=section("$title · 渐变")
        toggle(box,"渐变叠加",read().gradient.enabled) { write(read().copy(gradient=read().gradient.copy(enabled=it))) }
        val types=listOf("linear","radial","sweep")
        choice(box,"渐变类型",listOf("线性","径向","扫描"),types.indexOf(read().gradient.type).coerceAtLeast(0)) { write(read().copy(gradient=read().gradient.copy(type=types[it]))) }
        color(box,"渐变起始色",read().gradient.startColor) { write(read().copy(gradient=read().gradient.copy(startColor=it))) }
        color(box,"渐变结束色",read().gradient.endColor) { write(read().copy(gradient=read().gradient.copy(endColor=it))) }
        slider(box,"渐变角度",read().gradient.angle,0f,360f,15f,"°") { write(read().copy(gradient=read().gradient.copy(angle=it))) }
        slider(box,"渐变强度",read().gradient.opacity.toFloat(),0f,100f,unit="%") { write(read().copy(gradient=read().gradient.copy(opacity=it.toInt()))) }
        box=section("$title · 高光与磨砂")
        toggle(box,"顶部高光",read().gloss) { write(read().copy(gloss=it)) }
        slider(box,"高光强度",read().glossStrength.toFloat(),0f,60f,unit="%") { write(read().copy(glossStrength=it.toInt())) }
        toggle(box,"磨砂颗粒",read().noise) { write(read().copy(noise=it)) }
        slider(box,"颗粒强度",read().noiseStrength.toFloat(),0f,30f,unit="%") { write(read().copy(noiseStrength=it.toInt())) }
        box=section("$title · 内阴影")
        toggle(box,"内阴影",read().innerShadow.enabled) { write(read().copy(innerShadow=read().innerShadow.copy(enabled=it))) }
        color(box,"内阴影颜色",read().innerShadow.color) { write(read().copy(innerShadow=read().innerShadow.copy(color=it))) }
        slider(box,"内阴影柔化",read().innerShadow.blur,0f,24f,unit=" dp") { write(read().copy(innerShadow=read().innerShadow.copy(blur=it))) }
        slider(box,"内阴影水平偏移",read().innerShadow.offsetX,-12f,12f,unit=" dp") { write(read().copy(innerShadow=read().innerShadow.copy(offsetX=it))) }
        slider(box,"内阴影垂直偏移",read().innerShadow.offsetY,-12f,12f,unit=" dp") { write(read().copy(innerShadow=read().innerShadow.copy(offsetY=it))) }
        box=section("$title · 边框与立体")
        toggle(box,"渐变边框",read().borderGradient.enabled) { write(read().copy(borderGradient=read().borderGradient.copy(enabled=it))) }
        color(box,"边框起始色",read().borderGradient.startColor) { write(read().copy(borderGradient=read().borderGradient.copy(startColor=it))) }
        color(box,"边框结束色",read().borderGradient.endColor) { write(read().copy(borderGradient=read().borderGradient.copy(endColor=it))) }
        slider(box,"渐变边框粗细",read().borderGradient.width,.5f,8f,.5f," dp") { write(read().copy(borderGradient=read().borderGradient.copy(width=it))) }
        slider(box,"渐变边框角度",read().borderGradient.angle,0f,360f,15f,"°") { write(read().copy(borderGradient=read().borderGradient.copy(angle=it))) }
        toggle(box,"立体底边",read().edge3d) { write(read().copy(edge3d=it)) }
        slider(box,"底边厚度",read().edgeDepth,0f,8f,.5f," dp") { write(read().copy(edgeDepth=it)) }
        color(box,"底边颜色",read().edgeColor) { write(read().copy(edgeColor=it)) }
    }
    private fun windowPage() {
        note("只编辑当前自定义键盘的悬浮候选窗。点击右上角保存；翻页箭头和悬浮球仅为预览。")
        toggle(body,"使用当前布局的悬浮窗外观",draft.enabled) { draft=draft.copy(enabled=it) }
        val presets=section("快捷风格与恢复")
        listOf("淡粉","通透","磨砂").forEachIndexed { index,title ->
            presets.addView(MaterialButton(this).apply { text=title;setOnClickListener {
                draft=when(index) {
                    0 -> draft.copy(enabled=true,panel=SurfaceStyle(background="#FFFFF5F3",radius=18f,borderWidth=1f,borderColor="#FFE5C5C7"),highlighted=SurfaceStyle(background="#FFF8DADD",radius=10f),panelEffects=KeyEffects())
                    1 -> draft.copy(enabled=true,panel=draft.panel.copy(background=if(draft.panel.background.startsWith("/")) draft.panel.background else "#CCF5EFF5",radius=20f,borderWidth=1f,borderColor="#99FFFFFF"),panelEffects=KeyEffects(gloss=true,glossStrength=18),highlighted=SurfaceStyle(background="#55FFFFFF",radius=10f))
                    else -> draft.copy(enabled=true,panel=draft.panel.copy(background=if(draft.panel.background.startsWith("/")) draft.panel.background else "#F2EFE8EB",blur=true,radius=18f),panelEffects=KeyEffects(noise=true,noiseStrength=6,gloss=true,glossStrength=8))
                };changed();render()
            } })
        }
        presets.addView(MaterialButton(this).apply { text="恢复默认外观";setOnClickListener {
            MaterialAlertDialogBuilder(this@FloatingCandidatesEditActivity).setMessage("保存后将恢复继承默认外观。其他键盘不受影响。").setPositiveButton("恢复") { _,_ -> draft=FloatingCandidateStyle(enabled=false);changed();render() }.setNegativeButton("取消",null).show()
        } })
        surface("窗口背景、圆角与边框",{draft.panel}) { draft=draft.copy(panel=it) }
        materials("窗口渐变与材质",{draft.panelEffects}) { draft=draft.copy(panelEffects=it) }
        val geometry=section("尺寸、留白与透明度")
        slider(geometry,"最小宽度",draft.minWidth.toFloat(),48f,600f,unit=" dp") { draft=draft.copy(minWidth=it.toInt()) }
        slider(geometry,"最大宽度",draft.maxWidthPercent.toFloat(),35f,100f,unit="%") { draft=draft.copy(maxWidthPercent=it.toInt()) }
        slider(geometry,"水平内边距",draft.paddingHorizontal.toFloat(),0f,32f,unit=" dp") { draft=draft.copy(paddingHorizontal=it.toInt()) }
        slider(geometry,"垂直内边距",draft.paddingVertical.toFloat(),0f,32f,unit=" dp") { draft=draft.copy(paddingVertical=it.toInt()) }
        slider(geometry,"整体不透明度",draft.opacity.toFloat(),20f,100f,unit="%") { draft=draft.copy(opacity=it.toInt()) }
        val shadow=section("投影与背景柔化")
        slider(shadow,"窗口投影高度",draft.elevation.toFloat(),0f,24f,unit=" dp") { draft=draft.copy(elevation=it.toInt()) }
        color(shadow,"投影颜色",draft.shadowColor) { draft=draft.copy(shadowColor=it) }
        note(shadow,"投影颜色需要 Android 9 及以上；旧系统保留系统投影色。")
        slider(shadow,"背景柔化强度",draft.blurRadius.toFloat(),2f,32f,unit=" dp") { draft=draft.copy(blurRadius=it.toInt()) }
        slider(shadow,"柔化后原背景叠加强度",draft.glassOpacity.toFloat(),0f,100f,unit="%") { draft=draft.copy(glassOpacity=it.toInt()) }
        note(shadow,"只柔化悬浮窗自己的背景图片，不模糊聊天内容。纯色背景的柔化变化不明显。")
    }
    private fun candidatesPage() {
        surface("普通候选背景",{draft.normal}) { draft=draft.copy(normal=it) }
        surface("高亮候选背景",{draft.highlighted}) { draft=draft.copy(highlighted=it) }
        materials("候选渐变与材质",{draft.itemEffects}) { draft=draft.copy(itemEffects=it) }
        val spacing=section("候选留白与投影")
        slider(spacing,"水平留白",draft.itemPaddingHorizontal.toFloat(),0f,24f,unit=" dp") { draft=draft.copy(itemPaddingHorizontal=it.toInt()) }
        slider(spacing,"垂直留白",draft.itemPaddingVertical.toFloat(),0f,24f,unit=" dp") { draft=draft.copy(itemPaddingVertical=it.toInt()) }
        slider(spacing,"文字行距",draft.lineSpacing.toFloat(),0f,24f,unit=" dp") { draft=draft.copy(lineSpacing=it.toInt()) }
        slider(spacing,"候选投影高度",draft.itemElevation.toFloat(),0f,12f,unit=" dp") { draft=draft.copy(itemElevation=it.toInt()) }
        val press=section("按压反馈")
        slider(press,"按下缩放",draft.itemEffects.pressScale,0f,10f,.5f,"%") { draft=draft.copy(itemEffects=draft.itemEffects.copy(pressScale=it)) }
        slider(press,"按下下沉",draft.itemEffects.pressSink,0f,8f,.5f," dp") { draft=draft.copy(itemEffects=draft.itemEffects.copy(pressSink=it)) }
        slider(press,"按下变暗",draft.itemEffects.pressDarken.toFloat(),0f,60f,unit="%") { draft=draft.copy(itemEffects=draft.itemEffects.copy(pressDarken=it.toInt())) }
        slider(press,"动画时长",draft.itemEffects.animationMs.toFloat(),0f,300f,10f," ms") { draft=draft.copy(itemEffects=draft.itemEffects.copy(animationMs=it.toInt())) }
        toggle(press,"按下波纹",draft.itemEffects.ripple) { draft=draft.copy(itemEffects=draft.itemEffects.copy(ripple=it)) }
        color(press,"波纹颜色",draft.itemEffects.rippleColor) { draft=draft.copy(itemEffects=draft.itemEffects.copy(rippleColor=it)) }
    }
    private fun font(role:String,spec:FontSpec) { draft=draft.copy(fonts=draft.fonts+(role to spec));changed() }
    private fun textPage() {
        toggle(body,"显示候选序号",draft.showLabels) { draft=draft.copy(showLabels=it) }
        toggle(body,"显示候选注释",draft.showComments) { draft=draft.copy(showComments=it) }
        toggle(body,"显示编码",draft.showPreedit) { draft=draft.copy(showPreedit=it) }
        FloatingCandidateStyle.roles.forEach { (role,title) ->
            val box=section(title)
            val current=draft.fonts[role] ?: FontSpec()
            val pick=MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text="字体 · ${if(current.font.isBlank()) "继承当前布局" else FontOverrides.name(current.font)}"
                setOnClickListener {
                    val choices=FontOverrides.choices()
                    MaterialAlertDialogBuilder(this@FloatingCandidatesEditActivity).setTitle("$title 字体").setItems((choices.map(FontOverrides::name)+"导入字体文件…").toTypedArray()) { _,index ->
                        if(index==choices.size) { fontRole=role;importer.launch("*/*") }
                        else { font(role,(draft.fonts[role] ?: FontSpec()).copy(font=choices[index]));text="字体 · ${if(choices[index].isBlank()) "继承当前布局" else FontOverrides.name(choices[index])}" }
                    }.show()
                }
            }
            box.addView(pick);fontButtons[role]=pick
            slider(box,"字号（0继承）",current.size,0f,100f,unit=" sp") { font(role,(draft.fonts[role] ?: FontSpec()).copy(size=it)) }
            color(box,"普通颜色",draft.colors[role].orEmpty()) { draft=draft.copy(colors=draft.colors+(role to it)) }
            color(box,"高亮颜色",draft.colors["highlight.$role"].orEmpty()) { draft=draft.copy(colors=draft.colors+("highlight.$role" to it)) }
        }
        val shadow=section("候选文字阴影")
        toggle(shadow,"文字阴影",draft.itemEffects.textShadow.enabled) { draft=draft.copy(itemEffects=draft.itemEffects.copy(textShadow=draft.itemEffects.textShadow.copy(enabled=it))) }
        color(shadow,"阴影颜色",draft.itemEffects.textShadow.color) { draft=draft.copy(itemEffects=draft.itemEffects.copy(textShadow=draft.itemEffects.textShadow.copy(color=it))) }
        slider(shadow,"柔化半径",draft.itemEffects.textShadow.blur,0f,16f,.5f," dp") { draft=draft.copy(itemEffects=draft.itemEffects.copy(textShadow=draft.itemEffects.textShadow.copy(blur=it))) }
        slider(shadow,"水平偏移",draft.itemEffects.textShadow.offsetX,-8f,8f,unit=" dp") { draft=draft.copy(itemEffects=draft.itemEffects.copy(textShadow=draft.itemEffects.textShadow.copy(offsetX=it))) }
        slider(shadow,"垂直偏移",draft.itemEffects.textShadow.offsetY,-8f,8f,unit=" dp") { draft=draft.copy(itemEffects=draft.itemEffects.copy(textShadow=draft.itemEffects.textShadow.copy(offsetY=it))) }
    }
    private fun auxiliaryPage() {
        val paging=section("翻页位置与箭头")
        val values=listOf("inherit","top","bottom")
        choice(paging,"翻页位置",listOf("继承全局","上方集中","底部左右"),values.indexOf(draft.pagination).coerceAtLeast(0)) { draft=draft.copy(pagination=values[it]) }
        slider(paging,"箭头区域高度",draft.arrowSize.toFloat(),12f,48f,unit=" dp") { draft=draft.copy(arrowSize=it.toInt()) }
        color(paging,"箭头颜色",draft.colors["arrow"].orEmpty()) { draft=draft.copy(colors=draft.colors+("arrow" to it)) }
        surface("翻页按钮背景",{draft.pager}) { draft=draft.copy(pager=it) }
        val ball=section("悬浮球大小与字符")
        note(ball,"悬浮球使用独立的全局开关，默认关闭，不受状态栏显示影响。这里仅设置当前布局的外观。")
        slider(ball,"悬浮球尺寸",draft.ballSize.toFloat(),32f,72f,unit=" dp") { draft=draft.copy(ballSize=it.toInt()) }
        val label=TextInputEditText(this).apply { setSingleLine();setText(draft.ballLabel);filters=arrayOf(android.text.InputFilter.LengthFilter(16)) }
        ball.addView(TextInputLayout(this).apply { hint="悬浮球显示字符";addView(label) })
        label.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) { draft=draft.copy(ballLabel=s?.toString().orEmpty());changed() }
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        color(ball,"悬浮球字符颜色",draft.colors["ball"].orEmpty()) { draft=draft.copy(colors=draft.colors+("ball" to it)) }
        surface("悬浮球背景",{draft.ball},false) { draft=draft.copy(ball=it) }
    }
    companion object {
        fun start(context:Context,id:String) { context.startActivity(Intent(context,FloatingCandidatesEditActivity::class.java).putExtra("id",id)) }
    }
}
