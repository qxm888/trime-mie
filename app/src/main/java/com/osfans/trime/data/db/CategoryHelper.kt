/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.db

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Categories (folders) for the collection tab.
 *
 * The category list itself is a small JSON file next to collection.db;
 * membership is stored on [DatabaseBean.categoryId] inside collection.db.
 * All mutations are serialized through [mutex] and immediately flushed to
 * disk; listeners are notified so open UIs can refresh.
 */
object CategoryHelper : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default) {
    /** Display id of the built-in uncategorized pseudo tab. */
    const val UNCATEGORIZED_ID = "uncategorized"

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CategoryBean.serializer())
    private val mutex = Mutex()

    private lateinit var file: File

    @Volatile
    private var cache: List<CategoryBean> = emptyList()

    fun interface OnCategoriesChangeListener {
        fun onChange()
    }

    private val listeners = mutableSetOf<OnCategoriesChangeListener>()

    fun addOnChangeListener(listener: OnCategoriesChangeListener) {
        listeners.add(listener)
    }

    fun removeOnChangeListener(listener: OnCategoriesChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyChange() {
        // Snapshot to allow listeners to unregister during dispatch.
        listeners.toList().forEach { it.onChange() }
    }

    fun init(context: Context) {
        file = File(context.filesDir, "collection_categories.json")
        cache = readFromDisk()
    }

    private fun readFromDisk(): List<CategoryBean> =
        runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                json.decodeFromString(serializer, file.readText())
            }
        }.onFailure {
            Timber.w("Failed to read collection categories: $it")
        }.getOrDefault(emptyList())

    private fun writeToDisk(categories: List<CategoryBean>) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, categories))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(serializer, categories))
                tmp.delete()
            }
        }.onFailure {
            Timber.w("Failed to write collection categories: $it")
        }
    }
    fun all(): List<CategoryBean> = cache.sortedBy { it.order }

    fun byId(id: String): CategoryBean? = cache.firstOrNull { it.id == id }

    /**
     * Resolves a display name for [id], treating null/empty and unknown ids
     * as the uncategorized pseudo tab.
     */
    fun nameOf(id: String?): String =
        id?.let { byId(it)?.name } ?: UNCATEGORIZED_ID

    fun existsName(name: String): Boolean = cache.any { it.name == name }

    /**
     * Creates a category with a unique name, returning its id,
     * or null when the name is blank.
     */
    suspend fun create(name: String): String? =
        mutex.withLock {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return@withLock null
            var unique = trimmed
            var suffix = 2
            while (cache.any { it.name == unique }) {
                unique = "$trimmed ($suffix)"
                suffix++
            }
            val category = CategoryBean(id = UUID.randomUUID().toString(), name = unique, order = cache.size)
            cache = cache + category
            withContext(Dispatchers.IO) { writeToDisk(cache) }
            category.id
        }.also { if (it != null) notifyChange() }

    suspend fun rename(
        id: String,
        newName: String,
    ): Boolean =
        mutex.withLock {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) return@withLock false
            val target = cache.firstOrNull { it.id == id } ?: return@withLock false
            if (cache.any { it.name == trimmed && it.id != id }) return@withLock false
            cache = cache.map { if (it.id == id) target.copy(name = trimmed) else it }
            withContext(Dispatchers.IO) { writeToDisk(cache) }
            true
        }.also { if (it) notifyChange() }

    suspend fun reorder(orderedIds: List<String>) {
        mutex.withLock {
            val ids = orderedIds.distinct().filter { id -> cache.any { it.id == id } }
            val ordered = ids + cache.sortedBy { it.order }.map { it.id }.filterNot { it in ids }
            cache = ordered.mapIndexed { index, id -> cache.first { it.id == id }.copy(order = index) }
            withContext(Dispatchers.IO) { writeToDisk(cache) }
        }.also { notifyChange() }
    }

    /**
     * Deletes a category. With [deleteBeans] the contained collection beans
     * are removed as well; otherwise they fall back to the uncategorized tab.
     */
    suspend fun delete(
        id: String,
        deleteBeans: Boolean = false,
    ) {
        val db = CollectionHelper.databaseOrNull() ?: return
        mutex.withLock {
            cache = cache.filterNot { it.id == id }
            cache.forEachIndexed { index, category ->
                if (category.order != index) cache = cache.map { c -> if (c.id == category.id) c.copy(order = index) else c }
            }
            withContext(Dispatchers.IO) { writeToDisk(cache) }
        }
        runCatching {
            db.withTransaction {
                if (deleteBeans) {
                    db.databaseDao().deleteByCategory(id)
                } else {
                    db.databaseDao().clearCategory(id)
                }
            }
        }.onFailure {
            Timber.w("Failed to update beans of deleted category: $it")
        }
        notifyChange()
    }
}
