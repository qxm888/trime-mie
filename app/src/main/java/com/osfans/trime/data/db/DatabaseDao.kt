/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface DatabaseDao {
    @Insert
    suspend fun insert(bean: DatabaseBean): Long

    @Update
    suspend fun update(bean: DatabaseBean)

    @Query("UPDATE ${DatabaseBean.TABLE_NAME} SET text=:newText WHERE id=:id")
    suspend fun updateText(
        id: Int,
        newText: String,
    )

    @Query("UPDATE ${DatabaseBean.TABLE_NAME} SET pinned=:pinned WHERE id=:id")
    suspend fun updatePinned(
        id: Int,
        pinned: Boolean,
    )

    @Query("UPDATE ${DatabaseBean.TABLE_NAME} SET time=:timestamp WHERE id=:id")
    suspend fun updateTime(
        id: Int,
        timestamp: Long,
    )

    @Query("UPDATE ${DatabaseBean.TABLE_NAME} SET categoryId=:categoryId WHERE id=:id")
    suspend fun updateCategory(
        id: Int,
        categoryId: String?,
    )

    @Query("UPDATE ${DatabaseBean.TABLE_NAME} SET categoryId=NULL WHERE categoryId=:categoryId")
    suspend fun clearCategory(categoryId: String)

    @Query("DELETE FROM ${DatabaseBean.TABLE_NAME} WHERE categoryId=:categoryId")
    suspend fun deleteByCategory(categoryId: String)

    @Delete
    suspend fun delete(bean: DatabaseBean)

    @Query("DELETE FROM ${DatabaseBean.TABLE_NAME} WHERE text=:text")
    suspend fun delete(text: String)

    @Query("DELETE FROM ${DatabaseBean.TABLE_NAME} WHERE id=:id")
    suspend fun delete(id: Int)

    @Delete
    suspend fun delete(beans: List<DatabaseBean>)

    @Query("DELETE FROM ${DatabaseBean.TABLE_NAME}")
    suspend fun deleteAll()

    @Query("DELETE FROM ${DatabaseBean.TABLE_NAME} WHERE NOT pinned")
    suspend fun deleteAllUnpinned()

    @Query("DELETE FROM ${DatabaseBean.TABLE_NAME} WHERE time<:timestamp AND pinned=0")
    suspend fun deletedUnpinnedEarlierThan(timestamp: Long)

    @Query("UPDATE ${DatabaseBean.TABLE_NAME} SET orderIndex=:orderIndex WHERE id=:id")
    suspend fun updateOrderIndex(
        id: Int,
        orderIndex: Int,
    )

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} ORDER BY pinned DESC, orderIndex ASC, time DESC")
    fun allBeans(): PagingSource<Int, DatabaseBean>

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} WHERE text LIKE '%' || :query || '%' ORDER BY pinned DESC, orderIndex ASC, time DESC")
    fun searchBeans(query: String): PagingSource<Int, DatabaseBean>

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} WHERE text LIKE :pattern ESCAPE '\\' ORDER BY pinned DESC, orderIndex ASC, time DESC LIMIT :limit")
    suspend fun searchSnapshot(pattern: String, limit: Int = 200): List<DatabaseBean>

    /** Snapshot of every row, used by export (paging source can't be collected directly). */
    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} ORDER BY time ASC")
    suspend fun allBeansSnapshot(): List<DatabaseBean>

    @Query(
        "SELECT * FROM ${DatabaseBean.TABLE_NAME} " +
            "WHERE (:categoryId IS NULL AND categoryId IS NULL) OR categoryId=:categoryId " +
            "ORDER BY pinned DESC, orderIndex ASC, time DESC",
    )
    fun allBeansByCategory(categoryId: String?): PagingSource<Int, DatabaseBean>

    @Query("SELECT DISTINCT categoryId FROM ${DatabaseBean.TABLE_NAME} WHERE categoryId IS NOT NULL")
    suspend fun usedCategoryIds(): List<String>

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} ORDER BY pinned DESC, orderIndex ASC, time DESC")
    suspend fun getAll(): List<DatabaseBean>

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} WHERE id=:id LIMIT 1")
    suspend fun get(id: Int): DatabaseBean?

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} WHERE rowId=:rowId LIMIT 1")
    suspend fun get(rowId: Long): DatabaseBean?

    @Query("SELECT EXISTS(SELECT 1 FROM ${DatabaseBean.TABLE_NAME} WHERE pinned=0)")
    suspend fun haveUnpinned(): Boolean

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} WHERE pinned=0")
    suspend fun getAllUnpinned(): List<DatabaseBean>

    @Query("SELECT * FROM ${DatabaseBean.TABLE_NAME} WHERE text=:text LIMIT 1")
    suspend fun find(text: String): DatabaseBean?

    @Query("SELECT COUNT(*) FROM ${DatabaseBean.TABLE_NAME}")
    suspend fun itemCount(): Int

    @Query("SELECT COUNT(*) FROM ${DatabaseBean.TABLE_NAME} WHERE categoryId=:categoryId")
    suspend fun itemCountByCategory(categoryId: String): Int

    @Query("SELECT COUNT(*) FROM ${DatabaseBean.TABLE_NAME} WHERE categoryId IS NULL")
    suspend fun itemCountUncategorized(): Int
}
