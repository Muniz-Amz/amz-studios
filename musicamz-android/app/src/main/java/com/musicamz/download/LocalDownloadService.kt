package com.musicamz.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import com.musicamz.MainActivity
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

/** Runs only after an explicit user action. Media is streamed to private internal storage. */
class LocalDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentTask: Task? = null
    private var latestStartId = 0

    private class Task(val update: Boolean) {
        val id = UUID.randomUUID().toString()
        val lock = Any()
        val started = SystemClock.elapsedRealtime()
        @Volatile var cancelled = false
        @Volatile var failure: String? = null
        @Volatile var committing = false
        @Volatile var finished = false
        @Volatile var thread: Thread? = null
        @Volatile var directory: File? = null
        var result: DownloadSnapshot? = null
        var watchdog: Job? = null
        var lastProgressAt = 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Importação de áudio", NotificationManager.IMPORTANCE_LOW))
        // Do this before initialization, filesystem access or network work.
        val notification = notification(DownloadSnapshot(DownloadPhase.PREPARING, message = "Preparando importação…"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            val task = currentTask
            if (task == null) stopSelf(startId) else requestCancel(task)
            return START_NOT_STICKY
        }
        if (currentTask != null) return START_NOT_STICKY
        if (intent?.action != ACTION_START && intent?.action != ACTION_UPDATE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val task = Task(update = intent.action == ACTION_UPDATE)
        // Process-wide: a destroyed service can still be unwinding a blocking native call.
        if (!engineLock.tryLock(task)) {
            DownloadState.publish(DownloadSnapshot(DownloadPhase.FAILED,
                message = "A operação anterior ainda está encerrando. Aguarde alguns segundos e tente novamente."))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        currentTask = task
        val rawUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val useCookies = intent.getBooleanExtra(EXTRA_COOKIES, false)
        publish(task, DownloadSnapshot(DownloadPhase.PREPARING, message = "Preparando o motor de áudio…"))
        scope.launch { perform(task, rawUrl, useCookies) }
        return START_NOT_STICKY
    }

    private suspend fun perform(task: Task, rawUrl: String, useCookies: Boolean) {
        task.watchdog = scope.launch { watch(task) }
        try {
            val url = if (task.update) null else DownloadInput.normalizeYoutubeUrl(rawUrl)
            blocking(task) {
                checkActive(task)
                val root = workRoot().apply { check(isDirectory || mkdirs()) }
                // No other job owns this root while engineLock is held.
                root.listFiles()?.forEach { it.deleteRecursively() }
                check(root.usableSpace >= MIN_START_FREE_BYTES) {
                    "Libere pelo menos 768 MB no armazenamento interno para preparar a importação."
                }
                task.directory = File(root, task.id).apply { check(mkdirs()) }
                restoreInterruptedUpdate()
                YoutubeDL.init(applicationContext)
                checkActive(task)
                FFmpeg.init(applicationContext)
                checkActive(task)
            }
            LocalAudioImporter.recoverInterruptedImports(applicationContext)
            checkActive(task)
            if (task.update) {
                update(task)
            } else {
                download(task, requireNotNull(url), useCookies)
            }
        } catch (error: Exception) {
            val cancelled = task.cancelled || error is CancellationException ||
                error is InterruptedException || error is YoutubeDL.CanceledException
            val message = task.failure ?: if (cancelled) "Importação cancelada." else safeError(error, task.update)
            task.result = DownloadSnapshot(
                if (cancelled && task.failure == null) DownloadPhase.CANCELLED else DownloadPhase.FAILED,
                message = message
            )
        } catch (error: LinkageError) {
            Log.e("MusicAmzEngine", "Audio engine linkage failure", error)
            task.result = DownloadSnapshot(DownloadPhase.FAILED,
                message = "Não foi possível preparar o motor de áudio. Atualize o aplicativo e tente novamente.")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                task.watchdog?.cancel()
                // Native calls finish before temporary files/cookies are removed.
                terminateProcesses(task)
                task.directory?.deleteRecursively()
                synchronized(task.lock) { task.finished = true }
                engineLock.unlock(task)
                withContext(Dispatchers.Main) {
                    if (currentTask === task) {
                        task.result?.let { publish(task, it) }
                        currentTask = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf(latestStartId)
                    }
                }
            }
        }
    }

    private suspend fun download(task: Task, url: String, useCookies: Boolean) {
        val directory = requireNotNull(task.directory)
        blocking(task) {
            checkActive(task)
            val cookies = if (useCookies) CookieStore(applicationContext).writeSessionFile(directory) else null
            if (useCookies && cookies == null) error("Importe seus cookies antes de ativar essa opção.")
            val request = commonRequest(YoutubeDLRequest(url))
                .addOption("--no-playlist")
                .addOption("--no-continue")
                .addOption("--no-mtime")
                .addOption("--newline")
                .addOption("--progress-delta", "1")
                .addOption("--concurrent-fragments", "1")
                .addOption("--max-filesize", "500M")
                .addOption("--match-filters", "!is_live & !is_upcoming & duration <= 14400")
                .addOption("--format", "bestaudio/best")
                .addOption("--extract-audio")
                .addOption("--audio-format", "mp3")
                .addOption("--audio-quality", "192K")
                .addOption("--embed-metadata")
                .addOption("--output", File(directory, "audio.%(ext)s").absolutePath)
            cookies?.let { request.addOption("--cookies", it.absolutePath) }
            publish(task, DownloadSnapshot(DownloadPhase.DOWNLOADING, message = "Baixando o áudio no aparelho…"))
            checkActive(task)
            YoutubeDL.execute(request, task.id) { percent, _, line ->
                // Callback comes from the library's stream-reader thread: never throw from it.
                if (!task.cancelled) {
                    val converting = line.contains("[ExtractAudio]") || line.contains("[Metadata]")
                    val now = SystemClock.elapsedRealtime()
                    if (now - task.lastProgressAt >= 500 || converting) {
                        task.lastProgressAt = now
                        val phase = if (converting || DownloadState.state.value.phase == DownloadPhase.CONVERTING)
                            DownloadPhase.CONVERTING else DownloadPhase.DOWNLOADING
                        publish(task, DownloadSnapshot(phase,
                            if (phase == DownloadPhase.DOWNLOADING) percent.takeIf { it.isFinite() && it >= 0 }?.toInt()?.coerceIn(0, 100) else null,
                            if (phase == DownloadPhase.CONVERTING) "Convertendo para MP3…" else "Baixando o áudio no aparelho…"))
                    }
                }
            }
            checkActive(task)
        }
        val output = File(directory, "audio.mp3")
        val audio = blocking(task) { LocalAudioImporter.inspect(output) }
        checkActive(task)
        val song = LocalAudioImporter.publish(applicationContext, output, audio, beforeCommit = {
            synchronized(task.lock) {
                checkActive(task)
                task.committing = true
            }
            publish(task, DownloadSnapshot(DownloadPhase.IMPORTING, message = "Salvando na biblioteca…"))
        })
        task.result = DownloadSnapshot(DownloadPhase.COMPLETED, 100, "Áudio adicionado à biblioteca.", song.title)
    }

    private suspend fun update(task: Task) = blocking(task) {
        checkActive(task)
        publish(task, DownloadSnapshot(DownloadPhase.UPDATING, message = "Atualizando o extrator pelo canal oficial…"))
        val binary = extractorBinary()
        val backup = extractorBackup()
        val backupPart = File(noBackupFilesDir, "extractor.rollback.tmp")
        // yt-dlp updates zip executables in place. Keep a durable rollback before starting.
        binary.inputStream().use { input ->
            FileOutputStream(backupPart).use { output -> input.copyTo(output); output.fd.sync() }
        }
        check(backupPart.renameTo(backup)) { "Não foi possível preservar a versão anterior do extrator." }
        var installed = false
        try {
            checkActive(task)
            val request = commonRequest(YoutubeDLRequest(emptyList()))
                .addOption("--update-to", "stable")
            YoutubeDL.execute(request, task.id, null)
            checkActive(task)
            ZipFile(binary).use { zip ->
                check(zip.getEntry("__main__.py") != null && zip.getEntry("yt_dlp/__init__.py") != null)
            }
            synchronized(task.lock) {
                checkActive(task)
                task.committing = true
                installed = true
            }
            backup.delete()
            task.result = DownloadSnapshot(DownloadPhase.COMPLETED, 100, "Extrator atualizado. Você já pode importar áudio.")
        } finally {
            if (!installed) restoreInterruptedUpdate()
        }
    }

    private suspend fun <T> blocking(task: Task, block: () -> T): T = runInterruptible {
        synchronized(task.lock) { task.thread = Thread.currentThread() }
        try {
            checkActive(task)
            block()
        } finally {
            synchronized(task.lock) {
                task.thread = null
                // The watchdog's interrupt belongs to this task, not the pooled IO worker.
                if (task.cancelled) Thread.interrupted()
            }
        }
    }

    private fun commonRequest(request: YoutubeDLRequest): YoutubeDLRequest = request
        .addOption("--ignore-config")
        .addOption("--no-cache-dir")
        .addOption("--no-colors")
        .addOption("--socket-timeout", "20")
        .addOption("--retries", "2")
        .addOption("--fragment-retries", "2")
        .addOption("--extractor-retries", "1")

    private suspend fun watch(task: Task) {
        while (scope.isActive && !task.finished) {
            if (!task.committing) {
                val elapsed = SystemClock.elapsedRealtime() - task.started
                if (elapsed > (if (task.update) UPDATE_TIMEOUT_MS else DOWNLOAD_TIMEOUT_MS)) {
                    requestCancel(task, "A operação demorou demais. Verifique a conexão e tente novamente.")
                }
                task.directory?.let { directory ->
                    if (directory.usableSpace < RESERVED_FREE_BYTES) {
                        requestCancel(task, "O espaço livre ficou baixo. Nenhum áudio incompleto foi adicionado.")
                    } else if (directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } > MAX_WORK_BYTES) {
                        requestCancel(task, "O arquivo ultrapassa o limite de importação de 500 MB.")
                    }
                }
            }
            if (task.cancelled) {
                terminateProcesses(task)
                synchronized(task.lock) { task.thread?.interrupt() }
            }
            delay(500)
        }
    }

    private fun requestCancel(task: Task, failure: String? = null) {
        synchronized(task.lock) {
            if (task.committing || task.finished || task.cancelled) return
            task.failure = failure
            task.cancelled = true
        }
    }

    private fun checkActive(task: Task) {
        if (task.cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Cancelled")
    }

    private fun publish(task: Task, state: DownloadSnapshot) {
        if (currentTask !== task) return
        DownloadState.publish(state)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
    }

    private fun notification(state: DownloadSnapshot): Notification {
        val open = PendingIntent.getActivity(this, 31, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 32, Intent(this, LocalDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("AMZ Music · Importar áudio")
            .setContentText(state.message)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isRunning)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .apply {
                if (state.isRunning) setProgress(100, state.progress ?: 0, state.progress == null)
                if (state.canCancel) addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
            }.build()
    }

    private fun workRoot() = File(noBackupFilesDir, "audio_jobs")
    private fun extractorBinary() = File(noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
    private fun extractorBackup() = File(noBackupFilesDir, "extractor.rollback")

    private fun restoreInterruptedUpdate() {
        File(noBackupFilesDir, "extractor.rollback.tmp").delete()
        val backup = extractorBackup()
        if (!backup.isFile) return
        val target = extractorBinary()
        target.parentFile?.mkdirs()
        check(backup.renameTo(target)) { "Não foi possível restaurar o motor de áudio." }
    }

    private fun terminateProcesses(task: Task) {
        // The library stops the parent Python process. Also stop an ffmpeg child using this
        // specific private job path; cancellation must not leave conversion running in the background.
        val directory = task.directory?.absolutePath
        if (directory != null) {
            val uid = android.os.Process.myUid().toString()
            File("/proc").listFiles()?.forEach { entry ->
                val pid = entry.name.toIntOrNull() ?: return@forEach
                if (pid == android.os.Process.myPid()) return@forEach
                runCatching {
                    val statusUid = File(entry, "status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)
                    }
                    if (statusUid == uid) {
                        val command = File(entry, "cmdline").readText()
                        if (command.contains(directory)) Os.kill(pid, OsConstants.SIGKILL)
                    }
                }
            }
        }
        runCatching { YoutubeDL.destroyProcessById(task.id) }
    }

    override fun onDestroy() {
        currentTask?.let { requestCancel(it) }
        scope.cancel()
        super.onDestroy()
    }

    private fun safeError(error: Exception, updating: Boolean): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            "cookies antes" in message -> "Importe seus cookies antes de ativar essa opção."
            "768 mb" in message -> "Libere pelo menos 768 MB no armazenamento interno para importar áudio."
            "sign in" in message || "not a bot" in message || "confirm you" in message || "cookies" in message ->
                "O YouTube exigiu uma verificação. Toque em Verificar acesso ao YouTube ou atualize o extrator nas opções."
            "private video" in message || "not available" in message || "unavailable" in message || "copyright" in message ->
                "Este vídeo está indisponível ou exige acesso que esta sessão não possui."
            "filter" in message || "live" in message -> "Use um vídeo concluído com duração de até 4 horas."
            "500 mb" in message || "max-filesize" in message || "larger than" in message -> "O áudio ultrapassa o limite de 500 MB."
            "space" in message || "enospc" in message -> "Não há espaço livre suficiente. Libere armazenamento e tente novamente."
            "timeout" in message || "timed out" in message || "resolve" in message || "network" in message ->
                "Não foi possível concluir a conexão. Verifique a internet e tente novamente."
            "link" in message || "youtube" in message && error is IllegalArgumentException -> "Cole um link válido de um vídeo do YouTube."
            updating -> "Não foi possível atualizar. A versão anterior foi preservada; tente novamente com uma conexão estável."
            else -> "Não foi possível importar este áudio. Atualize o extrator e tente novamente. Nenhum arquivo incompleto foi adicionado."
        }
    }

    companion object {
        private const val CHANNEL = "local_audio_import"
        private const val NOTIFICATION_ID = 140
        private const val ACTION_START = "com.musicamz.download.START"
        private const val ACTION_CANCEL = "com.musicamz.download.CANCEL"
        private const val ACTION_UPDATE = "com.musicamz.download.UPDATE"
        private const val EXTRA_URL = "url"
        private const val EXTRA_COOKIES = "cookies"
        private const val RESERVED_FREE_BYTES = 128L * 1024 * 1024
        private const val MIN_START_FREE_BYTES = 768L * 1024 * 1024
        private const val MAX_WORK_BYTES = 1050L * 1024 * 1024
        private const val DOWNLOAD_TIMEOUT_MS = 30L * 60 * 1000
        private const val UPDATE_TIMEOUT_MS = 5L * 60 * 1000
        private val engineLock = Mutex()

        fun start(context: Context, rawUrl: String, useCookies: Boolean) {
            ContextCompat.startForegroundService(context, Intent(context, LocalDownloadService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_URL, rawUrl).putExtra(EXTRA_COOKIES, useCookies))
        }

        fun cancel(context: Context) {
            if (DownloadState.state.value.canCancel) context.startService(
                Intent(context, LocalDownloadService::class.java).setAction(ACTION_CANCEL))
        }

        fun updateExtractor(context: Context) {
            ContextCompat.startForegroundService(context,
                Intent(context, LocalDownloadService::class.java).setAction(ACTION_UPDATE))
        }
    }
}
