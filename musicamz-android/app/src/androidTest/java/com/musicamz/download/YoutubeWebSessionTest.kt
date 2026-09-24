package com.musicamz.download

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicamz.MainActivity
import com.musicamz.ui.YoutubeSessionActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class YoutubeWebSessionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun webSessionIsEncryptedAndInvalidReplacementPreservesPreviousSession() {
        val store = CookieStore(context)
        assumeFalse("Dedicated test install only", store.hasCookies())
        val folder = File(context.noBackupFilesDir, "web-session-test").apply { mkdirs() }
        try {
            store.importWebSession(mapOf("https://www.youtube.com/" to "SID=synthetic-web-secret"))
            val encrypted = File(context.noBackupFilesDir, "youtube-session.aes").readBytes()
            assertFalse(encrypted.toString(Charsets.UTF_8).contains("synthetic-web-secret"))
            try { store.importWebSession(emptyMap()); fail("Empty session accepted") } catch (_: IllegalArgumentException) { }
            assertArrayEquals(encrypted, File(context.noBackupFilesDir, "youtube-session.aes").readBytes())
            assertTrue(store.writeSessionFile(folder)!!.readText().contains("synthetic-web-secret"))
        } finally { store.delete(); folder.deleteRecursively() }
    }

    @Test fun cancelClearsBrowserCookiesWithoutChangingSavedSession() {
        val store = CookieStore(context)
        assumeFalse("Dedicated test install only", store.hasCookies())
        try {
            store.importWebSession(mapOf("https://www.youtube.com/" to "SID=previous-synthetic-session"))
            val before = File(context.noBackupFilesDir, "youtube-session.aes").readBytes()
            ActivityScenario.launch(YoutubeSessionActivity::class.java).use { scenario ->
                val ready = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val web = descendants(activity.window.decorView).filterIsInstance<WebView>().single()
                    assertFalse(web.settings.allowFileAccess)
                    assertFalse(web.settings.allowContentAccess)
                    assertFalse(CookieManager.getInstance().acceptThirdPartyCookies(web))
                    CookieManager.getInstance().setCookie("https://www.youtube.com/", "SID=temporary-synthetic; Secure; Path=/") { ready.countDown() }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                scenario.onActivity { it.finish() }
            }
            runBlocking {
                withTimeout(10000) {
                    var empty = false
                    while (!empty) {
                        instrumentation.runOnMainSync { empty = CookieManager.getInstance().getCookie("https://www.youtube.com/").isNullOrEmpty() }
                        delay(100)
                    }
                }
            }
            assertArrayEquals(before, File(context.noBackupFilesDir, "youtube-session.aes").readBytes())
        } finally { store.delete() }
    }

    /** Opt-in live acceptance: actual YouTube page -> Use session -> download with that saved session. */
    @Test fun livePageSessionCanBeUsedByDownload() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("webSessionSmoke") == "true")
        val store = CookieStore(context)
        assumeFalse("Dedicated test install only", store.hasCookies())
        val url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        try {
            val intent = Intent(context, YoutubeSessionActivity::class.java).putExtra(YoutubeSessionActivity.EXTRA_LINK, url)
            ActivityScenario.launch<YoutubeSessionActivity>(intent).use { scenario ->
                var usable = false
                withTimeout(90000) {
                    while (!usable) {
                        scenario.onActivity { activity ->
                            usable = descendants(activity.window.decorView).filterIsInstance<Button>()
                                .any { it.text == "Usar esta sessão" && it.isEnabled }
                        }
                        delay(300)
                    }
                }
                scenario.onActivity { activity ->
                    descendants(activity.window.decorView).filterIsInstance<Button>()
                        .single { it.text == "Usar esta sessão" }.performClick()
                }
                withTimeout(15000) { while (!store.hasCookies()) delay(100) }
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                LocalDownloadService.start(context, url, true)
                withTimeout(30000) { while (!DownloadState.state.value.isRunning) delay(100) }
                withTimeout(240000) { while (DownloadState.state.value.isRunning) delay(500) }
                val result = DownloadState.state.value
                assertEquals(result.message, DownloadPhase.COMPLETED, result.phase)
            }
        } finally {
            if (DownloadState.state.value.isRunning) LocalDownloadService.cancel(context)
            store.delete()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
