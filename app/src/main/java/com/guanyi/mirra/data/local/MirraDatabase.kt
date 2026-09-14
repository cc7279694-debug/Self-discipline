package com.guanyi.mirra.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.guanyi.mirra.data.local.dao.IntentDao
import com.guanyi.mirra.data.local.dao.ImageAssetDao
import com.guanyi.mirra.data.local.dao.LearningItemDao
import com.guanyi.mirra.data.local.dao.NoteDao
import com.guanyi.mirra.data.local.dao.SessionDao
import com.guanyi.mirra.data.local.dao.SearchFtsDao
import com.guanyi.mirra.data.local.dao.TopicDao
import com.guanyi.mirra.data.local.dao.FocusDao
import com.guanyi.mirra.data.local.entity.FocusEventEntity
import com.guanyi.mirra.data.local.entity.RiskAppEntity
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.SessionRiskAppSnapshotEntity
import com.guanyi.mirra.data.local.entity.SessionSegmentEntity
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.ImageAssetEntity
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity
import com.guanyi.mirra.data.local.entity.NoteTopicCrossRef
import com.guanyi.mirra.data.local.entity.SearchFtsEntity
import com.guanyi.mirra.data.local.entity.TopicEntity

@Database(
    entities = [
        LearningItemEntity::class,
        StudyIntentEntity::class,
        StudySessionEntity::class,
        NoteEntity::class,
        ImageAssetEntity::class,
        TopicEntity::class,
        NoteTopicCrossRef::class,
        SearchFtsEntity::class,
        RiskAppEntity::class,
        SessionFocusContextEntity::class,
        SessionRiskAppSnapshotEntity::class,
        SessionSegmentEntity::class,
        FocusEventEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MirraDatabase : RoomDatabase() {
    abstract fun learningItemDao(): LearningItemDao
    abstract fun intentDao(): IntentDao
    abstract fun sessionDao(): SessionDao
    abstract fun noteDao(): NoteDao
    abstract fun imageAssetDao(): ImageAssetDao
    abstract fun topicDao(): TopicDao
    abstract fun searchFtsDao(): SearchFtsDao
    abstract fun focusDao(): FocusDao
}
