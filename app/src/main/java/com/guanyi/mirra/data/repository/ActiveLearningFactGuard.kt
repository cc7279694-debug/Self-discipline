package com.guanyi.mirra.data.repository

import com.guanyi.mirra.data.local.entity.FocusCloseoutState
import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity
import com.guanyi.mirra.data.local.entity.StudySessionEntity

/** Call only after reading both rows inside the caller's Room transaction. Slot ownership != learning eligibility. */
internal fun isLearningFactWritable(session: StudySessionEntity?, context: SessionFocusContextEntity?): Boolean =
    session?.activeSlot == 1 && session.endedAt == null && session.endType == null &&
        context?.closeoutState == FocusCloseoutState.ACTIVE
