package com.friday.ai.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file the owner picked, made into something the model can read.
 *
 * It used to read the whole file on the main thread and only then cut it:
 * a large document could freeze the app and a large photo run it out of
 * memory, and a PDF went to the model as binary pretending to be text.
 * Now reading happens off the main thread, every limit is enforced while
 * reading ([BoundedRead], [ImageBudget]), and the answer says plainly what
 * happened.
 */
class FileAnalyzer(private val context: Context) {

    sealed interface Outcome {
        val name: String

        data class Text(override val name: String, val text: String, val truncated: Boolean) : Outcome
        data class Image(override val name: String, val jpegBase64: String) : Outcome
        data class Unsupported(override val name: String, val mimeType: String) : Outcome
        data class TooLarge(override val name: String, val limitBytes: Long) : Outcome
        data class Unreadable(override val name: String) : Outcome
    }

    companion object {
        private const val TAG = "FileAnalyzer"

        /** Characters of a text file sent to the model: about what fits its context with room to answer. */
        const val MAX_TEXT_CHARS = 8_000

        /** A text file is read only this far looking for those characters. */
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024

        /** An image file larger than this is refused before decoding (a phone camera's photo is 3–15 MB). */
        const val MAX_IMAGE_BYTES = 30L * 1024 * 1024

        private const val PDF = "application/pdf"

        /** Text-like types that are not under "text/". */
        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/x-yaml", "application/csv")

        /** What a picked file becomes, by its type; null when it cannot be analysed. */
        fun kindOf(mimeType: String): Kind? = when {
            mimeType.startsWith("image/") -> Kind.IMAGE
            mimeType.startsWith("text/") || mimeType in TEXT_TYPES -> Kind.TEXT
            mimeType == PDF -> null
            // Unknown types are tried as text; binary is caught by its NUL bytes.
            mimeType == "application/octet-stream" -> Kind.TEXT
            else -> null
        }
    }

    enum class Kind { TEXT, IMAGE }

    /** The picker, for what can actually be analysed. PDFs are not offered: there is no extractor yet. */
    fun getFilePickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "image/*"))
    }

    /** Reads [uri] within the limits, on the I/O dispatcher. */
    suspend fun read(uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val name = runCatching { fileName(uri) }.getOrNull() ?: "file"
        try {
            when (kindOf(mimeType)) {
                Kind.TEXT -> text(uri, name, mimeType)
                Kind.IMAGE -> image(uri, name)
                null -> Outcome.Unsupported(name, mimeType)
            }
        } catch (e: BoundedRead.TooLarge) {
            Outcome.TooLarge(name, e.limit)
        } catch (e: IOException) {
            Log.w(TAG, "Could not read a picked file: ${e.javaClass.simpleName}")
            Outcome.Unreadable(name)
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to a picked file: ${e.javaClass.simpleName}")
            Outcome.Unreadable(name)
        }
    }

    private fun text(uri: Uri, name: String, mimeType: String): Outcome {
        val stream = context.contentResolver.openInputStream(uri) ?: return Outcome.Unreadable(name)
        val (text, truncated) = BoundedRead.limited(stream, MAX_TEXT_BYTES).bufferedReader().use {
            BoundedRead.text(it, MAX_TEXT_CHARS)
        }
        if (BoundedRead.looksBinary(text)) return Outcome.Unsupported(name, mimeType)
        return Outcome.Text(name, text, truncated)
    }

    private fun image(uri: Uri, name: String): Outcome {
        val (bitmap, failed) = decodeSampled(uri, name)
        if (bitmap == null) return failed ?: Outcome.Unreadable(name)
        val scaled = JpegEncoding.bounded(bitmap)
        return try {
            JpegEncoding.base64(scaled)?.let { Outcome.Image(name, it) }
                ?: Outcome.TooLarge(name, ImageBudget.MAX_JPEG_BYTES.toLong())
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    /**
     * Two passes: the size only, then pixels sampled down to
     * [ImageBudget.MAX_DECODE_PIXELS] — so a huge photo never decodes whole.
     */
    private fun decodeSampled(uri: Uri, name: String): Pair<Bitmap?, Outcome?> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val (_, failed) = decode(uri, name, bounds)
        if (failed != null) return null to failed
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null to Outcome.Unreadable(name)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageBudget.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return decode(uri, name, options)
    }

    /**
     * Decodes [uri] with [options] through a byte-limited stream: the bitmap
     * (null for a bounds-only pass), or why it could not be read.
     */
    private fun decode(uri: Uri, name: String, options: BitmapFactory.Options): Pair<Bitmap?, Outcome?> {
        val raw = context.contentResolver.openInputStream(uri) ?: return null to Outcome.Unreadable(name)
        val limited = BoundedRead.limited(raw, MAX_IMAGE_BYTES)
        val bitmap = try {
            limited.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: BoundedRead.TooLarge) {
            Log.i(TAG, "Image over ${e.limit} bytes")
            null
        }
        // BitmapFactory may swallow the stream's exception; the flag still says why.
        if (limited.exceeded) {
            bitmap?.recycle()
            return null to Outcome.TooLarge(name, MAX_IMAGE_BYTES)
        }
        return bitmap to null
    }

    private fun fileName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
