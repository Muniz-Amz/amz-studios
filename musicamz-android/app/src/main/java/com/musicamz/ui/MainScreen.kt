@file:OptIn(ExperimentalMaterial3Api::class)

package com.musicamz.ui

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.musicamz.R
import com.musicamz.data.PlaylistEntity
import com.musicamz.data.SongEntity
import com.musicamz.data.MusicRepository
import com.musicamz.player.MusicService
import com.musicamz.player.PlaybackSessionStore
import com.musicamz.ui.util.formatDuration
import com.musicamz.ui.util.songIdOrNull
import com.musicamz.ui.util.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

private enum class BottomTab(
    val label: String,
    val icon: @Composable () -> Unit
) {
    Home("Início", { Icon(Icons.Default.Home, contentDescription = null) }),
    Search("Buscar", { Icon(Icons.Default.Search, contentDescription = null) }),
    Library("Biblioteca", { Icon(Icons.Default.LibraryMusic, contentDescription = null) }),
    Import("Importar", { Icon(Icons.Default.Download, contentDescription = null) })
}

private enum class SongSortMode(val label: String) {
    Title("Nome"),
    Artist("Artista"),
    Album("Álbum"),
    Recent("Recentes"),
    MostPlayed("Mais tocadas")
}

private enum class HomeFilter(val label: String) {
    All("Tudo"),
    Songs("Músicas"),
    Playlists("Playlists")
}

private val StudioBlack = Color(0xFF070914)
private val StudioSurface = Color(0xFF101426)
private val StudioElevated = Color(0xFF181D31)
private val StudioInput = Color(0xFF0C1020)
private val StudioAccent = Color(0xFFA78BFA)
private val StudioAccentSoft = Color(0xFF2A214B)
private val StudioPulse = Color(0xFF4DE0C1)
private val StudioDanger = Color(0xFFFF879D)
private val StudioLine = Color(0xFFB9C0D5).copy(alpha = 0.13f)
private val StudioLineStrong = StudioAccent.copy(alpha = 0.62f)
private val StudioMuted = Color(0xFFB2B7C8)
private val StudioCardShape = RoundedCornerShape(20.dp)
private val StudioControlShape = RoundedCornerShape(14.dp)
private val StudioPillShape = RoundedCornerShape(999.dp)

private fun studioPageBrush(top: Color = StudioBlack) = Brush.verticalGradient(
    listOf(top, StudioSurface, StudioBlack),
    startY = 0f
)

private fun studioCardBorder(alpha: Float = 0.10f) = BorderStroke(1.dp, Color.White.copy(alpha = alpha))

@Composable
private fun AlbumArtwork(
    artworkUri: String?,
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    overlay: Boolean = false
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(listOf(StudioAccentSoft, StudioElevated, StudioBlack))),
        contentAlignment = Alignment.Center
    ) {
        if (!artworkUri.isNullOrBlank()) {
            AsyncImage(
                model = artworkUri,
                contentDescription = "Capa de $title",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (overlay) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, StudioBlack.copy(alpha = 0.76f))))
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(StudioAccentSoft, StudioSurface, StudioBlack)))
            )
            Text(
                text = title.trim().firstOrNull()?.uppercase() ?: "AMZ",
                color = Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "AMZ",
                color = StudioPulse.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            )
        }
    }
}

private data class QueueUiItem(
    val index: Int,
    val songId: Long?,
    val title: String,
    val artist: String,
    val isCurrent: Boolean
)

private data class PlayerUiState(
    val hasController: Boolean = false,
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val artworkUri: Uri? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val mediaItemIndex: Int = 0,
    val mediaItemCount: Int = 0,
    val songId: Long? = null,
    val queueItems: List<QueueUiItem> = emptyList()
)

