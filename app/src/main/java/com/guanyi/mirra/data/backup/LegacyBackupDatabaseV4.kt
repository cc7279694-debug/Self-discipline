package com.guanyi.mirra.data.backup

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.guanyi.mirra.data.local.Converters
import com.guanyi.mirra.data.local.entity.*

/** Locally compiled frozen v4 schema reference. Never opens an incoming backup or the live DB. */
@Database(entities = [LearningItemEntity::class, StudyIntentEntity::class, StudySessionEntity::class,
    NoteEntity::class, ImageAssetEntity::class, TopicEntity::class, NoteTopicCrossRef::class, SearchFtsEntity::class,
    RiskAppEntity::class, SessionFocusContextEntity::class, SessionRiskAppSnapshotEntity::class,
    SessionSegmentEntity::class, FocusEventEntity::class], version = 4, exportSchema = false)
@TypeConverters(Converters::class)
internal abstract class LegacyBackupDatabaseV4 : RoomDatabase()
