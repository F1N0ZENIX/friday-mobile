package com.friday.ai.service.messages

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.friday.ai.core.people.SmsLine
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends an SMS through the phone's own radio and waits for Android's
 * "sent" report, so a reply never says "отправлено" for a message that is
 * still stuck — or never left.
 */
class AndroidSms(private val context: Context) : SmsLine {

    private companion object {
        const val TAG = "AndroidSms"
        const val SENT_TIMEOUT_MS = 20_000L
    }

    override fun canSend(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)

    override suspend fun send(number: String, text: String): SmsLine.Result {
        if (!canSend()) return SmsLine.Result.Failed("нет разрешения на отправку SMS")
        val manager = smsManager()
        val parts = manager.divideMessage(text)
        val action = "com.friday.ai.SMS_SENT." + UUID.randomUUID()
        val outcome = CompletableDeferred<SmsLine.Result>()
        var reported = 0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) {
                    outcome.complete(SmsLine.Result.Failed(reason(resultCode)))
                } else if (++reported == parts.size) {
                    outcome.complete(SmsLine.Result.Sent)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        return try {
            val sent = ArrayList(
                parts.indices.map { i ->
                    PendingIntent.getBroadcast(
                        context, i, Intent(action).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    )
                }
            )
            manager.sendMultipartTextMessage(number, null, parts, sent, null)
            withTimeoutOrNull(SENT_TIMEOUT_MS) { outcome.await() } ?: SmsLine.Result.Unconfirmed
        } catch (e: SecurityException) {
            Log.w(TAG, "SMS refused: ${e.message}")
            SmsLine.Result.Failed("Android запретил отправку")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "SMS not accepted: ${e.message}")
            SmsLine.Result.Failed("номер не подошёл")
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Suppress("DEPRECATION") // getDefault() is the only way below Android 12
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    private fun reason(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "нет сети"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "мобильная связь выключена"
        SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "оператор не принял сообщение"
        else -> "ошибка $code"
    }
}
