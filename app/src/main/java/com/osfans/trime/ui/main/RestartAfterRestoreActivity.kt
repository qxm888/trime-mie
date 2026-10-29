/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

/** A short-lived separate process restarts only this app after a verified restore. */
class RestartAfterRestoreActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply { text="正在恢复设置…"; gravity=android.view.Gravity.CENTER })
        val pid=intent.getIntExtra("pid",-1)
        val manager=getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        if(manager.runningAppProcesses.orEmpty().any { it.pid==pid && it.uid==android.os.Process.myUid() && it.pid!=android.os.Process.myPid() }) android.os.Process.killProcess(pid)
        android.os.Handler(mainLooper).postDelayed({
            startActivity(android.content.Intent(this,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        },500)
    }
}
