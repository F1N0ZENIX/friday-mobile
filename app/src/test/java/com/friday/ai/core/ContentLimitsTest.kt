package com.friday.ai.core

import java.io.ByteArrayInputStream
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBudgetTest {

    @Test
    fun `a phone screen is scaled to the longest side`() {
        assertEquals(ImageBudget.Size(738, 1600), ImageBudget.bounded(1080, 2340))
    }

    @Test
    fun `a large photo is held to the edge and the pixel budget, never scaled up`() {
        assertEquals(ImageBudget.Size(1600, 1200), ImageBudget.bounded(8000, 6000))
        assertEquals(ImageBudget.Size(800, 600), ImageBudget.bounded(800, 600))
        val square = ImageBudget.bounded(1600, 1600)
        assertTrue(square.width.toLong() * square.height <= ImageBudget.MAX_PIXELS)
    }

    @Test
    fun `decoding is sampled down before allocation`() {
        assertEquals(4, ImageBudget.sampleSize(8000, 6000))
        assertEquals(1, ImageBudget.sampleSize(1600, 1200))
        val sample = ImageBudget.sampleSize(20_000, 20_000)
        assertTrue((20_000L / sample) * (20_000L / sample) <= ImageBudget.MAX_DECODE_PIXELS)
    }

    @Test
    fun `the first quality that fits is kept, and none fitting is said`() {
        val sizes = mapOf(80 to 2_000_000, 65 to 1_200_000, 50 to 800_000, 35 to 500_000)
        val tried = mutableListOf<Int>()
        val jpeg = ImageBudget.underBudget { q -> tried += q; ByteArray(sizes.getValue(q)) }
        assertEquals(800_000, jpeg?.size)
        assertEquals(listOf(80, 65, 50), tried)
        assertNull(ImageBudget.underBudget(budget = 10) { ByteArray(100) })
    }
}

class BoundedReadTest {

    private fun bytes(n: Int) = ByteArrayInputStream(ByteArray(n) { 'a'.code.toByte() })

    @Test
    fun `exactly the limit is read`() {
        assertEquals(1000, BoundedRead.limited(bytes(1000), 1000).readBytes().size)
    }

    @Test
    fun `past the limit the read stops, before the rest is allocated`() {
        val stream = BoundedRead.limited(bytes(10_000), 1000)
        val error = assertThrows(BoundedRead.TooLarge::class.java) { stream.readBytes() }
        assertEquals(1000L, error.limit)
        assertTrue(stream.exceeded)
    }

    @Test
    fun `text is cut at the limit and the cut is reported`() {
        assertEquals("abc" to false, BoundedRead.text(StringReader("abc"), 5))
        assertEquals("abcde" to false, BoundedRead.text(StringReader("abcde"), 5))
        assertEquals("abcde" to true, BoundedRead.text(StringReader("abcdefghij"), 5))
    }

    @Test
    fun `binary read as text is recognised`() {
        assertTrue(BoundedRead.looksBinary("%PDF-1.7\u0000\u0001"))
        assertFalse(BoundedRead.looksBinary("обычный текст"))
    }
}

class FileKindTest {

    @Test
    fun `what can be analysed, by type`() {
        assertEquals(FileAnalyzer.Kind.TEXT, FileAnalyzer.kindOf("text/plain"))
        assertEquals(FileAnalyzer.Kind.TEXT, FileAnalyzer.kindOf("application/json"))
        assertEquals(FileAnalyzer.Kind.TEXT, FileAnalyzer.kindOf("application/octet-stream"))
        assertEquals(FileAnalyzer.Kind.IMAGE, FileAnalyzer.kindOf("image/heic"))
    }

    @Test
    fun `PDFs and other binaries are not sent as text`() {
        assertNull(FileAnalyzer.kindOf("application/pdf"))
        assertNull(FileAnalyzer.kindOf("application/zip"))
    }
}
