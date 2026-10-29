/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import com.osfans.trime.data.sync.*
import com.osfans.trime.ui.main.ClipboardSyncActivity
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Explicit permission grant for the test package, generated HTTP payload and isolated prefs only. */
class Mie303LanRunner : Instrumentation() {
    private var endpoint=""
    override fun onCreate(args:Bundle?) { endpoint=args?.getString("url").orEmpty();super.onCreate(args);start() }
    private fun main(block:()->Unit) {
        var failure:Throwable?=null
        runOnMainSync { try { block() } catch(e:Throwable) { failure=e } }
        failure?.let { throw it }
    }
    override fun onStart() {
        val result=Bundle();var activity:Activity?=null
        try {
            main { }
            retryPersistence()
            activity=startActivitySync(Intent(targetContext,ClipboardSyncActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val needs=LocalNetworkAccess.required(targetContext) && !LocalNetworkAccess.granted(targetContext)
            if(needs) {
                main {
                    fun find(view:android.view.View):android.view.View? {
                        if(view is android.widget.TextView && view.text.toString()=="局域网访问权限") return view
                        return (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
                    }
                    find(activity!!.window.decorView)!!.performClick()
                }
                repeat(30) {
                    if(!LocalNetworkAccess.granted(targetContext)) {
                        val root=uiAutomation.rootInActiveWindow
                        fun allow(node:android.view.accessibility.AccessibilityNodeInfo?):Boolean {
                            if(node==null) return false
                            if(node.packageName.toString()!=targetContext.packageName && node.isClickable && node.text?.toString() in listOf("允许","Allow","使用应用时允许","仅在使用该应用时允许")) return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            for(i in 0 until node.childCount) if(allow(node.getChild(i))) return true
                            return false
                        }
                        allow(root);android.os.SystemClock.sleep(200)
                    }
                }
            }
            check(LocalNetworkAccess.granted(targetContext)) { "native LAN permission was not granted" }
            result.putString("runtimePermission",if(needs) "requested and granted through system UI" else "already granted or not required")
            var fixture:java.net.ServerSocket?=null
            var localThread:Thread?=null
            val target=if(endpoint.isNotBlank()) endpoint else {
                val address=java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap { java.util.Collections.list(it.inetAddresses) }
                    .first { it is java.net.Inet4Address && it.isSiteLocalAddress }
                fixture=java.net.ServerSocket(0,1,address).apply { soTimeout=10000 }
                val server=fixture!!
                localThread=Thread {
                    runCatching { server.accept().use { socket ->
                        val input=socket.getInputStream().bufferedReader()
                        while(input.readLine()?.isNotEmpty()==true) { }
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 18\r\nConnection: close\r\n\r\nmie303-lan-fixture".toByteArray())
                    } }
                }.apply { start() }
                "http://${address.hostAddress}:${server.localPort}/fixture"
            }
            try {
                val client=OkHttpClient.Builder().dns(LocalNetworkAccess.dns { targetContext }).connectTimeout(5,TimeUnit.SECONDS).callTimeout(8,TimeUnit.SECONDS).build()
                client.newCall(Request.Builder().url(target).build()).execute().use { response ->
                    check(response.code==200 && response.body!!.string()=="mie303-lan-fixture")
                }
                result.putString("lanHTTP","PASS: generated payload, no clipboard or credentials; " + if(endpoint.isBlank()) "phone private-IP local socket" else "external LAN host")
                client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()
            } finally { fixture?.close();localThread?.join(1000) }
            result.putString("result","PASS")
        } catch(e:Throwable) { result.putString("result","FAIL: ${e.message}");result.putString("trace",e.stackTraceToString()) }
        finally { activity?.let { main { it.finish() } } }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
    private fun retryPersistence() {
        val sync=ClipboardSync
        fun field(name:String)=sync.javaClass.getDeclaredField(name).apply { isAccessible=true }
        if(field("serviceAlive").getBoolean(sync)) { sendStatus(0,Bundle().apply { putString("retryCheck","SKIP: active input service") });return }
        val fields=listOf("context","pending","received","seen","blockRemote").associateWith { field(it) }
        val saved=fields.mapValues { (_,f) -> f.get(sync) }
        val status=field("status").get(sync) as MutableStateFlow<String>
        val previousStatus=status.value
        val name="mie303-sync-fixture-${java.util.UUID.randomUUID()}"
        val wrapper=object:ContextWrapper(targetContext) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(ignored:String,mode:Int)=targetContext.getSharedPreferences(name,mode)
        }
        try {
            sync.init(wrapper)
            val c=ClipboardSync.Config(url="http://192.0.2.10",allowHttp=true)
            sync.save(c)
            val prefs=wrapper.getSharedPreferences("clipboard_sync",Context.MODE_PRIVATE)
            prefs.edit().putString("seen","fixture-seen").commit()
            val pending=ClipboardTransfer.Local(text="fixture-pending")
            field("pending").set(sync,pending)
            val generation=field("generation").getLong(sync)
            sync.save(c.copy(url=" ${c.url} "))
            check(field("generation").getLong(sync)==generation+1)
            check(field("pending").get(sync)==pending && prefs.getString("seen","")=="fixture-seen")
            sendStatus(0,Bundle().apply { putString("retryCheck","PASS: unchanged normalized Save restarts immediately and preserves pending/seen; isolated disabled config") })
        } finally {
            fields.forEach { (name,f) -> f.set(sync,saved[name]) };status.value=previousStatus
            wrapper.getSharedPreferences("clipboard_sync",Context.MODE_PRIVATE).edit().clear().commit()
            if(android.os.Build.VERSION.SDK_INT>=24) targetContext.deleteSharedPreferences(name)
        }
    }
}
