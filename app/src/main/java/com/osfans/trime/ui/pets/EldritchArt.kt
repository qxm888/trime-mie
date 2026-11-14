/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.pets.EldritchCatalog
import com.osfans.trime.data.pets.PetHouse
import kotlin.math.sin

/**
 * 诡异典藏专用渲染器。上游的 PetPortraitView.drawAt() 与
 * PetTentacleMiniView.drawArm() 各用一行转交到这里，其余逻辑全部留在本文件。
 *
 * 与常规键灵的区别：透明位图立绘（而非三层矢量）、真/假两道错位重影、肩上三簇幽火、
 * 交互时飘铜钱而非爱心，以及打字时用「玄牝式触手」替代圆爪——触手从立绘道袍的
 * 裂口里成簇长出，而不是从键盘两侧伸出的两根独立手臂。
 */
internal object EldritchArt {
    private val cache=mutableMapOf<String,Bitmap?>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val matrix=Matrix()

    fun handles(id:String)=EldritchCatalog.handles(id)

    private fun bitmap(context:Context,spec:EldritchCatalog.Spec):Bitmap? {
        if(!cache.containsKey(spec.id)) {
            cache[spec.id]=runCatching {
                val opts=BitmapFactory.Options().apply {
                    inSampleSize=2;inPreferredConfig=Bitmap.Config.ARGB_8888
                }
                val stream=context.assets.open(spec.asset)
                try { BitmapFactory.decodeStream(stream,null,opts) } finally { stream.close() }
            }.getOrNull()
        }
        return cache[spec.id]
    }

    fun draw(context:Context,canvas:Canvas,w:Float,h:Float,pet:PetHouse.Pet,pose:PetPose) {
        val spec=EldritchCatalog.get(pet.id) ?: return
        val bmp=bitmap(context,spec) ?: return
        val side=minOf(w,h)
        val cx=side/2f
        canvas.save();canvas.translate((w-side)/2f,(h-side)/2f)

        // 脚下的暗红雾，替代常规键灵的浅色光晕。
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.FILL;paint.color=spec.aura
        for(i in 3 downTo 1) {
            paint.alpha=16*i
            val r=side*(.30f+i*.07f)
            canvas.drawOval(cx-r,side*.62f-r*.42f,cx+r,side*.62f+r*.42f,paint)
        }

        val scale=side/bmp.width.toFloat()
        matrix.reset();matrix.setScale(scale,scale)
        fun blit() { canvas.drawBitmap(bmp,matrix,paint) }

        // 迷惘天道：主体左右各一道半透明剪影重影，一红一青，对应真假两界。
        paint.alpha=(38+pose.amount*34).toInt()
        paint.colorFilter=PorterDuffColorFilter(spec.ghostA,PorterDuff.Mode.SRC_IN)
        canvas.save();canvas.translate(-side*.022f-pose.gaze*1.2f,side*.004f);blit();canvas.restore()
        paint.colorFilter=PorterDuffColorFilter(spec.ghostB,PorterDuff.Mode.SRC_IN)
        canvas.save();canvas.translate(side*.022f-pose.gaze*1.2f,-side*.004f);blit();canvas.restore()
        paint.colorFilter=null

        // 主体：沿用上游的姿态数据，但幅度更克制，显得非人而非弹跳。
        paint.alpha=255
        canvas.save();canvas.translate(cx,side*.52f)
        canvas.rotate(pose.angle*.55f)
        canvas.scale(1f+pose.squash*.7f,1f-pose.squash*.7f)
        canvas.translate(0f,pose.y*.8f+sin(pose.phase*6.283f)*side*.004f)
        canvas.translate(-cx,-side*.52f)
        blit();canvas.restore()

        embers(canvas,side,pose)
        if(pose.amount>.01f) relics(canvas,side,pose,spec)
        canvas.restore()
        paint.reset();paint.isAntiAlias=true;paint.colorFilter=null
    }

