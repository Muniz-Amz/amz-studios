package com.musicamz.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.musicamz.MusicApplication
import com.musicamz.data.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ValidatedAudio(val durationMs: Long, val title: String, val artist: String, val album: String)

/** A completed file is validated, synced, then renamed on the same internal filesystem. */
object LocalAudioImporter {
    const val MAX_AUDIO_BYTES = 500L * 1024 * 1024
    private val pendingName = Regex("youtube_[0-9a-f-]{36}\\.mp3\\.pending")

    fun inspect(file: File): ValidatedAudio {
        require(file.isFile && file.length() in 128..MAX_AUDIO_BYTES) {
            "O áudio recebido está vazio, incompleto ou ultrapassa 500 MB."
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            require(duration > 0 && mime == "audio/mpeg") {
                "Não foi possível validar o áudio. O arquivo não foi adicionado à biblioteca."
            }
            return ValidatedAudio(
                durationMs = duration,
                title = safeMetadata(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), "Áudio do YouTube"),
                artist = safeMetadata(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), "YouTube"),
                album = safeMetadata(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), "Importados")
            )
        } finally {
            retriever.release()
        }
    }

    /** beforeCommit atomically closes the cancellation window; insert is injectable for rollback tests. */
    suspend fun publish(
        context: Context,
        source: File,
        audio: ValidatedAudio,
        beforeCommit: () -> Unit = {},
        insert: suspend (SongEntity) -> Unit = {
            (context.applicationContext as MusicApplication).repository.addImportedSong(it)
        }
    ): SongEntity = withContext(Dispatchers.IO + NonCancellable) {
        val directory = File(context.filesDir, "youtube_audio").apply {
            check(isDirectory || mkdirs()) { "Não foi possível preparar o armazenamento." }
        }
        val target = File(directory, "youtube_${UUID.randomUUID()}.mp3")
        val pending = File(directory, "${target.name}.pending")
        val id = -(UUID.randomUUID().mostSignificantBits ushr 1).coerceAtLeast(1)
        val song = SongEntity(id, audio.title, audio.artist, audio.album, audio.durationMs,
            Uri.fromFile(target).toString(), null)
        var inserted = false
        try {
            // A killed process can leave a renamed file. The journal is reconciled with Room next time.
            FileOutputStream(pending).use { it.fd.sync() }
            FileOutputStream(source, true).use { it.fd.sync() }
            beforeCommit()
            check(source.renameTo(target)) { "Não foi possível salvar o áudio com segurança." }
            insert(song)
            inserted = true
            song
        } finally {
            if (!inserted) target.delete()
            pending.delete()
        }
    }

    suspend fun recoverInterruptedImports(context: Context) = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "youtube_audio")
        val journals = directory.listFiles()?.filter { pendingName.matches(it.name) }.orEmpty()
        if (journals.isEmpty()) return@withContext
        val stored = (context.applicationContext as MusicApplication).repository.allSongs.first()
            .mapTo(hashSetOf()) { it.uri }
        journals.forEach { journal ->
            val target = File(directory, journal.name.removeSuffix(".pending"))
            if (Uri.fromFile(target).toString() !in stored) target.delete()
            journal.delete()
        }
    }

    private fun safeMetadata(value: String?, fallback: String): String = value.orEmpty()
        .filterNot { it.isISOControl() }.trim().take(200).ifBlank { fallback }
}
