package com.friday.ai.core.apps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * [AppOpener.Platform] on a real phone.
 *
 * Enumerating every launcher entry needs QUERY_ALL_PACKAGES (declared in the
 * manifest for exactly this: "открой X" for any installed X). Without it
 * Android 11+ shows only a filtered list, and unknown names come back "not
 * found" rather than wrong.
 */
class AndroidApps(
    private val context: Context,
    private val roleIntent: (String) -> Intent?
) : AppOpener.Platform {

    private val packages get() = context.packageManager

    override fun launchable(): List<LaunchableApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(query, 0)
            .map { it.activityInfo.packageName to it }
            .distinctBy { it.first }
            // Only what can really be started: a stale or disabled entry is not a candidate.
            .filter { (pkg, _) -> packages.getLaunchIntentForPackage(pkg) != null }
            .map { (pkg, info) -> LaunchableApp(pkg, info.loadLabel(packages).toString()) }
    }

    override fun role(name: String): AppOpener.RoleLookup? =
        roleIntent(name)?.let { AppOpener.RoleLookup(it.resolveActivity(packages) != null) }

    override fun canStartActivities(): Boolean = AppVisibility.visible(Settings.canDrawOverlays(context))

    override fun start(target: AppOpener.Target) {
        context.startActivity(intentFor(target))
    }

    override fun offer(target: AppOpener.Target): Boolean {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return false
        val open = try {
            intentFor(target)
        } catch (_: android.content.ActivityNotFoundException) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Открыть приложение", NotificationManager.IMPORTANCE_HIGH)
        )
        val tap = PendingIntent.getActivity(
            context, target.label.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Открыть ${target.label}")
            .setContentText("Android не дал открыть его из фона — нажмите здесь")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        return try {
            notifications.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Launch notification refused: ${e.message}")
            false
        }
    }

    private fun intentFor(target: AppOpener.Target): Intent {
        val intent = when (target) {
            is AppOpener.Target.Role -> roleIntent(target.label)
            is AppOpener.Target.App -> packages.getLaunchIntentForPackage(target.app.packageName)
        } ?: throw android.content.ActivityNotFoundException(target.label)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val TAG = "AndroidApps"
        const val CHANNEL_ID = "friday_open_app"
        const val NOTIFICATION_ID = 4107
    }
}
