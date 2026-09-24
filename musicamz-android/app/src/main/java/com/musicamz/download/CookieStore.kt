package com.musicamz.download

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cookies opcionais: nunca são enviados à infraestrutura AMZ nem incluídos em backups. */
class CookieStore(context: Context) {
    private val appContext = context.applicationContext
    private val encryptedFile = AtomicFile(File(appContext.noBackupFilesDir, "youtube-session.aes"))

    fun hasCookies(): Boolean = synchronized(lock) {
        encryptedFile.baseFile.exists() || File(encryptedFile.baseFile.path + ".bak").exists()
    }

    /** Operação de I/O; chamar fora da thread principal. A importação anterior é preservada em caso de erro. */
    fun importFrom(uri: Uri) = synchronized(lock) {
        require(uri.scheme == "content") { "Selecione um arquivo cookies.txt pelo seletor de arquivos." }
        val inputBytes = appContext.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= NetscapeCookieParser.MAX_BYTES) {
                        "O arquivo de cookies deve ter no máximo 1 MB."
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        } ?: throw IllegalArgumentException("Não foi possível abrir o arquivo de cookies.")
        save(inputBytes)
    }

    /** Recebe somente a sessão da janela de verificação, após confirmação do usuário. */
    fun importWebSession(headers: Map<String, String?>) = synchronized(lock) {
        save(YoutubeWebSession.toNetscape(headers).toByteArray(Charsets.UTF_8))
    }

    private fun save(inputBytes: ByteArray) {
        val plaintext = try {
            NetscapeCookieParser.normalize(inputBytes).toByteArray(Charsets.UTF_8)
        } finally {
            inputBytes.fill(0)
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true))
            cipher.updateAAD(associatedData)
            check(cipher.iv.size == IV_BYTES) { "Não foi possível criar uma sessão criptografada compatível." }
            val encrypted = cipher.doFinal(plaintext)
            val stream = encryptedFile.startWrite()
            try {
                stream.write(FORMAT_VERSION)
                stream.write(cipher.iv)
                stream.write(encrypted)
                encryptedFile.finishWrite(stream)
            } catch (error: Exception) {
                encryptedFile.failWrite(stream)
                throw error
            }
        } finally {
            plaintext.fill(0)
        }
    }

    fun delete() = synchronized(lock) {
        encryptedFile.delete()
        keyStore().deleteEntry(KEY_ALIAS)
    }

    /** Cria cookies.txt somente na pasta privada de uma tarefa. O caller deve apagar a pasta no finally. */
    fun writeSessionFile(directory: File): File? = synchronized(lock) {
        if (!hasCookies()) return@synchronized null
        val targetDirectory = directory.canonicalFile
        val privateRoots = listOf(appContext.cacheDir, appContext.noBackupFilesDir)
        require(privateRoots.any { root ->
            targetDirectory.path.startsWith(root.canonicalPath + File.separator)
        }) { "A sessão deve ficar em uma pasta privada do aplicativo." }
        require(targetDirectory.isDirectory || targetDirectory.mkdirs()) { "Não foi possível preparar a sessão local." }
        val stored = encryptedFile.openRead().use { input ->
            val length = encryptedFile.baseFile.length()
            require(length in MIN_STORED_BYTES..MAX_STORED_BYTES) { "A sessão salva é inválida. Importe os cookies novamente." }
            input.readBytes()
        }
        require(stored[0].toInt() == FORMAT_VERSION) { "A sessão salva é incompatível. Importe os cookies novamente." }
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false), GCMParameterSpec(128, stored, 1, IV_BYTES))
            cipher.updateAAD(associatedData)
            cipher.doFinal(stored, 1 + IV_BYTES, stored.size - 1 - IV_BYTES)
        } catch (_: Exception) {
            throw IllegalStateException("Não foi possível desbloquear a sessão salva. Importe os cookies novamente.")
        }
        val output = File(targetDirectory, "cookies.txt")
        var created = false
        try {
            require(output.canonicalFile.parentFile == targetDirectory) { "O caminho da sessão é inválido." }
            // Criar um arquivo novo impede reutilizar links ou sessões parciais de outra tarefa.
            require(output.createNewFile()) { "A tarefa já possui um arquivo de sessão." }
            created = true
            require(output.setReadable(false, false) && output.setWritable(false, false) &&
                output.setReadable(true, true) && output.setWritable(true, true)) {
                "Não foi possível proteger o arquivo de sessão."
            }
            output.outputStream().use { it.write(plaintext) }
            output
        } catch (error: Exception) {
            if (created) output.delete()
            throw error
        } finally {
            plaintext.fill(0)
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(create: Boolean): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "A chave da sessão não está mais disponível." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    companion object {
        private val lock = Any()
        private const val KEY_ALIAS = "musicamz.youtube.session.v1"
        private const val FORMAT_VERSION = 1
        private const val IV_BYTES = 12
        private const val MIN_STORED_BYTES = 1L + IV_BYTES + 16
        private const val MAX_STORED_BYTES = NetscapeCookieParser.MAX_BYTES.toLong() + 128
        private val associatedData = "com.musicamz.youtube.cookies.v1".toByteArray(Charsets.UTF_8)
    }
}

/** Parser independente do Android para filtrar a sessão e testar entradas malformadas. */
internal object NetscapeCookieParser {
    const val MAX_BYTES = 1024 * 1024
    private val allowedDomains = setOf("youtube.com", ".youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
    private val cookieName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun normalize(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES) { "O arquivo de cookies deve ter no máximo 1 MB." }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("O arquivo de cookies deve ser um texto UTF-8 válido.")
        }
        val accepted = mutableListOf<String>()
        text.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val httpOnly = rawLine.startsWith("#HttpOnly_")
            if (rawLine.isBlank() || (rawLine.startsWith('#') && !httpOnly)) return@forEach
            val line = if (httpOnly) rawLine.removePrefix("#HttpOnly_") else rawLine
            val fields = line.split('\t')
            require(fields.size == 7 && fields.none { value -> value.any { it < ' ' || it == '\u007f' } }) {
                "Formato inválido. Use um arquivo cookies.txt no formato Netscape."
            }
            val domain = fields[0].lowercase(Locale.ROOT)
            require(fields[1] in setOf("TRUE", "FALSE") && fields[3] in setOf("TRUE", "FALSE")) {
                "O arquivo possui indicadores de cookie inválidos."
            }
            require(fields[2].startsWith('/') && fields[4].matches(Regex("[0-9]+")) &&
                fields[4].toLongOrNull() != null && cookieName.matches(fields[5])) {
                "O arquivo possui um cookie inválido."
            }
            if (domain !in allowedDomains) return@forEach
            val normalized = fields.toMutableList().apply { this[0] = domain }.joinToString("\t")
            accepted += (if (httpOnly) "#HttpOnly_" else "") + normalized
        }
        require(accepted.isNotEmpty()) { "O arquivo não contém cookies do YouTube." }
        val result = "# Netscape HTTP Cookie File\n" + accepted.joinToString("\n") + "\n"
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "O arquivo de cookies deve ter no máximo 1 MB." }
        return result
    }
}
