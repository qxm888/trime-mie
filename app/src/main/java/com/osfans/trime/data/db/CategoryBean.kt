// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.db

import kotlinx.serialization.Serializable

/**
 * A user-defined category (folder) for collection beans.
 *
 * Persisted as a JSON file next to collection.db (see [CategoryHelper]),
 * so no Room schema change is needed. [id] is a random UUID; [order]
 * keeps a stable custom ordering of tabs.
 */
@Serializable
data class CategoryBean(
    val id: String,
    val name: String,
    val order: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
