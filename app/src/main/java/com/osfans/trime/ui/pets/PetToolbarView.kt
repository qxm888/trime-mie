/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.pets

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.widget.FrameLayout
import com.osfans.trime.data.pets.PetHouse
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.PetHouseActivity
import kotlinx.coroutines.*

/** Small entry point: click toggles the miniature; long click opens the house. */
class PetToolbarView(context:Context):FrameLayout(context) {
    private val portrait=PetPortraitView(context).apply { halo=false }
    private var collector:CoroutineScope?=null
    private var policy=true
    private var signature=""
    private val ime:TrimeInputMethodService? by lazy {
        var ctx:Context=context
        var found:TrimeInputMethodService?=null
        for(i in 0..12) {
            if(ctx is TrimeInputMethodService) { found=ctx;break }
            if(ctx !is ContextWrapper || ctx.baseContext===ctx) break
            ctx=ctx.baseContext
        };found
    }
    init {
        id=View.generateViewId();visibility=View.GONE;isClickable=true;isFocusable=true
        val pad=(3*resources.displayMetrics.density).toInt();setPadding(pad,pad,pad,pad)
        addView(portrait,LayoutParams(-1,-1));contentDescription="键灵小屋"
        setOnClickListener { ime?.togglePetCompanion() ?: PetHouseActivity.start(context) }
        setOnLongClickListener { PetHouseActivity.start(context);true }
    }
    fun showWithToolbar(show:Boolean) { policy=show;refresh(PetHouse.state.value) }
    private fun refresh(s:PetHouse.State) {
        visibility=if(policy && s.started && s.mode>0 && s.error.isEmpty()) View.VISIBLE else View.GONE
        val p=s.companion() ?: return
        val key="${p.id}/${p.nickname}/${p.stage}/${p.accessory}"
        if(signature!=key) { signature=key;portrait.bind(p) }
        contentDescription=if(s.miniExpanded) "收起宠物迷你键盘，长按进入小屋" else "展开宠物迷你键盘，长按进入小屋"
        if(s.started && s.mode>0) ime?.preparePetCompanion()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();collector=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).also { scope -> scope.launch { PetHouse.state.collect { refresh(it) } } }
    }
    override fun onDetachedFromWindow() { collector?.cancel();collector=null;super.onDetachedFromWindow() }
}
