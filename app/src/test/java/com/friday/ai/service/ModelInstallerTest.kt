package com.friday.ai.service

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallerTest {

    private val root: File = Files.createTempDirectory("friday-installer").toFile()
    private val files = File(root, "files").apply { mkdirs() }
    private val cache = File(root, "cache").apply { mkdirs() }
    private val marker = "conf/model.conf"

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val model = zip(
        "vosk-model-small-ru/conf/model.conf" to "--sample-frequency=16000".toByteArray(),
        "vosk-model-small-ru/am/final.mdl" to ByteArray(1000) { 7 }
    )

    private fun installer(
        archive: ByteArray,
        limits: ModelInstaller.Limits = ModelInstaller.Limits(),
        sha: String = ModelInstaller.sha256(ByteArrayInputStream(archive))
    ) = ModelInstaller(files, cache, "vosk-model", marker, ModelInstaller.Artifact(sha, maxBytes = 1_000_000), limits)

    private fun ModelInstaller.installFrom(archive: ByteArray) = runBlocking { install { it.writeBytes(archive) } }

    /** A model already in place, as a previous install left it. */
    private fun existingModel(): File = File(files, "vosk-model/old/$marker").apply {
        parentFile.mkdirs()
        writeText("old")
    }

    private fun leftovers() = files.listFiles()!!.map { it.name }.filter { it != "vosk-model" } + cache.list()!!

    @Test
    fun `the pinned archive is installed and found`() {
        val installer = installer(model)
        installer.installFrom(model)
        val path = installer.modelRoot()
        assertNotNull(path)
        assertTrue(File(path!!, marker).isFile)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `an entry climbing out with dot-dot is refused and nothing escapes`() {
        val evil = zip("ok/conf/model.conf" to ByteArray(1), "../../escape.txt" to "pwned".toByteArray())
        assertThrows(ModelInstaller.Rejected::class.java) { installer(evil).installFrom(evil) }
        assertFalse(File(root, "escape.txt").exists())
        assertFalse(File(files, "escape.txt").exists())
        assertFalse(File(cache.parentFile, "escape.txt").exists())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `an absolute name stays inside the folder or is refused`() {
        val absolute = zip("/abs/conf/model.conf" to ByteArray(1))
        runCatching { installer(absolute).installFrom(absolute) }
        assertFalse(File("/abs/conf/model.conf").exists())
    }

    @Test
    fun `too many entries, a huge entry or a huge archive are refused`() {
        val many = zip(*Array(6) { "m/f$it" to ByteArray(1) })
        assertThrows(ModelInstaller.Rejected::class.java) {
            installer(many, ModelInstaller.Limits(maxEntries = 5)).installFrom(many)
        }
        assertThrows(ModelInstaller.Rejected::class.java) {
            installer(model, ModelInstaller.Limits(maxEntryBytes = 100)).installFrom(model)
        }
        assertThrows(ModelInstaller.Rejected::class.java) {
            installer(model, ModelInstaller.Limits(maxTotalBytes = 500)).installFrom(model)
        }
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `a wrong checksum leaves the working model in place`() {
        val old = existingModel()
        assertThrows(ModelInstaller.Rejected::class.java) {
            installer(model, sha = "0".repeat(64)).installFrom(model)
        }
        assertEquals("old", old.readText())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `an extraction that fails part-way leaves the working model in place`() {
        val old = existingModel()
        val broken = zip("new/conf/model.conf" to ByteArray(1), "../outside" to ByteArray(1))
        assertThrows(ModelInstaller.Rejected::class.java) { installer(broken).installFrom(broken) }
        assertEquals("old", old.readText())
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `an archive without the model is not installed`() {
        val old = existingModel()
        val empty = zip("readme.txt" to "nothing here".toByteArray())
        assertThrows(ModelInstaller.Rejected::class.java) { installer(empty).installFrom(empty) }
        assertEquals("old", old.readText())
    }

    @Test
    fun `a new model replaces the old one only when complete`() {
        existingModel()
        val installer = installer(model)
        installer.installFrom(model)
        assertTrue(installer.modelRoot()!!.name == "vosk-model-small-ru")
        assertFalse(File(files, "vosk-model/old").exists())
    }

    @Test
    fun `leftovers of an interrupted install are cleared`() {
        File(files, "vosk-model.staging-123/x").apply { parentFile.mkdirs(); writeText("x") }
        File(files, "vosk-model.old-456").mkdirs()
        File(cache, "vosk-model-789.zip").writeText("x")
        installer(model).cleanStale()
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun `sha-256 of a known input`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelInstaller.sha256(ByteArrayInputStream("abc".toByteArray()))
        )
    }
}
