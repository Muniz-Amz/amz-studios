package com.musicamz.player

import android.content.Context
import androidx.media3.common.Player
import com.musicamz.ui.util.songIdOrNull

data class PlaybackSessionSnapshot(
    val songId: Long,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
    val queueIds: List<Long>
)

/** Persiste a sessão exata da fila no próprio aparelho. */
object PlaybackSessionStore {
    private const val PLAYER_SESSION_PREFS = "musicamz_player_session"
    private const val KEY_LAST_SONG_ID = "last_song_id"
    private const val KEY_LAST_INDEX = "last_index"
    private const val KEY_LAST_POSITION_MS = "last_position_ms"
    private const val KEY_LAST_SHUFFLE = "last_shuffle"
    private const val KEY_LAST_REPEAT = "last_repeat"
    private const val KEY_LAST_QUEUE_IDS = "last_queue_ids"

    /**
     * Lê o player no thread dele e cria um retrato imutável da sessão.
     *
     * A gravação em SharedPreferences pode então acontecer em background sem
     * tocar no player fora do seu looper.
     */
    fun snapshot(player: Player): PlaybackSessionSnapshot? {
        val queueIds = (0 until player.mediaItemCount)
            .mapNotNull { index -> player.getMediaItemAt(index).songIdOrNull() }

        val currentIndex = player.currentMediaItemIndex
        val currentSongId = player.currentMediaItem?.songIdOrNull()
        // Uma fila com algum item sem id não pode ser restaurada de forma
        // confiável. Não grave uma sessão parcial que apontaria para a faixa
        // errada na próxima abertura.
        if (
            queueIds.isEmpty() ||
            queueIds.size != player.mediaItemCount ||
            currentSongId == null ||
            currentIndex !in queueIds.indices
        ) {
            return null
        }

        return PlaybackSessionSnapshot(
            songId = currentSongId,
            currentIndex = currentIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            // Não usar distinct(): uma mesma faixa pode aparecer mais de uma
            // vez na fila e precisa voltar exatamente na mesma ordem.
            queueIds = queueIds
        )
    }

    /** Persiste um retrato já capturado, idealmente fora da thread principal. */
    fun save(context: Context, snapshot: PlaybackSessionSnapshot?) {
        if (snapshot == null) {
            clear(context)
            return
        }

        context.getSharedPreferences(PLAYER_SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SONG_ID, snapshot.songId)
            .putInt(KEY_LAST_INDEX, snapshot.currentIndex)
            .putLong(KEY_LAST_POSITION_MS, snapshot.positionMs)
            .putBoolean(KEY_LAST_SHUFFLE, snapshot.shuffleEnabled)
            .putInt(KEY_LAST_REPEAT, snapshot.repeatMode)
            .putString(KEY_LAST_QUEUE_IDS, snapshot.queueIds.joinToString(","))
            .apply()
    }

    /** Mantido para chamadas simples que já acontecem fora de trechos críticos. */
    fun save(context: Context, player: Player) {
        save(context, snapshot(player))
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PLAYER_SESSION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    fun load(context: Context): PlaybackSessionSnapshot? {
        val prefs = context.getSharedPreferences(PLAYER_SESSION_PREFS, Context.MODE_PRIVATE)
        val songId = prefs.getLong(KEY_LAST_SONG_ID, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }
            ?: return null

        val queueIds = prefs.getString(KEY_LAST_QUEUE_IDS, "")
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .mapNotNull { it.toLongOrNull() }

        if (queueIds.isEmpty()) {
            clear(context)
            return null
        }

        val storedIndex = prefs.getInt(KEY_LAST_INDEX, queueIds.indexOf(songId))
        val currentIndex = storedIndex.takeIf { it in queueIds.indices }
            ?: queueIds.indexOf(songId).takeIf { it >= 0 }
            ?: 0

        return PlaybackSessionSnapshot(
            songId = songId,
            currentIndex = currentIndex,
            positionMs = prefs.getLong(KEY_LAST_POSITION_MS, 0L).coerceAtLeast(0L),
            shuffleEnabled = prefs.getBoolean(KEY_LAST_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_LAST_REPEAT, Player.REPEAT_MODE_OFF),
            queueIds = queueIds
        )
    }
}