@Composable
fun MainScreen(
    mediaController: MediaController?,
    repository: MusicRepository,
    hasAudioPermission: Boolean,
    onRequestPermissions: () -> Unit,
    sharedAudioUris: List<Uri> = emptyList(),
    sharedLink: String? = null,
    onSharedLinkConsumed: () -> Unit = {},
    onSharedConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val songs by repository.allSongs.collectAsState(initial = emptyList())
    val playlists by repository.allPlaylists.collectAsState(initial = emptyList())
    val playerState = rememberPlayerUiState(mediaController)
    var sleepTimerEndMs by rememberSaveable {
        mutableStateOf(getSavedSleepTimerEndMs(context))
    }
    var callPlaybackEnabled by rememberSaveable { mutableStateOf(isCallPlaybackModeEnabled(context)) }
    var audioEnhancementEnabled by rememberSaveable { mutableStateOf(isAudioEnhancementEnabled(context)) }
    var audioEnhancementPreset by rememberSaveable { mutableStateOf(getAudioEnhancementPreset(context)) }
    var audioBassLevel by rememberSaveable { mutableStateOf(getAudioControlLevel(context, MusicService.KEY_AUDIO_BASS_LEVEL, MusicService.AUDIO_DEFAULT_LEVEL)) }
    var audioVoiceLevel by rememberSaveable { mutableStateOf(getAudioControlLevel(context, MusicService.KEY_AUDIO_VOICE_LEVEL, MusicService.AUDIO_DEFAULT_LEVEL)) }
    var audioTrebleLevel by rememberSaveable { mutableStateOf(getAudioControlLevel(context, MusicService.KEY_AUDIO_TREBLE_LEVEL, MusicService.AUDIO_DEFAULT_LEVEL)) }
    var audioLoudnessLevel by rememberSaveable { mutableStateOf(getAudioControlLevel(context, MusicService.KEY_AUDIO_LOUDNESS_LEVEL, 0)) }
    var audioSettingsApplyVersion by rememberSaveable { mutableStateOf(0) }
    var restoredLastSession by rememberSaveable { mutableStateOf(false) }
    var lastMarkedSongId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingDeleteSong by remember { mutableStateOf<SongEntity?>(null) }

    val deleteAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) {
        pendingDeleteSong = null
        scope.launch {
            if (hasAudioPermission) repository.scanMusic()
        }
    }

    fun requestSongDelete(song: SongEntity) {
        if (song.id < 0) {
            scope.launch { repository.deleteImportedSong(song) }
            return
        }

        val songUri = Uri.parse(song.uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                pendingDeleteSong = song
                val deleteRequest = MediaStore.createDeleteRequest(context.contentResolver, listOf(songUri))
                deleteAudioLauncher.launch(IntentSenderRequest.Builder(deleteRequest.intentSender).build())
            }.onFailure {
                pendingDeleteSong = null
                Toast.makeText(context, "Não foi possível pedir exclusão dessa música", Toast.LENGTH_SHORT).show()
            }
        } else {
            scope.launch {
                runCatching {
                    context.contentResolver.delete(songUri, null, null)
                    repository.scanMusic()
                }.onFailure {
                    Toast.makeText(context, "Não foi possível excluir essa música", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) repository.scanMusic()
    }

    // Os sliders mudam o estado visual imediatamente, mas os efeitos nativos
    // só são recriados quando o usuário solta o controle. Isso evita churn de
    // Equalizer/BassBoost em aparelhos mais simples durante um arrasto lento.
    LaunchedEffect(audioSettingsApplyVersion) {
        setAudioEnhancement(
            context = context,
            enabled = audioEnhancementEnabled,
            preset = audioEnhancementPreset,
            bassLevel = audioBassLevel,
            voiceLevel = audioVoiceLevel,
            trebleLevel = audioTrebleLevel,
            loudnessLevel = audioLoudnessLevel
        )
    }

    LaunchedEffect(mediaController, songs, hasAudioPermission) {
        val controller = mediaController ?: return@LaunchedEffect
        if (!restoredLastSession && songs.isNotEmpty() && controller.mediaItemCount == 0) {
            restoredLastSession = true
            restoreLastPlaybackSession(
                context = context,
                controller = controller,
                repository = repository,
                songs = songs
            )
        }
    }

    LaunchedEffect(playerState.songId, playerState.isPlaying) {
        val songId = playerState.songId ?: return@LaunchedEffect
        if (playerState.isPlaying && lastMarkedSongId != songId) {
            lastMarkedSongId = songId
            repository.markPlayed(songId)
        }
    }

    LaunchedEffect(sleepTimerEndMs, mediaController) {
        val endMs = sleepTimerEndMs ?: return@LaunchedEffect
        while (System.currentTimeMillis() < endMs) {
            delay(minOf(1_000L, (endMs - System.currentTimeMillis()).coerceAtLeast(1L)))
        }
        mediaController?.pause()
        sleepTimerEndMs = null
        setSleepTimer(context, null)
    }

    var tab by rememberSaveable { mutableStateOf(BottomTab.Home) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sharedAudioUris, sharedLink) {
        if (sharedAudioUris.isNotEmpty() || sharedLink != null) {
            tab = BottomTab.Import
            showNowPlaying = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!showNowPlaying) Column(modifier = Modifier.background(StudioBlack)) {
                if (playerState.hasController && playerState.songId != null) {
                    MiniPlayerBar(
                        state = playerState,
                        onClick = { showNowPlaying = true },
                        onPlayPause = {
                            val controller = mediaController ?: return@MiniPlayerBar
                            if (controller.isPlaying) controller.pause() else controller.play()
                        },
                        onPrev = { mediaController?.seekToPreviousMediaItem() },
                        onNext = { mediaController?.seekToNextMediaItem() }
                    )
                }

                HorizontalDivider(color = StudioLine)
                NavigationBar(containerColor = StudioBlack, tonalElevation = 0.dp) {
                    BottomTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                tab = t
                                showNowPlaying = false
                            },
                            icon = t.icon,
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = StudioBlack,
                                selectedTextColor = StudioAccent,
                                indicatorColor = StudioAccent,
                                unselectedIconColor = StudioMuted,
                                unselectedTextColor = StudioMuted
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                BottomTab.Home -> HomeTab(
                    songs = songs,
                    playlists = playlists,
                    hasAudioPermission = hasAudioPermission,
                    onRequestPermissions = onRequestPermissions,
                    onOpenLibrary = { tab = BottomTab.Library },
                    onPlaySong = { song ->
                        val controller = mediaController ?: return@HomeTab
                        scope.launch {
                            playFromList(
                                controller = controller,
                                repository = repository,
                                list = songs,
                                start = song
                            )
                        }
                    },
                    onPlayShuffleAll = {
                        val controller = mediaController ?: return@HomeTab
                        scope.launch {
                            if (songs.isEmpty()) return@launch
                            controller.shuffleModeEnabled = true
                            playFromList(controller, repository, songs.shuffled(), songs.random())
                        }
                    }
                )

                BottomTab.Search -> SearchTab(
                    songs = songs,
                    playlists = playlists,
                    hasAudioPermission = hasAudioPermission,
                    onRequestPermissions = onRequestPermissions,
                    onPlaySong = { song ->
                        val controller = mediaController ?: return@SearchTab
                        scope.launch {
                            playFromList(controller, repository, songs, song)
                        }
                    },
                    onLikeToggle = { song, liked ->
                        scope.launch { repository.setLiked(song.id, liked) }
                    },
                    onAddToPlaylist = { playlistId, song ->
                        scope.launch { repository.addSongToPlaylist(playlistId, song.id) }
                    },
                    onAddToQueue = { song ->
                        mediaController?.addMediaItem(song.toMediaItem())
                    },
                    onEditImported = { song, title, artist, album ->
                        scope.launch { repository.updateImportedSong(song, title, artist, album) }
                    },
                    onShareImported = { song -> shareSong(context, song) },
                    onDeleteImported = { song ->
                        requestSongDelete(song)
                    }
                )

                BottomTab.Library -> LibraryTab(
                    repository = repository,
                    songs = songs,
                    playlists = playlists,
                    hasAudioPermission = hasAudioPermission,
                    onRequestPermissions = onRequestPermissions,
                    onPlayFromList = { list, song ->
                        val controller = mediaController ?: return@LibraryTab
                        scope.launch { playFromList(controller, repository, list, song) }
                    },
                    onLikeToggle = { song, liked ->
                        scope.launch { repository.setLiked(song.id, liked) }
                    },
                    onCreatePlaylist = { name ->
                        scope.launch { repository.createPlaylist(name) }
                    },
                    onAddToPlaylist = { playlistId, song ->
                        scope.launch { repository.addSongToPlaylist(playlistId, song.id) }
                    },
                    onAddToQueue = { song ->
                        mediaController?.addMediaItem(song.toMediaItem())
                    },
                    onEditImported = { song, title, artist, album ->
                        scope.launch { repository.updateImportedSong(song, title, artist, album) }
                    },
                    onShareImported = { song -> shareSong(context, song) },
                    onDeleteImported = { song ->
                        requestSongDelete(song)
                    }
                )

                BottomTab.Import -> ImportTab(
                    repository = repository,
                    incomingLink = sharedLink,
                    onLinkConsumed = onSharedLinkConsumed,
                    incomingAudioUris = sharedAudioUris,
                    onIncomingConsumed = onSharedConsumed,
                    onDeleteAllImported = {
                        scope.launch { repository.deleteAllImportedSongs() }
                    },
                    onPlayImported = { song ->
                        val controller = mediaController ?: return@ImportTab
                        scope.launch {
                            playFromList(controller, repository, songs + song, song)
                        }
                    }
                )
            }

            if (showNowPlaying) {
                BackHandler { showNowPlaying = false }
                NowPlayingScreen(
                    state = playerState,
                    onClose = { showNowPlaying = false },
                    onPlayPause = {
                        val controller = mediaController ?: return@NowPlayingScreen
                        if (controller.isPlaying) controller.pause() else controller.play()
                    },
                    onPrev = { mediaController?.seekToPreviousMediaItem() },
                    onNext = { mediaController?.seekToNextMediaItem() },
                    onSeek = { mediaController?.seekTo(it) },
                    onToggleShuffle = {
                        val controller = mediaController ?: return@NowPlayingScreen
                        controller.shuffleModeEnabled = !controller.shuffleModeEnabled
                    },
                    onCycleRepeat = {
                        val controller = mediaController ?: return@NowPlayingScreen
                        controller.repeatMode = when (controller.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    },
                    onQueueItemClick = { index ->
                        val controller = mediaController ?: return@NowPlayingScreen
                        controller.seekToDefaultPosition(index)
                        controller.play()
                    },
                    onQueueItemRemove = { index ->
                        val controller = mediaController ?: return@NowPlayingScreen
                        if (controller.mediaItemCount <= 1) {
                            controller.pause()
                            controller.clearMediaItems()
                        } else if (index in 0 until controller.mediaItemCount) {
                            controller.removeMediaItem(index)
                        }
                    },
                    onQueueItemMove = { from, to ->
                        val controller = mediaController ?: return@NowPlayingScreen
                        if (from != to && from in 0 until controller.mediaItemCount && to in 0 until controller.mediaItemCount) {
                            controller.moveMediaItem(from, to)
                            // O listener do serviço persiste a nova ordem
                            // depois que o MediaController confirma a mudança.
                            // Salvar aqui podia gravar a ordem anterior.
                        }
                    },
                    onSaveQueueAsPlaylist = {
                        val ids = playerState.queueItems.mapNotNull { it.songId }.distinct()
                        if (ids.isEmpty()) return@NowPlayingScreen
                        scope.launch {
                            val playlistId = repository.createPlaylist("Fila salva ${System.currentTimeMillis()}")
                            ids.forEach { songId -> repository.addSongToPlaylist(playlistId, songId) }
                            Toast.makeText(context, "Fila salva como playlist", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLikeToggle = { liked ->
                        val songId = playerState.songId ?: return@NowPlayingScreen
                        scope.launch { repository.setLiked(songId, liked) }
                    },
                    isLiked = playerState.songId?.let { id -> songs.firstOrNull { it.id == id }?.liked } == true,
                    sleepTimerEndMs = sleepTimerEndMs,
                    onSetSleepTimer = { minutes ->
                        val endMs = minutes?.let { System.currentTimeMillis() + it * 60_000L }
                        sleepTimerEndMs = endMs
                        setSleepTimer(context, endMs)
                    },
                    onOpenEqualizer = { openSystemEqualizer(context) },
                    callPlaybackEnabled = callPlaybackEnabled,
                    onToggleCallPlaybackMode = {
                        val enabled = !callPlaybackEnabled
                        callPlaybackEnabled = enabled
                        setCallPlaybackMode(context, enabled)
                    },
                    audioEnhancementEnabled = audioEnhancementEnabled,
                    audioEnhancementPresetLabel = audioEnhancementPresetLabel(audioEnhancementPreset),
                    onToggleAudioEnhancement = {
                        audioEnhancementEnabled = !audioEnhancementEnabled
                        audioSettingsApplyVersion += 1
                    },
                    onCycleAudioEnhancementPreset = {
                        val nextPreset = nextAudioEnhancementPreset(audioEnhancementPreset)
                        val levels = audioLevelsForPreset(nextPreset)
                        audioEnhancementPreset = nextPreset
                        audioBassLevel = levels.bass
                        audioVoiceLevel = levels.voice
                        audioTrebleLevel = levels.treble
                        audioLoudnessLevel = levels.loudness
                        audioEnhancementEnabled = true
                        audioSettingsApplyVersion += 1
                    },
                    audioBassLevel = audioBassLevel,
                    audioVoiceLevel = audioVoiceLevel,
                    audioTrebleLevel = audioTrebleLevel,
                    audioLoudnessLevel = audioLoudnessLevel,
                    onAudioLevelsChange = { bass, voice, treble, loudness ->
                        audioBassLevel = bass
                        audioVoiceLevel = voice
                        audioTrebleLevel = treble
                        audioLoudnessLevel = loudness
                        audioEnhancementEnabled = true
                    },
                    onAudioLevelsCommit = {
                        audioSettingsApplyVersion += 1
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(studioPageBrush())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(StudioAccent, StudioPulse))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = StudioBlack, modifier = Modifier.size(38.dp))
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            text = "Permita acesso às músicas",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Sem essa permissão o app não consegue listar sua biblioteca local.",
            style = MaterialTheme.typography.bodyMedium,
            color = StudioMuted
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Pedir permissão")
        }
    }
}

@Composable
private fun HomeTab(
    songs: List<SongEntity>,
    playlists: List<PlaylistEntity>,
    hasAudioPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenLibrary: () -> Unit,
    onPlaySong: (SongEntity) -> Unit,
    onPlayShuffleAll: () -> Unit
) {
    val bg = studioPageBrush()
    var selectedFilter by rememberSaveable { mutableStateOf(HomeFilter.All) }
    val showSongs = selectedFilter != HomeFilter.Playlists
    val showPlaylists = selectedFilter != HomeFilter.Songs

    val recent = remember(songs) {
        val withHistory = songs.filter { it.lastPlayedEpochMs > 0 }.sortedByDescending { it.lastPlayedEpochMs }
        (withHistory.ifEmpty { songs }).take(10)
    }

    val albums = remember(songs) {
        songs
            .filter { it.album.isNotBlank() }
            .groupBy { it.album }
            .entries
            .map { (albumName, albumSongs) ->
                AlbumCardData(
                    title = albumName,
                    subtitle = albumSongs.firstOrNull()?.artist.orEmpty(),
                    artworkUri = albumSongs.firstOrNull()?.albumArtUri,
                    pick = albumSongs.firstOrNull()
                )
            }
            .take(10)
    }

    val likedCount = remember(songs) { songs.count { it.liked } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            HeaderChips(
                songCount = songs.size,
                selectedFilter = selectedFilter,
                onSelectFilter = { selectedFilter = it }
            )
        }

        if (!hasAudioPermission) {
            item {
                PermissionCard(onRequestPermissions = onRequestPermissions)
            }
        }

        if (showSongs) {
            item {
                if (songs.isEmpty()) {
                EmptyStateCard(
                    title = "Sem músicas ainda",
                    body = "Adicione arquivos na aba Importar ou coloque músicas na pasta do celular."
                )
                } else {
                    TopTrackCarousel(
                    tracks = recent,
                    onPlaySong = onPlaySong,
                    onPlayShuffleAll = onPlayShuffleAll
                )
                }
            }

            item {
                SectionTitle(title = "Recentes", action = "Mostrar tudo", onActionClick = onOpenLibrary)
            Spacer(modifier = Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(recent, key = { it.id }) { song ->
                    SquareCard(
                        title = song.title,
                        subtitle = song.artist,
                        artworkUri = song.albumArtUri,
                        badge = if (song.liked) "\u2665\uFE0E" else null,
                        onClick = { onPlaySong(song) }
                    )
                }
            }
            }
        }

        if (showPlaylists) {
            item {
                SectionTitle(
                    title = "Sua biblioteca",
                    action = if (selectedFilter == HomeFilter.Playlists) "Abrir" else null,
                    onActionClick = onOpenLibrary
                )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SquareCard(
                    title = "Músicas curtidas",
                    subtitle = "$likedCount músicas",
                    artworkBrush = Brush.linearGradient(listOf(StudioDanger, StudioAccentSoft, StudioBlack)),
                    badge = "\u2665\uFE0E",
                    onClick = onOpenLibrary
                )
                SquareCard(
                    title = "Playlists",
                    subtitle = "${playlists.size} listas",
                    artworkBrush = Brush.linearGradient(listOf(StudioPulse, StudioAccentSoft, StudioBlack)),
                    badge = "\u266A\uFE0E",
                    onClick = onOpenLibrary
                )
            }
            }

            if (selectedFilter == HomeFilter.Playlists && playlists.isEmpty()) {
                item {
                    EmptyStateCard(
                        title = "Nenhuma playlist ainda",
                        body = "Entre na Biblioteca para criar playlists e organizar suas músicas."
                    )
                }
            }
        }

        if (showSongs && albums.isNotEmpty()) {
            item {
                SectionTitle(title = "Álbuns com as músicas que você adora", action = null)
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.title }) { album ->
                        SquareCard(
                            title = album.title,
                            subtitle = album.subtitle,
                            artworkUri = album.artworkUri,
                            onClick = { album.pick?.let(onPlaySong) }
                        )
                    }
                }
            }
        }

        if (showSongs && recent.isNotEmpty()) {
            item {
                SectionTitle(title = "Sua seleção", action = null)
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent.take(6), key = { it.id }) { song ->
                        WideMixCard(song = song, onClick = { onPlaySong(song) })
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

private data class AlbumCardData(
    val title: String,
    val subtitle: String,
    val artworkUri: String?,
    val pick: SongEntity?
)

@Composable
private fun HeaderChips(
    songCount: Int,
    selectedFilter: HomeFilter,
    onSelectFilter: (HomeFilter) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(StudioControlShape)
                    .background(Brush.linearGradient(listOf(StudioAccent, StudioPulse)))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.amz_logo),
                    contentDescription = "AMZ Music",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("AMZ MUSIC", style = MaterialTheme.typography.titleLarge, letterSpacing = 0.4.sp)
                Text(
                    "Sua biblioteca, do seu jeito",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .clip(StudioPillShape)
                    .background(StudioAccentSoft)
                    .border(1.dp, StudioAccent.copy(alpha = 0.40f), StudioPillShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    "$songCount faixas",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HomeFilter.entries.toList(), key = { it.name }) { filter ->
                Chip(
                    text = filter.label,
                    selected = selectedFilter == filter,
                    onClick = { onSelectFilter(filter) }
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) StudioAccent else StudioElevated
    val fg = if (selected) StudioBlack else Color.White
    val shape = StudioPillShape
    Box(
        modifier = Modifier
            .border(1.dp, if (selected) StudioAccent else StudioLine, shape)
            .clip(shape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun TopTrackCarousel(
    tracks: List<SongEntity>,
    onPlaySong: (SongEntity) -> Unit,
    onPlayShuffleAll: () -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        items(tracks.take(6), key = { it.id }) { song ->
            TopTrackCard(song = song, onClick = { onPlaySong(song) })
        }
        item {
            TopActionCard(
                title = "Aleatório",
                subtitle = "Tocar tudo no aleatório",
                onClick = onPlayShuffleAll
            )
        }
    }
}

@Composable
private fun TopTrackCard(
    song: SongEntity,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(288.dp)
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(184.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = studioCardBorder(alpha = 0.16f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AlbumArtwork(
                    artworkUri = song.albumArtUri,
                    title = song.title,
                    modifier = Modifier
                        .fillMaxSize(),
                    cornerRadius = 24.dp,
                    overlay = true
                )
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.TopStart)
                        .clip(StudioPillShape)
                        .background(StudioBlack.copy(alpha = 0.56f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("TOQUE PARA OUVIR", color = Color.White, style = MaterialTheme.typography.labelLarge, fontSize = 10.sp)
                }
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.TopEnd)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(StudioAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Tocar ${song.title}", tint = StudioBlack, modifier = Modifier.size(26.dp))
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Text(
                        "${song.artist} • ${formatDuration(song.duration)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TopActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        border = studioCardBorder(alpha = 0.16f),
        colors = CardDefaults.cardColors(containerColor = StudioAccentSoft),
        modifier = Modifier
            .width(204.dp)
            .height(184.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(StudioAccentSoft, StudioSurface, StudioBlack)))
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(StudioPulse),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = "Tocar todas as músicas no aleatório", tint = StudioBlack, modifier = Modifier.size(25.dp))
            }
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = StudioMuted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun WideMixCard(song: SongEntity, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = studioCardBorder(),
        shape = StudioCardShape,
        modifier = Modifier.width(252.dp)
    ) {
        Column {
            AlbumArtwork(
                artworkUri = song.albumArtUri,
                title = song.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp),
                cornerRadius = 20.dp,
                overlay = true
            )
            Column(modifier = Modifier.padding(14.dp)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = StudioMuted,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = studioCardBorder(),
        shape = StudioCardShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(StudioAccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = StudioAccent, modifier = Modifier.size(25.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(body, color = StudioMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, action: String?, onActionClick: (() -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = onActionClick ?: {}) {
                Text(action, color = StudioAccent, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SquareCard(
    title: String,
    subtitle: String,
    artworkUri: String? = null,
    artworkBrush: Brush? = null,
    badge: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(136.dp)
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.size(136.dp)) {
            if (artworkBrush != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(StudioCardShape)
                        .background(artworkBrush)
                )
            } else {
                AlbumArtwork(
                    artworkUri = artworkUri,
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 20.dp
                )
            }

            if (badge != null) {
                Box(
                    modifier = Modifier
                        .padding(8.dp)
                        .align(if (artworkBrush != null) Alignment.Center else Alignment.BottomStart)
                        .clip(StudioPillShape)
                        .background(if (artworkBrush != null) StudioBlack.copy(alpha = 0.62f) else StudioBlack.copy(alpha = 0.72f))
                        .padding(horizontal = if (artworkBrush != null) 12.dp else 8.dp, vertical = if (artworkBrush != null) 10.dp else 4.dp)
                ) {
                    Text(
                        badge,
                        color = if (artworkBrush != null) Color.White else Color.White,
                        fontSize = if (artworkBrush != null) 22.sp else 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun SearchTab(
    songs: List<SongEntity>,
    playlists: List<PlaylistEntity>,
    hasAudioPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onPlaySong: (SongEntity) -> Unit,
    onLikeToggle: (SongEntity, Boolean) -> Unit,
    onAddToPlaylist: (Long, SongEntity) -> Unit,
    onAddToQueue: (SongEntity) -> Unit,
    onEditImported: (SongEntity, String, String, String) -> Unit,
    onShareImported: (SongEntity) -> Unit,
    onDeleteImported: (SongEntity) -> Unit
) {
    val bg = studioPageBrush()

    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(songs, query) {
        val q = query.trim()
        if (q.isBlank()) emptyList() else songs.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.artist.contains(q, ignoreCase = true) ||
                it.album.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(bg).padding(16.dp)) {
        Text("Buscar", style = MaterialTheme.typography.headlineSmall)
        Text("Encontre por música, artista ou álbum.", color = StudioMuted, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        if (!hasAudioPermission) {
            PermissionCard(
                onRequestPermissions = onRequestPermissions,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .background(StudioInput, StudioControlShape),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
            placeholder = { Text("Buscar músicas, artistas ou álbuns") },
            singleLine = true,
            shape = StudioControlShape
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (query.isBlank()) {
            EmptyStateCard(
                title = "Busque algo",
                body = "Digite um nome de música, artista ou álbum."
            )
        } else {
            SongList(
                songs = results,
                playlists = playlists,
                onPlay = onPlaySong,
                onLikeToggle = onLikeToggle,
                onAddToPlaylist = onAddToPlaylist,
                onAddToQueue = onAddToQueue,
                onEditImported = onEditImported,
                onShareImported = onShareImported,
                onDeleteImported = onDeleteImported,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LibraryTab(
    repository: MusicRepository,
    songs: List<SongEntity>,
    playlists: List<PlaylistEntity>,
    hasAudioPermission: Boolean,
    onRequestPermissions: () -> Unit,
    onPlayFromList: (List<SongEntity>, SongEntity) -> Unit,
    onLikeToggle: (SongEntity, Boolean) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onAddToPlaylist: (Long, SongEntity) -> Unit,
    onAddToQueue: (SongEntity) -> Unit,
    onEditImported: (SongEntity, String, String, String) -> Unit,
    onShareImported: (SongEntity) -> Unit,
    onDeleteImported: (SongEntity) -> Unit
) {
    val bg = studioPageBrush()

    val scope = rememberCoroutineScope()
    var creatingPlaylist by rememberSaveable { mutableStateOf(false) }
    var playlistName by rememberSaveable { mutableStateOf("") }
    var openLiked by rememberSaveable { mutableStateOf(false) }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedPlaylistName by rememberSaveable { mutableStateOf("") }
    var playlistPendingDeletion by remember { mutableStateOf<PlaylistEntity?>(null) }
    var playlistPendingRename by remember { mutableStateOf<PlaylistEntity?>(null) }
    var renamePlaylistValue by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SongSortMode.Title) }

    val liked = remember(songs) { songs.filter { it.liked } }
    val sortedSongs = remember(songs, sortMode) { songs.sortedByMode(sortMode) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sua biblioteca", style = MaterialTheme.typography.headlineSmall)
                    Text("Músicas, favoritos e playlists", color = StudioMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (hasAudioPermission) {
                    IconButton(onClick = { scope.launch { repository.scanMusic() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar biblioteca")
                    }
                }
                IconButton(onClick = { creatingPlaylist = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Criar playlist", tint = StudioAccent)
                }
            }
        }

        if (!hasAudioPermission) {
            item {
                PermissionCard(
                    onRequestPermissions = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Card(
                onClick = { openLiked = true },
                colors = CardDefaults.cardColors(containerColor = StudioAccentSoft),
                border = studioCardBorder(alpha = 0.20f),
                shape = StudioCardShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(StudioAccentSoft, StudioSurface)))
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(StudioDanger.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = null, tint = StudioDanger, modifier = Modifier.size(27.dp))
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Músicas curtidas", style = MaterialTheme.typography.titleLarge)
                            Text("${liked.size} músicas salvas para você", color = StudioMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(Icons.Default.PlayArrow, contentDescription = "Abrir músicas curtidas", tint = StudioAccent, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }

        if (playlists.isNotEmpty()) {
            item {
                SectionTitle(title = "Playlists", action = null)
            }
            items(playlists, key = { it.id }) { playlist ->
                PlaylistRow(
                    playlist = playlist,
                    onOpen = {
                        selectedPlaylistId = playlist.id
                        selectedPlaylistName = playlist.name
                    },
                    onRename = {
                        playlistPendingRename = playlist
                        renamePlaylistValue = playlist.name
                    },
                    onDelete = { playlistPendingDeletion = playlist }
                )
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Todas as músicas", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.weight(1f))
                SortMenu(sortMode = sortMode, onSortChange = { sortMode = it })
            }
        }
        if (sortedSongs.isEmpty()) {
            item {
                EmptyStateCard(title = "Nada por aqui", body = "Nenhuma música encontrada.")
            }
        } else {
            items(sortedSongs, key = { it.id }) { song ->
                SongRow(
                    song = song,
                    onClick = { onPlayFromList(sortedSongs, song) },
                    playlists = playlists,
                    onLikeToggle = { liked -> onLikeToggle(song, liked) },
                    onAddToPlaylist = { playlistId -> onAddToPlaylist(playlistId, song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onEditImported = { title, artist, album -> onEditImported(song, title, artist, album) },
                    onShareImported = { onShareImported(song) },
                    onDeleteImported = { onDeleteImported(song) }
                )
            }
        }
    }

    if (creatingPlaylist) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { creatingPlaylist = false },
            title = { Text("Criar playlist") },
            text = {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    placeholder = { Text("Nome da playlist") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = playlistName.trim()
                        if (name.isNotEmpty()) onCreatePlaylist(name)
                        playlistName = ""
                        creatingPlaylist = false
                    }
                ) { Text("Criar") }
            },
            dismissButton = {
                TextButton(onClick = { creatingPlaylist = false }) { Text("Cancelar") }
            }
        )
    }

    if (openLiked) {
        BackHandler { openLiked = false }
        LibraryCollectionScreen(
            title = "Músicas curtidas",
            songs = liked,
            playlists = playlists,
            onBack = { openLiked = false },
            onPlayFromList = onPlayFromList,
            onLikeToggle = onLikeToggle,
            onAddToPlaylist = onAddToPlaylist,
            onAddToQueue = onAddToQueue,
            onEditImported = onEditImported,
            onShareImported = onShareImported,
            onDeleteImported = onDeleteImported
        )
    }

    if (selectedPlaylistId != null) {
        val playlistId = selectedPlaylistId!!
        val playlistSongs by repository.getSongsInPlaylist(playlistId).collectAsState(initial = emptyList())
        BackHandler { selectedPlaylistId = null }
        LibraryCollectionScreen(
            title = selectedPlaylistName.ifBlank { "Playlist" },
            songs = playlistSongs,
            playlists = playlists,
            onBack = { selectedPlaylistId = null },
            onPlayFromList = onPlayFromList,
            onLikeToggle = onLikeToggle,
            onAddToPlaylist = onAddToPlaylist,
            onAddToQueue = onAddToQueue,
            onEditImported = onEditImported,
            onShareImported = onShareImported,
            onDeleteImported = onDeleteImported,
            preserveSongOrder = true,
            onMoveSong = { from, to ->
                scope.launch { repository.moveSongInPlaylist(playlistId, from, to) }
            },
            onDeleteCollection = {
                val playlist = playlists.firstOrNull { it.id == playlistId }
                if (playlist != null) playlistPendingDeletion = playlist
            },
            trailingAction = { song ->
                scope.launch { repository.removeSongFromPlaylist(playlistId, song.id) }
            },
            trailingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
            trailingLabel = "Remover"
        )
    }

    if (playlistPendingDeletion != null) {
        val playlist = playlistPendingDeletion!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { playlistPendingDeletion = null },
            title = { Text("Excluir playlist") },
            text = { Text("A playlist \"${playlist.name}\" será apagada. As músicas continuam na biblioteca.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repository.deletePlaylist(playlist) }
                        if (selectedPlaylistId == playlist.id) selectedPlaylistId = null
                        playlistPendingDeletion = null
                    }
                ) { Text("Excluir", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { playlistPendingDeletion = null }) { Text("Cancelar") }
            }
        )
    }

    if (playlistPendingRename != null) {
        val playlist = playlistPendingRename!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { playlistPendingRename = null },
            title = { Text("Renomear playlist") },
            text = {
                OutlinedTextField(
                    value = renamePlaylistValue,
                    onValueChange = { renamePlaylistValue = it },
                    placeholder = { Text("Nome da playlist") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renamePlaylistValue.trim()
                        if (name.isNotEmpty()) {
                            scope.launch { repository.renamePlaylist(playlist.id, name) }
                            if (selectedPlaylistId == playlist.id) selectedPlaylistName = name
                        }
                        playlistPendingRename = null
                    }
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { playlistPendingRename = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = studioCardBorder(),
        shape = StudioControlShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(StudioPulse.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = StudioPulse)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Playlist", color = StudioMuted, fontSize = 12.sp)
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Opções da playlist ${playlist.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Renomear") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Excluir playlist") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun SortMenu(
    sortMode: SongSortMode,
    onSortChange: (SongSortMode) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Default.Sort, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(sortMode.label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SongSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        onSortChange(mode)
                        open = false
                    }
                )
            }
        }
    }
}

private fun List<SongEntity>.sortedByMode(sortMode: SongSortMode): List<SongEntity> {
    return when (sortMode) {
        SongSortMode.Title -> sortedBy { it.title.lowercase() }
        SongSortMode.Artist -> sortedWith(compareBy<SongEntity> { it.artist.lowercase() }.thenBy { it.title.lowercase() })
        SongSortMode.Album -> sortedWith(compareBy<SongEntity> { it.album.lowercase() }.thenBy { it.title.lowercase() })
        SongSortMode.Recent -> sortedByDescending { it.lastPlayedEpochMs }
        SongSortMode.MostPlayed -> sortedByDescending { it.playCount }
    }
}

private fun shareSong(context: Context, song: SongEntity) {
    runCatching {
        val source = Uri.parse(song.uri)
        val shareUri = if (source.scheme == "file" && source.path != null) {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(source.path!!)
            )
        } else {
            source
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Compartilhar música"))
    }.onFailure {
        Toast.makeText(context, "Não foi possível compartilhar este arquivo", Toast.LENGTH_SHORT).show()
    }
}

private fun openSystemEqualizer(context: Context) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
    }
    runCatching { context.startActivity(intent) }
}

private fun isCallPlaybackModeEnabled(context: Context): Boolean {
    return context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
        .getBoolean(MusicService.KEY_CALL_PLAYBACK_ENABLED, false)
}

private fun isAudioEnhancementEnabled(context: Context): Boolean {
    return context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
        .getBoolean(MusicService.KEY_AUDIO_ENHANCEMENT_ENABLED, false)
}

private fun getAudioEnhancementPreset(context: Context): Int {
    return context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
        .getInt(MusicService.KEY_AUDIO_ENHANCEMENT_PRESET, MusicService.AUDIO_PRESET_BALANCED)
        .coerceIn(MusicService.AUDIO_PRESET_BALANCED, MusicService.AUDIO_PRESET_LOUD)
}

private fun getAudioControlLevel(context: Context, key: String, defaultValue: Int): Int {
    return context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
        .getInt(key, defaultValue)
        .coerceIn(0, 100)
}

private fun getSavedSleepTimerEndMs(context: Context): Long? {
    val preferences = context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
    val endMs = preferences.getLong(MusicService.KEY_SLEEP_TIMER_END_MS, 0L)
    return endMs.takeIf { it > System.currentTimeMillis() } ?: run {
        if (endMs > 0L) {
            preferences.edit().remove(MusicService.KEY_SLEEP_TIMER_END_MS).apply()
        }
        null
    }
}

private fun setSleepTimer(context: Context, endMs: Long?) {
    val safeEndMs = endMs?.takeIf { it > System.currentTimeMillis() } ?: 0L
    context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
        .edit()
        .apply {
            if (safeEndMs > 0L) putLong(MusicService.KEY_SLEEP_TIMER_END_MS, safeEndMs)
            else remove(MusicService.KEY_SLEEP_TIMER_END_MS)
        }
        .apply()

    val intent = Intent(context, MusicService::class.java).apply {
        action = MusicService.ACTION_SET_SLEEP_TIMER
        putExtra(MusicService.EXTRA_SLEEP_TIMER_END_MS, safeEndMs)
    }
    runCatching { context.startService(intent) }
}

private data class AudioLevels(
    val bass: Int,
    val voice: Int,
    val treble: Int,
    val loudness: Int
)

private fun audioLevelsForPreset(preset: Int): AudioLevels {
    return when (preset) {
        MusicService.AUDIO_PRESET_BASS -> AudioLevels(bass = 86, voice = 54, treble = 45, loudness = 18)
        MusicService.AUDIO_PRESET_VOICE -> AudioLevels(bass = 38, voice = 82, treble = 62, loudness = 22)
        MusicService.AUDIO_PRESET_LOUD -> AudioLevels(bass = 68, voice = 62, treble = 66, loudness = 72)
        else -> AudioLevels(bass = 56, voice = 54, treble = 55, loudness = 12)
    }
}

private fun nextAudioEnhancementPreset(currentPreset: Int): Int {
    return when (currentPreset) {
        MusicService.AUDIO_PRESET_BALANCED -> MusicService.AUDIO_PRESET_BASS
        MusicService.AUDIO_PRESET_BASS -> MusicService.AUDIO_PRESET_VOICE
        MusicService.AUDIO_PRESET_VOICE -> MusicService.AUDIO_PRESET_LOUD
        else -> MusicService.AUDIO_PRESET_BALANCED
    }
}

private fun audioEnhancementPresetLabel(preset: Int): String {
    return when (preset) {
        MusicService.AUDIO_PRESET_BASS -> "Graves"
        MusicService.AUDIO_PRESET_VOICE -> "Voz clara"
        MusicService.AUDIO_PRESET_LOUD -> "Alto"
        else -> "Equilibrado"
    }
}

private fun setCallPlaybackMode(context: Context, enabled: Boolean) {
    context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(MusicService.KEY_CALL_PLAYBACK_ENABLED, enabled)
        .apply()

    val intent = Intent(context, MusicService::class.java).apply {
        action = MusicService.ACTION_SET_CALL_PLAYBACK_MODE
        putExtra(MusicService.EXTRA_CALL_PLAYBACK_ENABLED, enabled)
    }

    runCatching {
        context.startService(intent)
    }
}

private fun setAudioEnhancement(
    context: Context,
    enabled: Boolean,
    preset: Int,
    bassLevel: Int,
    voiceLevel: Int,
    trebleLevel: Int,
    loudnessLevel: Int
) {
    context.getSharedPreferences(MusicService.SETTINGS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(MusicService.KEY_AUDIO_ENHANCEMENT_ENABLED, enabled)
        .putInt(MusicService.KEY_AUDIO_ENHANCEMENT_PRESET, preset)
        .putInt(MusicService.KEY_AUDIO_BASS_LEVEL, bassLevel)
        .putInt(MusicService.KEY_AUDIO_VOICE_LEVEL, voiceLevel)
        .putInt(MusicService.KEY_AUDIO_TREBLE_LEVEL, trebleLevel)
        .putInt(MusicService.KEY_AUDIO_LOUDNESS_LEVEL, loudnessLevel)
        .apply()

    val intent = Intent(context, MusicService::class.java).apply {
        action = MusicService.ACTION_SET_AUDIO_ENHANCEMENT
        putExtra(MusicService.EXTRA_AUDIO_ENHANCEMENT_ENABLED, enabled)
        putExtra(MusicService.EXTRA_AUDIO_ENHANCEMENT_PRESET, preset)
        putExtra(MusicService.EXTRA_AUDIO_BASS_LEVEL, bassLevel)
        putExtra(MusicService.EXTRA_AUDIO_VOICE_LEVEL, voiceLevel)
        putExtra(MusicService.EXTRA_AUDIO_TREBLE_LEVEL, trebleLevel)
        putExtra(MusicService.EXTRA_AUDIO_LOUDNESS_LEVEL, loudnessLevel)
    }

    runCatching {
        context.startService(intent)
    }
}

@Composable
private fun LibraryCollectionScreen(
    title: String,
    songs: List<SongEntity>,
    playlists: List<PlaylistEntity>,
    onBack: () -> Unit,
    onPlayFromList: (List<SongEntity>, SongEntity) -> Unit,
    onLikeToggle: (SongEntity, Boolean) -> Unit,
    onAddToPlaylist: (Long, SongEntity) -> Unit,
    onAddToQueue: (SongEntity) -> Unit,
    onEditImported: (SongEntity, String, String, String) -> Unit,
    onShareImported: (SongEntity) -> Unit,
    onDeleteImported: (SongEntity) -> Unit,
    preserveSongOrder: Boolean = false,
    onMoveSong: ((Int, Int) -> Unit)? = null,
    onDeleteCollection: (() -> Unit)? = null,
    trailingAction: ((SongEntity) -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    trailingLabel: String? = null
) {
    var sortMode by rememberSaveable { mutableStateOf(SongSortMode.Title) }
    val sortedSongs = remember(songs, sortMode) { songs.sortedByMode(sortMode) }
    val displayedSongs = if (preserveSongOrder) songs else sortedSongs
    val bg = studioPageBrush()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onDeleteCollection != null) {
                IconButton(onClick = onDeleteCollection) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFFF6B6B))
                }
            }
            if (!preserveSongOrder) {
                SortMenu(sortMode = sortMode, onSortChange = { sortMode = it })
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (songs.isEmpty()) {
            EmptyStateCard(title = "Nada por aqui", body = "Adicione músicas e volte aqui depois.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = {
                        val first = displayedSongs.firstOrNull() ?: return@FilledTonalButton
                        onPlayFromList(displayedSongs, first)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Tocar") }

                FilledTonalButton(
                    onClick = {
                        val shuffled = displayedSongs.shuffled()
                        val first = shuffled.firstOrNull() ?: return@FilledTonalButton
                        onPlayFromList(shuffled, first)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Aleatório") }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = StudioLine)
            Spacer(modifier = Modifier.height(6.dp))

            SongList(
                songs = displayedSongs,
                playlists = playlists,
                onPlay = { song -> onPlayFromList(displayedSongs, song) },
                onLikeToggle = onLikeToggle,
                onAddToPlaylist = onAddToPlaylist,
                onAddToQueue = onAddToQueue,
                onEditImported = onEditImported,
                onShareImported = onShareImported,
                onDeleteImported = onDeleteImported,
                trailingAction = trailingAction,
                trailingIcon = trailingIcon,
                trailingLabel = trailingLabel,
                onMoveSong = onMoveSong,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SongList(
    songs: List<SongEntity>,
    playlists: List<PlaylistEntity> = emptyList(),
    onPlay: (SongEntity) -> Unit,
    onLikeToggle: (SongEntity, Boolean) -> Unit,
    onAddToPlaylist: (Long, SongEntity) -> Unit = { _, _ -> },
    onAddToQueue: ((SongEntity) -> Unit)? = null,
    onEditImported: ((SongEntity, String, String, String) -> Unit)? = null,
    onShareImported: ((SongEntity) -> Unit)? = null,
    onDeleteImported: ((SongEntity) -> Unit)? = null,
    trailingAction: ((SongEntity) -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    trailingLabel: String? = null,
    onMoveSong: ((Int, Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (songs.isEmpty()) {
        EmptyStateCard(title = "Nada por aqui", body = "Nenhuma música encontrada.")
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { onPlay(song) },
                    playlists = playlists,
                    onLikeToggle = { liked -> onLikeToggle(song, liked) },
                    onAddToPlaylist = { playlistId -> onAddToPlaylist(playlistId, song) },
                    onAddToQueue = onAddToQueue?.let { action -> { action(song) } },
                    onEditImported = onEditImported?.let { action -> { title, artist, album -> action(song, title, artist, album) } },
                    onShareImported = onShareImported?.let { action -> { action(song) } },
                    onDeleteImported = onDeleteImported?.let { action -> { action(song) } },
                    trailingAction = trailingAction?.let { action -> { action(song) } },
                    trailingIcon = trailingIcon,
                    trailingLabel = trailingLabel,
                    onMoveUp = onMoveSong?.let { action -> { action(index, index - 1) } }?.takeIf { index > 0 },
                    onMoveDown = onMoveSong?.let { action -> { action(index, index + 1) } }?.takeIf { index < songs.lastIndex }
                )
            }
        }
    }
}

@Composable
private fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    playlists: List<PlaylistEntity>,
    onLikeToggle: (Boolean) -> Unit,
    onAddToPlaylist: (Long) -> Unit,
    onAddToQueue: (() -> Unit)? = null,
    onEditImported: ((String, String, String) -> Unit)? = null,
    onShareImported: (() -> Unit)? = null,
    onDeleteImported: (() -> Unit)? = null,
    trailingAction: (() -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    trailingLabel: String? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    var choosePlaylist by remember { mutableStateOf(false) }
    var confirmDeleteImported by remember { mutableStateOf(false) }
    var editImported by remember { mutableStateOf(false) }
    var editTitle by rememberSaveable(song.id) { mutableStateOf(song.title) }
    var editArtist by rememberSaveable(song.id) { mutableStateOf(song.artist) }
    var editAlbum by rememberSaveable(song.id) { mutableStateOf(song.album) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StudioControlShape)
            .background(if (song.liked) StudioAccentSoft.copy(alpha = 0.30f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtwork(
            artworkUri = song.albumArtUri,
            title = song.title,
            modifier = Modifier.size(50.dp),
            cornerRadius = 14.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(
                "${song.artist} • ${song.album}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = StudioMuted,
                fontSize = 12.sp
            )
        }
        if (onMoveUp != null || onMoveDown != null) {
            Column {
                IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Mover música para cima")
                }
                IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Mover música para baixo")
                }
            }
        }
        if (onMoveUp == null && onMoveDown == null) {
            Text(
                formatDuration(song.duration),
                color = StudioMuted,
                fontSize = 12.sp
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Mais opções para ${song.title}")
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (song.liked) "Descurtir" else "Curtir") },
                leadingIcon = {
                    Icon(
                        if (song.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null
                    )
                },
                onClick = {
                    onLikeToggle(!song.liked)
                    menuOpen = false
                }
            )

            if (playlists.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Adicionar à playlist") },
                    onClick = {
                        choosePlaylist = true
                        menuOpen = false
                    }
                )
            }

            if (onAddToQueue != null) {
                DropdownMenuItem(
                    text = { Text("Adicionar à fila") },
                    leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                    onClick = {
                        onAddToQueue()
                        menuOpen = false
                    }
                )
            }

            if (trailingAction != null && trailingLabel != null) {
                DropdownMenuItem(
                    text = { Text(trailingLabel) },
                    leadingIcon = trailingIcon,
                    onClick = {
                        trailingAction()
                        menuOpen = false
                    }
                )
            }

            if (onEditImported != null) {
                DropdownMenuItem(
                    text = { Text("Renomear música") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = {
                        editTitle = song.title
                        editArtist = song.artist
                        editAlbum = song.album
                        editImported = true
                        menuOpen = false
                    }
                )
            }

            if (song.id < 0 && onShareImported != null) {
                DropdownMenuItem(
                    text = { Text("Compartilhar") },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                    onClick = {
                        onShareImported()
                        menuOpen = false
                    }
                )
            }

            if (onDeleteImported != null) {
                DropdownMenuItem(
                    text = { Text(if (song.id < 0) "Remover da biblioteca" else "Excluir do aparelho") },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    onClick = {
                        confirmDeleteImported = true
                        menuOpen = false
                    }
                )
            }
        }
    }

    if (choosePlaylist) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosePlaylist = false },
            title = { Text("Adicionar à playlist") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        FilledTonalButton(
                            onClick = {
                                onAddToPlaylist(p.id)
                                choosePlaylist = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(p.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { choosePlaylist = false }) { Text("Cancelar") }
            }
        )
    }

    if (editImported) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editImported = false },
            title = { Text("Editar música") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Nome") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editArtist,
                        onValueChange = { editArtist = it },
                        label = { Text("Artista") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editAlbum,
                        onValueChange = { editAlbum = it },
                        label = { Text("Álbum") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val title = editTitle.trim().ifBlank { song.title }
                        val artist = editArtist.trim().ifBlank { "Importado" }
                        val album = editAlbum.trim().ifBlank { "Downloads" }
                        onEditImported?.invoke(title, artist, album)
                        editImported = false
                    }
                ) { Text("Salvar") }
            },
            dismissButton = {
                TextButton(onClick = { editImported = false }) { Text("Cancelar") }
            }
        )
    }

    if (confirmDeleteImported) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteImported = false },
            title = { Text(if (song.id < 0) "Remover música" else "Excluir música") },
            text = {
                Text(
                    if (song.id < 0) {
                        "A música \"${song.title}\" será removida da biblioteca do MusicAmz."
                    } else {
                        "O Android vai pedir confirmação para excluir \"${song.title}\" do aparelho."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteImported?.invoke()
                        confirmDeleteImported = false
                    }
                ) { Text(if (song.id < 0) "Remover" else "Excluir", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteImported = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun ImportTab(
    repository: MusicRepository,
    incomingLink: String?,
    onLinkConsumed: () -> Unit,
    incomingAudioUris: List<Uri>,
    onIncomingConsumed: () -> Unit,
    onDeleteAllImported: () -> Unit,
    onPlayImported: (SongEntity) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bg = studioPageBrush()

    var working by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("") }
    var confirmClearImported by remember { mutableStateOf(false) }
    var cacheSizeLabel by rememberSaveable { mutableStateOf("Calculando...") }

    suspend fun refreshCacheSize() {
        cacheSizeLabel = formatStorageBytes(repository.getPlaybackCacheSizeBytes())
    }

    LaunchedEffect(Unit) {
        refreshCacheSize()
    }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { outputUri ->
        if (outputUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            status = "Exportando backup..."
            try {
                val result = repository.exportBackup(outputUri)
                status = "Backup salvo: ${result.songs} músicas, ${result.playlists} playlists. ${result.note.orEmpty()}"
            } catch (e: Exception) {
                status = "Erro no backup: ${e.message ?: "falha ao exportar"}"
            } finally {
                working = false
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { inputUri ->
        if (inputUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            status = "Importando backup..."
            try {
                val result = repository.importBackup(inputUri)
                status = "Backup importado: ${result.songs} músicas, ${result.playlists} playlists. ${result.note.orEmpty()}"
            } catch (e: Exception) {
                status = "Erro no backup: ${e.message ?: "falha ao importar"}"
            } finally {
                working = false
            }
        }
    }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            status = "Importando arquivo..."
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(
                    pickedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                ) }

                val song = copyUriToLibrary(context = context, source = pickedUri)
                repository.addImportedSong(song)
                status = "Importado: ${song.title}"
                onPlayImported(song)
            } catch (e: Exception) {
                status = "Erro: ${e.message ?: "falha ao importar"}"
            } finally {
                working = false
            }
        }
    }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { pickedUri ->
        if (pickedUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            status = "Extraindo áudio do vídeo..."
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(
                    pickedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                ) }

                val song = extractAudioFromVideo(context = context, source = pickedUri)
                repository.addImportedSong(song)
                status = "Áudio extraído: ${song.title}"
                onPlayImported(song)
            } catch (e: Exception) {
                status = "Não foi possível extrair o áudio: ${e.message ?: "formato incompatível"}"
            } finally {
                working = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Adicionar música", style = MaterialTheme.typography.headlineSmall)
        Text("Sua coleção fica organizada e salva no seu aparelho.", color = StudioMuted, style = MaterialTheme.typography.bodyMedium)

        if (incomingAudioUris.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = studioCardBorder(),
                shape = StudioCardShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Compartilhado", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${incomingAudioUris.size} arquivo(s) recebido(s). Toque em importar para adicionar na biblioteca.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                working = true
                                status = "Importando arquivos..."
                                try {
                                    val imported = mutableListOf<SongEntity>()
                                    var failed = 0
                                    incomingAudioUris.forEach { shared ->
                                        try {
                                            val song = copyUriToLibrary(context = context, source = shared)
                                            repository.addImportedSong(song)
                                            imported += song
                                        } catch (_: Exception) {
                                            failed += 1
                                        }
                                    }
                                    if (imported.isEmpty()) {
                                        error("Nenhum arquivo pôde ser importado")
                                    }
                                    status = buildString {
                                        append("Importado: ${imported.size} arquivo(s)")
                                        if (failed > 0) append(". $failed arquivo(s) não puderam ser lidos")
                                    }
                                    imported.lastOrNull()?.let(onPlayImported)
                                    onIncomingConsumed()
                                } catch (e: Exception) {
                                    status = "Erro: ${e.message ?: "falha ao importar"}"
                                } finally {
                                    working = false
                                }
                            }
                        },
                        enabled = !working
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Importar agora")
                    }
                }
            }
        }

        LinkDownloadCard(incomingLink = incomingLink, onLinkConsumed = onLinkConsumed)

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = studioCardBorder(),
            shape = StudioCardShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Arquivo local", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Escolha um arquivo de áudio do seu celular e adicione na biblioteca.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
                FilledTonalButton(
                    onClick = { pickAudioLauncher.launch(arrayOf("audio/*")) },
                    enabled = !working
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Escolher arquivo")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = studioCardBorder(alpha = 0.16f),
            shape = StudioCardShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ferramenta opcional", style = MaterialTheme.typography.titleMedium)
                Text("Extrair áudio de vídeo local", fontWeight = FontWeight.SemiBold)
                Text(
                    "Extrai áudio AAC ou Opus de MP4, WebM e MKV para M4A ou WebM. Não é conversão para MP3.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
                FilledTonalButton(
                    onClick = { pickVideoLauncher.launch(arrayOf("video/*")) },
                    enabled = !working
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Selecionar vídeo local")
                }
            }
        }

        Text("Organização", style = MaterialTheme.typography.titleLarge)

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = studioCardBorder(),
            shape = StudioCardShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Backup local", fontWeight = FontWeight.SemiBold)
                Text(
                    "Exporta favoritos, playlists e nomes editados. Os arquivos de áudio não são copiados; ao importar, playlists novas são criadas para proteger as existentes.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(
                        onClick = { exportBackupLauncher.launch("musicamz-backup.json") },
                        enabled = !working,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Exportar")
                    }
                    FilledTonalButton(
                        onClick = { importBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        enabled = !working,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Importar")
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = studioCardBorder(),
            shape = StudioCardShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Arquivos temporários", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Uso atual: $cacheSizeLabel. Novas reproduções são diretas; use esta opção para limpar arquivos temporários de versões anteriores.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            working = true
                            val removed = repository.clearPlaybackCache()
                            refreshCacheSize()
                            status = "Cache limpo: ${formatStorageBytes(removed)} removidos"
                            working = false
                        }
                    },
                    enabled = !working
                ) {
                    Text("Limpar")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = studioCardBorder(),
            shape = StudioCardShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Limpar importados", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Remove da biblioteca todas as músicas importadas pelo MusicAmz.",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                }
                TextButton(onClick = { confirmClearImported = true }, enabled = !working) {
                    Text("Limpar", color = Color(0xFFFF6B6B))
                }
            }
        }

        if (working) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(status)
            }
        } else if (status.isNotBlank()) {
            Text(status, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f))
        }

        Spacer(modifier = Modifier.height(90.dp))
    }

    if (confirmClearImported) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClearImported = false },
            title = { Text("Limpar importados") },
            text = { Text("Todas as músicas importadas pelo MusicAmz serão removidas da biblioteca.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAllImported()
                        status = "Importados removidos"
                        confirmClearImported = false
                    }
                ) { Text("Limpar", color = Color(0xFFFF6B6B)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearImported = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun PermissionCard(
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = studioCardBorder(),
        shape = StudioCardShape,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(StudioAccentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = StudioAccent)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Permissão de músicas", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Para listar as músicas do seu celular, permita o acesso.",
                    color = StudioMuted,
                    fontSize = 13.sp
                )
            }
            FilledTonalButton(onClick = onRequestPermissions) {
                Text("Permitir")
            }
        }
    }
}

private data class AudioExtractionTarget(
    val extension: String,
    val outputFormat: Int
)

// Metadados malformados podem declarar uma amostra gigantesca. Arquivos de
// áudio comuns usam poucos KB por amostra; 2 MB é uma margem segura sem expor
// o processo a uma alocação que encerre o app por falta de memória.
private const val MAX_VIDEO_AUDIO_SAMPLE_BUFFER_BYTES = 2 * 1024 * 1024

private suspend fun extractAudioFromVideo(
    context: android.content.Context,
    source: Uri,
    fallbackTitle: String? = null
): SongEntity = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(source)

    if (mime?.startsWith("audio/") == true) {
        return@withContext copyPlayableFileToLibrary(
            context = context,
            source = source,
            fallbackTitle = fallbackTitle
        )
    }

    val displayName = getDisplayName(context, source)

    val safeNameBase = sanitizeFileName(
        displayName?.substringBeforeLast('.', missingDelimiterValue = displayName)
            ?: fallbackTitle
            ?: "arquivo_${System.currentTimeMillis()}"
    ).ifBlank { "arquivo_${System.currentTimeMillis()}" }

    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    var muxerStarted = false
    var outputFile: File? = null

    try {
        if (source.scheme == "file") {
            extractor.setDataSource(source.path ?: error("Arquivo inválido"))
        } else {
            extractor.setDataSource(context, source, null)
        }

        var selectedTrack = -1
        var selectedFormat: MediaFormat? = null
        var selectedMime = ""

        for (trackIndex in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(trackIndex)
            val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (trackMime.startsWith("audio/")) {
                selectedTrack = trackIndex
                selectedFormat = format
                selectedMime = trackMime
                break
            }
        }

        if (selectedTrack < 0 || selectedFormat == null) {
            error("O vídeo não possui uma faixa de áudio compatível")
        }

        val target = resolveAudioExtractionTarget(selectedMime)
            ?: error("O codec de áudio deste vídeo não pode ser extraído. Use o MP3 do AMZ Studios.")

        val dir = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir)
            .apply { mkdirs() }
        var file = File(dir, "$safeNameBase.${target.extension}")
        if (file.exists()) {
            file = File(dir, "${file.nameWithoutExtension}_${System.currentTimeMillis()}.${target.extension}")
        }
        outputFile = file

        extractor.selectTrack(selectedTrack)
        muxer = MediaMuxer(file.absolutePath, target.outputFormat)
        val muxerTrack = muxer.addTrack(selectedFormat)
        muxer.start()
        muxerStarted = true

        val maxInputSize = if (selectedFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            selectedFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                .coerceIn(64 * 1024, MAX_VIDEO_AUDIO_SAMPLE_BUFFER_BYTES)
        } else {
            1024 * 1024
        }
        val buffer = ByteBuffer.allocate(maxInputSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var wroteSamples = false

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            // MediaExtractor e MediaCodec usam conjuntos de flags distintos.
            // Para o muxer basta preservar a marca de quadro-chave.
            val muxerFlags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            bufferInfo.set(
                0,
                sampleSize,
                extractor.sampleTime.coerceAtLeast(0L),
                muxerFlags
            )
            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
            wroteSamples = true
            extractor.advance()
        }

        if (!wroteSamples) {
            error("Faixa de áudio vazia")
        }
    } catch (error: Exception) {
        outputFile?.delete()
        throw error
    } finally {
        extractor.release()
        try {
            if (muxerStarted) runCatching { muxer?.stop() }
        } finally {
            muxer?.release()
        }
    }

    val file = outputFile ?: error("Arquivo convertido não foi gerado")
    val uri = Uri.fromFile(file)
    buildSongFromUri(context = context, uri = uri, fallbackTitle = safeNameBase)
}

private fun resolveAudioExtractionTarget(mime: String): AudioExtractionTarget? {
    return when (mime) {
        MediaFormat.MIMETYPE_AUDIO_AAC,
        "audio/aac" -> AudioExtractionTarget(
            extension = "m4a",
            outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        "audio/opus",
        "audio/vorbis" -> AudioExtractionTarget(
            extension = "webm",
            outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        )
        else -> null
    }
}

private fun getDisplayName(context: android.content.Context, source: Uri): String? {
    return runCatching {
        context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
    }.getOrNull() ?: source.lastPathSegment
}

private suspend fun copyUriToLibrary(context: android.content.Context, source: Uri): SongEntity = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(source)
    if (mime != null && !mime.startsWith("audio/")) {
        error("Tipo não suportado: $mime")
    }
    copyPlayableFileToLibrary(context = context, source = source)
}

private suspend fun copyPlayableFileToLibrary(
    context: android.content.Context,
    source: Uri,
    fallbackTitle: String? = null
): SongEntity = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(source)
    val displayName = getDisplayName(context, source)

    val safeNameBase = sanitizeFileName(
        displayName
            ?: fallbackTitle
            ?: "arquivo_${System.currentTimeMillis()}"
    )
        .ifBlank { "audio_${System.currentTimeMillis()}" }
    val extFromMime = guessExtensionFromMime(mime)
    val fileNameBase = if (safeNameBase.contains('.')) safeNameBase else "$safeNameBase.$extFromMime"

    val dir = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir)
        .apply { mkdirs() }
    var file = File(dir, fileNameBase)
    if (file.exists()) {
        file = File(dir, "${file.nameWithoutExtension}_${System.currentTimeMillis()}.${file.extension.ifBlank { extFromMime }}")
    }

    resolver.openInputStream(source)?.use { input ->
        file.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: error("Não foi possível ler o arquivo")

    val uri = Uri.fromFile(file)
    buildSongFromUri(context = context, uri = uri, fallbackTitle = file.nameWithoutExtension)
}

private fun formatStorageBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val mb = safeBytes / (1024f * 1024f)
    return if (mb >= 1f) {
        "%.1f MB".format(mb)
    } else {
        "${safeBytes / 1024L} KB"
    }
}

private fun guessExtensionFromMime(mime: String?): String {
    return when (mime) {
        "audio/mpeg" -> "mp3"
        "audio/mp4",
        "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg",
        "application/ogg" -> "ogg"
        "audio/wav",
        "audio/x-wav" -> "wav"
        "audio/flac" -> "flac"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        else -> "media"
    }
}

private fun sanitizeFileName(raw: String): String {
    val cleaned = raw
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.take(80)
}

private fun buildSongFromUri(
    context: android.content.Context,
    uri: Uri,
    fallbackTitle: String? = null
): SongEntity {
    val retriever = MediaMetadataRetriever()
    // Evita colisões quando vários arquivos são importados no mesmo milissegundo.
    val songId = newImportedSongId()
    var title: String
    var artist = "Importado"
    var album = "Downloads"
    var durationMs = 0L
    var albumArtUri: String? = null

    try {
        retriever.setDataSource(context, uri)
        title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
            ?: "Importado"
        artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "Importado"
        album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() } ?: "Downloads"
        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        albumArtUri = persistEmbeddedArtwork(context, songId, retriever.embeddedPicture)
    } catch (_: Exception) {
        title = fallbackTitle ?: "Importado"
    } finally {
        retriever.release()
    }

    return SongEntity(
        id = songId,
        title = title,
        artist = artist,
        album = album,
        duration = durationMs,
        uri = uri.toString(),
        albumArtUri = albumArtUri
    )
}

private fun persistEmbeddedArtwork(
    context: android.content.Context,
    songId: Long,
    embeddedPicture: ByteArray?
): String? {
    if (embeddedPicture == null || embeddedPicture.isEmpty()) return null

    val artworkDir = File(context.filesDir, "album_art").apply { mkdirs() }
    val artworkFile = File(artworkDir, "song_${kotlin.math.abs(songId)}.${guessArtworkExtension(embeddedPicture)}")

    return runCatching {
        artworkFile.outputStream().use { output -> output.write(embeddedPicture) }
        Uri.fromFile(artworkFile).toString()
    }.getOrNull()
}

private fun newImportedSongId(): Long {
    val randomPositive = UUID.randomUUID().mostSignificantBits ushr 1
    return -randomPositive.coerceAtLeast(1L)
}

private fun guessArtworkExtension(bytes: ByteArray): String {
    return when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size >= 12 && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "webp"
        else -> "jpg"
    }
}

@Composable
private fun MiniPlayerBar(
    state: PlayerUiState,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StudioBlack)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        val progress = if (state.durationMs > 0) {
            (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Card(
            onClick = onClick,
            shape = StudioCardShape,
            colors = CardDefaults.cardColors(containerColor = StudioSurface),
            border = studioCardBorder(alpha = 0.18f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, top = 9.dp, end = 6.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArtwork(
                        artworkUri = state.artworkUri?.toString(),
                        title = state.title,
                        modifier = Modifier.size(46.dp),
                        cornerRadius = 14.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            state.artist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = StudioMuted,
                            fontSize = 12.sp
                        )
                    }

                    IconButton(onClick = onPrev, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Faixa anterior", tint = Color.White)
                    }
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(46.dp)) {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pausar" else "Tocar",
                            tint = StudioAccent,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Próxima faixa", tint = Color.White)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.18f))
                ) {
                    Box(
                        modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(StudioAccent)
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingScreen(
    state: PlayerUiState,
    isLiked: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onQueueItemClick: (Int) -> Unit,
    onQueueItemRemove: (Int) -> Unit,
    onQueueItemMove: (Int, Int) -> Unit,
    onSaveQueueAsPlaylist: () -> Unit,
    onLikeToggle: (Boolean) -> Unit,
    sleepTimerEndMs: Long?,
    onSetSleepTimer: (Int?) -> Unit,
    onOpenEqualizer: () -> Unit,
    callPlaybackEnabled: Boolean,
    onToggleCallPlaybackMode: () -> Unit,
    audioEnhancementEnabled: Boolean,
    audioEnhancementPresetLabel: String,
    onToggleAudioEnhancement: () -> Unit,
    onCycleAudioEnhancementPreset: () -> Unit,
    audioBassLevel: Int,
    audioVoiceLevel: Int,
    audioTrebleLevel: Int,
    audioLoudnessLevel: Int,
    onAudioLevelsChange: (Int, Int, Int, Int) -> Unit,
    onAudioLevelsCommit: () -> Unit
) {
    val bg = studioPageBrush(top = StudioSurface)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Column {
                Text("TOCANDO AGORA", color = StudioAccent, style = MaterialTheme.typography.labelLarge, fontSize = 11.sp)
                Text("AMZ MUSIC", color = StudioMuted, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { onLikeToggle(!isLiked) }) {
                Icon(
                    if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Remover dos favoritos" else "Adicionar aos favoritos",
                    tint = if (isLiked) StudioDanger else MaterialTheme.colorScheme.onBackground
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally)
        ) {
            AlbumArtwork(
                artworkUri = state.artworkUri?.toString(),
                title = state.title,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 28.dp,
                overlay = true
            )
            Box(
                modifier = Modifier
                    .padding(14.dp)
                    .align(Alignment.BottomStart)
                    .clip(StudioPillShape)
                    .background(StudioBlack.copy(alpha = 0.56f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("AMZ MUSIC", color = Color.White, style = MaterialTheme.typography.labelLarge, fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            state.title.ifBlank { "Nenhuma faixa selecionada" },
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            state.artist,
            color = StudioMuted,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(14.dp))

        val duration = state.durationMs.coerceAtLeast(0)
        val pos = state.positionMs.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        var userSeeking by remember { mutableStateOf(false) }
        var seekPreviewMs by remember { mutableStateOf(pos) }

        LaunchedEffect(pos, duration) {
            if (!userSeeking) seekPreviewMs = pos
            seekPreviewMs = seekPreviewMs.coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        }

        val sliderPos = if (userSeeking) seekPreviewMs else pos
        Slider(
            value = if (duration > 0) sliderPos.toFloat() else 0f,
            onValueChange = { value ->
                userSeeking = true
                seekPreviewMs = value.toLong()
            },
            onValueChangeFinished = {
                userSeeking = false
                onSeek(seekPreviewMs)
            },
            valueRange = 0f..(duration.takeIf { it > 0 }?.toFloat() ?: 1f),
            colors = SliderDefaults.colors(
                thumbColor = StudioAccent,
                activeTrackColor = StudioAccent,
                inactiveTrackColor = StudioLineStrong.copy(alpha = 0.32f)
            )
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(formatDuration(sliderPos), fontSize = 12.sp, color = StudioMuted)
            Spacer(modifier = Modifier.weight(1f))
            Text(formatDuration(duration), fontSize = 12.sp, color = StudioMuted)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onToggleShuffle, modifier = Modifier.size(46.dp)) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (state.shuffleEnabled) "Desativar aleatório" else "Ativar aleatório",
                    tint = if (state.shuffleEnabled) StudioAccent else StudioMuted
                )
            }
            IconButton(onClick = onPrev, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Faixa anterior")
            }

            Box(
                modifier = Modifier
                    .size(74.dp)
                    .clip(CircleShape)
                    .background(StudioAccent),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pausar" else "Tocar",
                        tint = StudioBlack,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, contentDescription = "Próxima faixa")
            }
            IconButton(onClick = onCycleRepeat, modifier = Modifier.size(46.dp)) {
                Icon(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = if (state.repeatMode == Player.REPEAT_MODE_OFF) "Ativar repetição" else "Alterar repetição",
                    tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) StudioAccent else StudioMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        AudioControlsCard(
            audioEnhancementEnabled = audioEnhancementEnabled,
            audioEnhancementPresetLabel = audioEnhancementPresetLabel,
            onToggleAudioEnhancement = onToggleAudioEnhancement,
            onCycleAudioEnhancementPreset = onCycleAudioEnhancementPreset,
            audioBassLevel = audioBassLevel,
            audioVoiceLevel = audioVoiceLevel,
            audioTrebleLevel = audioTrebleLevel,
            audioLoudnessLevel = audioLoudnessLevel,
            onAudioLevelsChange = onAudioLevelsChange,
            onAudioLevelsCommit = onAudioLevelsCommit,
            onOpenEqualizer = onOpenEqualizer
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (state.queueItems.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = StudioSurface),
                border = studioCardBorder(),
                shape = StudioCardShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(StudioAccentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, tint = StudioAccent)
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Fila atual", style = MaterialTheme.typography.titleMedium)
                            Text("A ordem fica salva no aplicativo", color = StudioMuted, fontSize = 11.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(StudioPillShape)
                                .background(StudioInput)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "${state.mediaItemIndex + 1}/${state.mediaItemCount}",
                                color = StudioMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    TextButton(onClick = onSaveQueueAsPlaylist) {
                        Text("Salvar como playlist", color = StudioAccent, style = MaterialTheme.typography.labelLarge)
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.queueItems) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(StudioControlShape)
                                    .clickable { onQueueItemClick(item.index) }
                                    .background(if (item.isCurrent) StudioAccentSoft.copy(alpha = 0.78f) else StudioInput)
                                    .padding(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(if (item.isCurrent) StudioAccent else StudioElevated),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${item.index + 1}",
                                            color = if (item.isCurrent) StudioBlack else StudioMuted,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.artist, color = StudioMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    IconButton(onClick = { onQueueItemRemove(item.index) }, modifier = Modifier.size(40.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remover ${item.title} da fila", tint = StudioDanger)
                                    }
                                }
                                Row(
                                    modifier = Modifier.padding(start = 38.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(
                                        onClick = { onQueueItemMove(item.index, (item.index - 1).coerceAtLeast(0)) },
                                        enabled = item.index > 0
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Subir", style = MaterialTheme.typography.labelLarge, fontSize = 11.sp)
                                    }
                                    TextButton(
                                        onClick = { onQueueItemMove(item.index, (item.index + 1).coerceAtMost(state.mediaItemCount - 1)) },
                                        enabled = item.index < state.mediaItemCount - 1
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Text("Descer", style = MaterialTheme.typography.labelLarge, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        Text("Timer de pausa", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { onSetSleepTimer(15) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("15 min")
            }
            FilledTonalButton(onClick = { onSetSleepTimer(30) }, modifier = Modifier.weight(1f)) {
                Text("30 min")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = { onSetSleepTimer(60) }, modifier = Modifier.weight(1f)) {
                Text("60 min")
            }
            FilledTonalButton(onClick = { onSetSleepTimer(null) }, modifier = Modifier.weight(1f)) {
                Text("Cancelar")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        FilledTonalButton(
            onClick = onToggleCallPlaybackMode,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (callPlaybackEnabled) "Modo ligação ativado" else "Modo padrão")
        }

        Text(
            text = if (callPlaybackEnabled) {
                "Tenta tocar junto com ligações do WhatsApp/Discord. Pode misturar com a voz da chamada."
            } else {
                "Modo padrão: respeita chamadas e foco de áudio do Android."
            },
            color = StudioMuted,
            fontSize = 12.sp
        )

        val remainingTimer = sleepTimerEndMs?.let { ((it - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1) }
        if (remainingTimer != null) {
            Text(
                "Timer ativo: pausa em ${remainingTimer} min",
                color = StudioPulse,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AudioControlsCard(
    audioEnhancementEnabled: Boolean,
    audioEnhancementPresetLabel: String,
    onToggleAudioEnhancement: () -> Unit,
    onCycleAudioEnhancementPreset: () -> Unit,
    audioBassLevel: Int,
    audioVoiceLevel: Int,
    audioTrebleLevel: Int,
    audioLoudnessLevel: Int,
    onAudioLevelsChange: (Int, Int, Int, Int) -> Unit,
    onAudioLevelsCommit: () -> Unit,
    onOpenEqualizer: () -> Unit
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = StudioSurface),
        border = studioCardBorder(),
        shape = StudioCardShape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(StudioAccentSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = StudioAccent)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ajustes de áudio", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (audioEnhancementEnabled) "Equalizador ativo • $audioEnhancementPresetLabel" else "Equalizador desligado",
                        color = StudioMuted,
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Ocultar" else "Ajustar", color = StudioAccent, style = MaterialTheme.typography.labelLarge)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = onToggleAudioEnhancement, modifier = Modifier.weight(1f)) {
                    Text(if (audioEnhancementEnabled) "Desligar" else "Ligar")
                }
                FilledTonalButton(onClick = onCycleAudioEnhancementPreset, modifier = Modifier.weight(1f)) {
                    Text(audioEnhancementPresetLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            if (showDetails) {
                AudioControlSlider(
                    label = "Graves",
                    value = audioBassLevel,
                    onValueChange = { value -> onAudioLevelsChange(value, audioVoiceLevel, audioTrebleLevel, audioLoudnessLevel) },
                    onValueChangeFinished = onAudioLevelsCommit
                )
                AudioControlSlider(
                    label = "Voz",
                    value = audioVoiceLevel,
                    onValueChange = { value -> onAudioLevelsChange(audioBassLevel, value, audioTrebleLevel, audioLoudnessLevel) },
                    onValueChangeFinished = onAudioLevelsCommit
                )
                AudioControlSlider(
                    label = "Agudos",
                    value = audioTrebleLevel,
                    onValueChange = { value -> onAudioLevelsChange(audioBassLevel, audioVoiceLevel, value, audioLoudnessLevel) },
                    onValueChangeFinished = onAudioLevelsCommit
                )
                AudioControlSlider(
                    label = "Volume extra",
                    value = audioLoudnessLevel,
                    onValueChange = { value -> onAudioLevelsChange(audioBassLevel, audioVoiceLevel, audioTrebleLevel, value) },
                    onValueChangeFinished = onAudioLevelsCommit
                )
                Text(
                    text = "Ajuste cada controle e o MusicAmz aplica a mudança quando você soltar o dedo.",
                    color = StudioMuted,
                    fontSize = 12.sp
                )
            } else {
                Text(
                    text = if (audioEnhancementEnabled) "Preset $audioEnhancementPresetLabel aplicado. Abra Ajustar para personalizar." else "Ative para usar presets e controles manuais.",
                    color = StudioMuted,
                    fontSize = 12.sp
                )
            }

            FilledTonalButton(onClick = onOpenEqualizer, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Abrir equalizador do Android")
            }
        }
    }
}

@Composable
private fun AudioControlSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            Text("$value%", color = StudioMuted, fontSize = 12.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 100)) },
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..100f
        )
    }
}

@Composable
private fun rememberPlayerUiState(controller: MediaController?): PlayerUiState {
    var state by remember(controller) { mutableStateOf(controller.toUiState()) }

    DisposableEffect(controller) {
        if (controller == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                state = controller.toUiState()
            }
        }
        controller.addListener(listener)
        onDispose { controller.removeListener(listener) }
    }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        while (runCatching { c.isConnected }.getOrDefault(false)) {
            // O controller pode ser desconectado quando o Android recria o
            // serviço. Não deixe uma leitura inválida dentro de LaunchedEffect
            // encerrar a Activity inteira.
            val playbackSnapshot = runCatching {
                val duration = c.duration.takeIf { it > 0L } ?: 0L
                val position = (c.currentPosition.takeIf { it >= 0L } ?: 0L)
                    .coerceIn(0L, duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
                Triple(c.isPlaying, position, duration)
            }.getOrNull() ?: break

            state = state.copy(
                isPlaying = playbackSnapshot.first,
                positionMs = playbackSnapshot.second,
                durationMs = playbackSnapshot.third
            )
            // Com a reprodução pausada, eventos do player já atualizam a UI.
            // Um intervalo maior reduz recomposições enquanto o app está em
            // segundo plano ou parado em uma faixa.
            delay(if (playbackSnapshot.first) 750L else 2_000L)
        }

        state = PlayerUiState()
    }

    return state
}

private fun MediaController?.toUiState(positionMs: Long? = null, durationMs: Long? = null): PlayerUiState {
    val controller = this ?: return PlayerUiState()
    if (!controller.isConnected) return PlayerUiState()

    return runCatching {
        val mediaItem = controller.currentMediaItem
        val metadata = mediaItem?.mediaMetadata
        val durationSafe = (controller.duration.takeIf { it > 0 } ?: 0L)
        val positionSafe = (controller.currentPosition.takeIf { it >= 0 } ?: 0L)
            .coerceIn(0, durationSafe.takeIf { it > 0 } ?: Long.MAX_VALUE)
        val currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val totalItems = controller.mediaItemCount.coerceAtLeast(0)
        val queueItems = (0 until totalItems).map { index ->
            val queueItem = controller.getMediaItemAt(index)
            val itemMetadata = queueItem.mediaMetadata
            QueueUiItem(
                index = index,
                songId = queueItem.songIdOrNull(),
                title = itemMetadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "Sem título",
                artist = itemMetadata.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Desconhecido",
                isCurrent = index == currentIndex
            )
        }

        PlayerUiState(
            hasController = true,
            isPlaying = controller.isPlaying,
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            artworkUri = metadata?.artworkUri,
            positionMs = positionMs ?: positionSafe,
            durationMs = durationMs ?: durationSafe,
            shuffleEnabled = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode,
            mediaItemIndex = currentIndex,
            mediaItemCount = totalItems,
            songId = mediaItem?.songIdOrNull(),
            queueItems = queueItems
        )
    }.getOrDefault(PlayerUiState())
}

private suspend fun restoreLastPlaybackSession(
    context: Context,
    controller: MediaController,
    repository: MusicRepository,
    songs: List<SongEntity>
) {
    val snapshot = PlaybackSessionStore.load(context) ?: return
    val songsById = songs.associateBy { it.id }
    // Mantém a posição original quando alguma faixa da sessão anterior foi
    // apagada do celular antes da próxima abertura do app.
    val availableQueue = snapshot.queueIds.mapIndexedNotNull { originalIndex, songId ->
        songsById[songId]?.let { originalIndex to it }
    }
    // Uma sessão antiga pode apontar só para arquivos que foram apagados. Em
    // vez de substituir essa fila pela biblioteca inteira (que pode ter
    // milhares de itens e travar a abertura), descarte apenas a sessão ruim.
    if (availableQueue.isEmpty()) {
        PlaybackSessionStore.clear(context)
        return
    }

    val restoredQueue = availableQueue.map { it.second }
    val restoredIndex = availableQueue.indexOfFirst { it.first == snapshot.currentIndex }
        .takeIf { it >= 0 }
        ?: availableQueue.indexOfFirst { it.second.id == snapshot.songId }.takeIf { it >= 0 }
        ?: 0
    val song = restoredQueue.getOrNull(restoredIndex)
        ?: songsById[snapshot.songId]
        ?: restoredQueue.firstOrNull()
        ?: return

    controller.shuffleModeEnabled = snapshot.shuffleEnabled
    controller.repeatMode = snapshot.repeatMode
    playFromList(
        controller = controller,
        repository = repository,
        list = restoredQueue,
        start = song,
        startIndex = restoredIndex,
        startPositionMs = snapshot.positionMs,
        playWhenReady = false
    )
}

private suspend fun playFromList(
    controller: MediaController,
    repository: MusicRepository,
    list: List<SongEntity>,
    start: SongEntity,
    startIndex: Int? = null,
    startPositionMs: Long = 0L,
    playWhenReady: Boolean = true
) {
    if (list.isEmpty()) return
    // A fila permite a mesma música em posições diferentes; não remova
    // duplicatas aqui, pois isso muda a sequência salva pelo usuário.
    val playbackList = list.ifEmpty { listOf(start) }
    val preparedStart = runCatching {
        repository.prepareSongForPlayback(start)
    }.getOrElse {
        start
    }
    // Construir a fila e seus MediaItems fora da thread de interface evita
    // congelar ao tocar uma biblioteca/playlist grande.
    val (mediaItems, resolvedStartIndex) = withContext(Dispatchers.Default) {
        val preparedList = playbackList.map { song ->
            if (song.id == preparedStart.id) preparedStart else song
        }
        val resolvedIndex = startIndex
            ?.takeIf { it in playbackList.indices }
            ?: playbackList.indexOfFirst { it.id == start.id }.takeIf { it >= 0 }
            ?: 0
        preparedList.map { it.toMediaItem() } to resolvedIndex
    }

    withContext(Dispatchers.Main) {
        if (!controller.isConnected) return@withContext
        runCatching {
            controller.stop()
            controller.setMediaItems(mediaItems, resolvedStartIndex, startPositionMs)
            controller.playWhenReady = playWhenReady
            controller.prepare()
            if (playWhenReady) {
                controller.play()
            } else {
                controller.pause()
            }
        }
    }
}
