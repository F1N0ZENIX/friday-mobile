package com.friday.ai.data.local

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataClearerTest {
    private val root: File = Files.createTempDirectory("friday-clear").toFile()
    private val files = File(root, "files").apply { mkdirs() }
    private val cache = File(root, "cache").apply { mkdirs() }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `clears every known Friday artifact but leaves unrelated private files alone`() {
        listOf("local-models", "vosk-model", "tts_cache", "vosk-model.staging-123", "vosk-model.old-456")
            .forEach { File(files, "$it/private.bin").apply { parentFile!!.mkdirs(); writeText("private") } }
        listOf("friday-asr-1.wav", "vosk-model-1.zip", "tts_123.mp3")
            .forEach { File(cache, it).writeText("private") }
        val unrelatedFiles = File(files, "keep.txt").apply { writeText("keep") }
        val unrelatedCache = File(cache, "other-app-cache.tmp").apply { writeText("keep") }

        LocalDataClearer.clearOwnedFiles(files, cache)

        listOf("local-models", "vosk-model", "tts_cache", "vosk-model.staging-123", "vosk-model.old-456")
            .forEach { assertFalse(File(files, it).exists()) }
        listOf("friday-asr-1.wav", "vosk-model-1.zip", "tts_123.mp3")
            .forEach { assertFalse(File(cache, it).exists()) }
        assertTrue(unrelatedFiles.isFile)
        assertTrue(unrelatedCache.isFile)
    }
}
