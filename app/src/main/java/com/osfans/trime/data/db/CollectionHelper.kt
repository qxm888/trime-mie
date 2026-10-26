/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import android.content.Context
import androidx.room.Room
import androidx.paging.PagingSource
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

object CollectionHelper : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    private lateinit var cltDb: Database
    private lateinit var cltDao: DatabaseDao

    private val mutex = Mutex()

    private var lastBean: DatabaseBean? = null

    /** Access to the underlying database for cross-module operations (e.g. category cleanup). */
    fun databaseOrNull(): Database? =
        if (::cltDb.isInitialized) cltDb else null

    fun init(context: Context) {
        cltDb =
            Room
                .databaseBuilder(context, Database::class.java, "collection.db")
                .addMigrations(Database.MIGRATION_3_4, Database.MIGRATION_4_5, Database.MIGRATION_5_6)
                .build()
        cltDao = cltDb.databaseDao()
    }

    suspend fun insert(bean: DatabaseBean) = cltDao.insert(bean)

    suspend fun get(id: Int) = cltDao.get(id)

    suspend fun haveUnpinned() = cltDao.haveUnpinned()

    fun searchBeans(query: String): PagingSource<Int, DatabaseBean> = databaseOrNull()!!.databaseDao().searchBeans(query)

    suspend fun exportBeans(): List<DatabaseBean> = databaseOrNull()?.databaseDao()?.getAll() ?: emptyList()

    suspend fun updateOrderIndex(
        id: Int,
        orderIndex: Int,
    ) {
        databaseOrNull()?.databaseDao()?.updateOrderIndex(id, orderIndex)
    }

    fun allBeans() = cltDao.allBeans()

    suspend fun allBeansSnapshot() = cltDao.allBeansSnapshot()

    /** [categoryId] = null means the uncategorized pseudo tab. */
    fun allBeansByCategory(categoryId: String?) = cltDao.allBeansByCategory(categoryId)

    suspend fun pin(id: Int) = cltDao.updatePinned(id, true)

    suspend fun unpin(id: Int) = cltDao.updatePinned(id, false)

    suspend fun delete(id: Int) = cltDao.delete(id)

    suspend fun deleteAll(skipPinned: Boolean = true) {
        if (skipPinned) {
            cltDao.deleteAllUnpinned()
        } else {
            cltDao.deleteAll()
        }
    }

    /** Deletes every bean of a category, pinned or not (used by per-page clear-all). */
    suspend fun deleteAllInCategory(categoryId: String) {
        cltDao.deleteByCategory(categoryId)
    }

    suspend fun updateText(
        id: Int,
        text: String,
    ) {
        lastBean?.let {
            if (id == it.id) lastBean = it.copy(text = text)
        }
        cltDao.updateText(id, text)
    }

    suspend fun updateCategory(
        id: Int,
        categoryId: String?,
    ) {
        lastBean?.let {
            if (id == it.id) lastBean = it.copy(categoryId = categoryId)
        }
        cltDao.updateCategory(id, categoryId)
    }

    suspend fun itemCountByCategory(categoryId: String?): Int {
        val dao = databaseOrNull()?.databaseDao() ?: return 0
        return if (categoryId == null) dao.itemCountUncategorized() else dao.itemCountByCategory(categoryId)
    }

    fun addNewBean(
        text: String,
        categoryId: String? = null,
    ) {
        launch {
            mutex.withLock {
                val bean = DatabaseBean(text = text, categoryId = categoryId)
                if (bean.text.isNullOrBlank()) return@withLock
                try {
                    cltDao.find(text)?.let {
                        lastBean = it.copy(time = bean.time)
                        cltDao.updateTime(it.id, bean.time)
                        return@withLock
                    }
                    val insertedBean =
                        cltDb.withTransaction {
                            val rowId = cltDao.insert(bean)
                            cltDao.get(rowId) ?: bean
                        }
                    lastBean = insertedBean
                } catch (e: Exception) {
                    Timber.w("Failed to update collection database: $e")
                    lastBean = bean
                }
            }
        }
    }
}
