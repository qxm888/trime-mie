/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.pets

import android.content.Context
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.data.stats.WordCloudVocabulary
import com.osfans.trime.util.readAtomicText
import com.osfans.trime.util.writeAtomicText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate

/** Offline economy. Only integer input counts cross the IME boundary; never retain typed text. */
object PetHouse {
    const val FILE="pet_house.json"
    data class Pet(val id:String,val nickname:String="",val exp:Long=0,val hunger:Int=100,val mood:Int=80,val bond:Int=0,val fragments:Int=0,val stage:Int=0,val accessory:String="none")
    data class Entry(val time:Long,val text:String,val delta:Int=0)
    data class State(
        val started:Boolean=false,val enabled:Boolean=true,val coins:Int=0,val selected:String="",val pets:List<Pet> = emptyList(),
        val inventory:Map<String,Int> = emptyMap(),val day:Long=0,val dayPoints:Int=0,val claims:Int=0,val pats:Int=0,
        val totalPoints:Long=0,val pitySR:Int=0,val pitySSR:Int=0,val draws:Int=0,val mode:Int=0,
        val ledger:List<Entry> = emptyList(),val history:List<Entry> = emptyList(),val error:String="",
        val miniExpanded:Boolean=false,val miniScale:Int=100,
        val miniX:Float=-1f,val miniY:Float=-1f,val miniLandscapeX:Float=-1f,val miniLandscapeY:Float=-1f,
        val redeemed:Boolean=false,
    ) { fun companion()=pets.firstOrNull { it.id==selected } }
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val countsLock=Any()
    private val random=SecureRandom()
    private var context:Context?=null
    @Volatile private var initialized=false
    @Volatile private var allowed=false
    private var proof=0
    private var proofTime=0L
    private val pending=linkedMapOf<Long,Int>()
    private var drain:Job?=null
    private var lastMirror=0L
    private fun today()=LocalDate.now().toEpochDay()

