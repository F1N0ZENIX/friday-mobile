package com.friday.ai.service

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Installs a downloaded model archive as one transaction.
 *
 * The old installer unzipped straight into the model folder, writing
 * File(target, entry.name) for whatever names the archive held: an archive
 * with "../" names could overwrite other files of the app, and a huge one
 * fill the storage; nothing checked that the download was the archive it
 * should be. Now:
 *
 *  1. the archive must be the pinned one (size cap, then SHA-256);
 *  2. it is extracted into a fresh temporary folder, every entry checked to
 *     stay inside it, with caps on entries, entry size and total size;
 *  3. the model's marker must be there;
 *  4. only then does the new folder replace the old one, by renames;
 *  5. leftovers of an interrupted install are removed on the next attempt.
 *
 * A working model is never removed before its replacement is complete.
 */
class ModelInstaller(
    private val filesDir: File,
    private val cacheDir: File,
    private val folderName: String,
    private val marker: String,
    private val expected: Artifact,
    private val limits: Limits = Limits()
) {

    /** The archive the installer accepts. */
    data class Artifact(val sha256: String, val maxBytes: Long)

    /** Generous for vosk-model-small-ru-0.22: 20 entries, 33 MB largest, 91 MB in all. */
    data class Limits(
        val maxEntries: Int = MAX_ENTRIES,
        val maxEntryBytes: Long = MAX_ENTRY_MB * MB,
        val maxTotalBytes: Long = MAX_TOTAL_MB * MB
    )

    /** Why an archive was refused. */
    class Rejected(message: String) : IOException(message)

    private val target get() = File(filesDir, folderName)

    /**
     * Downloads with [download] (which writes the archive to the file it is
     * given) and installs the result. The previous folder stays until the
     * new one is complete.
     */
    suspend fun install(download: suspend (File) -> Unit) {
        cleanStale()
        val archive = File(cacheDir, "$folderName-${UUID.randomUUID()}.zip")
        val staging = File(filesDir, "$folderName$STAGING${UUID.randomUUID()}")
        try {
            download(archive)
            verify(archive)
            archive.inputStream().use { extract(it, staging) }
            if (modelRoot(staging) == null) throw Rejected("The archive holds no model ($marker missing)")
            swapIn(staging)
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    /** The folder holding the model's marker, at the top or one level down; null when there is none. */
    fun modelRoot(dir: File = target): File? {
        if (!dir.isDirectory) return null
        if (File(dir, marker).isFile) return dir
        return dir.listFiles()?.firstOrNull { it.isDirectory && File(it, marker).isFile }
    }

    /** Leftovers of an install that was interrupted: temporary folders and archives. */
    fun cleanStale() {
        filesDir.listFiles()
            ?.filter { it.name.startsWith(folderName + STAGING) || it.name.startsWith(folderName + OLD) }
            ?.forEach { it.deleteRecursively() }
        cacheDir.listFiles()?.filter { it.name.startsWith("$folderName-") && it.name.endsWith(".zip") }
            ?.forEach { it.delete() }
    }

    private fun verify(archive: File) {
        if (!archive.isFile || archive.length() > expected.maxBytes) {
            throw Rejected("The download is not the expected model (size ${archive.length()})")
        }
        if (sha256(archive) != expected.sha256) throw Rejected("The download is not the expected model (checksum)")
    }

    /** Extracts [zip] into [into], refusing names that leave it and archives past [limits]. */
    fun extract(zip: InputStream, into: File) {
        into.mkdirs()
        val root = into.canonicalFile
        var entries = 0
        var total = 0L
        ZipInputStream(zip).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (++entries > limits.maxEntries) throw Rejected("Too many entries in the archive")
                val file = File(root, entry.name).canonicalFile
                if (!file.path.startsWith(root.path + File.separator)) {
                    throw Rejected("An entry would be written outside the model folder")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    total += copyBounded(zis, file, total)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Copies one entry, counting against the entry and total caps as it goes; returns its size. */
    private fun copyBounded(input: InputStream, file: File, before: Long): Long {
        var size = 0L
        val buffer = ByteArray(BUFFER)
        FileOutputStream(file).use { output ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                if (size > limits.maxEntryBytes) throw Rejected("An entry in the archive is too large")
                if (before + size > limits.maxTotalBytes) throw Rejected("The archive expands too large")
                output.write(buffer, 0, n)
            }
        }
        return size
    }

    /**
     * The staged folder becomes the model folder. The old one is moved aside
     * first and removed only once the new one is in place; if the move fails
     * it is put back.
     */
    private fun swapIn(staging: File) {
        val old = File(filesDir, "$folderName$OLD${UUID.randomUUID()}")
        val hadOld = target.exists()
        if (hadOld && !target.renameTo(old)) throw IOException("Could not move the old model aside")
        if (!staging.renameTo(target)) {
            if (hadOld) old.renameTo(target)
            throw IOException("Could not install the new model")
        }
        old.deleteRecursively()
    }

    companion object {
        private const val STAGING = ".staging-"
        private const val OLD = ".old-"
        private const val BUFFER = 64 * 1024
        private const val MB = 1024L * 1024
        private const val MAX_ENTRIES = 500
        private const val MAX_ENTRY_MB = 64L
        private const val MAX_TOTAL_MB = 256L

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
