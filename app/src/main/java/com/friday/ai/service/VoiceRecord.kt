package com.friday.ai.service

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Opens the microphone the way every listener here does: VOICE_RECOGNITION
 * (the tuned path the hardware effects expect), 16-bit mono PCM.
 *
 * Null when it cannot be had: RECORD_AUDIO not granted — the wake service
 * can be started at boot or on resume after the owner revoked it — or the
 * microphone held by a call or another app. Callers already treat null as
 * "microphone unavailable"; nothing here throws for those cases.
 */
fun openVoiceRecord(sampleRate: Int, bufferSize: Int, tag: String): AudioRecord? {
    val record = try {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
    } catch (e: SecurityException) {
        Log.w(tag, "No microphone permission: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(tag, "Microphone parameters refused: ${e.message}")
        null
    }
    if (record != null && record.state != AudioRecord.STATE_INITIALIZED) {
        Log.w(tag, "Microphone unavailable")
        record.release()
        return null
    }
    return record
}
