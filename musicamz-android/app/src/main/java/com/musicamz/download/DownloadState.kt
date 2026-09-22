package com.musicamz.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadPhase {
    IDLE, PREPARING, DOWNLOADING, CONVERTING, IMPORTING, UPDATING, COMPLETED, CANCELLED, FAILED
}

data class DownloadSnapshot(
    val phase: DownloadPhase = DownloadPhase.IDLE,
    val progress: Int? = null,
    val message: String = "Cole um link do YouTube para importar o áudio.",
    val title: String? = null
) {
    val isRunning: Boolean get() = phase in setOf(
        DownloadPhase.PREPARING, DownloadPhase.DOWNLOADING, DownloadPhase.CONVERTING,
        DownloadPhase.IMPORTING, DownloadPhase.UPDATING
    )
    val canCancel: Boolean get() = isRunning && phase != DownloadPhase.IMPORTING
}

/** Only safe display text is kept here. URLs, cookies and extractor logs are never exposed. */
object DownloadState {
    private val mutableState = MutableStateFlow(DownloadSnapshot())
    val state: StateFlow<DownloadSnapshot> = mutableState.asStateFlow()

    internal fun publish(snapshot: DownloadSnapshot) { mutableState.value = snapshot }
}
