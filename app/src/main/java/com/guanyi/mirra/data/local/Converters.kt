package com.guanyi.mirra.data.local

import androidx.room.TypeConverter
import com.guanyi.mirra.data.local.entity.IntentOutcome
import com.guanyi.mirra.data.local.entity.LearningItemStatus
import com.guanyi.mirra.data.local.entity.NoteSemanticType
import com.guanyi.mirra.data.local.entity.SessionEndType
import com.guanyi.mirra.data.local.entity.SessionSegmentType
import com.guanyi.mirra.data.local.entity.MonitoringCoverage
import com.guanyi.mirra.data.local.entity.DndLifecycle
import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.FocusEventType
import com.guanyi.mirra.data.local.entity.InterventionDeliveryChannel

class Converters {
    @TypeConverter fun learningItemStatus(value: LearningItemStatus): String = value.name
    @TypeConverter fun learningItemStatus(value: String): LearningItemStatus = LearningItemStatus.valueOf(value)
    @TypeConverter fun intentOutcome(value: IntentOutcome?): String? = value?.name
    @TypeConverter fun intentOutcome(value: String?): IntentOutcome? = value?.let(IntentOutcome::valueOf)
    @TypeConverter fun sessionEndType(value: SessionEndType?): String? = value?.name
    @TypeConverter fun sessionEndType(value: String?): SessionEndType? = value?.let(SessionEndType::valueOf)
    @TypeConverter fun noteSemanticType(value: NoteSemanticType): String = value.name
    @TypeConverter fun noteSemanticType(value: String): NoteSemanticType = NoteSemanticType.valueOf(value)
    @TypeConverter fun sessionSegmentType(value: SessionSegmentType): String = value.name
    @TypeConverter fun sessionSegmentType(value: String): SessionSegmentType = SessionSegmentType.valueOf(value)
    @TypeConverter fun monitoringCoverage(value: MonitoringCoverage): String = value.name
    @TypeConverter fun monitoringCoverage(value: String): MonitoringCoverage = MonitoringCoverage.valueOf(value)
    @TypeConverter fun dndLifecycle(value: DndLifecycle): String = value.name
    @TypeConverter fun dndLifecycle(value: String): DndLifecycle = DndLifecycle.valueOf(value)
    @TypeConverter fun focusCloseoutState(value: FocusCloseoutState): String = value.name
    @TypeConverter fun focusCloseoutState(value: String): FocusCloseoutState = FocusCloseoutState.valueOf(value)
    @TypeConverter fun focusEventType(value: FocusEventType): String = value.name
    @TypeConverter fun focusEventType(value: String): FocusEventType = FocusEventType.valueOf(value)
    @TypeConverter fun interventionDeliveryChannel(value: InterventionDeliveryChannel?): String? = value?.name
    @TypeConverter fun interventionDeliveryChannel(value: String?): InterventionDeliveryChannel? = value?.let(InterventionDeliveryChannel::valueOf)
}