    /** 心素「肩上三把火」，画成三簇幽火。 */
    private fun embers(canvas:Canvas,side:Float,pose:PetPose) {
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.FILL
        val xs=floatArrayOf(side*.30f,side*.50f,side*.70f)
        val ys=floatArrayOf(side*.235f,side*.180f,side*.235f)
        for(i in 0..2) {
            val sway=sin(pose.phase*5f+i*1.9f)*side*.008f
            val r=side*(.016f+(i%2)*.004f)+pose.amount*side*.008f
            paint.color=0xffb86b3a.toInt();paint.alpha=95
            canvas.drawCircle(xs[i]+sway,ys[i]-sway*.6f,r*1.9f,paint)
            paint.color=0xffffd08a.toInt();paint.alpha=215
            path.reset()
            path.moveTo(xs[i]+sway,ys[i]-r*1.7f)
            path.quadTo(xs[i]+sway+r*.9f,ys[i],xs[i]+sway,ys[i]+r*.8f)
            path.quadTo(xs[i]+sway-r*.9f,ys[i],xs[i]+sway,ys[i]-r*1.7f)
            canvas.drawPath(path,paint)
        }
        paint.alpha=255
    }

    /** 交互特效：上浮的铜钱与符灰，不画常规键灵的爱心/星星。 */
    private fun relics(canvas:Canvas,side:Float,pose:PetPose,spec:EldritchCatalog.Spec) {
        paint.reset();paint.isAntiAlias=true;paint.style=Paint.Style.STROKE
        paint.strokeWidth=side*.006f;paint.color=spec.ghostA
        paint.alpha=(pose.amount*190).toInt()
        for(i in 0..2) {
            val t=(pose.phase+i*.33f)%1f
            val x=side*(.18f+i*.32f)+sin(pose.phase*4f+i)*side*.01f
            val y=side*(.76f-t*.40f)
            val r=side*.017f
            canvas.drawCircle(x,y,r,paint)
            canvas.drawRect(x-r*.34f,y-r*.34f,x+r*.34f,y+r*.34f,paint)
        }
        paint.alpha=255;paint.style=Paint.Style.FILL
    }

