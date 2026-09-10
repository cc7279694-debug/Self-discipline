package com.guanyi.mirra.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.guanyi.mirra.data.local.dao.IntentDao
import com.guanyi.mirra.data.local.dao.LearningItemDao
import com.guanyi.mirra.data.local.dao.NoteDao
import com.guanyi.mirra.data.local.dao.SessionDao
import com.guanyi.mirra.data.local.entity.LearningItemEntity
import com.guanyi.mirra.data.local.entity.NoteEntity
import com.guanyi.mirra.data.local.entity.StudyIntentEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity

@Database(
    entities = [LearningItemEntity::class, StudyIntentEntity::class, StudySessionEntity::class, NoteEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MirraDatabase : RoomDatabase() {
    abstract fun learningItemDao(): LearningItemDao
    abstract fun intentDao(): IntentDao
    abstract fun sessionDao(): SessionDao
    abstract fun noteDao(): NoteDao
}
