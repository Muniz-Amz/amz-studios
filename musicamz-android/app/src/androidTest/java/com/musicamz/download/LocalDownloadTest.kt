package com.musicamz.download

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LocalDownloadTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun nativeEngineDownloadsAndConvertsGeneratedAudioAndRollsBackFailedImport() = runBlocking<Unit> {
        val directory = File(context.noBackupFilesDir, "engine-test-${UUID.randomUUID()}").apply { mkdirs() }
        val pool = Executors.newSingleThreadExecutor()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 60000
        try {
            YoutubeDL.init(context)
            FFmpeg.getInstance().init(context)
            val native = File(context.applicationInfo.nativeLibraryDir)
            assertTrue(File(native, "libqjs.so").exists())
            assertTrue(File(native, "libffmpeg.so").exists())
            val audio = wav()
            val task = pool.submit {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        val output = it.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: ${audio.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        if (!request.startsWith("HEAD")) output.write(audio)
                        output.flush()
                    }
                }
            }
            val request = YoutubeDLRequest("http://127.0.0.1:${server.localPort}/generated.wav")
                .addOption("--ignore-config").addOption("--no-playlist")
                .addOption("--socket-timeout", 10).addOption("--retries", 0)
                .addOption("-x").addOption("--audio-format", "mp3")
                .addOption("-o", File(directory, "audio.%(ext)s").absolutePath)
            val response = YoutubeDL.execute(request, "engine-test", null)
            assertEquals(0, response.exitCode)
            val output = File(directory, "audio.mp3")
            val metadata = LocalAudioImporter.inspect(output)
            assertTrue(metadata.durationMs >= 1500)
            val library = File(context.filesDir, "youtube_audio")
            val before = library.listFiles()?.map { it.name }?.toSet().orEmpty()
            try {
                LocalAudioImporter.publish(context, output, metadata, insert = { error("test database failure") })
                fail("database failure must propagate")
            } catch (expected: IllegalStateException) {
                assertEquals("test database failure", expected.message)
            }
            assertEquals(before, library.listFiles()?.map { it.name }?.toSet().orEmpty())
            server.close()
            task.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            pool.shutdownNow()
            directory.deleteRecursively()
        }
    }

    @Test fun invalidAudioNeverEntersTheLibrary() {
        val file = File(context.cacheDir, "invalid-test.mp3")
        try {
            file.writeText("not audio".repeat(100))
            try { LocalAudioImporter.inspect(file); fail("invalid audio accepted") } catch (_: RuntimeException) { }
            file.writeBytes(byteArrayOf())
            try { LocalAudioImporter.inspect(file); fail("empty audio accepted") } catch (_: RuntimeException) { }
        } finally { file.delete() }
    }

    @Test fun cookiesAreEncryptedAndTamperingFailsClosed() {
        val store = CookieStore(context)
        // The emulator is a dedicated test install with no real cookies.
        assertFalse("Test requires no saved user cookies", store.hasCookies())
        val source = File(context.cacheDir, "cookies-test.txt")
        val taskDir = File(context.noBackupFilesDir, "cookies-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            source.writeText("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t0\tSID\tsynthetic-test-secret\n.google.com\tTRUE\t/\tTRUE\t0\tSID\tforbidden-cookie\n")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
            store.importFrom(uri)
            assertTrue(store.hasCookies())
            val encrypted = File(context.noBackupFilesDir, "youtube-session.aes")
            assertFalse(encrypted.readText().contains("synthetic-test-secret"))
            val session = store.writeSessionFile(taskDir)!!
            assertTrue(session.readText().contains("synthetic-test-secret"))
            assertFalse(session.readText().contains("forbidden-cookie"))
            session.delete()
            val data = encrypted.readBytes()
            data[data.lastIndex] = (data.last().toInt() xor 1).toByte()
            encrypted.writeBytes(data)
            try { store.writeSessionFile(taskDir); fail("tampered session accepted") } catch (_: IllegalStateException) { }
            assertFalse(File(taskDir, "cookies.txt").exists())
        } finally {
            store.delete()
            source.delete()
            taskDir.deleteRecursively()
        }
    }

    private fun wav(): ByteArray {
        val samples = 44100 * 2
        val size = samples * 2
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(44100); putInt(88200)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
            repeat(samples) { i -> putShort((kotlin.math.sin(i * 2.0 * Math.PI * 440.0 / 44100) * 8000).toInt().toShort()) }
        }.array()
    }
}