    /**
     * 打字触手：左右各 3 条，合计 6 条。
     *
     * - 每次按键只随机挑 1 条（activeLocal，由 PetMiniKeyboardView 掷出）伸向真实
     *   键位；其余继续做待机卷动，不参与按压。
     * - 按空格时 6 条全体沿空格键横向排开、一起压下。
     * - idlePhase 由墙钟驱动，View 附带待机刷新循环，所以不打字时触手也一直在动。
     * - 起点仍是 sx,sy 的道袍裂口，上游的挥动、比心等交互照常生效。
     */
    fun drawTentacle(
        canvas:Canvas,p:Paint,pth:Path,
        sx:Float,sy:Float,ex:Float,ey:Float,pressure:Float,
        accent:Int,phase:Float,leftSide:Boolean,dark:Boolean,
        activeLocal:Int=-1,isSpace:Boolean=false,idlePhase:Float=0f,
    ) {
        val dir=if(leftSide) -1f else 1f
        val ink=if(dark) 0xff100a0e.toInt() else 0xff241a20.toInt()
        val tendril=ColorUtils.blendARGB(0xff7b646f.toInt(),accent,.22f)
        val root=ColorUtils.blendARGB(tendril,ink,.46f)
        val shade=ColorUtils.blendARGB(root,0xff080407.toInt(),.58f)
        val flesh=ColorUtils.blendARGB(tendril,0xffc0a7af.toInt(),.34f)
        val sucker=ColorUtils.blendARGB(0xffd6b9ad.toInt(),accent,.14f)
        val eye=0xffe8c883.toInt()
        canvas.save()
        p.reset();p.isAntiAlias=true;p.shader=null;p.colorFilter=null;p.alpha=255
        p.style=Paint.Style.FILL;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND

        // 道袍上的裂口：不规则暗肉 + 散落瞳孔。
        val mx=sx+dir*2f;val my=sy+7f
        canvas.save();canvas.rotate(dir*11f,mx,my)
        p.color=shade;p.alpha=220
        canvas.drawOval(mx-7f,my-4.2f,mx+7f,my+4.2f,p)
        canvas.drawOval(mx-5f-dir*2f,my-5.5f,mx+4f-dir*2f,my+2f,p)
        p.color=root;p.alpha=255
        canvas.drawOval(mx-5f,my-2.6f,mx+5f,my+2.6f,p)
        canvas.restore()
        for(i in 0..4) {
            val px=mx+(i-2)*3.1f+dir*(i%2)*1.4f
            val py=my-3f+(i%3)*2.6f
            p.color=eye;p.alpha=150;canvas.drawCircle(px,py,.62f,p)
            p.color=shade;p.alpha=255;canvas.drawCircle(px,py,.25f,p)
        }

        val count=3
        val pressing=pressure>.02f
        val lead=if(pressing && !isSpace && activeLocal in 0 until count) activeLocal else -1
        val allPress=pressing && isSpace
        val pt=FloatArray(4)

        for(i in 0 until count) {
            val off=i-(count-1)/2f
            // 待机相位：墙钟驱动，左右侧错开，保证不按键时也在缓慢游动。
            val bias=if(leftSide) 0f else 2.4f
            val sway=sin(idlePhase*2.1f+i*1.7f+bias+phase*1.2f)
            val sway2=sin(idlePhase*1.37f+i*2.3f+bias+1.1f)
            val x0=sx+dir*(i%3)*1.9f
            val y0=sy+7f+(i%2)*1.6f
            var tx:Float;var ty:Float
            when {
                // 空格：6 条触手全部压向空格键。
                allPress -> { tx=ex+off*9f+sway*.7f;ty=ey+(i%2)*1.3f }
                // 普通按键：只有被随机选中的那一条去按。
                i==lead -> { tx=ex+sway*.5f;ty=ey-(1f-pressure)*5f }
                // 其余（含待机）：在身体四周持续卷动。
                else -> {
                    tx=sx+dir*(16f+i*9f)+sway*6f
                    ty=sy+off*11f+sway2*5f
                }
            }
            val c1x=x0+dir*(11f+i*3f)+sway*3f
            val c1y=y0+14f
            val c2x=tx-dir*(9f+i*2f)-sway2*3f
            val c2y=ty-12f-pressure*5f
            val w=when { allPress -> 4.6f;i==lead -> 5.6f;else -> 3.2f-i*.3f }
            val tip=if(i==lead || allPress) 1.1f else .9f
            ribbon(canvas,p,pth,x0,y0,c1x,c1y,c2x,c2y,tx,ty,w,tip,shade,1.0f)
            ribbon(canvas,p,pth,x0,y0,c1x,c1y,c2x,c2y,tx,ty,w,tip,flesh,0f)

            // 末端回卷的钩尖，或生眼瞳、或带倒刺。
            bez(x0,y0,c1x,c1y,c2x,c2y,tx,ty,1f,pt)
            val hx=pt[0];val hy=pt[1]
            pth.reset();pth.moveTo(hx,hy)
            pth.quadTo(hx+dir*4.2f,hy+2f,hx+dir*1.4f,hy+5.2f)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.2f;p.color=flesh
            canvas.drawPath(pth,p)
            if(i%2==0) {
                p.style=Paint.Style.FILL;p.color=eye
                canvas.drawCircle(hx+dir*1.4f,hy+5.2f,1.5f,p)
                p.color=0xff231016.toInt()
                canvas.drawCircle(hx+dir*1.4f,hy+5.2f,.7f,p)
            } else {
                p.style=Paint.Style.FILL;p.color=shade
                canvas.drawCircle(hx+dir*1.4f,hy+5.2f,1.0f,p)
            }
            p.style=Paint.Style.FILL

            // 吸盘沿触手下侧。
            for(k in 0..3) {
                val t=.45f+k*.13f
                bez(x0,y0,c1x,c1y,c2x,c2y,tx,ty,t,pt)
                val len=Math.hypot(pt[2].toDouble(),pt[3].toDouble()).toFloat().coerceAtLeast(.001f)
                val nx=-pt[3]/len;val ny=pt[2]/len
                val ww=(tip+(w-tip)*Math.pow(1.0-t,1.35).toFloat())*.5f
                val px=pt[0]+nx*ww*.62f;val py=pt[1]+ny*ww*.62f
                val r=1.3f-k*.12f
                p.color=shade;canvas.drawCircle(px,py,r+.5f,p)
                p.color=sucker;canvas.drawCircle(px,py,r,p)
                p.color=shade;canvas.drawCircle(px,py,r*.42f,p)
            }
            // 一条湿润高光，沿触手内侧走。
            pth.reset()
            for(k in 0..8) {
                val t=k/8f
                bez(x0,y0,c1x,c1y,c2x,c2y,tx,ty,t,pt)
                if(k==0) pth.moveTo(pt[0],pt[1]) else pth.lineTo(pt[0],pt[1])
            }
            p.style=Paint.Style.STROKE;p.strokeWidth=.8f;p.color=sucker;p.alpha=55
            canvas.drawPath(pth,p)
            p.alpha=255;p.style=Paint.Style.FILL
        }

        // 键帽上的吸附印记。
        if(pressing) {
            val cy=ey+pressure*1.3f
            p.color=sucker;p.alpha=(70+pressure*110).toInt()
            canvas.drawOval(ex-4.4f,cy-2.6f,ex+4.4f,cy+2.6f,p)
            p.color=shade;p.alpha=255
            canvas.drawOval(ex-2.0f,cy-1.1f,ex+2.0f,cy+1.1f,p)
        }
        p.alpha=255;p.style=Paint.Style.FILL;p.strokeWidth=1f
        p.strokeCap=Paint.Cap.BUTT;p.shader=null;p.colorFilter=null
        canvas.restore()
    }

