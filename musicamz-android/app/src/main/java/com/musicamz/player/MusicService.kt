package com.musicamz.player

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.audiofx.BassBoost
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musicamz.R
import com.musicamz.data.MusicDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var consecutivePlaybackErrors = 0
    // Impede que o serviço recém-criado apague a fila salva antes de a UI ter
    // a chance de restaurá-la. Depois que uma fila real existiu, limpar todos
    // os itens continua limpando a sessão normalmente.
    private var hasSeenMediaItems = false
    @Volatile private var isDestroying = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionSaveMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val periodicSessionSaveRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                savePlaybackSession()
            }
            mainHandler.postDelayed(this, SESSION_SAVE_INTERVAL_MS)
        }
    }
    private val delayedSessionSaveRunnable = Runnable { savePlaybackSession() }
    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            finishSleepTimerIfNeeded()
        }
    }

    companion object {
        const val ACTION_SET_CALL_PLAYBACK_MODE = "com.musicamz.action.SET_CALL_PLAYBACK_MODE"
        const val ACTION_SET_AUDIO_ENHANCEMENT = "com.musicamz.action.SET_AUDIO_ENHANCEMENT"
        const val ACTION_SET_SLEEP_TIMER = "com.musicamz.action.SET_SLEEP_TIMER"
        const val EXTRA_CALL_PLAYBACK_ENABLED = "com.musicamz.extra.CALL_PLAYBACK_ENABLED"
        const val EXTRA_AUDIO_ENHANCEMENT_ENABLED = "com.musicamz.extra.AUDIO_ENHANCEMENT_ENABLED"
        const val EXTRA_AUDIO_ENHANCEMENT_PRESET = "com.musicamz.extra.AUDIO_ENHANCEMENT_PRESET"
        const val EXTRA_AUDIO_BASS_LEVEL = "com.musicamz.extra.AUDIO_BASS_LEVEL"
        const val EXTRA_AUDIO_VOICE_LEVEL = "com.musicamz.extra.AUDIO_VOICE_LEVEL"
        const val EXTRA_AUDIO_TREBLE_LEVEL = "com.musicamz.extra.AUDIO_TREBLE_LEVEL"
        const val EXTRA_AUDIO_LOUDNESS_LEVEL = "com.musicamz.extra.AUDIO_LOUDNESS_LEVEL"
        const val EXTRA_SLEEP_TIMER_END_MS = "com.musicamz.extra.SLEEP_TIMER_END_MS"
        const val SETTINGS_NAME = "musicamz_settings"
        const val KEY_CALL_PLAYBACK_ENABLED = "call_playback_enabled"
        const val KEY_AUDIO_ENHANCEMENT_ENABLED = "audio_enhancement_enabled"
        const val KEY_AUDIO_ENHANCEMENT_PRESET = "audio_enhancement_preset"
        const val KEY_AUDIO_BASS_LEVEL = "audio_bass_level"
        const val KEY_AUDIO_VOICE_LEVEL = "audio_voice_level"
        const val KEY_AUDIO_TREBLE_LEVEL = "audio_treble_level"
        const val KEY_AUDIO_LOUDNESS_LEVEL = "audio_loudness_level"
        const val KEY_SLEEP_TIMER_END_MS = "sleep_timer_end_ms"
        const val AUDIO_PRESET_BALANCED = 0
        const val AUDIO_PRESET_BASS = 1
        const val AUDIO_PRESET_VOICE = 2
        const val AUDIO_PRESET_LOUD = 3
        const val AUDIO_DEFAULT_LEVEL = 50
        // Salva posição suficiente para retomada, sem gravar em disco a cada
        // segundo durante reproduções longas.
        private const val SESSION_SAVE_INTERVAL_MS = 10_000L
        private const val SESSION_SAVE_DEBOUNCE_MS = 750L
        private const val SLEEP_TIMER_CHECK_INTERVAL_MS = 30_000L
        private const val COMMAND_TOGGLE_LIKE = "com.musicamz.command.TOGGLE_LIKE"
        private const val COMMAND_TOGGLE_SHUFFLE = "com.musicamz.command.TOGGLE_SHUFFLE"
        private const val COMMAND_CYCLE_REPEAT = "com.musicamz.command.CYCLE_REPEAT"
    }

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_LOCAL)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            consecutivePlaybackErrors = 0
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        skipBrokenCurrentItem()
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        // Eventos podem chegar em sequência quando uma faixa,
                        // fila ou modo muda. Agrupar essas gravações evita
                        // serializar toda a fila várias vezes na UI thread.
                        if (player.mediaItemCount > 0) {
                            hasSeenMediaItems = true
                        }
                        if (!isDestroying && hasSeenMediaItems) schedulePlaybackSessionSave()
                    }
                })
            }

        audioSessionId = generateAudioSessionId()
        if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            player.setAudioSessionId(audioSessionId)
        }

        applyCallPlaybackMode(isCallPlaybackEnabled())
        applyAudioEnhancementMode(
            enabled = isAudioEnhancementEnabled(),
            preset = getAudioEnhancementPreset(),
            bassLevel = getAudioControlLevel(KEY_AUDIO_BASS_LEVEL, AUDIO_DEFAULT_LEVEL),
            voiceLevel = getAudioControlLevel(KEY_AUDIO_VOICE_LEVEL, AUDIO_DEFAULT_LEVEL),
            trebleLevel = getAudioControlLevel(KEY_AUDIO_TREBLE_LEVEL, AUDIO_DEFAULT_LEVEL),
            loudnessLevel = getAudioControlLevel(KEY_AUDIO_LOUDNESS_LEVEL, 0)
        )

        mediaSession = buildMediaSession()
        restoreSleepTimer()
        mainHandler.postDelayed(periodicSessionSaveRunnable, SESSION_SAVE_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_CALL_PLAYBACK_MODE -> {
                val enabled = intent.getBooleanExtra(EXTRA_CALL_PLAYBACK_ENABLED, false)
                getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_CALL_PLAYBACK_ENABLED, enabled)
                    .apply()

                if (::player.isInitialized) {
                    applyCallPlaybackMode(enabled)
                }
            }

            ACTION_SET_AUDIO_ENHANCEMENT -> {
                val enabled = intent.getBooleanExtra(EXTRA_AUDIO_ENHANCEMENT_ENABLED, false)
                val preset = intent.getIntExtra(EXTRA_AUDIO_ENHANCEMENT_PRESET, AUDIO_PRESET_BALANCED)
                    .coerceIn(AUDIO_PRESET_BALANCED, AUDIO_PRESET_LOUD)
                val bassLevel = intent.getIntExtra(EXTRA_AUDIO_BASS_LEVEL, AUDIO_DEFAULT_LEVEL).coerceIn(0, 100)
                val voiceLevel = intent.getIntExtra(EXTRA_AUDIO_VOICE_LEVEL, AUDIO_DEFAULT_LEVEL).coerceIn(0, 100)
                val trebleLevel = intent.getIntExtra(EXTRA_AUDIO_TREBLE_LEVEL, AUDIO_DEFAULT_LEVEL).coerceIn(0, 100)
                val loudnessLevel = intent.getIntExtra(EXTRA_AUDIO_LOUDNESS_LEVEL, 0).coerceIn(0, 100)

                getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUDIO_ENHANCEMENT_ENABLED, enabled)
                    .putInt(KEY_AUDIO_ENHANCEMENT_PRESET, preset)
                    .putInt(KEY_AUDIO_BASS_LEVEL, bassLevel)
                    .putInt(KEY_AUDIO_VOICE_LEVEL, voiceLevel)
                    .putInt(KEY_AUDIO_TREBLE_LEVEL, trebleLevel)
                    .putInt(KEY_AUDIO_LOUDNESS_LEVEL, loudnessLevel)
                    .apply()

                if (::player.isInitialized) {
                    applyAudioEnhancementMode(
                        enabled = enabled,
                        preset = preset,
                        bassLevel = bassLevel,
                        voiceLevel = voiceLevel,
                        trebleLevel = trebleLevel,
                        loudnessLevel = loudnessLevel
                    )
                }
            }

            ACTION_SET_SLEEP_TIMER -> {
                val endMs = intent.getLongExtra(EXTRA_SLEEP_TIMER_END_MS, 0L)
                setSleepTimer(endMs)
            }
        }

        return super.onStartCommand(intent, flags, startId)
    }


    private fun skipBrokenCurrentItem() {
        if (!::player.isInitialized) return

        consecutivePlaybackErrors += 1
        val count = player.mediaItemCount
        if (count <= 1 || consecutivePlaybackErrors >= 4) {
            player.pause()
            return
        }

        val currentIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        runCatching { player.removeMediaItem(currentIndex) }
        val remaining = player.mediaItemCount
        if (remaining <= 0) {
            player.pause()
            return
        }

        val nextIndex = currentIndex.coerceAtMost(remaining - 1)
        player.seekToDefaultPosition(nextIndex)
        player.prepare()
        player.play()
    }

    private fun savePlaybackSession() {
        if (isDestroying || !::player.isInitialized) return

        // O Player só é lido no looper do serviço. A serialização do CSV e a
        // escrita em disco ficam no Dispatcher.IO para não travar os controles.
        val snapshot = runCatching { PlaybackSessionStore.snapshot(player) }
            .getOrElse { return }
        if (snapshot == null && !hasSeenMediaItems) return
        if (snapshot != null) hasSeenMediaItems = true
        serviceScope.launch {
            sessionSaveMutex.withLock {
                PlaybackSessionStore.save(applicationContext, snapshot)
            }
        }
    }

    private fun savePlaybackSessionImmediately() {
        if (!::player.isInitialized) return
        runCatching {
            val snapshot = PlaybackSessionStore.snapshot(player)
            if (snapshot != null) hasSeenMediaItems = true
            if (snapshot != null || hasSeenMediaItems) {
                PlaybackSessionStore.save(applicationContext, snapshot)
            }
        }
    }

    private fun schedulePlaybackSessionSave() {
        if (isDestroying) return
        mainHandler.removeCallbacks(delayedSessionSaveRunnable)
        mainHandler.postDelayed(delayedSessionSaveRunnable, SESSION_SAVE_DEBOUNCE_MS)
    }

    private fun restoreSleepTimer() {
        val endMs = getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
            .getLong(KEY_SLEEP_TIMER_END_MS, 0L)
        if (endMs > 0L) {
            scheduleSleepTimer(endMs)
        }
    }

    private fun setSleepTimer(endMs: Long) {
        getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
            .edit()
            .apply {
                if (endMs > 0L) putLong(KEY_SLEEP_TIMER_END_MS, endMs) else remove(KEY_SLEEP_TIMER_END_MS)
            }
            .apply()
        scheduleSleepTimer(endMs)
    }

    private fun scheduleSleepTimer(endMs: Long) {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        if (endMs <= 0L) return

        val remainingMs = endMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            finishSleepTimerIfNeeded()
        } else {
            mainHandler.postDelayed(sleepTimerRunnable, minOf(remainingMs, SLEEP_TIMER_CHECK_INTERVAL_MS))
        }
    }

    private fun finishSleepTimerIfNeeded() {
        val endMs = getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
            .getLong(KEY_SLEEP_TIMER_END_MS, 0L)
        if (endMs <= 0L) return

        if (System.currentTimeMillis() >= endMs) {
            if (::player.isInitialized) player.pause()
            getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
                .edit()
                .remove(KEY_SLEEP_TIMER_END_MS)
                .apply()
        } else {
            scheduleSleepTimer(endMs)
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaSession(): MediaSession {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = MediaSession.Builder(this, player)
            .setCallback(buildSessionCallback())
            .setCustomLayout(buildNotificationLayout())
        if (pendingIntent != null) {
            builder.setSessionActivity(pendingIntent)
        }
        return builder.build()
    }

    @OptIn(UnstableApi::class)
    private fun buildSessionCallback(): MediaSession.Callback {
        return object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(toggleLikeCommand())
                    .add(toggleShuffleCommand())
                    .add(cycleRepeatCommand())
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .setCustomLayout(buildNotificationLayout())
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    COMMAND_TOGGLE_LIKE -> toggleCurrentSongLike()
                    COMMAND_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
                    COMMAND_CYCLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                    else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }

                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }
    }

    private fun toggleCurrentSongLike() {
        val songId = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        serviceScope.launch {
            val dao = MusicDatabase.getDatabase(applicationContext).musicDao()
            val liked = dao.isSongLiked(songId) ?: false
            dao.setSongLiked(songId, !liked)
        }
    }

    private fun toggleLikeCommand() = SessionCommand(COMMAND_TOGGLE_LIKE, Bundle.EMPTY)
    private fun toggleShuffleCommand() = SessionCommand(COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY)
    private fun cycleRepeatCommand() = SessionCommand(COMMAND_CYCLE_REPEAT, Bundle.EMPTY)

    @OptIn(UnstableApi::class)
    private fun buildNotificationLayout(): List<CommandButton> {
        return listOf(
            CommandButton.Builder()
                .setSessionCommand(toggleLikeCommand())
                .setIconResId(R.drawable.ic_notification_favorite)
                .setDisplayName("Curtir")
                .build(),
            CommandButton.Builder()
                .setSessionCommand(toggleShuffleCommand())
                .setIconResId(R.drawable.ic_notification_shuffle)
                .setDisplayName("Aleatório")
                .build(),
            CommandButton.Builder()
                .setSessionCommand(cycleRepeatCommand())
                .setIconResId(R.drawable.ic_notification_repeat)
                .setDisplayName("Repetir")
                .build()
        )
    }

    private fun isCallPlaybackEnabled(): Boolean {
        return getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_CALL_PLAYBACK_ENABLED, false)
    }

    private fun isAudioEnhancementEnabled(): Boolean {
        return getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_ENHANCEMENT_ENABLED, false)
    }

    private fun getAudioEnhancementPreset(): Int {
        return getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
            .getInt(KEY_AUDIO_ENHANCEMENT_PRESET, AUDIO_PRESET_BALANCED)
            .coerceIn(AUDIO_PRESET_BALANCED, AUDIO_PRESET_LOUD)
    }

    private fun getAudioControlLevel(key: String, defaultValue: Int): Int {
        return getSharedPreferences(SETTINGS_NAME, MODE_PRIVATE)
            .getInt(key, defaultValue)
            .coerceIn(0, 100)
    }

    private fun applyCallPlaybackMode(enabled: Boolean) {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(if (enabled) C.USAGE_VOICE_COMMUNICATION else C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player.setAudioAttributes(audioAttributes, !enabled)
    }

    private fun generateAudioSessionId(): Int {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val generatedSessionId = audioManager.generateAudioSessionId()
        return if (generatedSessionId > 0) generatedSessionId else C.AUDIO_SESSION_ID_UNSET
    }

    private fun applyAudioEnhancementMode(
        enabled: Boolean,
        preset: Int,
        bassLevel: Int,
        voiceLevel: Int,
        trebleLevel: Int,
        loudnessLevel: Int
    ) {
        releaseAudioEffects()

        if (!enabled || audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            return
        }

        equalizer = createAudioEffect(
            factory = { Equalizer(0, audioSessionId) }
        ) { effect ->
            effect.apply {
                val minLevel = bandLevelRange[0].toInt()
                val maxLevel = bandLevelRange[1].toInt()
                val levels = buildEqualizerLevels(
                    bandCount = numberOfBands.toInt(),
                    minLevel = minLevel,
                    maxLevel = maxLevel,
                    bassLevel = bassLevel,
                    voiceLevel = voiceLevel,
                    trebleLevel = trebleLevel
                )

                levels.forEachIndexed { index, level ->
                    setBandLevel(index.toShort(), level.toShort())
                }

                setEnabled(true)
            }
        }

        val strength = ((bassLevel - AUDIO_DEFAULT_LEVEL).coerceAtLeast(0) * 20).coerceIn(0, 1000)

        if (strength > 0) {
            bassBoost = createAudioEffect(
                factory = { BassBoost(0, audioSessionId) }
            ) { effect ->
                effect.apply {
                    setStrength(strength.toShort())
                    setEnabled(true)
                }
            }
        }

        val presetGain = when (preset) {
            AUDIO_PRESET_LOUD -> 300
            AUDIO_PRESET_VOICE -> 160
            else -> 0
        }
        val targetGain = (loudnessLevel * 12 + presetGain).coerceIn(0, 1500)

        if (targetGain > 0) {
            loudnessEnhancer = createAudioEffect(
                factory = { LoudnessEnhancer(audioSessionId) }
            ) { effect ->
                effect.apply {
                    setTargetGain(targetGain)
                    setEnabled(true)
                }
            }
        }
    }

    /**
     * Se um efeito nativo falhar ao ser configurado, ele ainda precisa ser
     * liberado. Caso contrário ajustes repetidos podem esgotar recursos de
     * áudio em alguns aparelhos.
     */
    private fun <T : AudioEffect> createAudioEffect(
        factory: () -> T,
        configure: (T) -> Unit
    ): T? {
        var candidate: T? = null
        return try {
            factory().also { effect ->
                candidate = effect
                configure(effect)
            }
        } catch (_: Exception) {
            releaseAudioEffect(candidate)
            null
        }
    }

    private fun buildEqualizerLevels(
        bandCount: Int,
        minLevel: Int,
        maxLevel: Int,
        bassLevel: Int,
        voiceLevel: Int,
        trebleLevel: Int
    ): List<Int> {
        if (bandCount <= 0) return emptyList()

        return List(bandCount) { index ->
            val position = if (bandCount == 1) 0f else index.toFloat() / (bandCount - 1).toFloat()
            val gain = when {
                position < 0.30f -> normalizeEqLevel(bassLevel)
                position in 0.30f..0.72f -> normalizeEqLevel(voiceLevel)
                else -> normalizeEqLevel(trebleLevel)
            }

            val scaled = if (gain >= 0f) {
                maxLevel * gain
            } else {
                -kotlin.math.abs(minLevel) * kotlin.math.abs(gain)
            }
            scaled.toInt().coerceIn(minLevel, maxLevel)
        }
    }

    private fun normalizeEqLevel(level: Int): Float {
        return ((level.coerceIn(0, 100) - AUDIO_DEFAULT_LEVEL) / 50f).coerceIn(-1f, 1f) * 0.82f
    }

    private fun releaseAudioEffects() {
        releaseAudioEffect(equalizer)
        releaseAudioEffect(bassBoost)
        releaseAudioEffect(loudnessEnhancer)

        equalizer = null
        bassBoost = null
        loudnessEnhancer = null
    }

    private fun releaseAudioEffect(effect: AudioEffect?) {
        runCatching {
            effect?.setEnabled(false)
            effect?.release()
        }
    }

    override fun onDestroy() {
        // player.release() também pode disparar eventos. Marque antes de
        // remover callbacks para que o listener não agende uma gravação nova
        // com a fila já liberada.
        isDestroying = true
        mainHandler.removeCallbacks(periodicSessionSaveRunnable)
        mainHandler.removeCallbacks(delayedSessionSaveRunnable)
        mainHandler.removeCallbacks(sleepTimerRunnable)
        // Na saída não dependa de uma coroutine que pode ser cancelada logo
        // depois: persista uma única vez, antes de liberar o player.
        savePlaybackSessionImmediately()
        serviceScope.cancel()
        releaseAudioEffects()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        if (::player.isInitialized) {
            player.release()
        }
        mainHandler.removeCallbacks(delayedSessionSaveRunnable)
        super.onDestroy()
    }
}
