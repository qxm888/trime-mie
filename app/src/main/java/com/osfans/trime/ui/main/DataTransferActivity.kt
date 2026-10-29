/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import com.osfans.trime.util.readBoundedText

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.osfans.trime.R
import com.osfans.trime.data.db.CategoryHelper
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.DatabaseBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup / restore of clipboard history and collection items, so migrating to a
 * new phone does not mean losing everything.
 *
 * The file is plain JSON, so it can also be inspected or edited by hand.
 */
class DataTransferActivity : AppCompatActivity() {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Serializable
    private data class BackupItem(
        val text: String = "",
        val html: String? = null,
        val type: Int = 0,
        val time: Long = 0,
        val pinned: Boolean = false,
        val category: String? = null,
    )

    @Serializable
    private data class BackupData(
        val version: Int = 1,
        val exportedAt: Long = 0,
        val categories: List<String> = emptyList(),
        val collection: List<BackupItem> = emptyList(),
        val clipboard: List<BackupItem> = emptyList(),
    )

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val ok = runCatching { withContext(Dispatchers.IO) { writeBackup(uri) } }.isSuccess
                toast(if (ok) R.string.data_export_success else R.string.data_export_failed)
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                val count =
                    runCatching { withContext(Dispatchers.IO) { readBackup(uri) } }.getOrNull()
                if (count == null) {
                    toast(R.string.data_import_failed)
                } else {
                    Toast
                        .makeText(
                            this@DataTransferActivity,
                            getString(R.string.data_import_success, count),
                            Toast.LENGTH_LONG,
                        ).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_transfer)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.data_toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.data_app_bar)) { v, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, systemBars.top, 0, 0)
            windowInsets
        }
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.menu.add("整包备份与恢复").setOnMenuItemClickListener {
            startActivity(android.content.Intent(this,FullBackupActivity::class.java)); true
        }
        toolbar.menu.add("统计与候选备份").setOnMenuItemClickListener {
            startActivity(android.content.Intent(this, PersonalDataActivity::class.java)); true
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.data_export_button)
            .setOnClickListener {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                exportLauncher.launch("trime-backup-$stamp.json")
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.data_import_button)
            .setOnClickListener {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
    }

    private fun beanToItem(
        bean: DatabaseBean,
        category: String?,
    ): BackupItem =
        BackupItem(
            text = bean.text ?: "",
            html = bean.html,
            type = if (bean.type == DatabaseBean.BeanType.HTML) 1 else 0,
            time = bean.time,
            pinned = bean.pinned,
            category = category,
        )

    private suspend fun writeBackup(uri: Uri) {
        val categories = CategoryHelper.all()
        val nameById = categories.associate { it.id to it.name }
        val data =
            BackupData(
                exportedAt = System.currentTimeMillis(),
                categories = categories.map { it.name },
                collection =
                    CollectionHelper.allBeansSnapshot().map {
                        beanToItem(it, it.categoryId?.let(nameById::get))
                    },
                clipboard =
                    ClipboardHelper.allBeansSnapshot().map {
                        beanToItem(it, null)
                    },
            )
        val text = json.encodeToString(data)
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("cannot open output")
    }

    private suspend fun readBackup(uri: Uri): Int {
        val raw =
            contentResolver.openInputStream(uri)?.use { it.readBoundedText(16 * 1024 * 1024) }
                ?: error("cannot open input")
        val data = json.decodeFromString<BackupData>(raw)
        require(data.version == 1) { "不支持此备份版本" }
        require(data.categories.size <= 256 && data.collection.size + data.clipboard.size <= 20000) { "备份条目数量过多" }

        // Re-map categories by name: ids are device-specific.
        val nameToId = mutableMapOf<String, String?>()
        for (name in data.categories) {
            val existing = CategoryHelper.all().firstOrNull { it.name == name }
            nameToId[name] = existing?.id ?: CategoryHelper.create(name)
        }

        var inserted = 0
        val collectionDao = CollectionHelper.databaseOrNull()?.databaseDao()
        if (collectionDao != null) {
            val existing =
                CollectionHelper.allBeansSnapshot().mapNotNull { it.text }.toHashSet()
            CollectionHelper.databaseOrNull()!!.withTransaction {
                for (item in data.collection) {
                    if (item.text.isBlank() || item.text in existing) continue
                    collectionDao.insert(item.toBean(nameToId[item.category]))
                    existing += item.text
                    inserted++
                }
            }
        }
        val clipboardDao = ClipboardHelper.databaseOrNull()?.databaseDao()
        if (clipboardDao != null) {
            val existing =
                ClipboardHelper.allBeansSnapshot().mapNotNull { it.text }.toHashSet()
            ClipboardHelper.databaseOrNull()!!.withTransaction {
                for (item in data.clipboard) {
                    if (item.text.isBlank() || item.text in existing) continue
                    clipboardDao.insert(item.toBean(null))
                    existing += item.text
                    inserted++
                }
            }
        }
        return inserted
    }

    private fun BackupItem.toBean(categoryId: String?): DatabaseBean =
        DatabaseBean(
            text = text,
            html = html,
            type = if (type == 1) DatabaseBean.BeanType.HTML else DatabaseBean.BeanType.TEXT,
            time = if (time > 0) time else System.currentTimeMillis(),
            pinned = pinned,
            categoryId = categoryId,
        )
}
