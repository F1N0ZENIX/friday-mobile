package com.friday.ai.data.local

import android.content.Context
import com.friday.ai.service.FridayWakeWordService
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Clears only files and records owned by this app. It never contacts third parties. */
class LocalDataClearer(
    private val context: Context,
    private val database: FridayDatabase
) {
    private val app = context.applicationContext
    private val mutex = Mutex()

    suspend fun clear(): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Stop the service before removing its preferences and model beneath it.
            FridayWakeWordService.stop(app)
            database.clearAllTables()
            listOf("friday_ai_setup", "friday_local_ai").forEach { name ->
                check(app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()) {
                    "Could not clear local preferences: $name"
                }
            }
            clearOwnedFiles(app.filesDir, app.cacheDir)
            Result
        }
    }

    data object Result

    companion object {
        /**
         * Deletes every on-disk artifact currently produced by Friday, including interrupted
         * model installs and generated speech. Names are deliberately allow-listed: this never
         * walks external/shared storage or unrelated files in the app's private directories.
         */
        internal fun clearOwnedFiles(filesDir: File, cacheDir: File) {
            listOf("local-models", "vosk-model", "tts_cache").forEach { name ->
                deleteIfPresent(File(filesDir, name))
            }
            filesDir.listFiles()
                ?.filter { it.name.startsWith("vosk-model.staging-") || it.name.startsWith("vosk-model.old-") }
                ?.forEach(::deleteIfPresent)
            cacheDir.listFiles()
                ?.filter { file ->
                    file.name.startsWith("friday-asr-") ||
                        file.name.startsWith("vosk-model-") ||
                        (file.name.startsWith("tts_") && file.name.endsWith(".mp3"))
                }
                ?.forEach(::deleteIfPresent)
        }

        private fun deleteIfPresent(file: File) {
            if (file.exists() && (!file.deleteRecursively() || file.exists())) {
                throw IOException("Could not delete local data: ${file.name}")
            }
        }
    }
}
