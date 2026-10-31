@file:Suppress("MagicNumber")

package com.friday.ai.localai

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Keeps user-selected GGUF files in Friday's private files directory.
 * Models are never bundled in the APK and are never deleted on app update.
 */
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

    fun installed(): List<InstalledModel> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
        .sortedBy { it.name.lowercase() }
        .map { file ->
            val id = file.name
            InstalledModel(
                id = id,
                fileName = file.name,
                bytes = file.length(),
                sha256 = sha256(file),
                active = prefs.getString(KEY_ACTIVE, null) == id
            )
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

    /** Imports one user-picked file using the Android Storage Access Framework. */
    fun import(uri: Uri, displayName: String): InstalledModel {
        val safeName = displayName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .let { if (it.endsWith(".gguf", true)) it else "$it.gguf" }
        require(safeName.length > 5) { "Nome de arquivo inválido." }
        val target = File(directory, safeName)
        val temp = File(directory, "$safeName.part")
        app.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir o arquivo selecionado." }
            temp.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        try {
            require(temp.length() >= 4L) { "O arquivo está vazio ou incompleto." }
            FileInputStream(temp).use { stream ->
                val magic = ByteArray(4)
                require(stream.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) {
                    "O arquivo selecionado não parece ser um modelo GGUF válido."
                }
            }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Não foi possível salvar o modelo." }
        } finally {
            temp.delete()
        }
        return InstalledModel(target.name, target.name, target.length(), sha256(target), false)
    }

    fun delete(fileName: String): Boolean {
        val file = File(directory, fileName)
        if (!file.isFile || file.extension != "gguf") return false
        if (prefs.getString(KEY_ACTIVE, null) == file.name) {
            prefs.edit().remove(KEY_ACTIVE).apply()
        }
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
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_ACTIVE = "active_model_file"
    }
}
