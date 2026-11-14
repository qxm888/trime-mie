/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.data.pets.PetCatalog
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.ui.pets.PetPortraitView
import com.osfans.trime.ui.pets.PetInteraction
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PetHouseActivity:AppCompatActivity() {
    private lateinit var content:LinearLayout
    private lateinit var scroll:ScrollView
    private lateinit var balance:TextView
    private var tab=0
    private var busy=false
    private var heroPortrait:PetPortraitView?=null
    private var rarityFilter=-1
    private var data=PetHouse.state.value
    private val ink get()=themeColor(com.google.android.material.R.attr.colorOnSurface)
    private val muted get()=themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val primary get()=themeColor(androidx.appcompat.R.attr.colorPrimary)
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun themeColor(attr:Int):Int { val v=android.util.TypedValue();theme.resolveAttribute(attr,v,true);return v.data }
    private fun lp(w:Int=-1,h:Int=-2)=LinearLayout.LayoutParams(if(w<0) w else dp(w),if(h<0) h else dp(h))
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        PetHouse.init(applicationContext)
        tab=savedInstanceState?.getInt("pet-tab",0) ?: 0
        rarityFilter=savedInstanceState?.getInt("pet-rarity",-1) ?: -1
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface)) }
        val toolbar=MaterialToolbar(this).apply {
            title="键灵小屋";setNavigationIcon(R.drawable.ic_baseline_arrow_back_24);setNavigationOnClickListener { finish() }
            menu.add("玩法说明").setOnMenuItemClickListener { rules();true }
            menu.add("键元账本").setOnMenuItemClickListener { history(false);true }
            menu.add("设置与备份").setOnMenuItemClickListener { settings();true }
        }
        root.addView(toolbar,lp())
        val intro=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(22),dp(4),dp(22),dp(12)) }
        intro.addView(text("让每一次输入，都有小小的陪伴",13,muted),LinearLayout.LayoutParams(0,-2,1f))
        balance=text("",17,primary,true);intro.addView(balance);root.addView(intro,lp())
        val tabs=TabLayout(this).apply {
            tabMode=TabLayout.MODE_FIXED
            listOf("小屋","图鉴","孵化","商店").forEach { addTab(newTab().setText(it)) }
            getTabAt(tab)?.select()
            addOnTabSelectedListener(object:TabLayout.OnTabSelectedListener {
                override fun onTabSelected(t:TabLayout.Tab) { tab=t.position;scroll.scrollTo(0,0);render() }
                override fun onTabUnselected(t:TabLayout.Tab) {}
                override fun onTabReselected(t:TabLayout.Tab) {}
            })
        }
        root.addView(tabs,lp())
        scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(24)) }
        scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { PetHouse.state.collect { if(data!=it || content.childCount==0) { data=it;render() } } } }
        lifecycleScope.launch { runCatching { PetHouse.refresh() } }
    }
    override fun onSaveInstanceState(outState:Bundle) { outState.putInt("pet-tab",tab);outState.putInt("pet-rarity",rarityFilter);super.onSaveInstanceState(outState) }
    private fun text(value:String,size:Int=15,color:Int=ink,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size.toFloat();setTextColor(color);if(bold) setTypeface(typeface,Typeface.BOLD);setLineSpacing(dp(3).toFloat(),1f)
    }
    private fun title(value:String,sub:String="") {
        content.addView(text(value,20,ink,true),lp().apply { bottomMargin=dp(6) })
        if(sub.isNotEmpty()) content.addView(text(sub,13,muted),lp().apply { bottomMargin=dp(16) })
    }
    private fun card(parent:LinearLayout=content,build:LinearLayout.()->Unit):MaterialCardView {
        val card=MaterialCardView(this).apply { radius=dp(22).toFloat();cardElevation=0f;strokeWidth=dp(1);strokeColor=themeColor(com.google.android.material.R.attr.colorOutlineVariant);setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow)) }
        val inner=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(16),dp(18),dp(16));build() }
        card.addView(inner);parent.addView(card,lp().apply { bottomMargin=dp(14) });return card
    }
    private fun button(label:String,filled:Boolean=false,onClick:()->Unit)=MaterialButton(this,null,if(filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text=label;isAllCaps=false;minHeight=dp(48);cornerRadius=dp(15);setOnClickListener { if(!busy) onClick() }
    }
    private fun LinearLayout.buttons(vararg choices:Pair<String,()->Unit>) {
        val row=LinearLayout(this@PetHouseActivity).apply { orientation=LinearLayout.HORIZONTAL }
        choices.forEachIndexed { index,pair -> row.addView(button(pair.first,index==0,pair.second),LinearLayout.LayoutParams(0,-2,1f).apply { if(index>0) leftMargin=dp(8) }) }
        addView(row,lp())
    }
    private fun action(action:String,id:String="",result:Boolean=false) {
        if(busy) return
        busy=true
        lifecycleScope.launch {
            try {
                val old=PetHouse.state.value
                val before=old.draws
                val message=PetHouse.act(action,id)
                if(result) reveal(message,if(PetHouse.state.value.draws>before) id.toInt() else 0)
                else {
                    Toast.makeText(this@PetHouseActivity,message,Toast.LENGTH_SHORT).show()
                    val after=PetHouse.state.value
                    val reaction=when {
                        action=="use" && (after.inventory[id] ?: 0)<(old.inventory[id] ?: 0) -> if(id=="toy") com.osfans.trime.ui.pets.PetInteraction.PLAY else com.osfans.trime.ui.pets.PetInteraction.EAT
                        action=="evolve" && (after.companion()?.stage ?: 0)>(old.companion()?.stage ?: 0) -> com.osfans.trime.ui.pets.PetInteraction.LEVEL_UP
                        action=="pet" && after.pats>old.pats -> com.osfans.trime.ui.pets.PetInteraction.PET
                        else -> null
                    }
                    if(reaction!=null && tab==0) { data=after;render();heroPortrait?.react(reaction) }
                }
            } finally { busy=false }
        }
    }
    private fun reveal(message:String,count:Int) {
        val builder=MaterialAlertDialogBuilder(this).setTitle("新相遇，已珍藏")
        if(count==0) builder.setTitle("暂时无法孵化").setMessage(message) else {
            val rows=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(8),dp(22),dp(8)) }
            val results=PetHouse.state.value.history.take(count).reversed()
            results.forEach { entry ->
                val species=PetCatalog.species.firstOrNull { entry.text.startsWith(it.name+" ·") }
                if(species!=null) {
                    val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
                    row.addView(PetPortraitView(this).apply { bind(PetHouse.Pet(species.id)) },lp(84,84))
                    row.addView(text(entry.text.replace(" · ","\n"),14,ink,true),LinearLayout.LayoutParams(0,-2,1f));rows.addView(row,lp())
                }
            }
            builder.setView(ScrollView(this).apply { addView(rows) })
        }
        builder.setPositiveButton("收下这份相遇",null).show()
    }
    private fun render() {
        if(!::content.isInitialized) return
        val y=scroll.scrollY;heroPortrait=null;content.removeAllViews();balance.text="◈ ${data.coins} 键元"
        if(data.error.isNotEmpty()) { title("存档需要恢复",data.error);content.addView(button("打开备份与恢复") { startActivity(Intent(this,FullBackupActivity::class.java)) });return }
        if(!data.started) { starter();return }
        when(tab) { 0 -> home();1 -> collection();2 -> hatch();3 -> shop() }
        scroll.post { scroll.scrollTo(0,y) }
    }
    private fun starter() {
        title("选一位，陪你慢慢打字", "免费领养一位伙伴，领取 200 键元与食物。以后每天的输入，都能变成小屋的新收获。")
        listOf("sheep","cat","rabbit").forEach { id ->
            val species=PetCatalog.get(id)
            card {
                val row=LinearLayout(this@PetHouseActivity).apply { gravity=Gravity.CENTER_VERTICAL }
                row.addView(PetPortraitView(this@PetHouseActivity).apply { bind(PetHouse.Pet(id)) },lp(88,88))
                val words=LinearLayout(this@PetHouseActivity).apply { orientation=LinearLayout.VERTICAL;addView(text(species.name,22,ink,true));addView(text(species.description,13,muted)) }
                row.addView(words,LinearLayout.LayoutParams(0,-2,1f));addView(row,lp());addView(button("领养${species.name}",true) { action("start",id) },lp())
            }
        }
        content.addView(text("仅在本机记录输入数量，无联网抽奖、充值或广告。密码框、粘贴和自动输出不会产生键元。",12,muted))
    }
    private fun home() {
        val pet=data.companion() ?: return;val species=PetCatalog.get(pet.id);val level=PetCatalog.level(pet.exp)
        card {
            gravity=Gravity.CENTER_HORIZONTAL
            addView(text("TODAY'S COMPANION",11,muted,true))
            val portrait=PetPortraitView(this@PetHouseActivity).apply { bind(pet);setOnClickListener { react();action("pet") } }
            heroPortrait=portrait
            addView(portrait,lp(210,210))
            addView(text(pet.nickname.ifBlank { species.name },27,ink,true))
            addView(text("${PetCatalog.rarities[species.rarity]}  ·  Lv.$level  ·  ${listOf("初遇","微光","闪耀","璀璨")[pet.stage]}",12,primary))
            addView(text(species.description,13,muted),lp().apply { topMargin=dp(10);bottomMargin=dp(12) })
            meter(this,"饱腹",pet.hunger,"${pet.hunger} / 100")
            meter(this,"心情",pet.mood,"${pet.mood} / 100")
            meter(this,"成长",if(level==30) 100 else (((pet.exp-(level-1L)*(level-1)*300)*100)/(level.toLong()*level*300-(level-1L)*(level-1)*300)).toInt().coerceIn(0,100),if(level==30) "已满级" else "向 Lv.${level+1} 前进")
            addView(text("羁绊 ${pet.bond}  ·  同名碎片 ${pet.fragments}",13,muted),lp().apply { topMargin=dp(6);bottomMargin=dp(8) })
            buttons("摸摸 ${3-data.pats}/3" to { portrait.react();action("pet") },"起昵称" to { rename(pet) })
            buttons("挥爪" to { portrait.react(PetInteraction.GREET) },"比心" to { portrait.react(PetInteraction.LOVE) },"逗逗它" to { portrait.react(PetInteraction.PLAY) })
            addView(text("表情互动随时可玩，不消耗键元",11,muted),lp().apply { bottomMargin=dp(8) })
            buttons("喂养 / 玩耍" to { backpack() },"装扮 / 进化" to { wardrobe(pet) })
            addView(button(if(data.mode==0) "开启迷你键盘陪伴" else if(!data.miniExpanded) "展开迷你键盘" else "迷你键盘陪伴设置") {
                if(data.mode==0) action("mode","2") else if(!data.miniExpanded) action("mini_toggle") else settings()
            },lp())
        }
        title("今天的小收获")
        card {
            meter(this,"打字键元",data.dayPoints/100,"${data.dayPoints/50} / 200")
            addView(text("计奖输入 ${data.dayPoints/5} / 2000 字当量",13,muted))
            listOf(1 to "首次输入 · +10 键元",2 to "输入 300 字 · +20 键元",4 to "输入 1000 字 · +30 键元").forEach { (bit,label) ->
                addView(text((if(data.claims and bit!=0) "✓  " else "○  ")+label,14,if(data.claims and bit!=0) primary else muted),lp().apply { topMargin=dp(10) })
            }
            addView(text("奖励自动领取 · 中文 1 字 / 英文约 5 字母计 1 字当量",11,muted),lp().apply { topMargin=dp(12) })
        }
        if(!data.enabled) card { addView(text("已暂停打字奖励，宠物仍会留在小屋。",14,muted));addView(button("继续获得键元") { action("enabled") }) }
    }
    private fun meter(parent:LinearLayout,label:String,value:Int,detail:String) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        row.addView(text(label,13,muted),LinearLayout.LayoutParams(0,-2,1f));row.addView(text(detail,12,muted));parent.addView(row,lp())
        val bar=com.google.android.material.progressindicator.LinearProgressIndicator(this).apply { max=100;progress=value.coerceIn(0,100);trackCornerRadius=dp(3);trackThickness=dp(5);setIndicatorColor(primary) }
        parent.addView(bar,lp(-1,6).apply { topMargin=dp(6);bottomMargin=dp(12) })
    }
    private fun collection() {
        title("相遇图鉴","已相遇 ${data.pets.size} / ${PetCatalog.species.size} 位伙伴 · 点选伙伴即可切换陪伴")
        val filters=com.google.android.material.chip.ChipGroup(this).apply { isSingleSelection=true;isSelectionRequired=true }
        listOf("全部","普通","稀有","SR","SSR","UR").forEachIndexed { i,label ->
            filters.addView(com.google.android.material.chip.Chip(this).apply {
                text=label;isCheckable=true;isChecked=rarityFilter==i-1
                setOnClickListener { rarityFilter=i-1;scroll.scrollTo(0,0);render() }
            })
        }
        content.addView(filters,lp().apply { bottomMargin=dp(12) })
        PetCatalog.species.filter { rarityFilter<0 || it.rarity==rarityFilter }.chunked(2).forEach { pair ->
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false }
            pair.forEachIndexed { index,species ->
                val owned=data.pets.firstOrNull { it.id==species.id }
                val c=MaterialCardView(this).apply { radius=dp(22).toFloat();cardElevation=0f;strokeWidth=dp(if(data.selected==species.id) 2 else 1);strokeColor=if(data.selected==species.id) primary else themeColor(com.google.android.material.R.attr.colorOutlineVariant) }
                val inner=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(10),dp(12),dp(10),dp(12)) }
                inner.addView(PetPortraitView(this).apply { bind(owned ?: PetHouse.Pet(species.id));alpha=if(owned==null) .34f else 1f },lp(-1,110))
                inner.addView(text(owned?.nickname?.ifBlank { species.name } ?: species.name,18,ink,true))
                inner.addView(text(PetCatalog.rarities[species.rarity],11,muted))
                inner.addView(button(if(owned==null) "尚未相遇" else if(data.selected==species.id) "正在陪伴" else "选择陪伴") {
                    if(owned==null) MaterialAlertDialogBuilder(this).setTitle(species.name).setMessage(species.description+"\n可在孵化页相遇。").setPositiveButton("知道了",null).show() else action("select",species.id)
                },lp())
                c.addView(inner);row.addView(c,LinearLayout.LayoutParams(0,-2,1f).apply { if(index>0) leftMargin=dp(12) })
            };content.addView(row,lp().apply { bottomMargin=dp(12) })
        }
    }
    private fun hatch() {
        title("下一场相遇","每一个伙伴都有自己的光，重复相遇会变成同名碎片。")
        card {
            gravity=Gravity.CENTER_HORIZONTAL
            addView(PetPortraitView(this@PetHouseActivity).apply { bind(PetHouse.Pet("moon")) },lp(180,180))
            addView(text("星光孵化池",24,ink,true));addView(text("${PetCatalog.hatchPool.size} 位伙伴 · 常驻孵化",13,muted),lp().apply { bottomMargin=dp(18) })
            buttons("孵化一次 · 100" to { confirmDraw(1) },"孵化十次 · 900" to { confirmDraw(10) })
            addView(text("再 ${10-data.pitySR} 次必得 SR 或以上\n再 ${50-data.pitySSR} 次必得 SSR 或以上\n再 ${200-data.pityUR} 次必得 UR",14,primary),lp().apply { topMargin=dp(16) })
        }
        card {
            addView(text("基础概率与保底",17,ink,true))
            addView(text("普通 60% · 稀有 28% · SR 10% · SSR 1.5% · UR 猴哥 0.5%\nUR 猴哥直接入池；另有专属兑换码可直领，UR 200 次保底。\n保底连续累计，单次与十次共用进度；出现对应品质或以上时重置计数。\n重复伙伴转为 3 / 5 / 10 / 20 / 50 枚同名碎片。",13,muted),lp().apply { topMargin=dp(10) })
            addView(button("查看相遇记录") { history(true) },lp())
        }
    }
    private fun confirmDraw(n:Int) { MaterialAlertDialogBuilder(this).setTitle("花费 ${if(n==1) 100 else 900} 键元孵化？").setMessage("当前有 ${data.coins} 键元。孵化后自动保存伙伴与保底进度。").setNegativeButton("再等等",null).setPositiveButton("开始孵化") { _,_ -> action("draw",n.toString(),true) }.show() }
    private fun shop() {
        title("小屋补给站","用日常输入的小收获，换一点温柔的照顾。")
        PetCatalog.items.forEach { item -> card {
            addView(text(item.name,20,ink,true));addView(text(item.description,13,muted),lp().apply { topMargin=dp(8);bottomMargin=dp(12) })
            addView(text("背包中有 ${data.inventory[item.id] ?: 0} 份",13,muted))
            buttons("购买 · ${item.cost} 键元" to { action("buy",item.id) },"使用" to { action("use",item.id) })
        } }
        card {
            addView(text("兑换口",20,ink,true))
            addView(text("输入兑换码，领取对应伙伴；小咩、猴哥和鸡哥支持专属兑换。",13,muted),lp().apply { topMargin=dp(8);bottomMargin=dp(12) })
            if(data.redeemed) addView(text("任选伙伴兑换已领取，可继续输入其它兑换码。",13,muted))
            if(data.xiaomieRedeemed) addView(text("小咩专属兑换已领取。",13,muted))
            if(data.monkeyRedeemed) addView(text("猴哥专属兑换已领取。",13,muted))
            if(data.jigeRedeemed) addView(text("鸡哥专属兑换已领取。",13,muted))
            buttons("输入兑换码" to { redeemCode() })
        }
        content.addView(text("离线时饱腹每天降低 30，最低保留 20；伙伴不会死亡或离开。",12,muted))
    }
    private fun redeemCode() {
        val field=TextInputEditText(this).apply {
            setSingleLine(true);filters=arrayOf(android.text.InputFilter.LengthFilter(24))
            // 兑换码是纯 ASCII：VISIBLE_PASSWORD 变体促使输入法直通字母，避免被中文模式组词。
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val wrap=TextInputLayout(this).apply { hint="兑换码（英文与数字，需在英文状态输入）";addView(field);setPadding(dp(22),dp(12),dp(22),0) }
        MaterialAlertDialogBuilder(this).setTitle("兑换口").setMessage("输入有效兑换码，领取指定伙伴或选择对应图鉴伙伴。每个兑换码只能使用一次。若被转成中文，请把键盘切到英文后重输。")
            .setView(wrap).setNegativeButton("取消",null)
            .setPositiveButton("下一步") { _,_ ->
                val code=field.text?.toString()?.trim() ?: ""
                val account=PetHouse.state.value
                val gift=com.osfans.trime.data.pets.PetRedeemCode.find(code)
                when {
                    code.isEmpty() -> Toast.makeText(this,"请先输入兑换码",Toast.LENGTH_SHORT).show()
                    gift==null -> Toast.makeText(this,"兑换码无效",Toast.LENGTH_SHORT).show()
                    gift.used(account) -> Toast.makeText(this,"这个兑换码已经用过了，可以输入其它兑换码",Toast.LENGTH_SHORT).show()
                    gift.petId==null -> redeemPick(code)
                    else -> { val sp=PetCatalog.get(gift.petId);redeemConfirm(code,sp.id,sp.name) }
                }
            }.show()
    }
    private fun redeemPick(code:String) {
        val grid=android.widget.GridLayout(this).apply { columnCount=3 }
        PetCatalog.species.filter { it.rarity<4 }.forEach { sp ->
            val cell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(6),dp(10),dp(6),dp(10));isClickable=true;isFocusable=true }
            cell.addView(PetPortraitView(this).apply { halo=false;bind(PetHouse.Pet(sp.id)) },LinearLayout.LayoutParams(dp(64),dp(64)))
            cell.addView(text(sp.name,12,ink,true))
            cell.setOnClickListener { redeemConfirm(code,sp.id,sp.name) }
            grid.addView(cell,android.widget.GridLayout.LayoutParams().apply { width=dp(110);height=LinearLayout.LayoutParams.WRAP_CONTENT })
        }
        val scroll=ScrollView(this).apply { addView(grid);setPadding(dp(8),dp(4),dp(8),0) }
        MaterialAlertDialogBuilder(this).setTitle("选择你的伙伴 · 共 ${PetCatalog.species.count { it.rarity<4 }} 位").setView(scroll).setNegativeButton("取消",null).show()
    }
    private fun redeemConfirm(code:String,petId:String,name:String) {
        MaterialAlertDialogBuilder(this).setTitle("确认兑换").setMessage("使用兑换码直接获得「$name」？每个兑换码只能使用一次。")
            .setNegativeButton("再看看",null).setPositiveButton("获得") { _,_ -> action("redeem","$code:$petId") }.show()
    }
    private fun backpack() {
        val items=PetCatalog.items.filter { (data.inventory[it.id] ?: 0)>0 }
        if(items.isEmpty()) { Toast.makeText(this,"背包空空的，去商店挑点补给吧",Toast.LENGTH_SHORT).show();return }
        MaterialAlertDialogBuilder(this).setTitle("背包 · 选一份礼物").setItems(items.map { "${it.name} ×${data.inventory[it.id]} · ${it.description}" }.toTypedArray()) { _,i -> action("use",items[i].id) }.setNegativeButton("取消",null).show()
    }
    private fun rename(pet:PetHouse.Pet) {
        val field=TextInputEditText(this).apply { setText(pet.nickname.ifBlank { PetCatalog.get(pet.id).name });setSelectAllOnFocus(true);filters=arrayOf(android.text.InputFilter.LengthFilter(24));isSingleLine=true }
        val wrap=TextInputLayout(this).apply { hint="伙伴昵称（1～12 个字符）";addView(field);setPadding(dp(22),dp(12),dp(22),0) }
        MaterialAlertDialogBuilder(this).setTitle("给伙伴起个昵称").setView(wrap).setNegativeButton("取消",null).setPositiveButton("保存") { _,_ -> action("rename",field.text.toString()) }.show()
    }
    private fun wardrobe(pet:PetHouse.Pet) {
        val cost=PetCatalog.evolutionCost(pet.stage)
        val labels=arrayOf("不戴装扮","软绒围巾 · Lv.5","星星发饰 · Lv.10","星光皇冠 · Lv.20",if(cost==null) "已完成全部进化" else "进化 · $cost 同名碎片（现有 ${pet.fragments}）")
        MaterialAlertDialogBuilder(this).setTitle("装扮与成长").setItems(labels) { _,i ->
            if(i<4) action("accessory",listOf("none","scarf","star","crown")[i]) else if(cost!=null) action("evolve")
        }.setNegativeButton("取消",null).show()
    }
    private fun history(draws:Boolean) {
        val entries=if(draws) data.history else data.ledger
        val format=SimpleDateFormat("MM-dd HH:mm",Locale.getDefault())
        MaterialAlertDialogBuilder(this).setTitle(if(draws) "相遇记录 · 最近 100 次" else "键元账本 · 最近 100 条")
            .setItems(if(entries.isEmpty()) arrayOf("暂时还没有记录") else entries.map { "${format.format(Date(it.time))}  ${it.text}${if(it.delta!=0) "  ${if(it.delta>0) "+" else ""}${it.delta}" else ""}" }.toTypedArray(),null).setPositiveButton("关闭",null).show()
    }
    private fun settings() {
        MaterialAlertDialogBuilder(this).setTitle("小屋设置").setItems(arrayOf("键盘陪伴：${listOf("仅在小屋","头像入口","迷你键盘陪伴")[data.mode]}",if(data.enabled) "暂停获得键元" else "继续获得键元","备份与恢复")) { _,i ->
            when(i) {
                0 -> MaterialAlertDialogBuilder(this).setTitle("键盘陪伴方式").setSingleChoiceItems(arrayOf("仅在小屋 · 不显示头像","头像入口 · 点按展开迷你键盘","迷你键盘陪伴 · 随输入法自动显示"),data.mode) { dialog,which -> action("mode",which.toString());dialog.dismiss() }.setNegativeButton("取消",null).show()
                1 -> action("enabled")
                2 -> startActivity(Intent(this,FullBackupActivity::class.java))
            }
        }.setNegativeButton("关闭",null).show()
    }
    private fun rules() { MaterialAlertDialogBuilder(this).setTitle("小屋生活指南").setMessage("点按工具栏宠物头像可展开或收起迷你键盘，长按头像进入小屋。迷你键盘可拖动摆放，点按或长按可缩放、重置位置。真实按键会同步对应小键帽和宠物爪子的动作。\n\n中文 1 字、英文约 5 字母计 1 字当量。每 10 字当量获得 1 键元，每天打字最多 200 键元。首次输入、300 字、1000 字里程碑分别自动领取 10、20、30 键元。\n\n奖励从领养伙伴后开始累计；粘贴、语音、自动输出、密码框和禁止个性化学习的输入框不计入。\n\n每天可免费摸摸 3 次。离线饱腹每天降低 30，最低 20；宠物不会死亡。升级可解锁围巾、星饰和皇冠，重复伙伴变为同名碎片，可进行 3 次进化。\n\n数据保存在本机，并镜像到 rime/.mie/宠物；整包备份包含钱包、伙伴、背包和保底进度。换机请使用“备份与恢复”整体导入，存档不会合并叠加。\n\n所有抽取只消耗打字获得的键元，不提供充值或交易。").setPositiveButton("知道了",null).show() }
    companion object { fun start(context:Context) { context.startActivity(Intent(context,PetHouseActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
