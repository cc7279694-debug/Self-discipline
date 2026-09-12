package com.guanyi.mirra.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

@Fts4(
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    notIndexed = ["entityType", "entityId", "searchableText"],
)
@Entity(tableName = "search_fts")
data class SearchFtsEntity(
    val entityType: String,
    val entityId: String,
    val searchableText: String,
    val normalizedTokens: String,
)
