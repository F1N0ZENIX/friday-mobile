package com.friday.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceVoiceInputTest {

    @Test
    fun `recognizer support accepts Brazilian Portuguese`() {
        assertTrue(OnDeviceVoiceInput.supportsBrazilianPortuguese(listOf("pt-BR")))
    }

    @Test
    fun `recognizer support accepts underscore Brazilian Portuguese`() {
        assertTrue(OnDeviceVoiceInput.supportsBrazilianPortuguese(listOf("pt_BR")))
    }

    @Test
    fun `recognizer support does not mistake another Portuguese locale for Brazilian Portuguese`() {
        assertFalse(OnDeviceVoiceInput.supportsBrazilianPortuguese(listOf("pt-PT")))
    }

    @Test
    fun `installed Brazilian Portuguese starts recognition`() {
        assertEquals(
            PortugueseModelAction.START_RECOGNITION,
            brazilianPortugueseModelAction(
                installed = listOf("pt-BR"),
                pending = emptyList(),
                supported = emptyList()
            )
        )
    }

    @Test
    fun `pending Brazilian Portuguese waits for queued download`() {
        assertEquals(
            PortugueseModelAction.WAIT_FOR_QUEUED_DOWNLOAD,
            brazilianPortugueseModelAction(
                installed = emptyList(),
                pending = listOf("pt-BR"),
                supported = listOf("pt-BR")
            )
        )
    }

    @Test
    fun `supported but uninstalled Brazilian Portuguese downloads model`() {
        assertEquals(
            PortugueseModelAction.DOWNLOAD_MODEL,
            brazilianPortugueseModelAction(
                installed = emptyList(),
                pending = emptyList(),
                supported = listOf("pt-BR")
            )
        )
    }

    @Test
    fun `unsupported Brazilian Portuguese reports unsupported`() {
        assertEquals(
            PortugueseModelAction.UNSUPPORTED,
            brazilianPortugueseModelAction(
                installed = emptyList(),
                pending = emptyList(),
                supported = listOf("pt-PT")
            )
        )
    }

    @Test
    fun `cannot check support falls back to the same on-device recognizer`() {
        assertTrue(shouldFallbackToOnDeviceRecognition(error = 14, cannotCheckSupport = 14))
    }

    @Test
    fun `other support errors do not start recognition`() {
        assertFalse(shouldFallbackToOnDeviceRecognition(error = 5, cannotCheckSupport = 14))
    }
}
