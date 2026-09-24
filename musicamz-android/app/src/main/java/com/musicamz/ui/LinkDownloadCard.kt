package com.musicamz.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicamz.download.CookieStore
import com.musicamz.download.DownloadInput
import com.musicamz.download.DownloadPhase
import com.musicamz.download.DownloadSnapshot
import com.musicamz.download.DownloadState
import com.musicamz.download.LocalDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LinkDownloadCard(
    incomingLink: String?,
    onLinkConsumed: () -> Unit,
    downloadState: StateFlow<DownloadSnapshot> = DownloadState.state,
    onStartDownload: ((String, Boolean) -> Unit)? = null,
    onUpdateExtractor: (() -> Unit)? = null,
    startupTimeoutMillis: Long = 12_000L
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val cookieStore = remember { CookieStore(context.applicationContext) }
    val download by downloadState.collectAsState()
    var link by rememberSaveable { mutableStateOf("") }
    // Transient feedback must share the pending request's lifecycle, not survive it on rotation.
    var localMessage by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf(false) }
    var localMessageBaseline by remember { mutableStateOf<DownloadSnapshot?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var pendingMessage by remember { mutableStateOf("") }
    var pendingBaseline by remember { mutableStateOf<DownloadSnapshot?>(null) }
    var cookiesPresent by remember { mutableStateOf(false) }
    var useCookies by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var cookieWork by remember { mutableStateOf(false) }
    val busy = pendingStart || download.isRunning || cookieWork

    fun dismissKeyboard() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
    }
    fun feedback(message: String, isError: Boolean = false) {
        localMessage = message
        localError = isError
        localMessageBaseline = downloadState.value
    }
    fun beginPending(message: String) {
        pendingBaseline = downloadState.value
        pendingMessage = message
        pendingStart = true
        feedback("")
    }

    // A service starts asynchronously. Keep the button busy until it acknowledges the request.
    LaunchedEffect(download, pendingStart) {
        if (pendingStart && (download.isRunning || download != pendingBaseline)) {
            pendingStart = false
        }
    }
    LaunchedEffect(download) {
        if (localMessage.isNotBlank() && localMessageBaseline != download) feedback("")
    }
    LaunchedEffect(pendingStart) {
        if (pendingStart) {
            delay(startupTimeoutMillis)
            if (pendingStart && !downloadState.value.isRunning && downloadState.value == pendingBaseline) {
                pendingStart = false
                feedback("O Android não confirmou o início. Toque novamente para tentar.", isError = true)
            }
        }
    }

    LaunchedEffect(Unit) {
        cookiesPresent = withContext(Dispatchers.IO) { cookieStore.hasCookies() }
    }
    LaunchedEffect(incomingLink) {
        if (incomingLink != null) {
            link = incomingLink
            feedback("Link recebido. Toque em baixar para começar.")
            onLinkConsumed()
        }
    }
    val importCookies = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            cookieWork = true
            try {
                withContext(Dispatchers.IO) { cookieStore.importFrom(uri) }
                cookiesPresent = true
                useCookies = true
                feedback("Cookies do YouTube guardados neste aparelho.")
            } catch (_: Exception) {
                feedback("Não foi possível importar os cookies. Confira o arquivo cookies.txt no formato Netscape.", isError = true)
            } finally {
                cookieWork = false
            }
        }
    }
    val verifySession = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            cookiesPresent = true
            useCookies = true
            feedback("Sessão guardada no aparelho. Toque em Baixar MP3 para testar o acesso.")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Do YouTube para sua biblioteca", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Baixe o áudio em MP3 direto no celular. Depois, ouça offline.",
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it.take(8192); feedback("") },
                label = { Text("Link do YouTube") },
                placeholder = { Text("https://youtu.be/…") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("link-download-input"),
                maxLines = 3,
                shape = RoundedCornerShape(14.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (pendingStart || download.isRunning || cookieWork) return@Button
                        dismissKeyboard()
                        val normalized = try {
                            DownloadInput.normalizeYoutubeUrl(link)
                        } catch (e: IllegalArgumentException) {
                            feedback(e.message ?: "Confira o link de vídeo do YouTube.", isError = true)
                            return@Button
                        } catch (_: Exception) {
                            feedback("Confira o link de vídeo do YouTube.", isError = true)
                            return@Button
                        }
                        beginPending("Iniciando download…")
                        try {
                            if (onStartDownload != null) onStartDownload(normalized, useCookies && cookiesPresent)
                            else LocalDownloadService.start(context, normalized, useCookies && cookiesPresent)
                        } catch (_: Exception) {
                            pendingStart = false
                            feedback("Não foi possível iniciar o download. Tente novamente.", isError = true)
                        }
                    },
                    enabled = !busy && link.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Baixar MP3") }
                OutlinedButton(onClick = {
                    link = clipboard.getText()?.text.orEmpty().take(8192)
                    feedback(if (link.isBlank()) "Copie um link do YouTube antes de colar." else "")
                }, enabled = !busy) {
                    Text("Colar")
                }
            }
            val visibleLocalMessage = localMessage.takeIf {
                !download.isRunning && localMessageBaseline == download
            }.orEmpty()
            val feedbackText = when {
                pendingStart -> pendingMessage
                download.isRunning -> download.message
                visibleLocalMessage.isNotBlank() -> visibleLocalMessage
                else -> download.message
            }
            val feedbackIsError = !pendingStart &&
                (if (visibleLocalMessage.isNotBlank()) localError else download.phase == DownloadPhase.FAILED)
            if (feedbackText.isNotBlank()) {
                val bringIntoView = remember { BringIntoViewRequester() }
                // Percentage/message updates must not continually pull the user's scroll position.
                LaunchedEffect(pendingStart, visibleLocalMessage, download.phase) {
                    if (pendingStart || visibleLocalMessage.isNotBlank() || download.phase != DownloadPhase.IDLE) {
                        withFrameNanos { }
                        bringIntoView.bringIntoView()
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .bringIntoViewRequester(bringIntoView)
                        .testTag("link-download-feedback")
                        .semantics {
                            liveRegion = if (feedbackIsError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                            if (feedbackIsError) error(feedbackText)
                        },
                    color = if (feedbackIsError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
                    contentColor = if (feedbackIsError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pendingStart || download.isRunning) {
                            if (!pendingStart && download.progress != null) {
                                LinearProgressIndicator(progress = download.progress!! / 100f, modifier = Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        Text(feedbackText, style = MaterialTheme.typography.bodyMedium)
                        if (!pendingStart && download.isRunning) {
                            TextButton(onClick = { LocalDownloadService.cancel(context) }, enabled = download.canCancel) { Text("Cancelar") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                dismissKeyboard()
                try {
                    verifySession.launch(Intent(context, YoutubeSessionActivity::class.java)
                        .putExtra(YoutubeSessionActivity.EXTRA_LINK, link))
                } catch (_: Exception) {
                    feedback("Não foi possível abrir a verificação. Tente novamente.", isError = true)
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Verificar acesso ao YouTube")
            }
            Text("Experimente se o YouTube pedir uma verificação. Você não precisa exportar um arquivo.",
                style = MaterialTheme.typography.bodySmall)
            if (cookiesPresent) {
                Row {
                    Checkbox(checked = useCookies, onCheckedChange = { useCookies = it }, enabled = !busy)
                    Text("Usar sessão salva neste download", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            TextButton(onClick = { showOptions = !showOptions }) {
                Text(if (showOptions) "Fechar opções" else "Cookies e atualização")
            }
            if (showOptions) {
                HorizontalDivider()
                Text("Cookies opcionais", fontWeight = FontWeight.SemiBold)
                Text(
                    "Use apenas seus próprios cookies se o YouTube pedir login. Importe um arquivo cookies.txt no formato Netscape. Eles ficam criptografados no aparelho e são enviados apenas ao YouTube durante o download. Podem expirar e não garantem acesso a todos os vídeos.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = { importCookies.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) {
                    Text(if (cookiesPresent) "Substituir cookies" else "Importar cookies.txt")
                }
                if (cookiesPresent) {
                    TextButton(onClick = {
                        scope.launch {
                            cookieWork = true
                            try {
                                withContext(Dispatchers.IO) { cookieStore.delete() }
                                YoutubeSessionActivity.clearBrowserSession()
                                cookiesPresent = false
                                useCookies = false
                                feedback("Cookies removidos do MusicAmz.")
                            } catch (_: Exception) {
                                feedback("Não foi possível remover os cookies. Tente novamente.", isError = true)
                            } finally { cookieWork = false }
                        }
                    }, enabled = !busy) { Text("Apagar cookies do aplicativo") }
                }
                HorizontalDivider()
                Text("Manter o download atualizado", fontWeight = FontWeight.SemiBold)
                Text("Se o YouTube mudar, atualize o extrator. Essa ação usa internet e não envia seus cookies.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    if (pendingStart || download.isRunning || cookieWork) return@OutlinedButton
                    dismissKeyboard()
                    beginPending("Iniciando atualização…")
                    try {
                        if (onUpdateExtractor != null) onUpdateExtractor()
                        else LocalDownloadService.updateExtractor(context)
                    } catch (_: Exception) {
                        pendingStart = false
                        feedback("Não foi possível iniciar a atualização. Tente novamente.", isError = true)
                    }
                }, enabled = !busy) { Text("Atualizar extrator") }
            }
        }
    }
}
