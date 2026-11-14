/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.database.sqlite.SQLiteOpenHelper
import android.os.Bundle
import com.osfans.trime.data.sentences.SentenceRepository
import com.osfans.trime.data.sentences.SentenceRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Uses only the disposable app database seeded by the host with a synthetic v2 save. */
class SentenceRetentionRunner : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args);start() }
    private fun pass(message: String) = sendStatus(0,Bundle().apply { putString("case","PASS_RETENTION $message") })
    override fun onStart() = runBlocking {
        val result=Bundle();var own:Activity?=null
        try {
            waitForIdleSync()
            own=startActivitySync(Intent(targetContext,SentenceLearningActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val repository=SentenceRepository.get(targetContext)
            val helper=repository.javaClass.getDeclaredField("database").apply { isAccessible=true }.get(repository) as SQLiteOpenHelper
            val realNow=System.currentTimeMillis()
            check(repository.counts()==(0 to 1)) { "Legacy pending cleanup or learned grace failed" }
            val migrated=repository.learned().single()
            check(migrated.text=="旧版保护期常用句子" && migrated.frequency==3L)
            check(migrated.updated>=realNow-60_000 && migrated.updated<=System.currentTimeMillis())
            check(withContext(Dispatchers.IO) { helper.readableDatabase.version }==3)
            pass("v2 to v3 keeps old learned rows for one grace period and removes 8-day pending rows")
            val now=realNow+365*SentenceRules.DAY_MS
            suspend fun seed(text:String,learned:Boolean,updated:Long) = withContext(Dispatchers.IO) {
                val db=helper.writableDatabase
                db.execSQL("INSERT OR REPLACE INTO sentences(text,lookup,t1,t2,t3,frequency,learned,updated) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(text,SentenceRules.lookup(text),updated-2,if(learned) updated-1 else 0L,if(learned) updated else 0L,if(learned) 3 else 1,if(learned) 1 else 0,updated))
            }
            suspend fun rows()=withContext(Dispatchers.IO) { helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM sentences",null).use { it.moveToFirst();it.getInt(0) } }
            suspend fun train(text:String,at:Long) { repeat(3) { repository.record(text,at+it) } }
            suspend fun row(text:String)=withContext(Dispatchers.IO) {
                helper.readableDatabase.rawQuery("SELECT learned,frequency,updated FROM sentences WHERE text=?",arrayOf(text)).use {
                    check(it.moveToFirst());listOf(it.getLong(0),it.getLong(1),it.getLong(2))
                }
            }
            repository.clear()
            seed("月前边界过期句子",true,now-SentenceRules.LEARNED_RETENTION_MS)
            seed("月内保留句子样本",true,now-SentenceRules.LEARNED_RETENTION_MS+1)
            seed("周前边界过期样本",false,now-SentenceRules.WEEK_MS)
            seed("周内保留学习样本",false,now-SentenceRules.WEEK_MS+1)
            check(repository.counts(now)==(1 to 1));check(rows()==2)
            pass("30-day learned and 7-day pending exact boundaries delete physically; one millisecond younger is retained")
            repository.clear();val text="点击补全续期验证句子"
            train(text,now-29*SentenceRules.DAY_MS)
            val old=repository.learned(now).single().updated
            check(repository.search("点击补全",now).single().updated==old)
            check(repository.learned(now).single().updated==old)
            repository.used(text,now);repository.awaitIdle()
            val used=repository.learned(now).single();check(used.updated==now && used.frequency==3L)
            check(repository.counts(now+SentenceRules.LEARNED_RETENTION_MS-1)==(0 to 1))
            check(repository.counts(now+SentenceRules.LEARNED_RETENTION_MS)==(0 to 0))
            pass("querying does not renew; successful use renews for 30 days without adding a learning occurrence")
            repository.clear();val manual="手动完成续期验证句子"
            train(manual,now-29*SentenceRules.DAY_MS);repository.record(manual,now)
            check(repository.learned(now).single().let { it.updated==now && it.frequency==4L })
            check(repository.counts(now+29*SentenceRules.DAY_MS)==(0 to 1))
            pass("manual full sentence reuse renews an existing learned phrase")
            repository.clear();val expired="过期以后重新学习句子"
            train(expired,now-31*SentenceRules.DAY_MS);repository.record(expired,now)
            check(repository.counts(now)==(1 to 0));check(row(expired)==listOf(0L,1L,now))
            repository.record(expired,now+1);repository.record(expired,now+2)
            check(repository.counts(now+2)==(0 to 1))
            pass("an expired learned phrase starts at occurrence one and needs three new occurrences")
            repository.clear();val pending="一周未学重新计数句子"
            repeat(2) { repository.record(pending,now-8*SentenceRules.DAY_MS+it) }
            repository.record(pending,now);check(row(pending)==listOf(0L,1L,now))
            repository.used(pending,now+1);repository.used("已经删除的常用句子",now+1);repository.awaitIdle()
            check(row(pending)==listOf(0L,1L,now));check(rows()==1)
            pass("7-day pending expiry resets old frequency; stale use cannot promote or resurrect rows")
            repository.clear();repository.counts(now)
            seed("清理间隔内过期句子",true,now-SentenceRules.LEARNED_RETENTION_MS+1000)
            check(repository.search("清理间隔",now+999).size==1)
            check(repository.search("清理间隔",now+1000).isEmpty());check(rows()==1)
            check(repository.learned(now+1000).isEmpty());check(rows()==0)
            pass("queries hide expired phrases even inside the sweep interval; management reads remove them immediately")
            seed("会话启动清理常用句子",true,now-SentenceRules.LEARNED_RETENTION_MS)
            seed("会话启动清理学习样本",false,now-SentenceRules.WEEK_MS)
            repository.maintain(now+2*60*60*1000);repository.awaitIdle();check(rows()==0)
            pass("session maintenance removes expired data without any new learning")
            result.putString("result","PASS")
        } catch(error:Throwable) { result.putString("result","FAIL");result.putString("failure",android.util.Log.getStackTraceString(error)) }
        finally {
            runCatching { SentenceRepository.get(targetContext).clear() }
            runOnMainSync { own?.finish() }
        }
        finish(if(result.getString("result")=="PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
    }
}
