package com.friday.ai.core.apps

import com.friday.ai.agent.AgentTools
import com.friday.ai.core.CommandRouter
import com.friday.ai.domain.model.CommandResult
import java.time.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {

    private val phone = listOf(
        LaunchableApp("com.android.chrome", "Chrome"),
        LaunchableApp("com.chrome.beta", "Chrome Beta"),
        LaunchableApp("com.google.android.googlequicksearchbox", "Google"),
        LaunchableApp("com.google.android.apps.maps", "Google Maps"),
        LaunchableApp("com.google.android.gm", "Gmail"),
        LaunchableApp("org.telegram.messenger", "Telegram"),
        LaunchableApp("com.whatsapp", "WhatsApp"),
        LaunchableApp("com.sec.android.app.camera", "Камера"),
        LaunchableApp("com.yandex.music", "Яндекс Музыка"),
        LaunchableApp("ru.yandex.taxi", "Яндекс Go"),
        LaunchableApp("com.samsung.android.app.notes", "Samsung Notes")
    )

    private fun found(name: String, hint: String? = null) =
        (AppMatcher.match(name, phone, hint) as? AppMatch.Found)?.app?.packageName

    @Test
    fun `an exact label wins over prefix and near spellings`() {
        assertEquals("com.android.chrome", found("Chrome"))
        assertEquals(AppMatch.How.EXACT, (AppMatcher.match("chrome", phone) as AppMatch.Found).how)
        assertEquals("com.chrome.beta", found("chrome beta"))
    }

    @Test
    fun `chrome, хром and the known aliases open only the intended app`() {
        assertEquals("com.android.chrome", found("хром"))
        assertEquals("org.telegram.messenger", found("телеграм"))
        assertEquals("com.whatsapp", found("ватсап"))
        assertEquals("org.telegram.messenger", found("телеграмм"))
    }

    @Test
    fun `russian case, ё, spacing, punctuation and script fold the same way`() {
        assertEquals("com.yandex.music", found("  ЯНДЕКС   музыка! "))
        assertEquals("com.yandex.music", found("яндекс-музыка"))
        assertEquals("яндекс ежик", AppMatcher.normalize("Яндекс Ёжик"))
        assertEquals("com.sec.android.app.camera", found("камеру"))
        assertEquals("com.samsung.android.app.notes", found("samsung notes"))
    }

    @Test
    fun `a short ambiguous name is refused with the candidates named`() {
        val m = AppMatcher.match("go", phone)
        assertTrue(m.toString(), m is AppMatch.Ambiguous)
        assertEquals(listOf("Google", "Яндекс Go", "Google Maps"), (m as AppMatch.Ambiguous).labels)
        assertTrue(AppMatcher.match("яндекс", phone) is AppMatch.Ambiguous)
    }

    @Test
    fun `a stale alias or hint falls back to the label, never to a missing package`() {
        assertEquals("org.telegram.messenger", found("telegram", hint = "org.removed.app"))
        val noChrome = phone.filterNot { it.packageName == "com.android.chrome" }
        assertEquals(AppMatch.NotFound, AppMatcher.match("хром", noChrome))
    }

    @Test
    fun `two launcher activities of one package are one app`() {
        val twice = phone + LaunchableApp("org.telegram.messenger", "Telegram")
        assertEquals("org.telegram.messenger", (AppMatcher.match("telegram", twice) as AppMatch.Found).app.packageName)
        val twoPackages = phone + LaunchableApp("com.other.camera", "Камера")
        assertTrue(AppMatcher.match("камера", twoPackages) is AppMatch.Ambiguous)
    }

    @Test
    fun `nothing close is not found`() {
        assertEquals(AppMatch.NotFound, AppMatcher.match("фотошоп", phone))
        assertEquals(AppMatch.NotFound, AppMatcher.match("   ", phone))
    }
}

class AppOpenerTest {

