package com.musicamz.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadInputTest {
    private val id = "BaW_jenozKc"
    private val canonical = "https://www.youtube.com/watch?v=$id"

    @Test fun normalizesSharedShortLinkAndRemovesTracking() {
        assertEquals(canonical, DownloadInput.normalizeYoutubeUrl("Olha esta música: https://youtu.be/$id?si=tracking"))
        assertEquals(canonical, DownloadInput.normalizeYoutubeUrl("Ouça (http://youtu.be/$id)."))
    }

    @Test fun acceptsSupportedVideoRoutesAndHosts() {
        listOf(
            "https://youtube.com/watch?v=$id",
            "http://www.youtube.com/watch?feature=share&v=$id&list=PL123",
            "https://m.youtube.com/shorts/$id",
            "https://music.youtube.com/watch?v=$id",
            "https://www.youtube.com/live/$id?feature=share",
            "https://www.youtube.com/embed/$id",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=$id",
        ).forEach { assertEquals(canonical, DownloadInput.normalizeYoutubeUrl(it)) }
    }

    @Test fun rejectsNonVideoLinksAndAmbiguousInputs() {
        listOf(
            "https://youtube.com/playlist?list=PL123",
            "https://youtube.com/@channel",
            "https://youtube.com/watch?list=PL123",
            "https://youtube.com/watch?v=$id&v=$id",
            "https://youtu.be/short",
            "https://youtu.be/$id/another",
            "https://youtube.com/shorts/$id/another",
            "https://youtu.be/$id https://youtu.be/$id",
            "youtube.com/watch?v=$id",
            "apenas texto",
        ).forEach(::reject)
    }

    @Test fun rejectsOtherDomainsCredentialsPortsAndEncodedDestinations() {
        listOf(
            "https://youtube.com.evil.example/watch?v=$id",
            "https://evil-youtube.com/watch?v=$id",
            "https://www.youtube.com@127.0.0.1/watch?v=$id",
            "https://someone@youtube.com/watch?v=$id",
            "https://youtube.com:443/watch?v=$id",
            "http://localhost/watch?v=$id",
            "http://192.168.0.1/watch?v=$id",
            "http://[::1]/watch?v=$id",
            "file:///etc/passwd",
            "https://youtube.com/redirect?q=http://127.0.0.1",
            "https://%79outube.com/watch?v=$id",
            "https://youtube.com/watch?v=%ZZ",
        ).forEach(::reject)
    }

    @Test fun rejectsUnboundedSharedText() {
        reject("x".repeat(8193) + "https://youtu.be/$id")
    }

    private fun reject(value: String) {
        assertThrows("Não deve aceitar: $value", IllegalArgumentException::class.java) {
            DownloadInput.normalizeYoutubeUrl(value)
        }
    }
}
