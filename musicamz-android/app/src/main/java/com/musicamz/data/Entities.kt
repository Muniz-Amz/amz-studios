package com.musicamz.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: String,
    val albumArtUri: String?,
    val liked: Boolean = false,
    val lastPlayedEpochMs: Long = 0,
    val playCount: Int = 0,
    /**
     * Marca a última varredura do MediaStore em que esta faixa física foi
     * encontrada. Faixas importadas pelo app usam ids negativos e ficam com 0.
     */
    @ColumnInfo(defaultValue = "0")
    val lastSeenScanEpochMs: Long = 0
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "playlist_songs", primaryKeys = ["playlistId", "songId"])
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: Long,
    @ColumnInfo(defaultValue = "0")
    val position: Int = 0
)
