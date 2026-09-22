package com.musicamz

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.musicamz.player.MusicService
import com.musicamz.ui.MainScreen
import com.musicamz.ui.theme.MusicAmzTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private var mediaController: MediaController? by mutableStateOf(null)
    private var hasAudioPermission: Boolean by mutableStateOf(false)
    private var pendingShareAudioUris: List<Uri> by mutableStateOf(emptyList())
    private var pendingShareLink: String? by mutableStateOf(null)
    private var showLaunchScreen: Boolean by mutableStateOf(true)
    private var isConnectingPlayer: Boolean by mutableStateOf(true)

    companion object {
        private const val MAX_SHARED_AUDIO_URIS = 40
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        hasAudioPermission = permissions[key] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasAudioPermission = hasAudioPermission()
        requestPermissions()
        handleIncomingShare(intent)

        connectToPlayer()

        setContent {
            MusicAmzTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        showLaunchScreen || isConnectingPlayer -> {
                            AmzLaunchScreen(onFinished = { showLaunchScreen = false })
                        }
                        mediaController == null -> {
                            PlayerConnectionScreen(onRetry = { connectToPlayer() })
                        }
                        else -> {
                            MainScreen(
                                mediaController = mediaController,
                                repository = (application as MusicApplication).repository,
                                hasAudioPermission = hasAudioPermission,
                                onRequestPermissions = { requestPermissions() },
                                sharedAudioUris = pendingShareAudioUris,
                                sharedLink = pendingShareLink,
                                onSharedLinkConsumed = { pendingShareLink = null },
                                onSharedConsumed = {
                                    pendingShareAudioUris = emptyList()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions)
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun connectToPlayer() {
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }

        isConnectingPlayer = true
        mediaController = null
        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, sessionToken)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    runOnUiThread {
                        if (mediaController === controller) {
                            mediaController = null
                            isConnectingPlayer = false
                        }
                    }
                }
            })
            .buildAsync()
        controllerFuture = future
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            mediaController = controller
            isConnectingPlayer = false
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return

        if (action == Intent.ACTION_SEND) {
            val type = intent.type?.lowercase()
            if (type == "text/plain") {
                pendingShareLink = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(8192)
                pendingShareAudioUris = emptyList()
                return
            }
            val audioUri = getSingleSharedUri(intent)

            val isAudio = audioUri != null && isAudioShare(audioUri, type)
            pendingShareAudioUris = if (isAudio && audioUri != null) listOf(audioUri) else emptyList()
            return
        }

        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val type = intent.type?.lowercase()
            val uris = getMultipleSharedUris(intent)
                .take(MAX_SHARED_AUDIO_URIS)
                .filter { uri -> isAudioShare(uri, type) }
            pendingShareAudioUris = uris
        }
    }

    private fun isAudioShare(uri: Uri, fallbackType: String?): Boolean {
        return runCatching {
            val resolved = contentResolver.getType(uri) ?: fallbackType
            resolved?.startsWith("audio/") == true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun getSingleSharedUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun getMultipleSharedUris(intent: Intent): List<Uri> {
        val list = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        return list?.filterIsInstance<Uri>().orEmpty()
    }

    override fun onDestroy() {
        if (::controllerFuture.isInitialized) {
            MediaController.releaseFuture(controllerFuture)
        }
        mediaController = null
        super.onDestroy()
    }
}

@Composable
private fun PlayerConnectionScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF181D31), Color(0xFF070914))))
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Não foi possível iniciar o player",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Tente novamente. Suas músicas e playlists continuam salvas no aparelho.",
                color = Color(0xFFB2B7C8),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(onClick = onRetry) {
                Text("Tentar novamente")
            }
        }
    }
}

@Composable
private fun AmzLaunchScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(650)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF181D31), Color(0xFF070914))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(176.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFA78BFA), Color(0xFF4DE0C1))))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.amz_logo),
                    contentDescription = "AMZ Music",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "AMZ MUSIC",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "OUÇA • ORGANIZE • SINTA",
                color = Color(0xFFB2B7C8),
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 1.1.sp
            )
        }
    }
}
