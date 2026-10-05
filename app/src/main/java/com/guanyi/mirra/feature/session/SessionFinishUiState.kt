package com.guanyi.mirra.feature.session

sealed interface SessionFinishUiState {
    data object Idle : SessionFinishUiState
    data object PreparingConfirmation : SessionFinishUiState
    data class Confirming(val endPageText: String) : SessionFinishUiState
    data object Saving : SessionFinishUiState
    data class SaveFailed(val logicallyClosed: Boolean, val message: String) : SessionFinishUiState
    data class Completed(val sessionId: String) : SessionFinishUiState
}
