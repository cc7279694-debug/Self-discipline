package com.guanyi.mirra.domain.backup

enum class FullBackupErrorCode {
    ACTIVE_LEARNING, OWNED_CLEANUP, LOW_SPACE, INVALID_ARCHIVE,
    WRITE_OR_READ_FAILED, RESTORE_BLOCKED, STORAGE_BUSY,
}

/** Only the code is a UI contract. Cause/message may contain private filesystem/provider details. */
sealed class FullBackupException(val code: FullBackupErrorCode, cause: Throwable?) :
    Exception(code.name, cause)

class FullBackupOperationException(code: FullBackupErrorCode, cause: Throwable? = null) :
    FullBackupException(code, cause)
