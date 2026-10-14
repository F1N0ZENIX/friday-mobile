package com.friday.ai.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader

/**
 * Reading what a content provider hands over without trusting its size.
 *
 * Providers may report no length or the wrong one, so the limit is enforced
 * while reading: past it, reading stops with [TooLarge] instead of
 * allocating whatever the file happens to be.
 */
object BoundedRead {

    /** More than [limit] bytes came from a stream. */
    class TooLarge(val limit: Long) : IOException("more than $limit bytes")

    /**
     * A stream that fails with [TooLarge] once more than [limit] bytes have
     * been read. [exceeded] stays set afterwards, for readers such as
     * BitmapFactory that swallow the exception and return nothing.
     */
    class Limited(stream: InputStream, private val limit: Long) : FilterInputStream(stream) {
        private var count = 0L

        var exceeded = false
            private set

        override fun read(): Int = super.read().also { if (it >= 0) add(1L) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { add(it) }

        private fun add(n: Long) {
            count += n
            if (count > limit) {
                exceeded = true
                throw TooLarge(limit)
            }
        }
    }

    fun limited(stream: InputStream, limit: Long): Limited = Limited(stream, limit)

    /** Up to [maxChars] characters, and whether there was more. Reads at most one character past the limit. */
    fun text(reader: Reader, maxChars: Int): Pair<String, Boolean> {
        val buffer = CharArray(maxChars + 1)
        var filled = 0
        while (filled < buffer.size) {
            val n = reader.read(buffer, filled, buffer.size - filled)
            if (n < 0) break
            filled += n
        }
        val truncated = filled > maxChars
        return String(buffer, 0, minOf(filled, maxChars)) to truncated
    }

    /** Text with NUL characters is a binary file read as text. */
    fun looksBinary(text: String): Boolean = '\u0000' in text
}
