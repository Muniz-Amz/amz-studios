package com.musicamz.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetscapeCookieParserTest {
    private fun cookie(
        domain: String = ".youtube.com",
        includeSubdomains: String = "TRUE",
        path: String = "/",
        secure: String = "TRUE",
        expires: String = "0",
        name: String = "SESSION_TEST",
        value: String = "synthetic-value",
    ) = listOf(domain, includeSubdomains, path, secure, expires, name, value).joinToString("\t")

    @Test fun acceptsSessionCookiesHttpOnlyAndCrLf() {
        val plain = cookie()
        val httpOnly = "#HttpOnly_" + cookie(domain = "youtube.com", expires = "1900000000")
        val result = parse("\uFEFF# Netscape HTTP Cookie File\r\n$plain\r\n$httpOnly\r\n")
        assertEquals("# Netscape HTTP Cookie File\n$plain\n$httpOnly\n", result)
    }

    @Test fun excludesGoogleAndUnrelatedDomainSessions() {
        val result = parse(listOf(
            cookie(domain = ".google.com", value = "google-secret"),
            cookie(domain = "youtube.com.attacker.example", value = "other-secret"),
            cookie(domain = ".youtube.com", value = "youtube-value"),
            cookie(domain = "youtu.be", value = "short-link-value"),
        ).joinToString("\n"))
        assertFalse(result.contains("google-secret"))
        assertFalse(result.contains("other-secret"))
        assertTrue(result.contains("youtube-value"))
        assertTrue(result.contains("short-link-value"))
    }

    @Test fun normalizesDomainCaseAndKeepsEmptyCookieValue() {
        assertEquals("# Netscape HTTP Cookie File\n${cookie(domain = "youtube.com", value = "")}\n",
            parse(cookie(domain = "YouTube.COM", value = "")))
    }

    @Test fun rejectsFilesWithoutYoutubeCookies() {
        reject("# Empty file\n")
        reject(cookie(domain = ".google.com"))
        reject(cookie(domain = "evil-youtube.com"))
    }

    @Test fun rejectsMalformedFlagsExpiryPathsNamesAndFields() {
        listOf(
            cookie(includeSubdomains = "yes"),
            cookie(secure = "1"),
            cookie(expires = "-1"),
            cookie(expires = "1.5"),
            cookie(expires = "99999999999999999999999999"),
            cookie(path = "relative"),
            cookie(name = ""),
            cookie(name = "invalid name"),
            cookie(value = "with\u0000control"),
            cookie() + "\textra-field",
            "not a cookie export",
        ).forEach(::reject)
    }

    @Test fun rejectsInvalidUtf8AndOversizedFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            NetscapeCookieParser.normalize(byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetscapeCookieParser.normalize(ByteArray(NetscapeCookieParser.MAX_BYTES + 1))
        }
    }

    private fun parse(value: String) = NetscapeCookieParser.normalize(value.toByteArray(Charsets.UTF_8))

    private fun reject(value: String) {
        assertThrows(IllegalArgumentException::class.java) { parse(value) }
    }
}
