/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.data.sentences

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Separate, bounded local database. No file/database operation runs in the key callback. */
internal class SentenceRepository private constructor(context: Context) {
    data class Entry(val text: String, val frequency: Long, val updated: Long)
    private class Database(context: Context) : SQLiteOpenHelper(context, "frequent_sentences.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE sentences (text TEXT PRIMARY KEY NOT NULL, lookup TEXT NOT NULL COLLATE NOCASE, t1 INTEGER NOT NULL, t2 INTEGER NOT NULL, t3 INTEGER NOT NULL, frequency INTEGER NOT NULL, learned INTEGER NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX sentence_prefix ON sentences(learned, lookup COLLATE BINARY)")
            db.execSQL("CREATE INDEX sentence_age ON sentences(learned, updated)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("DROP INDEX IF EXISTS sentence_prefix")
                db.execSQL("CREATE INDEX sentence_prefix ON sentences(learned, lookup COLLATE BINARY)")
            }
        }
    }
    private sealed interface Work {
        data class Learn(val text: String, val now: Long, val epoch: Long) : Work
        data class Barrier(val done: CompletableDeferred<Unit>) : Work
        data class Clear(val done: CompletableDeferred<Unit>) : Work
    }
    private val database = Database(context.applicationContext)
    private val lock = Mutex()
    private val inbox = Channel<Work>(64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong()
    private val clearing = AtomicBoolean()
    val dropped = AtomicLong()
    init {
        scope.launch {
            for (work in inbox) when (work) {
                is Work.Learn -> if (work.epoch == epoch.get()) runCatching { record(work.text, work.now) }
                    .onFailure { Timber.w(it, "Sentence learning write failed") }
                is Work.Barrier -> work.done.complete(Unit)
                is Work.Clear -> runCatching { lock.withLock { database.writableDatabase.delete("sentences", null, null) } }
                    .fold({ work.done.complete(Unit) }, { work.done.completeExceptionally(it) })
            }
        }
    }
    fun offer(text: String, now: Long = System.currentTimeMillis()) {
        if (clearing.get()) return
        if (inbox.trySend(Work.Learn(text, now, epoch.get())).isFailure) dropped.incrementAndGet()
    }
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>(); inbox.send(Work.Barrier(done)); done.await()
    }
    suspend fun clear() {
        clearing.set(true); epoch.incrementAndGet()
        try { val done = CompletableDeferred<Unit>(); inbox.send(Work.Clear(done)); done.await() }
        finally { clearing.set(false) }
    }
    suspend fun forget(text: String) = withContext(Dispatchers.IO) {
        awaitIdle()
        lock.withLock { database.writableDatabase.delete("sentences", "text=?", arrayOf(text)) }
    }
    suspend fun record(raw: String, now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val text = SentenceRules.normalize(raw) ?: return@withContext
        lock.withLock {
            val db = database.writableDatabase
            db.beginTransaction()
            try {
                var times = emptyList<Long>(); var frequency = 0L; var learned = false
                db.rawQuery("SELECT t1,t2,t3,frequency,learned FROM sentences WHERE text=?", arrayOf(text)).use {
                    if (it.moveToFirst()) {
                        times = (0..2).map { n -> it.getLong(n) }.filter { n -> n > 0 }
                        frequency = it.getLong(3); learned = it.getInt(4) != 0
                    }
                }
                val recent = SentenceRules.recent(times, now)
                val row = ContentValues().apply {
                    put("text", text); put("lookup", SentenceRules.lookup(text))
                    repeat(3) { put("t${it + 1}", recent.getOrElse(it) { 0L }) }
                    put("frequency", if (frequency == Long.MAX_VALUE) frequency else frequency + 1)
                    put("learned", if (learned || recent.size >= 3) 1 else 0); put("updated", now)
                }
                check(db.insertWithOnConflict("sentences", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L)
                db.delete("sentences", "learned=0 AND updated<=?", arrayOf((now - SentenceRules.WEEK_MS).toString()))
                for ((kind, limit) in listOf(0 to SentenceRules.PENDING_LIMIT, 1 to SentenceRules.LEARNED_LIMIT)) {
                    db.execSQL("DELETE FROM sentences WHERE text IN (SELECT text FROM sentences WHERE learned=$kind ORDER BY updated DESC, text LIMIT -1 OFFSET $limit)")
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    suspend fun search(prefix: String): List<Entry> = withContext(Dispatchers.IO) {
        if (prefix.length !in 2..SentenceRules.MAX_UTF16 || prefix.codePointCount(0, prefix.length) < 2) return@withContext emptyList()
        // Android's ICU LIKE implementation does not reliably use prefix range indexes.
        // Store normalized keys and query an explicit binary range instead.
        val lower = SentenceRules.lookup(prefix)
        val upper = SentenceRules.prefixEnd(lower)
        val range = if (upper == null) "lookup COLLATE BINARY>=?" else "lookup COLLATE BINARY>=? AND lookup COLLATE BINARY<?"
        val args = if (upper == null) arrayOf(lower) else arrayOf(lower, upper)
        lock.withLock {
            database.readableDatabase.rawQuery(
                "SELECT text,frequency,updated FROM sentences WHERE learned=1 AND $range ORDER BY frequency DESC, updated DESC LIMIT 4", args,
            ).use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    val text = cursor.getString(0)
                    if (!text.equals(prefix, ignoreCase = true) && size < 3) add(Entry(text, cursor.getLong(1), cursor.getLong(2)))
                }
            } }
        }
    }
    suspend fun learned(): List<Entry> = withContext(Dispatchers.IO) {
        lock.withLock { database.readableDatabase.rawQuery(
            "SELECT text,frequency,updated FROM sentences WHERE learned=1 ORDER BY updated DESC LIMIT ${SentenceRules.LEARNED_LIMIT}", null,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(Entry(cursor.getString(0), cursor.getLong(1), cursor.getLong(2))) } } }
    }
    suspend fun counts(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        lock.withLock { database.readableDatabase.rawQuery("SELECT learned,COUNT(*) FROM sentences GROUP BY learned", null).use {
            var pending = 0; var learned = 0
            while (it.moveToNext()) if (it.getInt(0) == 0) pending = it.getInt(1) else learned = it.getInt(1)
            pending to learned
        } }
    }
    companion object {
        @Volatile private var instance: SentenceRepository? = null
        fun get(context: Context): SentenceRepository = instance ?: synchronized(this) {
            instance ?: SentenceRepository(context).also { instance = it }
        }
    }
}
