package com.guanyi.mirra.domain.monitoring

import com.guanyi.mirra.data.local.entity.SessionFocusContextEntity

enum class AllowanceReason { REPLY, RESEARCH, TEMPORARY_TASK, CASUAL }

object InterventionPolicy {
    fun waitMillis(confirmations: Int, context: SessionFocusContextEntity): Long = when {
        confirmations <= 1 -> 0
        confirmations == 2 -> context.secondFrictionMillis
        else -> context.thirdPlusFrictionMillis
    }
    fun duration(reason: AllowanceReason, context: SessionFocusContextEntity): Long = when (reason) {
        AllowanceReason.REPLY -> context.replyAllowanceMillis
        AllowanceReason.RESEARCH -> context.researchAllowanceMillis
        AllowanceReason.TEMPORARY_TASK -> context.temporaryTaskAllowanceMillis
        AllowanceReason.CASUAL -> context.casualAllowanceMillis
    }
}

/** Selected reason owns this runtime wait; recreating it resets the visible-time budget. */
class FrictionWait(private val required: Long) {
    private var last: Long? = null
    private var visible = false
    private var accumulated = 0L
    fun update(elapsed: Long, visible: Boolean) {
        val previous = last
        if (previous != null && elapsed < previous) accumulated = 0
        else if (this.visible && previous != null) accumulated =
            (accumulated + elapsed - previous).coerceAtMost(required)
        last = elapsed
        this.visible = visible
    }
    fun remaining(elapsed: Long): Long = (required - accumulated -
        if (visible) ((elapsed - (last ?: elapsed)).coerceAtLeast(0)) else 0).coerceAtLeast(0)
}
