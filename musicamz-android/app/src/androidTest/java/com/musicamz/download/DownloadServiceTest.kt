package com.musicamz.download

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicamz.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DownloadServiceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun invalidRequestAndCancellationReleaseService() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            LocalDownloadService.start(context, "https://example.com/invalid", false)
            withTimeout(30000) { while (DownloadState.state.value.phase != DownloadPhase.FAILED) delay(100) }
            assertFalse(DownloadState.state.value.isRunning)
            LocalDownloadService.start(context, "https://www.youtube.com/watch?v=jNQXAC9IVRw", false)
            withTimeout(30000) { while (!DownloadState.state.value.isRunning) delay(100) }
            LocalDownloadService.cancel(context)
            withTimeout(30000) { while (DownloadState.state.value.isRunning) delay(100) }
            assertEquals(DownloadPhase.CANCELLED, DownloadState.state.value.phase)
            assertTrue(File(context.noBackupFilesDir, "audio_jobs").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun youtubeDownloadOnDevice() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("youtubeSmoke") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            LocalDownloadService.start(context, "https://www.youtube.com/watch?v=jNQXAC9IVRw", false)
            withTimeout(30000) { while (!DownloadState.state.value.isRunning) delay(100) }
            withTimeout(240000) { while (DownloadState.state.value.isRunning) delay(500) }
            val result = DownloadState.state.value
            assertEquals(result.message, DownloadPhase.COMPLETED, result.phase)
        }
    }

    @Test fun extractorUpdateOnDevice() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("updateSmoke") == "true")
        ActivityScenario.launch(MainActivity::class.java).use {
            LocalDownloadService.updateExtractor(context)
            withTimeout(30000) { while (!DownloadState.state.value.isRunning) delay(100) }
            withTimeout(240000) { while (DownloadState.state.value.isRunning) delay(500) }
            val result = DownloadState.state.value
            assertEquals(result.message, DownloadPhase.COMPLETED, result.phase)
        }
    }
}
