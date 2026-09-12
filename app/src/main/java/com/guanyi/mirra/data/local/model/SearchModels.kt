package com.guanyi.mirra.data.local.model

data class SearchDocumentRow(
    val entityType: String,
    val entityId: String,
    val searchableText: String,
    val normalizedTokens: String,
)

data class SearchHitRow(
    val entityType: String,
    val entityId: String,
    val searchableText: String,
    val sourceTimestamp: Long?,
)
