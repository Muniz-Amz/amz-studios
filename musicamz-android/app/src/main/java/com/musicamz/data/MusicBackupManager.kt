package com.musicamz.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MusicBackupResult(
    val songs: Int,
    val playlists: Int,
    val playlistSongs: Int,
    val skippedSongs: Int = 0,
    val note: String? = null
)

/**
 * Exporta preferências da biblioteca, não os arquivos de áudio em si.
 *
 * Os ids do MediaStore só são estáveis no mesmo aparelho e arquivos importados
 * ficam no armazenamento privado do app. Por isso, a importação sempre cria
 * playlists novas e remapeia ids, em vez de sobrescrever dados locais.
 */
class MusicBackupManager(
    private val context: Context,
    private val musicDao: MusicDao
) {
    companion object {
        private const val APP_NAME = "MusicAmz"
        private const val BACKUP_VERSION = 2
        private const val MAX_BACKUP_CHARS = 8_000_000
    }

    fun exportTo(outputUri: Uri): MusicBackupResult {
        val songs = runBlockingSnapshot { musicDao.getSongsSnapshot() }
        val playlists = runBlockingSnapshot { musicDao.getPlaylistsSnapshot() }
        val playlistSongs = runBlockingSnapshot { musicDao.getPlaylistSongsSnapshot() }

        val root = JSONObject()
            .put("version", BACKUP_VERSION)
            .put("app", APP_NAME)
            .put("exportedAt", System.currentTimeMillis())
            .put("containsAudioFiles", false)
            .put(
                "notice",
                "Este backup guarda dados da biblioteca, não copia arquivos de áudio importados."
            )
            .put("songs", JSONArray().also { array -> songs.forEach { array.put(it.toJson()) } })
            .put("playlists", JSONArray().also { array -> playlists.forEach { array.put(it.toJson()) } })
            .put("playlistSongs", JSONArray().also { array -> playlistSongs.forEach { array.put(it.toJson()) } })

        context.contentResolver.openOutputStream(outputUri)?.bufferedWriter()?.use { writer ->
            writer.write(root.toString(2))
        } ?: error("Não foi possível criar o arquivo de backup")

        return MusicBackupResult(
            songs = songs.size,
            playlists = playlists.size,
            playlistSongs = playlistSongs.size,
            note = "O backup não inclui os arquivos de áudio importados."
        )
    }

    fun importFrom(inputUri: Uri): MusicBackupResult {
        val root = JSONObject(readBackupJson(inputUri))
        val app = root.optString("app", APP_NAME)
        require(app.equals(APP_NAME, ignoreCase = true)) { "Este arquivo não é um backup do MusicAmz" }
        val version = root.optInt("version", 1)
        require(version in 1..BACKUP_VERSION) { "Versão de backup não suportada" }

        val backupSongs = root.optJSONArray("songs").toObjects { it.toSongEntity() }
        val backupPlaylists = root.optJSONArray("playlists").toObjects { it.toPlaylistEntity() }
        val backupPlaylistSongs = root.optJSONArray("playlistSongs").toObjects { it.toPlaylistSongCrossRef() }

        // Usa a transação corrotina do Room para que todas as operações
        // suspendidas usem a mesma conexão do banco.
        return runBlockingSnapshot {
            MusicDatabase.getDatabase(context).withTransaction {
                restoreSafely(backupSongs, backupPlaylists, backupPlaylistSongs)
            }
        }
    }

    private suspend fun restoreSafely(
        backupSongs: List<SongEntity>,
        backupPlaylists: List<PlaylistEntity>,
        backupPlaylistSongs: List<PlaylistSongCrossRef>
    ): MusicBackupResult {
        val existingSongs = musicDao.getSongsSnapshot()
        val existingById = existingSongs.associateBy { it.id }
        val usedSongIds = existingSongs.mapTo(mutableSetOf()) { it.id }
        val songIdMap = mutableMapOf<Long, Long>()
        var restoredSongs = 0
        var skippedSongs = 0

        backupSongs.forEach { song ->
            if (song.id >= 0) {
                // Música do MediaStore: só restauramos estado se ela existir
                // neste aparelho. Inserir o id de outro aparelho criaria uma
                // faixa fantasma que não toca.
                if (existingById.containsKey(song.id)) {
                    val updated = musicDao.restoreSongState(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        liked = song.liked,
                        lastPlayedEpochMs = song.lastPlayedEpochMs,
                        playCount = song.playCount
                    )
                    if (updated > 0) {
                        songIdMap[song.id] = song.id
                        restoredSongs += 1
                    }
                } else {
                    skippedSongs += 1
                }
            } else if (isImportedFileAvailable(song.uri)) {
                // Nunca reutiliza ids negativos do outro dispositivo: isso
                // evita substituir uma importação local já existente.
                val newId = nextImportedSongId(usedSongIds)
                musicDao.insertSong(song.copy(id = newId, lastSeenScanEpochMs = 0))
                songIdMap[song.id] = newId
                usedSongIds += newId
                restoredSongs += 1
            } else {
                skippedSongs += 1
            }
        }

        val existingNames = musicDao.getPlaylistsSnapshot()
            .mapTo(mutableSetOf()) { normalizePlaylistName(it.name) }
        val playlistIdMap = mutableMapOf<Long, Long>()
        var restoredPlaylists = 0

        backupPlaylists.forEach { playlist ->
            val name = uniquePlaylistName(playlist.name, existingNames)
            val newId = musicDao.createPlaylist(PlaylistEntity(name = name))
            playlistIdMap[playlist.id] = newId
            existingNames += normalizePlaylistName(name)
            restoredPlaylists += 1
        }

        val refs = backupPlaylistSongs
            .sortedWith(compareBy<PlaylistSongCrossRef> { it.playlistId }.thenBy { it.position }.thenBy { it.songId })
            .mapNotNull { ref ->
                val playlistId = playlistIdMap[ref.playlistId] ?: return@mapNotNull null
                val songId = songIdMap[ref.songId] ?: return@mapNotNull null
                PlaylistSongCrossRef(playlistId = playlistId, songId = songId, position = ref.position.coerceAtLeast(0))
            }
            .distinctBy { it.playlistId to it.songId }

        if (refs.isNotEmpty()) {
            musicDao.insertPlaylistSongs(refs)
        }
        musicDao.removeOrphanedPlaylistSongs()

        val note = buildList {
            add("Playlists foram importadas como novas para proteger as existentes.")
            add("Arquivos de áudio não fazem parte do backup.")
            if (skippedSongs > 0) {
                add("$skippedSongs música(s) não estavam disponíveis neste aparelho.")
            }
        }.joinToString(" ")

        return MusicBackupResult(
            songs = restoredSongs,
            playlists = restoredPlaylists,
            playlistSongs = refs.size,
            skippedSongs = skippedSongs,
            note = note
        )
    }

    private fun readBackupJson(inputUri: Uri): String {
        val builder = StringBuilder()
        context.contentResolver.openInputStream(inputUri)?.bufferedReader()?.use { reader ->
            val buffer = CharArray(8 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (builder.length + read > MAX_BACKUP_CHARS) {
                    error("O arquivo de backup é grande demais")
                }
                builder.append(buffer, 0, read)
            }
        } ?: error("Não foi possível ler o backup")
        return builder.toString()
    }

    private fun isImportedFileAvailable(uriText: String): Boolean {
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return false
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it).isFile } == true
            "content" -> runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.read() >= -1 } == true
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun nextImportedSongId(usedSongIds: Set<Long>): Long {
        var candidate = -System.currentTimeMillis().coerceAtLeast(1L)
        while (candidate in usedSongIds || candidate == Long.MIN_VALUE) {
            candidate -= 1L
        }
        return candidate
    }

    private fun uniquePlaylistName(rawName: String, existingNames: Set<String>): String {
        val base = rawName.trim().ifBlank { "Playlist importada" }.take(80)
        if (normalizePlaylistName(base) !in existingNames) return base

        var suffix = 2
        while (true) {
            val candidate = "${base.take(70)} (importada $suffix)"
            if (normalizePlaylistName(candidate) !in existingNames) return candidate
            suffix += 1
        }
    }

    private fun normalizePlaylistName(value: String): String = value.trim().lowercase()

    private fun SongEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("artist", artist)
            .put("album", album)
            .put("duration", duration)
            .put("uri", uri)
            .put("albumArtUri", albumArtUri ?: JSONObject.NULL)
            .put("liked", liked)
            .put("lastPlayedEpochMs", lastPlayedEpochMs)
            .put("playCount", playCount)
    }

    private fun PlaylistEntity.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
    }

    private fun PlaylistSongCrossRef.toJson(): JSONObject {
        return JSONObject()
            .put("playlistId", playlistId)
            .put("songId", songId)
            .put("position", position)
    }

    private fun JSONObject.toSongEntity(): SongEntity {
        return SongEntity(
            id = optLong("id"),
            title = optString("title", "Sem título"),
            artist = optString("artist", "Desconhecido"),
            album = optString("album", "Biblioteca"),
            duration = optLong("duration", 0L),
            uri = optString("uri", ""),
            albumArtUri = optNullableString("albumArtUri"),
            liked = optBoolean("liked", false),
            lastPlayedEpochMs = optLong("lastPlayedEpochMs", 0L),
            playCount = optInt("playCount", 0)
        )
    }

    private fun JSONObject.toPlaylistEntity(): PlaylistEntity {
        return PlaylistEntity(
            id = optLong("id", 0L),
            name = optString("name", "Playlist importada")
        )
    }

    private fun JSONObject.toPlaylistSongCrossRef(): PlaylistSongCrossRef {
        return PlaylistSongCrossRef(
            playlistId = optLong("playlistId"),
            songId = optLong("songId"),
            position = optInt("position", 0)
        )
    }

    private fun JSONObject.optNullableString(name: String): String? {
        return if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
    }

    private fun <T> JSONArray?.toObjects(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { add(transform(it)) }
            }
        }
    }
}

private fun <T> runBlockingSnapshot(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
