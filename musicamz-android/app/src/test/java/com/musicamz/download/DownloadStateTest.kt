package com.musicamz.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadStateTest {
    @Test fun retryWithSameFailureStillAcknowledgesNewWorkToSlowCollectors() {
        val failure = DownloadSnapshot(DownloadPhase.FAILED, message = "Sem espaço para importar.")
        DownloadState.publish(failure)
        val previousAttempt = DownloadState.state.value
        // The UI can skip these intermediate values when both arrive within one frame.
        DownloadState.publish(DownloadSnapshot(DownloadPhase.PREPARING))
        DownloadState.publish(failure)
        val retriedAttempt = DownloadState.state.value

        assertEquals(previousAttempt.message, retriedAttempt.message)
        assertEquals(DownloadPhase.FAILED, retriedAttempt.phase)
        assertNotEquals(previousAttempt, retriedAttempt)
    }
}
