package com.musicamz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.musicamz.download.CookieStore
import com.musicamz.download.DownloadInput
import com.musicamz.download.DownloadState
import com.musicamz.download.LocalDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LinkDownloadCard(incomingLink: String?, onLinkConsumed: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val cookieStore = remember { CookieStore(context.applicationContext) }
    val download by DownloadState.state.collectAsState()
    var link by rememberSaveable { mutableStateOf("") }
    var localMessage by rememberSaveable { mutableStateOf("") }
    var cookiesPresent by remember { mutableStateOf(false) }
    var useCookies by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var cookieWork by remember { mutableStateOf(false) }
    val busy = download.isRunning || cookieWork

    LaunchedEffect(Unit) {
        cookiesPresent = withContext(Dispatchers.IO) { cookieStore.hasCookies() }
    }
    LaunchedEffect(incomingLink) {
        if (incomingLink != null) {
            link = incomingLink
            localMessage = "Link recebido. Toque em baixar para começar."
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
                localMessage = "Cookies do YouTube guardados neste aparelho."
            } catch (e: Exception) {
                localMessage = e.message ?: "Não foi possível importar os cookies."
            } finally {
                cookieWork = false
            }
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
                onValueChange = { link = it.take(8192); localMessage = "" },
                label = { Text("Link do YouTube") },
                placeholder = { Text("https://youtu.be/…") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                shape = RoundedCornerShape(14.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        try {
                            val normalized = DownloadInput.normalizeYoutubeUrl(link)
                            LocalDownloadService.start(context, normalized, useCookies && cookiesPresent)
                            localMessage = ""
                        } catch (e: Exception) {
                            localMessage = e.message ?: "Não foi possível iniciar o download."
                        }
                    },
                    enabled = !busy && link.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Baixar MP3") }
                OutlinedButton(onClick = { link = clipboard.getText()?.text.orEmpty().take(8192) }, enabled = !busy) {
                    Text("Colar")
                }
            }
            if (download.isRunning) {
                if (download.progress != null) {
                    LinearProgressIndicator(progress = download.progress!! / 100f, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(download.message, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { LocalDownloadService.cancel(context) }, enabled = download.canCancel) { Text("Cancelar") }
            } else if (download.message.isNotBlank()) {
                Text(download.message, style = MaterialTheme.typography.bodyMedium)
            }
            if (localMessage.isNotBlank()) {
                Text(localMessage, style = MaterialTheme.typography.bodySmall)
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
                if (cookiesPresent) {
                    Row {
                        Checkbox(checked = useCookies, onCheckedChange = { useCookies = it }, enabled = !busy)
                        Text("Usar meus cookies neste download", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedButton(onClick = { importCookies.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) {
                    Text(if (cookiesPresent) "Substituir cookies" else "Importar cookies.txt")
                }
                if (cookiesPresent) {
                    TextButton(onClick = {
                        scope.launch {
                            cookieWork = true
                            try {
                                withContext(Dispatchers.IO) { cookieStore.delete() }
                                cookiesPresent = false
                                useCookies = false
                                localMessage = "Cookies removidos do MusicAmz."
                            } catch (_: Exception) {
                                localMessage = "Não foi possível remover os cookies. Tente novamente."
                            } finally { cookieWork = false }
                        }
                    }, enabled = !busy) { Text("Apagar cookies do aplicativo") }
                }
                HorizontalDivider()
                Text("Manter o download atualizado", fontWeight = FontWeight.SemiBold)
                Text("Se o YouTube mudar, atualize o extrator. Essa ação usa internet e não envia seus cookies.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    try {
                        LocalDownloadService.updateExtractor(context)
                        localMessage = ""
                    } catch (_: Exception) {
                        localMessage = "Não foi possível iniciar a atualização."
                    }
                }, enabled = !busy) { Text("Atualizar extrator") }
            }
        }
    }
}
