/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.osfans.trime.R
import com.osfans.trime.core.data.CandidatePreferences
import com.osfans.trime.core.data.CandidatePreferences.CreatedWord
import kotlinx.coroutines.*

/** A searchable view of the actual aliases used by candidate projection, including older saves. */
class CreatedWordsActivity : AppCompatActivity() {
    private var schema=""
    private var query=""
    private var current=""
    private var names=emptyMap<String,String>()
    private var all=emptyList<CreatedWord>()
    private lateinit var filter:MaterialButton
    private lateinit var count:TextView
    private lateinit var empty:TextView
    private var filtering:Job?=null
    private val adapter=WordsAdapter()
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        schema=state?.getString("schema") ?: intent.getStringExtra("schema").orEmpty();query=state?.getString("query").orEmpty()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(ContextCompat.getColor(context,R.color.framework_background)) }
        root.addView(MaterialToolbar(this).apply {
            title="造词管理";setNavigationIcon(R.drawable.ic_baseline_arrow_back_24);setNavigationOnClickListener { finish() }
        },LinearLayout.LayoutParams(-1,dp(64)))
        val top=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8)) }
        val search=TextInputEditText(this).apply { setSingleLine();setText(query);contentDescription="搜索词条、编码或方案" }
        search.hint="搜索词条、编码或方案";search.background=null;search.minHeight=dp(56)
        top.addView(com.google.android.material.card.MaterialCardView(this).apply {
            radius=dp(28).toFloat();cardElevation=0f;strokeWidth=0
            setCardBackgroundColor(ContextCompat.getColor(context,R.color.settings_surface))
            addView(TextInputLayout(context).apply {
                isHintEnabled=false;boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_NONE
                startIconDrawable=ContextCompat.getDrawable(context,R.drawable.ic_baseline_search_24)
                endIconMode=TextInputLayout.END_ICON_CLEAR_TEXT;addView(search)
            },ViewGroup.LayoutParams(-1,-2))
        })
        val actions=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        filter=MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text="全部方案";setOnClickListener { chooseSchema() } }
        actions.addView(filter,LinearLayout.LayoutParams(0,-2,1f))
        actions.addView(MaterialButton(this).apply { text="新建词条";setOnClickListener { create() } },LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(12) })
        top.addView(actions)
        count=TextView(this).apply { textSize=13f;setPadding(dp(4),dp(4),0,dp(8));setTextColor(ContextCompat.getColor(context,R.color.framework_text_secondary)) }
        top.addView(count);root.addView(top)
        val frame=FrameLayout(this)
        frame.addView(RecyclerView(this).apply { layoutManager=LinearLayoutManager(this@CreatedWordsActivity);adapter=this@CreatedWordsActivity.adapter;setPadding(dp(16),0,dp(16),dp(16));clipToPadding=false },FrameLayout.LayoutParams(-1,-1))
        empty=TextView(this).apply { gravity=Gravity.CENTER;textSize=16f;setPadding(dp(24),dp(24),dp(24),dp(24)) }
        frame.addView(empty,FrameLayout.LayoutParams(-1,-1));root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime=insets.getInsets(WindowInsetsCompat.Type.ime());view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,ime.bottom));insets
        }
        setContentView(root)
        search.doAfterTextChanged { query=it.toString();showWords(true) }
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val snapshot=withContext(Dispatchers.IO) { WordSchemas.load() to CandidatePreferences.createdWords() }
            current=snapshot.first.current;names=snapshot.first.names;all=snapshot.second
            filter.text=if(schema.isBlank()) "全部方案" else names[schema] ?: schema
            showWords()
        }
    }
    private fun showWords(debounce:Boolean=false) {
        filtering?.cancel()
        filtering=lifecycleScope.launch {
            if(debounce) delay(150)
            val source=all;val selected=schema;val term=query.trim();val labels=names
            val words=withContext(Dispatchers.Default) { source.filter { (selected.isBlank() || it.schema==selected) &&
                (term.isBlank() || it.word.contains(term,true) || it.code.contains(term,true) || it.schema.contains(term,true) || labels[it.schema].orEmpty().contains(term,true)) }
                .sortedWith(compareBy<CreatedWord> { labels[it.schema] ?: it.schema }.thenBy { it.code }.thenBy { it.word }) }
            adapter.submitList(words)
            count.text="${words.size} 条 / 共 ${source.size} 条 · 点击词条编辑"
            empty.text=if(source.isEmpty()) "还没有造词\n点击“新建词条”开始" else "没有找到匹配的词条"
            empty.visibility=if(words.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    private fun chooseSchema() {
        val ids=(names.keys+all.map { it.schema }).distinct()
        val labels=listOf("全部方案")+ids.map { "${names[it] ?: it} · $it" }
        MaterialAlertDialogBuilder(this).setTitle("筛选方案").setSingleChoiceItems(labels.toTypedArray(),if(schema.isBlank()) 0 else ids.indexOf(schema)+1) { dialog,index ->
            schema=if(index==0) "" else ids[index-1];filter.text=if(schema.isBlank()) "全部方案" else names[schema] ?: schema;showWords();dialog.dismiss()
        }.setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun create() { startActivity(Intent(this,WordCreationActivity::class.java).putExtra("schema",schema.ifBlank { current })) }
    private fun edit(word:CreatedWord) { startActivity(Intent(this,WordCreationActivity::class.java)
        .putExtra("schema",word.schema).putExtra("code",word.code).putExtra("word",word.word)
        .putExtra("editing",true)) }
    private fun delete(word:CreatedWord) {
        MaterialAlertDialogBuilder(this).setTitle("删除此造词？").setMessage("${word.word}\n${word.code} · ${names[word.schema] ?: word.schema}\n\n只移除这条应用内造词，不删除 Rime 原有词典中的词。")
            .setNegativeButton(android.R.string.cancel,null).setPositiveButton("删除") { _,_ -> lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { CandidatePreferences.editCreatedWord(word,null) } }
                    .onSuccess { all=withContext(Dispatchers.IO) { CandidatePreferences.createdWords() };showWords() }
                    .onFailure { Toast.makeText(this@CreatedWordsActivity,it.message ?: "删除失败",1).show() }
            } }.show()
    }
    private inner class WordsAdapter : ListAdapter<CreatedWord,Holder>(object:DiffUtil.ItemCallback<CreatedWord>() {
        override fun areItemsTheSame(a:CreatedWord,b:CreatedWord)=a==b
        override fun areContentsTheSame(a:CreatedWord,b:CreatedWord)=a==b
    }) {
        override fun onCreateViewHolder(parent:ViewGroup,type:Int):Holder {
            val row=LinearLayout(this@CreatedWordsActivity).apply {
                gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(12),dp(4),dp(12))
                background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(20).toFloat();setColor(ContextCompat.getColor(context,R.color.settings_surface)) }
                layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }
            }
            val texts=LinearLayout(this@CreatedWordsActivity).apply { orientation=LinearLayout.VERTICAL }
            val word=TextView(this@CreatedWordsActivity).apply { textSize=19f;maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END;setTextColor(ContextCompat.getColor(context,R.color.framework_text_primary)) }
            val detail=TextView(this@CreatedWordsActivity).apply { textSize=13f;setPadding(0,dp(6),0,0);setTextColor(ContextCompat.getColor(context,R.color.framework_text_secondary)) }
            texts.addView(word);texts.addView(detail);row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            val more=MaterialButton(this@CreatedWordsActivity).apply { backgroundTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT);setTextColor(ContextCompat.getColor(context,R.color.framework_text_secondary));text="⋮";setPadding(0,0,0,0);gravity=Gravity.CENTER;contentDescription="词条操作";minWidth=dp(48);minimumWidth=dp(48) }
            row.addView(more,LinearLayout.LayoutParams(dp(48),dp(48)));return Holder(row,word,detail,more)
        }
        override fun onBindViewHolder(holder:Holder,index:Int) {
            val entry=getItem(index);holder.word.text=entry.word;holder.detail.text="${entry.code} · ${names[entry.schema] ?: entry.schema}"
            holder.itemView.setOnClickListener { edit(entry) }
            holder.more.setOnClickListener { MaterialAlertDialogBuilder(this@CreatedWordsActivity).setTitle(entry.word.take(40)).setItems(arrayOf("编辑词条与编码","删除造词")) { _,which -> if(which==0) edit(entry) else delete(entry) }.show() }
        }
    }
    private class Holder(row:View,val word:TextView,val detail:TextView,val more:MaterialButton):RecyclerView.ViewHolder(row)
    override fun onSaveInstanceState(state:Bundle) { state.putString("schema",schema);state.putString("query",query);super.onSaveInstanceState(state) }
}
