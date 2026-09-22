package com.musicamz.download

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Normaliza links compartilhados sem permitir destinos arbitrários ao extrator. */
object DownloadInput {
    private val urlPattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val shortHosts = setOf("youtu.be", "www.youtu.be")

    fun normalizeYoutubeUrl(raw: String): String {
        require(raw.length <= 8192) { "O texto compartilhado é muito longo." }
        val links = urlPattern.findAll(raw.trim()).toList()
        require(links.size == 1) { "Cole ou compartilhe apenas um link de vídeo do YouTube." }
        val candidate = links.single().value.trimEnd('.', ',', ';', '!', '?', ')', ']', '}')
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            throw IllegalArgumentException("O link do YouTube é inválido.")
        }
        val host = uri.host?.lowercase(Locale.ROOT)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
            "Use um link HTTP ou HTTPS do YouTube."
        }
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawAuthority?.contains(':') == false) {
            "O link não pode conter credenciais nem uma porta personalizada."
        }
        require(host in youtubeHosts || host in shortHosts) { "Use um link de vídeo do YouTube." }

        val path = uri.rawPath.orEmpty().trimEnd('/')
        val videoId = if (host in shortHosts) {
            path.removePrefix("/")
        } else if (path == "/watch") {
            val ids = try {
                uri.rawQuery.orEmpty().split('&').mapNotNull { field ->
                    val parts = field.split('=', limit = 2)
                    if (URLDecoder.decode(parts[0], "UTF-8") == "v" && parts.size == 2) {
                        URLDecoder.decode(parts[1], "UTF-8")
                    } else null
                }
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("O link do YouTube é inválido.")
            }
            require(ids.size == 1) { "Use o link de um vídeo, em vez do link de uma playlist." }
            ids.single()
        } else {
            val segments = path.removePrefix("/").split('/')
            require(segments.size == 2 && segments[0] in setOf("shorts", "live", "embed")) {
                "Use o link de um vídeo, em vez de uma playlist ou canal."
            }
            segments[1]
        }
        require(videoIdPattern.matches(videoId)) { "O link não contém um identificador de vídeo válido." }
        return "https://www.youtube.com/watch?v=$videoId"
    }
}
