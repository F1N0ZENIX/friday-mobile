package com.friday.ai.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenEndTest {

    @Test
    fun `nothing ends before the decoder closes an utterance with words`() {
        val s = SpokenEnd()
        s.onPartial("открой")
        assertFalse(s.finished(10_000))
        s.onFinal("", 1_000)
        assertFalse("silence the decoder closed is not a sentence", s.finished(10_000))
    }

    @Test
    fun `a short complete command ends almost at once`() {
        val s = SpokenEnd()
        s.onFinal("открой камеру", 1_500)
        assertFalse(s.finished(1_500 + SpokenEnd.SHORT_PHRASE_MS - 20))
        assertTrue(s.finished(1_500 + SpokenEnd.SHORT_PHRASE_MS))
    }

    @Test
    fun `a longer request leaves room for a pause to think`() {
        val s = SpokenEnd()
        s.onFinal("напомни купить хлеб вечером", 3_000)
        assertFalse(s.finished(3_000 + SpokenEnd.SHORT_PHRASE_MS))
        assertTrue(s.finished(3_000 + SpokenEnd.LONG_PHRASE_MS))
    }

    @Test
    fun `a sentence stopped on a joining word waits for the rest`() {
        listOf("включи музыку и", "напомни мне в", "set a timer for", "открой телеграм потом").forEach { said ->
            val s = SpokenEnd()
            s.onFinal(said, 2_000)
            assertFalse(said, s.finished(2_000 + SpokenEnd.LONG_PHRASE_MS))
            assertTrue(said, s.finished(2_000 + SpokenEnd.OPEN_PHRASE_MS))
        }
    }

    @Test
    fun `words heard after a close cancel it, and the next close counts`() {
        val s = SpokenEnd()
        s.onFinal("включи музыку и", 2_000)
        s.onPartial("выключи")
        assertFalse(s.finished(10_000))
        s.onFinal("выключи свет", 3_200)
        assertEquals(5, s.wordCount)
        assertTrue("five words: the longer patience", s.finished(3_200 + SpokenEnd.LONG_PHRASE_MS))
        assertFalse(s.finished(3_200 + SpokenEnd.SHORT_PHRASE_MS))
    }

    @Test
    fun `an empty partial is the decoder waiting, not the owner talking`() {
        val s = SpokenEnd()
        s.onFinal("да", 800)
        s.onPartial("")
        assertTrue(s.finished(800 + SpokenEnd.SHORT_PHRASE_MS))
    }
}

class EndpointerWordsTest {

    @Test
    fun `the words end a turn only once there was speech`() {
        val e = SpeechEndpointer(450.0)
        assertEquals(null, e.endByWords())
        val lead = SpeechEndpointer(450.0, leadMs = 600)
        assertEquals(SpeechEndpointer.End.FINISHED, lead.endByWords())
        assertTrue(lead.endedByWords)
        assertTrue(lead.usable)
    }
}