    private class FakePlatform(
        val apps: List<LaunchableApp> = listOf(
            LaunchableApp("org.telegram.messenger", "Telegram"),
            LaunchableApp("com.google.android.googlequicksearchbox", "Google"),
            LaunchableApp("com.google.android.apps.maps", "Google Maps"),
            LaunchableApp("com.browser.cleaner", "Browser Cleaner")
        )
    ) : AppOpener.Platform {
        var visible = true
        var notificationsOn = true
        var roleResolves = true
        var failure: RuntimeException? = null
        val started = mutableListOf<AppOpener.Target>()
        val offered = mutableListOf<AppOpener.Target>()

        override fun launchable() = apps
        override fun role(name: String) =
            if (AppMatcher.normalize(name).startsWith("браузер")) AppOpener.RoleLookup(roleResolves) else null
        override fun canStartActivities() = visible
        override fun start(target: AppOpener.Target) {
            failure?.let { throw it }
            started += target
        }
        override fun offer(target: AppOpener.Target): Boolean {
            if (notificationsOn) offered += target
            return notificationsOn
        }
    }

    private val platform = FakePlatform()
    private val opener = AppOpener(platform)

    @Test
    fun `a found app is requested and reported as opening, not opened`() {
        assertEquals("Открываю Telegram", opener.open("телеграм", null, russian = true))
        val started = platform.started.map { (it as AppOpener.Target.App).app.packageName }
        assertEquals(listOf("org.telegram.messenger"), started)
    }

    @Test
    fun `browser opens the default browser, whatever it is, and never a namesake`() {
        assertEquals("Открываю браузер", opener.open("браузер", null, russian = true))
        assertEquals(AppOpener.Target.Role("браузер"), platform.started.single())

        platform.roleResolves = false
        val reply = opener.open("браузер", null, russian = true)
        assertTrue(reply, reply.startsWith("Не нашла приложение по умолчанию"))
        assertEquals("Browser Cleaner was not picked", 1, platform.started.size)
    }

    @Test
    fun `ambiguous and absent names launch nothing`() {
        val ambiguous = opener.open("go", null, russian = true)
        assertEquals("Под «go» подходят: Google, Google Maps. Скажите полное название", ambiguous)
        assertEquals("App \"photoshop\" not found on this device", opener.open("photoshop", null, russian = false))
        assertTrue(platform.started.isEmpty())
    }

    @Test
    fun `startActivity failing becomes a specific reply`() {
        platform.failure = SecurityException("blocked")
        assertEquals("Android не разрешил открыть Telegram", opener.open("telegram", null, russian = true))
        platform.failure = android.content.ActivityNotFoundException("gone")
        assertTrue(opener.open("telegram", null, russian = true).contains("больше нет"))
        assertTrue(platform.started.isEmpty())
    }

    @Test
    fun `from the background the launch is offered once, not fired blind`() {
        platform.visible = false
        val reply = opener.open("telegram", null, russian = true)
        assertEquals("Android не даёт открыть Telegram из фона — нажмите уведомление", reply)
        assertTrue(platform.started.isEmpty())
        assertEquals(1, platform.offered.size)

        platform.notificationsOn = false
        assertTrue(opener.open("telegram", null, russian = true).contains("откройте его сами"))
        assertEquals(1, platform.offered.size)
    }

    @Test
    fun `typed, spoken and model requests all become the same OpenApp`() {
        val router = CommandRouter()
        val spoken = router.route("открой телеграм") as CommandResult.OpenApp
        assertEquals(CommandResult.OpenApp("телеграм", "org.telegram.messenger"), spoken)
        val tool = AgentTools.interpret(
            "open_app",
            Json.parseToJsonElement("""{"name":"телеграм"}""") as JsonObject,
            LocalDateTime.of(2026, 10, 14, 9, 0)
        ) as AgentTools.Call.Command
        val fromModel = tool.command as CommandResult.OpenApp
        // The model passes no hint; the matcher finds the same alias itself.
        assertEquals(spoken.appName, fromModel.appName)
        assertEquals(
            AppMatcher.match(spoken.appName, platform.apps, spoken.packageHint),
            AppMatcher.match(fromModel.appName, platform.apps, fromModel.packageHint)
        )
    }
}