    /** 三次贝塞尔取点，out = [x, y, dx, dy]。 */
    private fun bez(
        x0:Float,y0:Float,c1x:Float,c1y:Float,c2x:Float,c2y:Float,x1:Float,y1:Float,
        t:Float,out:FloatArray,
    ) {
        val u=1f-t
        val a=u*u*u;val b=3f*u*u*t;val c=3f*u*t*t;val d=t*t*t
        out[0]=a*x0+b*c1x+c*c2x+d*x1
        out[1]=a*y0+b*c1y+c*c2y+d*y1
        out[2]=3f*u*u*(c1x-x0)+6f*u*t*(c2x-c1x)+3f*t*t*(x1-c2x)
        out[3]=3f*u*u*(c1y-y0)+6f*u*t*(c2y-c1y)+3f*t*t*(y1-c2y)
    }

    /** 把触手画成从粗到细的实体带（而非等宽线条，避免又变成「筷子」）。 */
    private fun ribbon(
        canvas:Canvas,p:Paint,pth:Path,
        x0:Float,y0:Float,c1x:Float,c1y:Float,c2x:Float,c2y:Float,x1:Float,y1:Float,
        rootW:Float,tipW:Float,color:Int,extra:Float,
    ) {
        val n=14
        val l=FloatArray((n+1)*2)
        val r=FloatArray((n+1)*2)
        val out=FloatArray(4)
        for(i in 0..n) {
            val t=i.toFloat()/n
            bez(x0,y0,c1x,c1y,c2x,c2y,x1,y1,t,out)
            val len=Math.hypot(out[2].toDouble(),out[3].toDouble()).toFloat().coerceAtLeast(.001f)
            val nx=-out[3]/len;val ny=out[2]/len
            val w=(tipW+(rootW-tipW)*Math.pow(1.0-t,1.35).toFloat())*.5f+extra
            l[2*i]=out[0]+nx*w;l[2*i+1]=out[1]+ny*w
            r[2*i]=out[0]-nx*w;r[2*i+1]=out[1]-ny*w
        }
        pth.reset();pth.moveTo(l[0],l[1])
        for(i in 1..n) pth.lineTo(l[2*i],l[2*i+1])
        for(i in n downTo 0) pth.lineTo(r[2*i],r[2*i+1])
        pth.close()
        p.style=Paint.Style.FILL;p.color=color;p.alpha=255
        canvas.drawPath(pth,p)
    }
}
