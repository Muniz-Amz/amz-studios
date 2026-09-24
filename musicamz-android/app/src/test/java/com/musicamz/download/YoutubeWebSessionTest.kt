package com.musicamz.download

import org.junit.Assert.*
import org.junit.Test

class YoutubeWebSessionTest {
    @Test fun navigationRejectsUntrustedUrlsAndSchemes() {
        listOf("https://www.youtube.com/watch?v=jNQXAC9IVRw", "https://accounts.google.com/", "https://consent.youtube.com/")
            .forEach { assertTrue(YoutubeWebSession.allowsNavigation(it)) }
        listOf("http://www.youtube.com/", "https://youtube.com.attacker.test/", "https://evil.youtube.com/",
            "https://www.youtube.com@attacker.test/", "https://attacker@youtube.com/", "https://youtube.com:443/",
            "file:///etc/passwd", "javascript:alert(1)", "intent://youtube.com", "https://google.com/", "bad url")
            .forEach { assertFalse(it, YoutubeWebSession.allowsNavigation(it)) }
    }

    @Test fun initialUrlOnlyUsesNormalizedVideoOrHomepage() {
        assertEquals("https://www.youtube.com/watch?v=jNQXAC9IVRw", YoutubeWebSession.initialUrl("https://youtu.be/jNQXAC9IVRw?si=tracking"))
        assertEquals("https://www.youtube.com/", YoutubeWebSession.initialUrl("https://attacker.test/"))
        assertEquals("https://www.youtube.com/", YoutubeWebSession.initialUrl(null))
    }

    @Test fun snapshotsAreHostOnlySecureAndKeepEqualsInValues() {
        val result = YoutubeWebSession.toNetscape(mapOf("https://www.youtube.com/" to "SID=synthetic==; PREF=; SID=ignored"))
        assertTrue(result.contains("www.youtube.com\tFALSE\t/\tTRUE\t0\tSID\tsynthetic=="))
        assertTrue(result.contains("www.youtube.com\tFALSE\t/\tTRUE\t0\tPREF\t\n"))
        assertFalse(result.contains("ignored"))
        assertFalse(result.contains(".youtube.com\tTRUE"))
    }

    @Test fun rejectsForeignOriginsEmptySessionsAndHeaderInjection() {
        listOf(mapOf("https://accounts.google.com/" to "SID=secret"), emptyMap(),
            mapOf("https://www.youtube.com/" to ""), mapOf("https://www.youtube.com/" to "SID=value\nInjected=yes"),
            mapOf("https://www.youtube.com/" to "bad name=value"), mapOf("https://www.youtube.com/" to "not-a-cookie"),
            mapOf("https://www.youtube.com/" to "SID=" + "x".repeat(NetscapeCookieParser.MAX_BYTES)))
            .forEach { headers -> assertThrows(IllegalArgumentException::class.java) { YoutubeWebSession.toNetscape(headers) } }
    }
}
