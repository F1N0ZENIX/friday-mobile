package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhrasesTest {

    private fun wakes(text: String) = WakePhrases.isWakeCall(text)

    @Test
    fun `russian name wakes the assistant`() {
        listOf("пятница", "Пятница", "ПЯТНИЦА", "пятница!").forEach {
            assertTrue("'$it' should wake", wakes(it))
        }
    }

    @Test
    fun `english name wakes the assistant`() {
        // These are the Cyrillic spellings the Russian recogniser actually
        // returns when the user says "Friday" out loud.
        listOf("фрайдей", "фрайди", "прайди", "фрэйди", "friday").forEach {
            assertTrue("'$it' should wake", wakes(it))
        }
    }

    @Test
    fun `brazilian pronunciation wakes and strips the call`() {
        listOf("Friday", "fraidei", "fráidei", "fridei").forEach {
            assertTrue("'$it' should wake", wakes(it))
        }
        assertTrue(wakes("Ei, Fráidei, ligar lanterna"))
        assertEquals("ligar lanterna", WakePhrases.stripCall("Ei, Fráidei, ligar lanterna"))
        assertEquals("abrir câmera", WakePhrases.stripCall("Friday, abrir câmera"))
        assertFalse(wakes("eu disse fraidei ontem"))
    }

    @Test
    fun `wake word followed by a command still wakes`() {
        assertTrue(wakes("пятница открой камеру"))
        assertTrue(wakes("фрайдей какая погода"))
        assertTrue(wakes("friday what's the weather"))
    }

    @Test
    fun `attention prefix is accepted`() {
        assertTrue(wakes("окей пятница"))
        assertTrue(wakes("привет пятница включи свет"))
        assertTrue(wakes("hey friday"))
        assertTrue(wakes("слушай фрайдей"))
    }

    @Test
    fun `weekday used mid sentence does not wake`() {
        // The whole reason matching is position-aware: "пятница" is also just
        // a day of the week.
        assertFalse(wakes("встретимся в пятницу"))
        assertFalse(wakes("давай перенесём на пятницу"))
        assertFalse(wakes("в эту пятницу я занят"))
        assertFalse(wakes("сегодня пятница"))
    }

    @Test
    fun `unrelated speech does not wake`() {
        assertFalse(wakes("привет как дела"))
        assertFalse(wakes("what is the weather today"))
        assertFalse(wakes("открой камеру"))
        assertFalse(wakes(""))
        assertFalse(wakes("   "))
    }

    @Test
    fun `case endings on the name still wake`() {
        // Recogniser output is noisy and often adds an ending.
        assertTrue(wakes("пятницу открой ютуб"))
        assertTrue(wakes("пятнице"))
    }

    @Test
    fun `language is reported so the reply can match`() {
        assertEquals(WakePhrases.Language.RUSSIAN, WakePhrases.detectLanguage("пятница открой камеру"))
        assertEquals(WakePhrases.Language.ENGLISH, WakePhrases.detectLanguage("фрайдей открой камеру"))
        assertEquals(WakePhrases.Language.ENGLISH, WakePhrases.detectLanguage("hey friday"))
        assertEquals(WakePhrases.Language.RUSSIAN, WakePhrases.detectLanguage("окей пятница"))
    }

    @Test
    fun `language is null when it was not a wake call`() {
        assertNull(WakePhrases.detectLanguage("встретимся в пятницу"))
        assertNull(WakePhrases.detectLanguage("hello there"))
    }

    @Test
    fun `an attention word alone does not wake`() {
        assertFalse(wakes("окей"))
        assertFalse(wakes("привет"))
        assertFalse(wakes("hey"))
    }

    @Test
    fun `Vosk grammar uses Brazilian Portuguese and excludes legacy Cyrillic names`() {
        val vocab = WakePhrases.grammarVocabulary()
        assertTrue(vocab.contains("friday"))
        assertTrue(vocab.contains("fraidei"))
        assertTrue(vocab.contains("ei"))
        assertTrue(vocab.contains("olá"))
        vocab.forEach { word ->
            assertTrue("'$word' is not Portuguese", word.matches(Regex("[a-záàâãéêíóôõúç ]+")))
        }
        assertFalse(vocab.contains("пятница"))
        assertFalse(vocab.contains("фрайдей"))
    }

    @Test
    fun `out-of-vocabulary tokens do not wake`() {
        // Grammar-constrained decoding maps unknown speech to [unk]; that must
        // never be mistaken for the wake word.
        assertFalse(wakes("[unk]"))
        assertFalse(wakes("[unk] [unk] пятница"))
    }
}
