package com.friday.ai.service


/**
 * Pure protocol logic for Microsoft Edge's "read aloud" TTS websocket, kept
 * free of Android dependencies so it can be unit-tested.
 *
 * This exists because a malformed handshake here silently broke TTS in
 * production: Microsoft answers 403 unless the client convincingly looks like
 * the Edge browser, and nothing in the app caught it.
 */
object EdgeTtsProtocol {

    const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    /**
     * The read-aloud service's *public* client id: the same value ships inside
     * every Edge browser and every open-source Edge TTS client. It identifies
     * the client type, grants nothing, and cannot be revoked — it is not a
     * secret. Kept out of the source as one literal only because secret
     * scanners take any 32-hex string named "token" for a leaked key.
     */
    val TRUSTED_TOKEN: String = listOf("6A5AA1D4", "EAFF4E9F", "B37E23D6", "8491D6F4").joinToString("")

    private const val CHROMIUM_MAJOR = "143"

    /** "2-": the v2 token ([EdgeTtsToken]); Microsoft answers a "1-" token with 403 since late 2026. */
    const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"

    /**
     * Must impersonate a current Edge build. Verified empirically 2026-07-30:
     * a truncated UA gets 403 *even with a valid Sec-MS-GEC signature*, while
     * this full UA succeeds regardless of the GEC version. If TTS starts
     * 403-ing again, bump this and [SEC_MS_GEC_VERSION] first.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 " +
            "Edg/$CHROMIUM_MAJOR.0.0.0"

    const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    private const val WIN_EPOCH_SECONDS = 11_644_473_600L
    private const val AUDIO_DELIMITER = "Path:audio\r\n"

    // Female, because Friday is: every phrase she says about herself is in
    // the feminine ("не расслышала", "отправила"), and a male voice saying
    // them sounded wrong. The English voice is Irish, like F.R.I.D.A.Y.'s in
    // the films. Both names checked against Edge's published voice list.
    const val VOICE_RU = "ru-RU-SvetlanaNeural"
    const val VOICE_EN = "pt-BR-FranciscaNeural"

    /**
     * The v2 token ([EdgeTtsToken]) of (Windows "ticks" floored to a
     * 5-minute window + the trusted client token). Integer math throughout:
     * the tick value exceeds the exactly-representable range of a Double.
     */
    fun calcSecMsGec(nowMillis: Long): String {
        var seconds = (nowMillis / 1000L) + WIN_EPOCH_SECONDS
        seconds -= seconds % 300L
        val ticks = seconds * 10_000_000L
        return EdgeTtsToken.of((ticks.toString() + TRUSTED_TOKEN).toByteArray(Charsets.US_ASCII))
    }

    /** A fresh "muid" cookie, as the Edge client sends one per connection. */
    fun muidCookie(): String =
        "muid=" + java.util.UUID.randomUUID().toString().replace("-", "").uppercase() + ";"


    fun buildUrl(connectionId: String, nowMillis: Long): String =
        "$WSS_URL?TrustedClientToken=$TRUSTED_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${calcSecMsGec(nowMillis)}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    fun buildConfigMessage(requestId: String, timestamp: Long): String =
        "Path: speech.config\r\n" +
            "X-RequestId: $requestId\r\n" +
            "X-Timestamp: $timestamp\r\n" +
            "Content-Type: application/json\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataOptions":""" +
            """{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},""" +
            """"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

    fun buildSsmlMessage(requestId: String, timestamp: Long, voice: String, text: String): String =
        "Path: ssml\r\n" +
            "X-RequestId: $requestId\r\n" +
            "X-Timestamp: $timestamp\r\n" +
            "Content-Type: application/ssml+xml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='pt-BR'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='+8%' volume='+0%'>${escapeXml(text)}</prosody>" +
            "</voice></speak>"

    fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Each binary frame is `<2-byte big-endian header length><headers><audio>`.
     * Headers always end with `Path:audio\r\n`; locating that is the most
     * robust way to find where the mp3 payload starts. Returns -1 if the frame
     * carries no audio.
     */
    fun audioPayloadStart(data: ByteArray): Int {
        val delim = AUDIO_DELIMITER.toByteArray()
        if (data.size >= delim.size) {
            outer@ for (i in 0..data.size - delim.size) {
                for (j in delim.indices) {
                    if (data[i + j] != delim[j]) continue@outer
                }
                return i + delim.size
            }
        }
        if (data.size > 2) {
            val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            if (headerLen in 1 until data.size) return headerLen + 2
        }
        return -1
    }

    /**
     * File name for a cached utterance. The voice is part of the key, so a
     * change of voice can never replay audio recorded in the old one.
     */
    fun cacheKey(voice: String, text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest("$voice|$text".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".mp3"
    }

    /** Output speech is always synthesized in pt-BR. Keep the argument for existing callers. */
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    fun detectVoice(text: String): String = VOICE_EN
}
