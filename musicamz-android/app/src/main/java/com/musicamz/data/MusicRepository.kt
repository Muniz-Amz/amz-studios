package com.musicamz.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class MusicRepository(private val context: Context, private val musicDao: MusicDao) {

    companion object {
        private const val TAG = "MusicRepository"
        private const val PLAYBACK_CACHE_MAX_BYTES = 256L * 1024L * 1024L
        private const val PLAYBACK_CACHE_MAX_FILES = 60
    }

    // Evita duas varreduras completas concorrentes quando a permissão é
    // concedida e o usuário toca em Atualizar quase ao mesmo tempo.
    private val scanMutex = Mutex()

    val allSongs = musicDao.getAllSongs()
    val allPlaylists = musicDao.getAllPlaylists()

    suspend fun scanMusic() = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            try {
                scanMusicLocked()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Alguns provedores de mídia retornam erro temporário ou uma
                // permissão é revogada enquanto a tela ainda está aberta. Uma
                // falha de leitura não deve encerrar o aplicativo inteiro.
                Log.w(TAG, "Não foi possível atualizar a biblioteca", error)
            }
        }
    }

    private suspend fun scanMusicLocked() {
        val statesById = musicDao.getSongStates().associateBy { it.id }
        val songs = mutableListOf<SongEntity>()
        val scanEpochMs = System.currentTimeMillis()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        // Se o provedor do sistema falhar e não devolver cursor, preservamos a
        // biblioteca atual. Um resultado vazio válido continua removendo as
        // faixas físicas que realmente saíram do aparelho.
        val cursor = context.contentResolver.query(
            collection,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        ) ?: return

        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                // Metadados do MediaStore não são confiavelmente obrigatórios
                // em todos os aparelhos. Nunca deixe um null de um arquivo
                // isolado interromper a atualização inteira da biblioteca.
                val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() } ?: "Sem título"
                val artist = cursor.getString(artistColumn)?.takeIf { it.isNotBlank() } ?: "Artista desconhecido"
                val album = cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "Álbum desconhecido"
                val duration = cursor.getLong(durationColumn)
                val albumId = cursor.getLong(albumIdColumn)

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                val albumArtUri = ContentUris.withAppendedId(
                    android.net.Uri.parse("content://media/external/audio/albumart"),
                    albumId
                ).toString()

                val state = statesById[id]
                songs.add(
                    SongEntity(
                        id = id,
                        title = state?.title?.takeIf { it.isNotBlank() } ?: title,
                        artist = state?.artist?.takeIf { it.isNotBlank() } ?: artist,
                        album = state?.album?.takeIf { it.isNotBlank() } ?: album,
                        duration = duration,
                        uri = uri,
                        albumArtUri = albumArtUri,
                        liked = state?.liked ?: false,
                        lastPlayedEpochMs = state?.lastPlayedEpochMs ?: 0,
                        playCount = state?.playCount ?: 0,
                        lastSeenScanEpochMs = scanEpochMs
                    )
                )
            }
        }
        // Mantém importações do MusicAmz (ids negativos) e remove apenas faixas
        // físicas que não existem mais no MediaStore. Assim, uma música apagada
        // fora do app não fica como item quebrado na biblioteca ou em playlists.
        musicDao.synchronizeMediaStoreSongs(songs, scanEpochMs)
    }

    suspend fun createPlaylist(name: String) = musicDao.createPlaylist(PlaylistEntity(name = name))
    suspend fun renamePlaylist(playlistId: Long, name: String) = musicDao.renamePlaylist(playlistId, name)
    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        musicDao.removeSongsFromPlaylist(playlist.id)
        musicDao.deletePlaylist(playlist)
    }
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long) {
        val position = musicDao.getNextPlaylistSongPosition(playlistId)
        musicDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, position))
    }
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        musicDao.removeSongFromPlaylist(playlistId, songId)
    fun getSongsInPlaylist(playlistId: Long) = musicDao.getSongsInPlaylist(playlistId)
    suspend fun moveSongInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) = withContext(Dispatchers.IO) {
        musicDao.moveSongInPlaylist(playlistId, fromIndex, toIndex)
    }

    suspend fun setLiked(songId: Long, liked: Boolean) = musicDao.setSongLiked(songId, liked)
    suspend fun markPlayed(songId: Long) = musicDao.markSongPlayed(songId, System.currentTimeMillis())
    suspend fun addImportedSong(song: SongEntity) = musicDao.insertSong(song)
    suspend fun prepareSongForPlayback(song: SongEntity): SongEntity = withContext(Dispatchers.IO) {
        // Faixas da biblioteca já são content:// do MediaStore ou file:// da
        // pasta privada do MusicAmz. O Media3 lê ambos diretamente. Copiar o
        // arquivo inteiro antes de tocar atrasava o play e duplicava o uso de
        // armazenamento, especialmente em músicas longas.
        song
    }

    suspend fun exportBackup(outputUri: Uri): MusicBackupResult = withContext(Dispatchers.IO) {
        MusicBackupManager(context, musicDao).exportTo(outputUri)
    }

    suspend fun importBackup(inputUri: Uri): MusicBackupResult = withContext(Dispatchers.IO) {
        MusicBackupManager(context, musicDao).importFrom(inputUri)
    }

    suspend fun getPlaybackCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        getPlaybackCacheDir().listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sumOf { it.length() }
            ?: 0L
    }

    suspend fun clearPlaybackCache(): Long = withContext(Dispatchers.IO) {
        val files = getPlaybackCacheDir().listFiles()
            ?.filter { it.isFile }
            .orEmpty()
        val removedBytes = files.sumOf { it.length() }
        files.forEach { it.delete() }
        removedBytes
    }
    suspend fun updateImportedSong(song: SongEntity, title: String, artist: String, album: String) =
        musicDao.updateSongMetadata(song.id, title, artist, album)
    suspend fun deleteImportedSong(song: SongEntity) = withContext(Dispatchers.IO) {
        if (song.id >= 0) return@withContext
        musicDao.removeSongFromAllPlaylists(song.id)
        musicDao.deleteSong(song)
        musicDao.removeOrphanedPlaylistSongs()

        val uri = Uri.parse(song.uri)
        if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() }
        }

        val artworkUri = song.albumArtUri?.let(Uri::parse)
        if (artworkUri?.scheme == "file") {
            artworkUri.path?.let { File(it).delete() }
        }
    }
    suspend fun deleteAllImportedSongs() = withContext(Dispatchers.IO) {
        musicDao.getImportedSongsSnapshot().forEach { deleteImportedSong(it) }
    }

    private fun getPlaybackCacheDir(): File {
        return File(context.cacheDir, "playback").apply { mkdirs() }
    }

    private fun trimPlaybackCache(playbackDir: File, keepFile: File) {
        val keepPath = runCatching { keepFile.canonicalPath }.getOrElse { keepFile.absolutePath }
        val files = playbackDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
            .toMutableList()

        var totalBytes = files.sumOf { it.length() }
        var totalFiles = files.size

        for (file in files) {
            val path = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
            if (path == keepPath) continue
            if (totalBytes <= PLAYBACK_CACHE_MAX_BYTES && totalFiles <= PLAYBACK_CACHE_MAX_FILES) break

            val fileSize = file.length()
            if (file.delete()) {
                totalBytes -= fileSize
                totalFiles -= 1
            }
        }
    }

    private fun resolvePlaybackExtension(source: Uri): String {
        val mime = context.contentResolver.getType(source)
        return when (mime) {
            "audio/mpeg" -> "mp3"
            "audio/mp4", "audio/aac" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/flac" -> "flac"
            else -> source.lastPathSegment
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.takeIf { it.length in 2..5 }
                ?: "audio"
        }
    }
}
