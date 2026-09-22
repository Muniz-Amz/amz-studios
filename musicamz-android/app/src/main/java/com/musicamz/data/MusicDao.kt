package com.musicamz.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id < 0")
    suspend fun getImportedSongsSnapshot(): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<SongEntity>)

    @Query("DELETE FROM songs WHERE id >= 0 AND lastSeenScanEpochMs != :scanEpochMs")
    suspend fun deleteStaleMediaStoreSongs(scanEpochMs: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId NOT IN (SELECT id FROM playlists) OR songId NOT IN (SELECT id FROM songs)")
    suspend fun removeOrphanedPlaylistSongs()

    @Transaction
    suspend fun synchronizeMediaStoreSongs(songs: List<SongEntity>, scanEpochMs: Long) {
        if (songs.isNotEmpty()) {
            insertSongs(songs)
        }
        deleteStaleMediaStoreSongs(scanEpochMs)
        removeOrphanedPlaylistSongs()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Query("SELECT * FROM songs")
    suspend fun getSongsSnapshot(): List<SongEntity>

    @Query("SELECT liked FROM songs WHERE id = :songId LIMIT 1")
    suspend fun isSongLiked(songId: Long): Boolean?

    @Query("UPDATE songs SET liked = :liked WHERE id = :songId")
    suspend fun setSongLiked(songId: Long, liked: Boolean)

    @Query("UPDATE songs SET title = :title, artist = :artist, album = :album WHERE id = :songId")
    suspend fun updateSongMetadata(songId: Long, title: String, artist: String, album: String)

    @Query("UPDATE songs SET lastPlayedEpochMs = :epochMs, playCount = playCount + 1 WHERE id = :songId")
    suspend fun markSongPlayed(songId: Long, epochMs: Long)

    @Query("""
        UPDATE songs
        SET title = :title,
            artist = :artist,
            album = :album,
            liked = :liked,
            lastPlayedEpochMs = :lastPlayedEpochMs,
            playCount = :playCount
        WHERE id = :songId
    """)
    suspend fun restoreSongState(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        liked: Boolean,
        lastPlayedEpochMs: Long,
        playCount: Int
    ): Int

    @Query("SELECT id, title, artist, album, liked, lastPlayedEpochMs, playCount FROM songs")
    suspend fun getSongStates(): List<SongState>

    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists")
    suspend fun getPlaylistsSnapshot(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs ORDER BY playlistId ASC, position ASC, songId ASC")
    suspend fun getPlaylistSongsSnapshot(): List<PlaylistSongCrossRef>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String)

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Delete
    suspend fun deleteSong(song: SongEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(playlistSong: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(playlistSongs: List<PlaylistSongCrossRef>)

    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN playlist_songs ON songs.id = playlist_songs.songId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position ASC, playlist_songs.songId ASC
    """)
    fun getSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getNextPlaylistSongPosition(playlistId: Long): Int

    @Query("SELECT songId FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC, songId ASC")
    suspend fun getPlaylistSongIds(playlistId: Long): List<Long>

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun setPlaylistSongPosition(playlistId: Long, songId: Long, position: Int)

    @Transaction
    suspend fun moveSongInPlaylist(playlistId: Long, fromIndex: Int, toIndex: Int) {
        val songIds = getPlaylistSongIds(playlistId).toMutableList()
        if (fromIndex !in songIds.indices || toIndex !in songIds.indices || fromIndex == toIndex) return

        val songId = songIds.removeAt(fromIndex)
        songIds.add(toIndex, songId)
        songIds.forEachIndexed { index, id ->
            setPlaylistSongPosition(playlistId, id, index)
        }
    }

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun removeSongsFromPlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE songId = :songId")
    suspend fun removeSongFromAllPlaylists(songId: Long)
}

data class SongState(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val liked: Boolean,
    val lastPlayedEpochMs: Long,
    val playCount: Int
)
