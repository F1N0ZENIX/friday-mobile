package com.friday.ai.core.apps

/**
 * Opens the app a name means, and says only what is known.
 *
 * Android gives no public signal that a third-party app actually reached the
 * screen, so a launch Android accepted is reported as "opening", never as
 * opened. A launch Android cannot make from the background (Android 10+:
 * no visible Friday window) is not attempted silently: the owner gets a
 * notification to tap, and is told so.
 *
 * The Android side — PackageManager, startActivity, notifications — is
 * behind [Platform], so every decision here is tested without a phone.
 */
class AppOpener(private val platform: Platform) {

    /** Something to start: the user's default app for a role, or one package. */
    sealed interface Target {
        val label: String

        data class Role(override val label: String) : Target
        data class App(val app: LaunchableApp) : Target {
            override val label get() = app.label
        }
    }

    interface Platform {
        /** Launcher entries; an entry is only listed if its package has a launch intent. */
        fun launchable(): List<LaunchableApp>

        /** The user's default app for a role named in [name] ("браузер"), if it names one; null otherwise. */
        fun role(name: String): RoleLookup?

        /** Whether Friday has a visible window, which Android 10+ requires to start another app. */
        fun canStartActivities(): Boolean

        /** Asks Android to start [target]; throws what startActivity throws. */
        fun start(target: Target)

        /** Posts a notification that starts [target] when tapped; false when notifications are off. */
        fun offer(target: Target): Boolean
    }

    /** A role was named: [resolvable] says whether any app on the phone takes it. */
    data class RoleLookup(val resolvable: Boolean)

    fun open(name: String, hint: String?, russian: Boolean): String {
        val say = Say(russian)
        val role = platform.role(name)
        if (role != null) {
            // "Браузер" with no browser installed must not open "Browser Cleaner".
            return if (role.resolvable) launch(Target.Role(name), say)
            else say("Не нашла приложение по умолчанию для «$name»", "No default app for \"$name\"")
        }
        return when (val match = AppMatcher.match(name, platform.launchable(), hint)) {
            is AppMatch.Found -> launch(Target.App(match.app), say)
            is AppMatch.Ambiguous -> say(
                "Под «$name» подходят: ${match.labels.joinToString(", ")}. Скажите полное название",
                "\"$name\" could be ${match.labels.joinToString(", ")}. Say the full name"
            )
            AppMatch.NotFound -> say("Не нашла приложение «$name»", "App \"$name\" not found on this device")
        }
    }

    private fun launch(target: Target, say: Say): String {
        val label = target.label
        if (!platform.canStartActivities()) {
            // Android drops a background start without an error; asking once through a notification is honest.
            return if (platform.offer(target)) {
                say("Android не даёт открыть $label из фона — нажмите уведомление",
                    "Android won't open $label from the background — tap the notification")
            } else {
                say("Android не даёт открыть $label из фона, а уведомления выключены — откройте его сами",
                    "Android won't open $label from the background and notifications are off — open it yourself")
            }
        }
        return try {
            platform.start(target)
            say("Открываю $label", "Opening $label")
        } catch (_: SecurityException) {
            say("Android не разрешил открыть $label", "Android refused to open $label")
        } catch (_: android.content.ActivityNotFoundException) {
            say(
                "Не получилось открыть $label — его больше нет на телефоне",
                "Couldn't open $label — it's no longer installed"
            )
        } catch (_: IllegalStateException) {
            say("Не получилось открыть $label", "Couldn't open $label")
        }
    }

    private class Say(private val russian: Boolean) {
        operator fun invoke(ru: String, en: String) = if (russian) ru else en
    }
}
