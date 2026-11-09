@file:Suppress("MagicNumber")

package com.friday.ai.localai

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Stores user-selected GGUF files privately; no model is bundled or downloaded automatically. */
class LocalModelStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("friday_local_ai", Context.MODE_PRIVATE)
    private val directory = File(app.filesDir, "local-models").apply { mkdirs() }

    data class InstalledModel(
        val id: String,
        val fileName: String,
        val bytes: Long,
        val sha256: String,
        val active: Boolean
    )

    /** Hashing large GGUF files stays off the UI thread. */
    suspend fun installed(): List<InstalledModel> = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
            .map { file ->
                currentCoroutineContext().ensureActive()
                InstalledModel(
                    file.name, file.name, file.length(), sha256(file),
                    prefs.getString(KEY_ACTIVE, null) == file.name
                )
            }
    }

    fun activeFile(): File? = prefs.getString(KEY_ACTIVE, null)
        ?.let { File(directory, it) }
        ?.takeIf { it.isFile && it.extension.equals("gguf", ignoreCase = true) }

    fun select(fileName: String): Boolean {
        val file = File(directory, fileName)
        if (!file.isFile || !file.extension.equals("gguf", ignoreCase = true)) return false
        prefs.edit().putString(KEY_ACTIVE, file.name).apply()
        return true
    }

    /** Downloads a pinned catalog artifact, verifies its exact bytes and SHA-256, then renames it. */
    @Suppress("LongMethod") // One atomic download/verify/install transaction with cleanup.
    suspend fun download(model: LocalModel, onProgress: (Int) -> Unit): InstalledModel = withContext(Dispatchers.IO) {
        val safeName = model.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        require(safeName.endsWith(".gguf", true)) { "O catálogo não aponta para um arquivo GGUF." }
        require(model.expectedBytes > 4L && model.expectedSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Metadados de verificação inválidos."
        }
        val target = File(directory, safeName)
        val temp = File(directory, "$safeName.part")
        temp.delete()
        val available = StatFs(directory.absolutePath).availableBytes
        require(available >= model.expectedBytes + DISK_HEADROOM_BYTES) {
            "Espaço insuficiente: libere pelo menos ${model.sizeLabel} e tente novamente."
        }
        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Friday-Android")
        }
        try {
            connection.connect()
            require(connection.responseCode in 200..299) { "Hugging Face respondeu HTTP ${connection.responseCode}." }
            val total = connection.contentLengthLong
            require(total <= 0L || total == model.expectedBytes) {
                "O servidor informou um tamanho diferente do modelo esperado."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= model.expectedBytes) { "O download excedeu o tamanho esperado." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress((copied * 100 / model.expectedBytes).toInt().coerceIn(0, 100))
                    }
                }
            }
            require(temp.length() == model.expectedBytes) { "Download incompleto. Tente novamente." }
            require(digest.digest().toHex() == model.expectedSha256) {
                "A verificação do arquivo falhou. Tente novamente."
            }
            FileInputStream(temp).use { input ->
                val magic = ByteArray(4)
                require(input.read(magic) == 4 && magic.contentEquals(GGUF_MAGIC)) {
                    "O arquivo recebido não é um GGUF válido."
                }
            }
            if (target.exists()) check(target.delete()) { "Não foi possível substituir o modelo anterior." }
            check(temp.renameTo(target)) {
                "Não foi possível instalar o modelo no armazenamento do Friday."
            }
            onProgress(100)
            InstalledModel(target.name, target.name, target.length(), model.expectedSha256, false)
        } finally {
            temp.delete()
            connection.disconnect()
        }
    }

    suspend fun import(uri: Uri, displayName: String): InstalledModel = withContext(Dispatchers.IO) {
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        require(safeName.length > 5) { "Nome de arquivo inválido." }
        val target = File(directory, safeName)
        val temp = File(directory, "$safeName.part")
        try {
            app.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Não foi possível abrir o arquivo selecionado." }
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            require(temp.length() >= 4L) { "O arquivo está vazio ou incompleto." }
            FileInputStream(temp).use { stream ->
                val magic = ByteArray(4)
                require(stream.read(magic) == 4 && magic.contentEquals(GGUF_MAGIC)) {
                    "O arquivo selecionado não parece ser um modelo GGUF válido."
                }
            }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Não foi possível salvar o modelo." }
            return@withContext InstalledModel(target.name, target.name, target.length(), sha256(target), false)
        } finally { temp.delete() }
    }

    fun delete(fileName: String): Boolean {
        val file = File(directory, fileName)
        if (!file.isFile || file.extension != "gguf") return false
        if (prefs.getString(KEY_ACTIVE, null) == file.name) prefs.edit().remove(KEY_ACTIVE).apply()
        return file.delete()
    }

    fun directoryBytes(): Long = directory.listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private companion object {
        const val KEY_ACTIVE = "active_model_file"
        const val DISK_HEADROOM_BYTES = 8L * 1024 * 1024
        val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
