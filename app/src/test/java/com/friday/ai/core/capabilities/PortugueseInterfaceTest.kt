package com.friday.ai.core.capabilities

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the untranslated screens reported from the Android build. */
class PortugueseInterfaceTest {

    private val cyrillic = Regex("[\\u0400-\\u04FF]")

    @Test
    fun `diagnostic text stays in Portuguese for enabled and missing capabilities`() {
        val enabled = FridayCapabilities(
            permissions = FridayCapabilities.Permissions(
                microphone = true, overlay = true, notificationListener = true, postNotifications = true,
                contacts = true, phone = true, calendar = true, location = true, batteryExempt = true,
                dndAccess = true, writeSettings = true, nearbyDevices = true
            ),
            integrations = FridayCapabilities.Integrations(groqKey = true, gmail = true, lazuri = true),
            voice = FridayCapabilities.Voice(
                wakeWordEnabled = true, wakeModelReady = true, voiceProfile = true, listening = true
            )
        )
        listOf(FridayCapabilities(), enabled, enabled.copy(voice = enabled.voice.copy(listening = false)))
            .flatMap(Diagnostics::rows)
            .forEach { row ->
                val displayed = "${row.group.title} ${row.title} ${row.detail}"
                assertFalse(displayed, cyrillic.containsMatchIn(displayed))
            }
        assertTrue(Diagnostics.rows(enabled).any { it.title == "Microfone" })
    }

    @Test
    fun `interface sources do not reintroduce Russian labels`() {
        val ui = File("src/main/java/com/friday/ai/ui")
        assertTrue("UI sources must be available", ui.isDirectory)
        val violations = ui.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().asSequence().mapIndexedNotNull { index, line ->
                val trimmed = line.trimStart()
                // Comments document historical examples; the chat detector intentionally recognises Cyrillic.
                val allowed = trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*") ||
                    (file.name == "ChatViewModel.kt" && trimmed.startsWith("text.any { it in"))
                if (!allowed && cyrillic.containsMatchIn(line)) "${file.name}:${index + 1}" else null
            }
        }.toList()
        assertTrue("Untranslated interface labels: $violations", violations.isEmpty())
    }
}
