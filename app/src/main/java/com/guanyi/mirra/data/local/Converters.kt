package com.guanyi.mirra.data.local

import androidx.room.TypeConverter
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.SessionEndType

class Converters {
    @TypeConverter fun learningItemStatus(value: LearningItemStatus): String = value.name
    @TypeConverter fun learningItemStatus(value: String): LearningItemStatus = LearningItemStatus.valueOf(value)
    @TypeConverter fun intentOutcome(value: IntentOutcome?): String? = value?.name
    @TypeConverter fun intentOutcome(value: String?): IntentOutcome? = value?.let(IntentOutcome::valueOf)
    @TypeConverter fun sessionEndType(value: SessionEndType?): String? = value?.name
    @TypeConverter fun sessionEndType(value: String?): SessionEndType? = value?.let(SessionEndType::valueOf)
    @TypeConverter fun noteSemanticType(value: NoteSemanticType): String = value.name
    @TypeConverter fun noteSemanticType(value: String): NoteSemanticType = NoteSemanticType.valueOf(value)
}
