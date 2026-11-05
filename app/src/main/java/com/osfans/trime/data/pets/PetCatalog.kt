/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.pets

object PetCatalog {
    data class Species(val id:String,val name:String,val rarity:Int,val description:String,val color:Int,val hatchable:Boolean=true)
    val rarities=listOf("普通","稀有","珍奇 SR","星耀 SSR","典藏 UR")
    val species=listOf(
        Species("sheep","绵绵",0,"把每一个字，织成柔软的云。",0xffe9d9d1.toInt()),
        Species("cat","奶盖",0,"喜欢趴在空格上打盹的小猫。",0xffead8b8.toInt()),
        Species("rabbit","糯糯",0,"收集标点，也收集你的好心情。",0xffedd0d7.toInt()),
        Species("dog","布丁",0,"听见键盘声，就会摇起尾巴。",0xffdcbf97.toInt()),
        Species("hamster","栗子",0,"把今天的小收获藏进腮帮。",0xffdfc5ad.toInt()),
        Species("bird","青团",0,"停在指尖，唱一首小小的歌。",0xffbfd6c8.toInt()),
        Species("fox","暮暮",1,"尾巴里藏着黄昏的最后一束光。",0xffdda98e.toInt()),
        Species("otter","泡泡",1,"抱着小石头，等待下一次相逢。",0xffb6c7d8.toInt()),
        Species("panda","竹墨",1,"一半是墨，一半是温柔。",0xffbdc8ba.toInt()),
        Species("dragon","云芽",2,"从一颗句号里孵化的小云龙。",0xffb2cec9.toInt()),
        Species("moon","望舒",2,"把月亮折成一枚安静的书签。",0xffc9c3df.toInt()),
        Species("whale","星眠",3,"载着你的字句，游过漫长星河。",0xffa9bedb.toInt()),
        Species("duck","蛋挞",0,"摇摇摆摆，把回车键踩得咔咔响。",0xfff2d47e.toInt()),
        Species("frog","抹茶",0,"在雨天的按键上，蹦出绿色的小音符。",0xffb5cfa4.toInt()),
        Species("penguin","芝麻",0,"走路的姿势，像没对齐的缩进。",0xff9aa6b8.toInt()),
        Species("turtle","墩墩",0,"慢一点也没关系，句子总会等到句号。",0xffa3c6b2.toInt()),
        Species("bear","可可",0,"抱着键盘也能睡着的小熊。",0xffc4a486.toInt()),
        Species("cow","奶糖",0,"斑纹是打翻的牛奶，呼噜里都是甜味。",0xffefe9dc.toInt()),
        Species("hedgehog","球球",0,"把刺收起来的时候，最想被你摸摸。",0xffd8bca4.toInt()),
        Species("squirrel","松果",0,"囤了满仓果实，最宝贝的是你打的字。",0xffd2a679.toInt()),
        Species("pig","桃酥",0,"打个哈欠，鼻尖冒出一个粉色泡泡。",0xfff2c9c0.toInt()),
        Species("deer","茸茸",1,"新长的茸角上，停着一点清晨的光。",0xffd9bc9c.toInt()),
        Species("koala","桉桉",1,"抱着桉树枝，梦话都是软的。",0xffb8bfc4.toInt()),
        Species("raccoon","浣月",1,"在溪水里洗月亮，洗出一圈圈涟漪。",0xffb3acb8.toInt()),
        Species("redpanda","焰焰",1,"尾巴一圈一圈，是烧得刚刚好的小火。",0xffe0966f.toInt()),
        Species("seal","汐汐",1,"把潮汐声收进肚子里，拍手时放给你听。",0xffbfd4dd.toInt()),
        Species("alpaca","云绒",1,"剪下一小撮云，想织成你冬天的围巾。",0xfff0e7da.toInt()),
        Species("tiger","山君",2,"额头的“王”字，是揉皱又抚平的草稿。",0xfff0be7e.toInt()),
        Species("crane","霜羽",2,"单脚站着，把黄昏站成一首诗。",0xffece8ef.toInt()),
        Species("jellyfish","浮灯",2,"提着一盏小灯，照亮深夜的输入框。",0xffd8c7e8.toInt()),
        Species("koi","锦鳞",2,"尾巴一摆，好运就顺着一个词游来。",0xfff2c9c1.toInt()),
        Species("snowleopard","雪隐",2,"雪地里的脚印，是一行没人读过的句子。",0xffcdd8e0.toInt()),
        Species("unicorn","云霓",2,"鬃毛扫过的地方，会留下浅浅的虹。",0xffe6d9f2.toInt()),
        Species("phoenix","青鸾",3,"每一根羽色，都是重新开始的一行。",0xff85c6be.toInt()),
        Species("qilin","玉麟",3,"踏过的每个字符，都轻轻发着光。",0xff96d0bc.toInt()),
        Species("ninefox","涂山",3,"九条尾巴，藏着九个没讲完的故事。",0xffe8b25e.toInt()),
        Species("panther","夜阑",3,"墨色的夜巡者，替你守着未完成的句子。",0xff6fe3e1.toInt()),
        Species("emberdragon","烛龙",3,"一盏不灭的灯，照亮最难的那段路。",0xffff9a4d.toInt()),
        Species("iceelk","冰璃",3,"鹿角上悬着极光，脚印开成霜花。",0xff9ad4f5.toInt()),
        Species("stormlion","雷霄",3,"鬃毛里藏着夏天第一声惊雷。",0xfff2c14e.toInt()),
        Species("glowmoth","幽萤",3,"翅膀一合，就收拢了一小片星河。",0xffb78fe8.toInt()),
        Species("blacktortoise","玄武",3,"壳上刻着星图，走再远也认得回家的字。",0xffd9b868.toInt()),
        Species("baize","白泽",3,"读过世间所有的书，最爱你写的那一行。",0xffe8d5a8.toInt()),
        Species("seadragon","沧溟",3,"掌心的珍珠，是深海替你珍藏的心事。",0xff6fd8e8.toInt()),
        Species("sunfalcon","曜金",3,"振翅时，把阳光抖落进每一个字符。",0xffffc94d.toInt()),
        Species("dreamtapir","梦泽",3,"吃掉噩梦，把最软的梦留在输入框里。",0xffc99be8.toInt()),
        Species("snowowl","雪鸮",2,"雪夜里睁着眼，做你安静的标点。",0xffa8d4f0.toInt()),
        Species("frostwolf","银霜",2,"呼出的白气，都是没说完的悄悄话。",0xffbfe0f0.toInt()),
        Species("jadeserpent","青珮",2,"铃铛一响，好运就在下一个词里等你。",0xff7fcba4.toInt()),
        Species("magpie","踏雪",2,"报喜的羽翼，掠过今天最好的一句。",0xff6fb8d9.toInt()),
        Species("salamander","朱砂",2,"背上点点朱砂，都是热烈的批注。",0xffff8a5c.toInt()),
        Species("cometmouse","星屑",2,"拖着彗星尾巴，把琐事都写成童话。",0xffc9b8f0.toInt()),
        Species("mistferret","岚烟",2,"从山雾里来，替你藏起打错的那一行。",0xffcfc8e8.toInt()),
        Species("dragonfly","琥珀",2,"薄翼载着远古的光，停在指尖稍作停留。",0xfff2c14e.toInt()),
        Species("axolotl","珠露",2,"腮边的小花，是春天写在信上的落款。",0xffffb3c6.toInt()),
        Species("hummingbird","紫电",2,"快过闪电的翅膀，追上每一个灵感。",0xffc97fe8.toInt()),
        Species("xiaomie","小咩",4,"把你的每次敲键，都变成闪闪发光的小心意。",0xffedc48d.toInt(),false),
        Species("monkey","猴哥",4,"金箍棒换成键盘，一个字就是一个筋斗。",0xffe05560.toInt()),
    )
    val hatchPool get()=species.filter { it.id!="xiaomie" }
    fun get(id:String)=species.first { it.id==id }
    data class Item(val id:String,val name:String,val cost:Int,val hunger:Int,val mood:Int,val bond:Int,val description:String)
    val items=listOf(
        Item("food","小鱼饼",5,30,0,2,"饱腹 +30 · 羁绊 +2"),
        Item("meal","暖心餐",15,60,10,5,"饱腹 +60 · 心情 +10 · 羁绊 +5"),
        Item("snack","星星糖",10,0,20,8,"心情 +20 · 羁绊 +8"),
        Item("toy","绒线球",20,0,30,15,"心情 +30 · 羁绊 +15"),
    )
    fun level(exp:Long)=(1+kotlin.math.sqrt(exp.coerceAtLeast(0)/300.0).toInt()).coerceAtMost(30)
    fun evolutionCost(stage:Int)=listOf(10,25,50).getOrNull(stage)
}