    @Synchronized fun init(ctx:Context) {
        if(initialized || com.osfans.trime.util.BootStorage.locked(ctx)) return
        context=ctx.applicationContext
        val file=File(ctx.filesDir,FILE)
        mutable.value=try { if(file.exists() || File(file.path+".bak").exists()) {
            require(maxOf(file.length(),File(file.path+".bak").length())<=512*1024);decode(file.readAtomicText() ?: error("存档为空"))
        } else State(day=today()) }
            catch(e:Exception) { State(error="宠物存档读取失败，原文件已保留。请通过备份恢复存档。") }
        initialized=true
    }
    fun editor(info:EditorInfo?) { allowed=info!=null && WordCloudVocabulary.allows(info.inputType,info.imeOptions); clearProof() }
    fun clearProof() { synchronized(countsLock) { proof=0 } }
    fun manualKey(value:Int,modifiers:Int) {
        val s=mutable.value
        if(!allowed || !s.started || !s.enabled || s.error.isNotEmpty()) return
        val m=KeyModifiers.of(modifiers)
        if(m.release) return
        synchronized(countsLock) {
            if(m.ctrl || m.alt || m.meta || value==0xff1b) { proof=0; return }
            if(SystemClock.elapsedRealtime()-proofTime>120_000) proof=0
            proofTime=SystemClock.elapsedRealtime()
            if(value==0xff08) proof=(proof-1).coerceAtLeast(0)
            else if(value in 0x21..0x7e || value in 0x1000000..0x110ffff) proof=(proof+1).coerceAtMost(512)
        }
    }
    fun engineCommit(text:String) {
        if(!allowed || !mutable.value.started || !mutable.value.enabled) return
        val count=synchronized(countsLock) {
            val n=if(SystemClock.elapsedRealtime()-proofTime<=120_000) proof else 0
            proof=0; n
        }
        if(count==0) return
        var i=0; var chars=0; var points=0
        while(i<text.length && chars<count && chars<512) {
            val cp=text.codePointAt(i); i+=Character.charCount(cp); chars++
            if(cp in 0x3400..0x4dbf || cp in 0x4e00..0x9fff || cp in 0xf900..0xfaff || cp in 0x20000..0x2ffff || cp in 0x30000..0x3347f) points+=5
            else if(Character.isLetterOrDigit(cp)) points++
        }
        if(points==0) return
        synchronized(countsLock) {
            val day=today(); pending[day]=((pending[day] ?: 0)+points).coerceAtMost(10000)
            if(drain==null) {
                drain=scope.launch(start=CoroutineStart.LAZY) {
                    while(true) {
                        delay(5000)
                        val success=runCatching { mutex.withLock { mergeAndSave() } }.isSuccess
                        synchronized(countsLock) {
                            if(!success || pending.isEmpty()) { drain=null;return@launch }
                        }
                    }
                }
                drain?.start()
            }
        }
    }
    // Native ASCII fallback has no commit response. Consume proof exactly once.
    fun directKey(value:Int,modifiers:Int) {
        val m=KeyModifiers.of(modifiers)
        if(!m.release && !m.ctrl && !m.alt && !m.meta && value in 0x21..0x7e) engineCommit(value.toChar().toString())
    }
    private fun normalize(s:State):State {
        val now=maxOf(today(),s.day)
        val days=(now-s.day).coerceIn(0,30).toInt()
        if(days==0) return s
        return s.copy(day=now,dayPoints=0,claims=0,pats=0,pets=s.pets.map { it.copy(hunger=(it.hunger-days*30).coerceAtLeast(20),mood=(it.mood-days*5).coerceAtLeast(30)) })
    }
    private fun log(s:State,text:String,delta:Int=0)=s.copy(coins=(s.coins.toLong()+delta).coerceIn(0,1_000_000_000).toInt(),ledger=(listOf(Entry(System.currentTimeMillis(),text,delta))+s.ledger).take(100))
    private fun reward(s:State,points:Int):State {
        if(!s.started || !s.enabled) return s
        val add=points.coerceIn(0,10000-s.dayPoints)
        var next=s.copy(dayPoints=s.dayPoints+add,totalPoints=(s.totalPoints+points).coerceAtMost(1_000_000_000_000),pets=s.pets.map { if(it.id==s.selected) it.copy(exp=(it.exp+points).coerceAtMost(1_000_000_000)) else it })
        val coins=next.dayPoints/50-s.dayPoints/50
        if(coins>0) next=log(next,"打字收获",coins)
        listOf(1 to (1 to 10),1500 to (2 to 20),5000 to (4 to 30)).forEach { (threshold,gift) ->
            if(next.dayPoints>=threshold && next.claims and gift.first==0) { next=log(next,"每日里程碑奖励",gift.second).copy(claims=next.claims or gift.first) }
        }
        return next
    }
    private fun mergeAndSave() {
        val batch=synchronized(countsLock) { pending.toMap().also { pending.clear() } }
        if(batch.isEmpty()) return
        try {
            var next=mutable.value
            if(next.error.isNotEmpty()) return
            batch.toSortedMap().forEach { (day,points) ->
                // Late batches from yesterday must not consume today's earning limit.
                if(day>=next.day) {
                    if(day>next.day) next=next.copy(day=day,dayPoints=0,claims=0,pats=0,pets=next.pets.map { it.copy(hunger=(it.hunger-((day-next.day).coerceAtMost(30)*30).toInt()).coerceAtLeast(20),mood=(it.mood-((day-next.day).coerceAtMost(30)*5).toInt()).coerceAtLeast(30)) })
                    next=reward(next,points)
                }
            }
            save(normalize(next))
        } catch(e:Exception) {
            synchronized(countsLock) { batch.forEach { (day,n) -> pending[day]=((pending[day] ?: 0)+n).coerceAtMost(10000) } }
            throw e
        }
    }
    private fun save(s:State,forceMirror:Boolean=false) {
        val ctx=context ?: error("宠物系统还在准备，请稍后重试")
        File(ctx.filesDir,FILE).writeAtomicText(encode(s))
        mutable.value=s
        // Mirror is best effort; primary atomic private save always remains authoritative.
        runCatching {
            if((forceMirror || SystemClock.elapsedRealtime()-lastMirror>30_000) && com.osfans.trime.core.data.UserDataManager.isStorageAvailable()) {
                val dir=File(com.osfans.trime.core.data.MieUserDirectory.directory(ctx),"宠物").apply { mkdirs() }
                File(dir,FILE).writeAtomicText(encode(s));lastMirror=SystemClock.elapsedRealtime()
            }
        }
    }
    fun requestFlush() { scope.launch { runCatching { mutex.withLock { mergeAndSave() } } } }
    /** Freeze the full account under the same mutex used by purchases and hatch operations. */
    fun backupSnapshot():String?=runBlocking(Dispatchers.IO) {
        mutex.withLock {
            check(mutable.value.error.isEmpty()) { "宠物存档损坏，请先恢复存档" }
            mergeAndSave()
            if(!mutable.value.started && context?.let { File(it.filesDir,FILE).exists() }!=true) null else encode(mutable.value)
        }
    }
    suspend fun refresh()=withContext(Dispatchers.IO) { mutex.withLock { if(mutable.value.error.isEmpty()) { mergeAndSave(); val n=normalize(mutable.value); if(n!=mutable.value) save(n) } } }
    suspend fun act(action:String,id:String=""):String=withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                check(initialized) { "宠物系统还在准备" }; check(mutable.value.error.isEmpty()) { mutable.value.error }
                mergeAndSave()
                var s=normalize(mutable.value)
                var message="已保存"
                when(action) {
                    "start" -> {
                        check(!s.started) { "已经领取过伙伴" }; check(id in listOf("sheep","cat","rabbit"))
                        s=log(s.copy(started=true,selected=id,pets=listOf(Pet(id)),inventory=mapOf("food" to 3,"toy" to 1)),"欢迎来到键灵小屋",200)
                        message="${PetCatalog.get(id).name}成为了你的第一位伙伴"
                    }
                    "enabled" -> s=s.copy(enabled=!s.enabled)
                    "mode" -> { val mode=id.toInt(); require(mode in 0..2); s=s.copy(mode=mode,miniExpanded=mode==2) }
                    "mini_toggle" -> { check(s.started && s.mode>0) { "请先在小屋设置中开启键盘陪伴" };s=s.copy(miniExpanded=!s.miniExpanded) }
                    "mini_scale" -> { val scale=id.toInt();require(scale in 60..150);s=s.copy(miniScale=scale) }
                    "mini_reset" -> s=s.copy(miniX=-1f,miniY=-1f,miniLandscapeX=-1f,miniLandscapeY=-1f)
                    "mini_position" -> {
                        val parts=id.split('|');require(parts.size==3 && parts[0] in listOf("p","l"))
                        val x=parts[1].toFloat();val y=parts[2].toFloat();require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
                        s=if(parts[0]=="p") s.copy(miniX=x,miniY=y) else s.copy(miniLandscapeX=x,miniLandscapeY=y)
                    }
                    "select" -> { check(s.pets.any { it.id==id }); s=s.copy(selected=id); message="已更换陪伴伙伴" }
                    "rename" -> {
                        val name=id.trim(); require(name.codePointCount(0,name.length) in 1..12 && name.none { it.isISOControl() }) { "名字请使用 1～12 个字符" }
                        s=s.copy(pets=s.pets.map { if(it.id==s.selected) it.copy(nickname=name) else it })
                    }
                    "pet" -> {
                        check(s.pats<3) { "今天已互动 3 次，明天再来吧" }
                        s=s.copy(pats=s.pats+1,pets=s.pets.map { if(it.id==s.selected) it.copy(mood=(it.mood+10).coerceAtMost(100),bond=(it.bond+5).coerceAtMost(1_000_000)) else it }); message="它开心地蹭了蹭你 · 羁绊 +5"
                    }
                    "buy","use" -> {
                        val item=PetCatalog.items.first { it.id==id }
                        val count=s.inventory[id] ?: 0
                        if(action=="buy") {
                            check(s.coins>=item.cost) { "键元不足，再和伙伴打会儿字吧" }; check(count<999) { "背包已满" }
                            s=log(s,"购买${item.name}",-item.cost).copy(inventory=s.inventory+(id to count+1)); message="${item.name}已放入背包"
                        } else {
                            check(count>0) { "背包里还没有${item.name}" }
                            s=s.copy(inventory=s.inventory+(id to count-1),pets=s.pets.map { if(it.id==s.selected) it.copy(hunger=(it.hunger+item.hunger).coerceAtMost(100),mood=(it.mood+item.mood).coerceAtMost(100),bond=(it.bond+item.bond).coerceAtMost(1_000_000)) else it })
                            message="${item.name} · ${item.description}"
                        }
                    }
                    "evolve" -> {
                        val pet=s.companion() ?: error("请选择伙伴"); val cost=PetCatalog.evolutionCost(pet.stage) ?: error("已完成全部进化")
                        check(pet.fragments>=cost) { "还需要 ${cost-pet.fragments} 枚同名碎片" }
                        s=s.copy(pets=s.pets.map { if(it.id==pet.id) it.copy(stage=it.stage+1,fragments=it.fragments-cost) else it }); message="进化成功，伙伴的星光更亮了"
                    }
                    "accessory" -> {
                        val level=PetCatalog.level(s.companion()?.exp ?: 0)
                        check(id=="none" || id=="scarf" && level>=5 || id=="star" && level>=10 || id=="crown" && level>=20) { "等级还不足以解锁这个装扮" }
                        s=s.copy(pets=s.pets.map { if(it.id==s.selected) it.copy(accessory=id) else it })
                    }
                    "draw" -> {
                        val n=id.toInt(); require(n==1 || n==10); val cost=if(n==1) 100 else 900
                        check(s.coins>=cost) { "键元不足，单次需 100 键元，十次需 900 键元" }
                        s=log(s,if(n==1) "孵化 1 次" else "孵化 10 次",-cost)
                        val results=mutableListOf<String>()
                        repeat(n) {
                            val roll=random.nextInt(10000)
                            var rarity=when { roll<6000 -> 0; roll<8800 -> 1; roll<9800 -> 2; else -> 3 }
                            if(s.pitySSR>=49) rarity=3 else if(s.pitySR>=9) rarity=maxOf(rarity,2)
                            val pool=PetCatalog.species.filter { it.rarity==rarity }; val species=pool[random.nextInt(pool.size)]
                            val exists=s.pets.any { it.id==species.id }; val fragments=listOf(3,5,10,20)[rarity]
                            val label="${species.name} · ${PetCatalog.rarities[rarity]}"+(if(exists) " → 碎片 ×$fragments" else " · 新伙伴")
                            results+=label
                            s=s.copy(pets=if(exists) s.pets.map { if(it.id==species.id) it.copy(fragments=(it.fragments+fragments).coerceAtMost(1_000_000)) else it } else s.pets+Pet(species.id),
                                pitySR=if(rarity>=2) 0 else s.pitySR+1,pitySSR=if(rarity==3) 0 else s.pitySSR+1,draws=(s.draws+1).coerceAtMost(1_000_000_000),
                                history=(listOf(Entry(System.currentTimeMillis(),label))+s.history).take(100))
                        }
                        message=results.joinToString("\n")
                    }
                    "redeem" -> {
                        val parts=id.split(":"); require(parts.size==2) { "兑换格式不正确" }
                        val (code,petId)=parts
                        check(!s.redeemed) { "这个兑换码已经用过了" }
                        check(code=="qxm888") { "兑换码无效" }
                        val species=PetCatalog.species.firstOrNull { it.id==petId } ?: error("没有这位伙伴")
                        val owned=s.pets.any { it.id==species.id }
                        s=if(owned) s.copy(pets=s.pets.map { if(it.id==species.id) it.copy(fragments=(it.fragments+20).coerceAtMost(1_000_000)) else it })
                          else s.copy(pets=s.pets+Pet(species.id),started=true,selected=species.id)
                        s=log(s.copy(redeemed=true),"兑换码 · ${species.name}",0)
                        message=if(owned) "已拥有${species.name}，转化为同名碎片 ×20" else "${species.name}来到你的小屋！"
                    }
                    else -> error("未知操作")
                }
                check(s.started || action=="enabled" || action=="mode") { "请先选择初始伙伴" }
                save(s,forceMirror=true); message
            }.getOrElse { it.message ?: "保存失败，请检查可用空间后重试" }
        }
    }
    fun validateBackup(raw:String) { decode(raw) }
    private fun encode(s:State):String {
        fun entries(list:List<Entry>)=JSONArray().apply { list.forEach { put(JSONObject().put("time",it.time).put("text",it.text).put("delta",it.delta)) } }
        return JSONObject().put("version",1).put("started",s.started).put("enabled",s.enabled).put("coins",s.coins).put("selected",s.selected)
            .put("day",s.day).put("dayPoints",s.dayPoints).put("claims",s.claims).put("pats",s.pats).put("totalPoints",s.totalPoints)
            .put("miniExpanded",s.miniExpanded).put("miniScale",s.miniScale).put("miniX",s.miniX).put("miniY",s.miniY).put("miniLandscapeX",s.miniLandscapeX).put("miniLandscapeY",s.miniLandscapeY)
            .put("redeemed",s.redeemed)
            .put("pitySR",s.pitySR).put("pitySSR",s.pitySSR).put("draws",s.draws).put("mode",s.mode).put("inventory",JSONObject(s.inventory))
            .put("pets",JSONArray().apply { s.pets.forEach { p -> put(JSONObject().put("id",p.id).put("nickname",p.nickname).put("exp",p.exp).put("hunger",p.hunger).put("mood",p.mood).put("bond",p.bond).put("fragments",p.fragments).put("stage",p.stage).put("accessory",p.accessory)) } })
            .put("ledger",entries(s.ledger)).put("history",entries(s.history)).toString()
    }
    private fun decode(raw:String):State {
        require(raw.length<=512*1024)
        val o=JSONObject(raw); require(o.getInt("version")==1)
        fun number(key:String,max:Long,default:Long=0)=o.optLong(key,default).also { require(it in 0..max) }
        fun entries(key:String):List<Entry> { val a=o.optJSONArray(key) ?: JSONArray(); require(a.length()<=100); return (0 until a.length()).map { val e=a.getJSONObject(it); Entry(e.getLong("time"),e.getString("text").also { t -> require(t.length<=200) },e.optInt("delta")) } }
        val a=o.getJSONArray("pets"); require(a.length()<=PetCatalog.species.size)
        val pets=(0 until a.length()).map { i -> val p=a.getJSONObject(i); val id=p.getString("id"); require(PetCatalog.species.any { it.id==id })
            fun n(key:String,max:Int)=p.optInt(key).also { require(it in 0..max) }
            Pet(id,p.optString("nickname").also { require(it.length<=48) },p.optLong("exp").also { require(it in 0..1_000_000_000) },n("hunger",100),n("mood",100),n("bond",1_000_000),n("fragments",1_000_000),n("stage",3),p.optString("accessory","none").also { require(it in listOf("none","scarf","star","crown")) }) }
        require(pets.map { it.id }.distinct().size==pets.size)
        val selected=o.optString("selected"); val started=o.getBoolean("started"); require(!started || pets.any { it.id==selected })
        val inv=o.optJSONObject("inventory") ?: JSONObject(); require(inv.length()<=4)
        val inventory=inv.keys().asSequence().associateWith { key -> require(PetCatalog.items.any { it.id==key }); inv.getInt(key).also { require(it in 0..999) } }
        fun position(key:String)=o.optDouble(key,-1.0).also { require(it.isFinite() && (it==-1.0 || it in 0.0..1.0)) }.toFloat()
        return State(started,o.optBoolean("enabled",true),number("coins",1_000_000_000).toInt(),selected,pets,inventory,number("day",1_000_000,today()),number("dayPoints",10000).toInt(),number("claims",7).toInt(),number("pats",3).toInt(),number("totalPoints",1_000_000_000_000),number("pitySR",9).toInt(),number("pitySSR",49).toInt(),number("draws",1_000_000_000).toInt(),number("mode",2).toInt(),entries("ledger"),entries("history")).copy(
            miniExpanded=o.optBoolean("miniExpanded",o.optInt("mode")==2),miniScale=number("miniScale",150,100).toInt().also { require(it>=60) },
            miniX=position("miniX"),miniY=position("miniY"),miniLandscapeX=position("miniLandscapeX"),miniLandscapeY=position("miniLandscapeY"),
            redeemed=o.optBoolean("redeemed",false))
    }
}
