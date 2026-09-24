package com.musicamz.download

import java.net.URI
import java.util.Locale

/** No Chrome access, user-agent spoofing, JavaScript injection or credential scraping. */
internal object YoutubeWebSession {
    val cookieOrigins = listOf("https://youtube.com/", "https://www.youtube.com/", "https://m.youtube.com/")
    private val pageHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com",
        "accounts.google.com", "consent.google.com", "consent.youtube.com")
    private val cookieName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun allowsNavigation(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme.equals("https", true) && uri.host?.lowercase(Locale.ROOT) in pageHosts &&
            uri.rawUserInfo == null && uri.port == -1
    } catch (_: Exception) { false }

    fun initialUrl(raw: String?): String = try {
        DownloadInput.normalizeYoutubeUrl(raw.orEmpty())
    } catch (_: IllegalArgumentException) { "https://www.youtube.com/" }

    fun toNetscape(headers: Map<String, String?>): String {
        require(headers.keys.all { it in cookieOrigins }) { "Origem de sessão inválida." }
        require(headers.values.sumOf { it?.length ?: 0 } <= NetscapeCookieParser.MAX_BYTES) {
            "A sessão é grande demais."
        }
        val lines = mutableListOf<String>()
        for (origin in cookieOrigins) {
            val header = headers[origin].orEmpty()
            require(header.none { it < ' ' || it == '\u007f' }) { "A sessão contém dados inválidos." }
            val names = mutableSetOf<String>()
            for (part in header.split(';')) {
                if (part.isBlank()) continue
                val pair = part.trim().split('=', limit = 2)
                require(pair.size == 2 && cookieName.matches(pair[0])) { "A sessão contém dados inválidos." }
                // getCookie omits Domain/Path/expiry. Never invent broader domain access:
                // scope this snapshot to the exact HTTPS host queried at its root path.
                if (names.add(pair[0])) lines += listOf(URI(origin).host, "FALSE", "/", "TRUE", "0", pair[0], pair[1]).joinToString("\t")
            }
        }
        require(lines.isNotEmpty()) { "O YouTube ainda não criou uma sessão. Aguarde a página carregar." }
        return NetscapeCookieParser.normalize(("# Netscape HTTP Cookie File\n" + lines.joinToString("\n") + "\n").toByteArray())
    }
}
