package com.guanyi.mirra.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class LearningItemStatus { IN_PROGRESS, PAUSED, COMPLETED }

enum class IntentOutcome { CONVERTED, ABANDONED, TIMEOUT }

enum class SessionEndType { NORMAL, EARLY, AUTO, START_INCOMPLETE, ABNORMAL }

enum class NoteSemanticType { QUOTE, SUMMARY, UNDERSTANDING, QUESTION }

@Entity(
    tableName = "learning_items",
    indices = [Index(value = ["mainlineSlot"], unique = true)],
)
data class LearningItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val status: LearningItemStatus,
    val totalPages: Int,
    val currentPage: Int,
    val mainlineSlot: Int?,
    val firstAction: String,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
) {
    val isMainline: Boolean get() = mainlineSlot != null
}

@Entity(
    tableName = "study_intents",
    foreignKeys = [
        ForeignKey(
            entity = LearningItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["learningItemId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("learningItemId"),
        Index(value = ["activeSlot"], unique = true),
    ],
)
data class StudyIntentEntity(
    @PrimaryKey val id: String,
    val learningItemId: String,
    val createdAt: Long,
    val transitionedAt: Long?,
    val convertedAt: Long?,
    val endedAt: Long?,
    val outcome: IntentOutcome?,
    val activeSlot: Int?,
)

@Entity(
    tableName = "study_sessions",
    foreignKeys = [
        ForeignKey(
            entity = LearningItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["learningItemId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudyIntentEntity::class,
            parentColumns = ["id"],
            childColumns = ["intentId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("learningItemId"),
        Index(value = ["intentId"], unique = true),
        Index(value = ["activeSlot"], unique = true),
    ],
)
data class StudySessionEntity(
    @PrimaryKey val id: String,
    val learningItemId: String,
    val intentId: String,
    val startedAt: Long,
    val stableStartedAt: Long?,
    val endedAt: Long?,
    val startPage: Int,
    val currentPage: Int,
    val endPage: Int?,
    val endType: SessionEndType?,
    val generatedSummary: String?,
    val activeSlot: Int?,
)

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = LearningItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["learningItemId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StudySessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("learningItemId"), Index("sessionId")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val learningItemId: String,
    val sessionId: String?,
    val semanticType: NoteSemanticType,
    val content: String,
    val pageNumber: Int?,
    val createdAt: Long,
    val updatedAt: Long,
)
