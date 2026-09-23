package com.musicamz.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.musicamz.download.DownloadPhase
import com.musicamz.download.DownloadSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual paste/button/scroll UI without network-dependent service work. */
@RunWith(AndroidJUnit4::class)
class LinkDownloadCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showCard(
        state: MutableStateFlow<DownloadSnapshot> = MutableStateFlow(DownloadSnapshot()),
        timeoutMillis: Long = 12_000L,
        start: (String, Boolean) -> Unit = { _, _ -> },
        update: () -> Unit = {},
        incomingLink: MutableState<String?>? = null,
        restoration: StateRestorationTester? = null
    ) {
        val content: @Composable () -> Unit = {
            MaterialTheme {
                Column(
                    Modifier.width(340.dp).height(300.dp)
                        .testTag("download-scroll").verticalScroll(rememberScrollState())
                ) {
                    LinkDownloadCard(
                        incomingLink = incomingLink?.value,
                        onLinkConsumed = { incomingLink?.value = null },
                        downloadState = state,
                        onStartDownload = start,
                        onUpdateExtractor = update,
                        startupTimeoutMillis = timeoutMillis
                    )
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    }

    private fun paste(text: String) {
        compose.runOnUiThread {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("link", text))
        }
        compose.onNodeWithText("Colar").performScrollTo().performClick()
    }

    @Test fun pastedInvalidLinkShowsVisibleValidationAndClearsFocus() {
        var starts = 0
        showCard(start = { _, _ -> starts++ })
        paste("https://example.com/video")
        compose.onNodeWithTag("link-download-input").performScrollTo().performClick()
        compose.onNodeWithText("Baixar MP3").performScrollTo().performClick()

        compose.onNodeWithText("Use um link de vídeo do YouTube.").assertIsDisplayed()
        compose.onNodeWithTag("link-download-input").assertIsNotFocused()
        compose.onNodeWithText("Baixar MP3").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun pastedLinkStartsOnceAndShowsPendingUntilServiceAcknowledges() {
        val state = MutableStateFlow(DownloadSnapshot())
        var starts = 0
        var receivedUrl = ""
        showCard(state = state, start = { url, _ -> starts++; receivedUrl = url })
        paste("https://youtu.be/jNQXAC9IVRw?si=tracking")
        val button = compose.onNodeWithText("Baixar MP3")
        // Invoke the same click twice in one frame, before enabled state can recompose.
        val click = button.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnUiThread { click(); click() }

        button.assertIsNotEnabled()
        compose.onNodeWithText("Iniciando download…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", receivedUrl)
            state.value = DownloadSnapshot(DownloadPhase.DOWNLOADING, progress = 5, message = "Baixando áudio…")
        }
        compose.onNodeWithText("Iniciando download…").assertDoesNotExist()
        compose.onNodeWithText("Baixando áudio…").assertIsDisplayed()
        button.assertIsNotEnabled()
    }

    @Test fun startupExceptionIsSafeAndVisibleWithoutManualScrolling() {
        showCard(
            state = MutableStateFlow(DownloadSnapshot(DownloadPhase.COMPLETED, message = "Download anterior concluído.")),
            start = { _, _ -> throw IllegalStateException("Private path /data/user/0/private-cookie") }
        )
        paste("https://youtu.be/jNQXAC9IVRw")
        compose.onNodeWithText("Baixar MP3").performScrollTo().performClick()

        val message = "Não foi possível iniciar o download. Tente novamente."
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag("link-download-feedback")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, message))
        compose.onNodeWithText("Private path", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Download anterior concluído.").assertDoesNotExist()
        compose.onNodeWithText("Baixar MP3").assertIsEnabled()
    }

    @Test fun terminalFailureReplacesPreviousCompletedStatusAndBecomesVisible() {
        val state = MutableStateFlow(DownloadSnapshot(DownloadPhase.COMPLETED, message = "Download anterior concluído."))
        showCard(state = state)
        paste("https://youtu.be/jNQXAC9IVRw")
        compose.onNodeWithText("Baixar MP3").performScrollTo().performClick()
        compose.onNodeWithText("Iniciando download…").assertIsDisplayed()
        compose.runOnIdle {
            state.value = DownloadSnapshot(DownloadPhase.DOWNLOADING, 30, "Baixando áudio…")
        }
        // Simulate the user scrolling away while the service works.
        compose.onNodeWithTag("link-download-input").performScrollTo()
        val failure = "O YouTube pediu confirmação de login."
        compose.runOnIdle { state.value = DownloadSnapshot(DownloadPhase.FAILED, message = failure) }

        compose.onNodeWithText(failure).assertIsDisplayed()
        compose.onNodeWithText("Download anterior concluído.").assertDoesNotExist()
        compose.onNodeWithText("Baixar MP3").assertIsEnabled()
    }

    @Test fun timeoutAllowsRetryAndLateServiceAcknowledgementReplacesTimeout() {
        val state = MutableStateFlow(DownloadSnapshot())
        showCard(state = state, timeoutMillis = 500L)
        paste("https://youtu.be/jNQXAC9IVRw")
        compose.onNodeWithText("Baixar MP3").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(600L)

        val timeout = "O Android não confirmou o início. Toque novamente para tentar."
        compose.onNodeWithText(timeout).assertIsDisplayed()
        compose.onNodeWithText("Baixar MP3").assertIsEnabled()
        compose.runOnIdle {
            state.value = DownloadSnapshot(DownloadPhase.PREPARING, message = "Preparando download…")
        }
        compose.onNodeWithText(timeout).assertDoesNotExist()
        compose.onNodeWithText("Preparando download…").assertIsDisplayed()
        compose.onNodeWithText("Baixar MP3").assertIsNotEnabled()
    }

    @Test fun percentageUpdatesDoNotPullScrollBackToFeedback() {
        val state = MutableStateFlow(DownloadSnapshot(DownloadPhase.DOWNLOADING, 10, "Baixando: 10%"))
        showCard(state = state)
        compose.onNodeWithText("Do YouTube para sua biblioteca").performScrollTo()
        val scroll = compose.onNodeWithTag("download-scroll")
        val before = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle {
            state.value = DownloadSnapshot(DownloadPhase.DOWNLOADING, 20, "Baixando: 20%")
        }
        val after = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before, after, 0.5f)
    }

    @Test fun incomingSharedLinkCannotMaskActiveProgressOrTerminalResult() {
        val state = MutableStateFlow(DownloadSnapshot(DownloadPhase.DOWNLOADING, 10, "Baixando áudio…"))
        val incoming = mutableStateOf<String?>(null)
        showCard(state = state, incomingLink = incoming)
        compose.runOnIdle { incoming.value = "https://youtu.be/jNQXAC9IVRw" }

        compose.onNodeWithText("Baixando áudio…").assertIsDisplayed()
        compose.onNodeWithText("Link recebido. Toque em baixar para começar.").assertDoesNotExist()
        compose.runOnIdle {
            state.value = DownloadSnapshot(DownloadPhase.FAILED, message = "O YouTube pediu confirmação de login.")
        }
        compose.onNodeWithText("O YouTube pediu confirmação de login.").assertIsDisplayed()
        compose.onNodeWithText("Link recebido. Toque em baixar para começar.").assertDoesNotExist()
        compose.onNodeWithText("Baixar MP3").assertIsEnabled()
    }

    @Test fun timeoutCannotMaskLateServiceProgressOrCompletionAfterRestoration() {
        val state = MutableStateFlow(DownloadSnapshot())
        val restoration = StateRestorationTester(compose)
        showCard(state = state, timeoutMillis = 500L, restoration = restoration)
        paste("https://youtu.be/jNQXAC9IVRw")
        compose.onNodeWithText("Baixar MP3").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(600L)
        val timeout = "O Android não confirmou o início. Toque novamente para tentar."
        compose.onNodeWithText(timeout).assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("link-download-input").assertTextContains("https://youtu.be/jNQXAC9IVRw")
        compose.runOnIdle {
            state.value = DownloadSnapshot(DownloadPhase.DOWNLOADING, 15, "Baixando áudio…")
        }
        compose.onNodeWithText(timeout).assertDoesNotExist()
        compose.onNodeWithText("Baixando áudio…").assertIsDisplayed()
        compose.runOnIdle {
            state.value = DownloadSnapshot(DownloadPhase.COMPLETED, message = "Áudio salvo na biblioteca.")
        }
        compose.onNodeWithText("Áudio salvo na biblioteca.").assertIsDisplayed()
        compose.onNodeWithText(timeout).assertDoesNotExist()
    }

    @Test fun repeatedFailureWithNewRevisionAcknowledgesWithoutFalseTimeout() {
        val failure = "O YouTube pediu confirmação de login."
        val previous = DownloadSnapshot(DownloadPhase.FAILED, message = failure, revision = 7L)
        val state = MutableStateFlow(previous)
        showCard(state = state, start = { _, _ -> state.value = previous.copy(revision = 8L) })
        paste("https://youtu.be/jNQXAC9IVRw")
        compose.onNodeWithText("Baixar MP3").performScrollTo().performClick()

        compose.onNodeWithText(failure).assertIsDisplayed()
        compose.onNodeWithText("Iniciando download…").assertDoesNotExist()
        compose.onNodeWithText("Baixar MP3").assertIsEnabled()
        compose.mainClock.advanceTimeBy(13_000L)
        compose.onNodeWithText("O Android não confirmou o início. Toque novamente para tentar.").assertDoesNotExist()
        compose.onNodeWithText(failure).assertIsDisplayed()
    }

    @Test fun updateStartupFailureIsVisibleAndSafe() {
        var updates = 0
        showCard(update = { updates++; throw SecurityException("Private path /data/secret") })
        compose.onNodeWithText("Cookies e atualização").performScrollTo().performClick()
        compose.onNodeWithText("Atualizar extrator").performScrollTo().performClick()

        compose.onNodeWithText("Não foi possível iniciar a atualização. Tente novamente.").assertIsDisplayed()
        compose.onNodeWithText("Atualizar extrator").assertIsEnabled()
        compose.onNodeWithText("Private path", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, updates) }
    }
}
