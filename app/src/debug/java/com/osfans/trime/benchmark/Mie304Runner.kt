/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.*
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import com.osfans.trime.data.sync.*
import com.osfans.trime.data.keymap.StandaloneKeyboardTheme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.appearance.*
import com.osfans.trime.ime.candidates.popup.PaginationUi
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.ServerSocket

/** Generated drawables, app-only fixture activity and disabled isolated sync config. No clipboard writes. */
class Mie304Runner : Instrumentation() {
    override fun onCreate(args:Bundle?) { super.onCreate(args);start() }
    private fun main(block:()->Unit) {
        var error:Throwable?=null
        runOnMainSync { try { block() } catch(e:Throwable) { error=e } }
        error?.let { throw it }
    }
    private fun pass(name:String)=sendStatus(0,Bundle().apply { putString("case","PASS: $name") })
    override fun onStart() {
        val result=Bundle()
        try {
            main { backgrounds();pagination() }
            immutableBlur();receiveFailure()
            result.putString("result","PASS")
        } catch(e:Throwable) { result.putString("result","FAIL: ${e.message}");result.putString("trace",e.stackTraceToString()) }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
    private fun backgrounds() {
        var invalidations=0
        val callback=object:Drawable.Callback {
            override fun invalidateDrawable(who:Drawable) { invalidations++ }
            override fun scheduleDrawable(who:Drawable,what:Runnable,whenMillis:Long) {}
            override fun unscheduleDrawable(who:Drawable,what:Runnable) {}
        }
        val normal=GradientDrawable().apply { setColor(Color.WHITE);this.callback=callback }
        val pressed=GradientDrawable().apply { setColor(Color.GRAY);this.callback=callback }
        val style=KeyBackgroundStyle()
        style.apply(normal,8f,1,Color.RED);style.apply(pressed,8f,1,Color.RED)
        invalidations=0
        repeat(2000) { style.apply(if(it%2==0) normal else pressed,8f,1,Color.RED) }
        check(invalidations==0)
        style.apply(normal,10f,2,Color.BLUE);check(invalidations>0)
        pass("2000 alternating normal/pressed backgrounds cause no repeated geometry invalidation; changed geometry still applies")
    }
    private fun pagination() {
        val theme=StandaloneKeyboardTheme.base()
        val scope=ThemeScope(theme).apply { updateScheme(theme.presetColorSchemes.getValue("default")) }
        var style:FloatingCandidateStyle?=FloatingCandidateStyle(arrowSize=36)
        val ui=PaginationUi(targetContext,scope) { style }
        ui.configure(true)
        val custom=ui.prevIcon.layoutParams.width
        style=null;ui.configure(true)
        val legacy=ui.prevIcon.layoutParams.width
        val density=targetContext.resources.displayMetrics.density
        check(custom==(36*density+.5f).toInt() && legacy==(44*density+.5f).toInt())
        pass("same arrow height restores legacy button width when a custom style is disabled")
    }
    private fun immutableBlur() {
        val activity=startActivitySync(Intent(targetContext,Mie302FixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var root:FrameLayout;lateinit var blur:BackdropBlur
        var captures=0;var color=Color.RED
        try {
            main {
                root=FrameLayout(activity)
                blur=BackdropBlur(root,{SurfaceAppearanceConfig(keys=SurfaceStyle(blur=true))}) { captures++;it.drawColor(color) }
                activity.setContentView(root)
            }
            waitForIdleSync();android.os.SystemClock.sleep(600)
            lateinit var first:Bitmap
            main { first=blur.javaClass.getDeclaredField("front").apply { isAccessible=true }.get(blur) as Bitmap;check(!first.isMutable);color=Color.BLUE;BackdropBlur.invalidateBackground(root) }
            android.os.SystemClock.sleep(350)
            main {
                val second=blur.javaClass.getDeclaredField("front").apply { isAccessible=true }.get(blur) as Bitmap
                check(second!==first && !second.isMutable && first.getPixel(0,0)==Color.RED && second.getPixel(0,0)==Color.BLUE)
            }
            var before=0
            main { root.visibility=View.GONE;before=captures;BackdropBlur.invalidateBackground(root) }
            android.os.SystemClock.sleep(300);main { check(captures==before);root.visibility=View.VISIBLE }
            waitForIdleSync();android.os.SystemClock.sleep(350);main { check(captures==before+1) }
            pass("published blur frames immutable and unchanged after next capture; hidden requests resume once visible")
        } finally { main { activity.finish() } }
    }
    private fun receiveFailure() {
        val sync=ClipboardSync
        fun field(name:String)=sync.javaClass.getDeclaredField(name).apply { isAccessible=true }
        check(!field("serviceAlive").getBoolean(sync)) { "fixture requires an inactive test IME service" }
        val fields=listOf("context","generation","revision","pending","received","seen","blockRemote").associateWith { field(it) }
        val saved=fields.mapValues { (_,value) -> value.get(sync) }
        val status=field("status").get(sync) as MutableStateFlow<String>;val oldStatus=status.value
        val name="mie304-receive-${java.util.UUID.randomUUID()}"
        val context=object:ContextWrapper(targetContext) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(ignored:String,mode:Int)=targetContext.getSharedPreferences(name,mode)
        }
        val server=ServerSocket(0,2,java.net.InetAddress.getByName("127.0.0.1")).apply { soTimeout=10000 }
        val worker=Thread {
            runCatching { for(code in listOf(404,500)) server.accept().use { socket ->
                val reader=socket.getInputStream().bufferedReader()
                while(reader.readLine()?.isNotEmpty()==true) {}
                socket.getOutputStream().write("HTTP/1.1 $code Fixture\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            } }
        }.apply { start() }
        try {
            main { sync.init(context);sync.save(ClipboardSync.Config(url="http://127.0.0.1:${server.localPort}",allowHttp=true)) }
            val pending=ClipboardTransfer.Local(text="mie304-generated-pending")
            main { field("pending").set(sync,pending);field("seen").set(sync,"fixture-seen");context.getSharedPreferences("unused",0).edit().putString("seen","fixture-seen").commit() }
            runBlocking { sync.downloadNow() }
            check(field("pending").get(sync)==pending && field("seen").get(sync)=="fixture-seen")
            check(runCatching { runBlocking { sync.downloadNow() } }.isFailure)
            check(field("pending").get(sync)==pending && field("seen").get(sync)=="fixture-seen")
            pass("manual receive 404 and HTTP500 retain unsent local content and seen marker; generated local endpoint, no clipboard writes")
        } finally {
            server.close();worker.join(1000)
            main { fields.forEach { (name,value) -> value.set(sync,saved[name]) };status.value=oldStatus }
            if(android.os.Build.VERSION.SDK_INT>=24) targetContext.deleteSharedPreferences(name)
        }
    }
}
