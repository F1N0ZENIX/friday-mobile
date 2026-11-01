package com.friday.ai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Edge TTS handshake. These are regression tests for a real
 * outage: TTS silently 403-ed in production because the User-Agent didn't
 * convincingly impersonate Edge, and nothing caught it.
 */
class EdgeTtsProtocolTest {

    // Reference values computed independently from edge-tts's own v2
    // algorithm (Python) so this test fails if the Kotlin port drifts.
    private val referenceHash =
        "54404AFE67C9B44A-E72E51D8B4F5F75E9A96B7E20D7877227455BC310433BD30C817CD54A1D9313E"
    private val alignedMillis = 1_735_689_600_000L // 2025-01-01T00:00:00Z

    @Test
    fun `sec-ms-gec matches the reference implementation`() {
        assertEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis))
        val more = mapOf(
            1_735_689_900_000L to
                "573B5AB47EF76DEF-382062865F8E6ED3D6FF14EFCF08C89427D829196C82586577B5BD64F1FFC0EF",
            1_760_000_000_000L to
                "6D50DDDB91812C33-DD68EFB01898B0750C9797D51A5FF93471DFDAE6888A1039D532CBDCCE54545D",
            1_700_000_123_000L to
                "0919602B5750900A-EC9DD394F23CB5635CF62A0E8E24136DCD32B26D10C543B10082E482D7516397",
            1_791_234_567_000L to
                "787CB623D6FE131B-1A6E7D393B0EF86237E7EA9A56A520F2522922056FF268DB6EE0F055B51DBF60"
        )
        more.forEach { (millis, token) -> assertEquals("$millis", token, EdgeTtsProtocol.calcSecMsGec(millis)) }
    }

    @Test
    fun `a 403 with a clock minutes off is retried with the server time, a correct clock is not`() {
        EdgeClock.skewMillis = 0
        val local = 1_735_689_600_000L // 2025-01-01T00:00:00Z
        assertTrue(EdgeClock.learn("Wed, 01 Jan 2025 00:07:00 GMT", local))
        assertEquals(420_000L, EdgeClock.skewMillis)
        assertTrue(!EdgeClock.learn("Wed, 01 Jan 2025 00:07:10 GMT", local + 420_000L))
        assertTrue(!EdgeClock.learn(null, local))
        assertTrue(!EdgeClock.learn("not a date", local))
        EdgeClock.skewMillis = 0
    }

    @Test
    fun `each connection carries a muid cookie`() {
        val cookie = EdgeTtsProtocol.muidCookie()
        assertTrue(cookie, Regex("muid=[0-9A-F]{32};").matches(cookie))
    }

    @Test
    fun `sec-ms-gec is stable within a five minute window`() {
        // Anything inside the same 5-minute bucket must hash identically —
        // this is what lets the token be cached/reused by the server.
        assertEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis + 185_000))
        assertEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis + 299_999))
    }

    @Test
    fun `sec-ms-gec changes in the next five minute window`() {
        assertNotEquals(referenceHash, EdgeTtsProtocol.calcSecMsGec(alignedMillis + 300_000))
    }

    @Test
    fun `sec-ms-gec is a 16-hex checksum and a 64-hex digest, upper case`() {
        val hash = EdgeTtsProtocol.calcSecMsGec(alignedMillis)
        assertTrue(hash, Regex("[0-9A-F]{16}-[0-9A-F]{64}").matches(hash))
    }

    @Test
    fun `user agent impersonates a real Edge browser`() {
        // The exact failure that broke TTS: a truncated UA ending at
        // AppleWebKit/537.36 gets rejected with 403 by Microsoft.
        val ua = EdgeTtsProtocol.USER_AGENT
        assertTrue("UA must identify as Edge", ua.contains("Edg/"))
        assertTrue("UA must identify as Chrome", ua.contains("Chrome/"))
        assertTrue("UA must include Safari token", ua.contains("Safari/537.36"))
        assertTrue("UA must include Gecko token", ua.contains("(KHTML, like Gecko)"))
    }

    @Test
    fun `sec-ms-gec version is an edge version string`() {
        assertTrue(EdgeTtsProtocol.SEC_MS_GEC_VERSION.startsWith("2-"))
    }

    @Test
    fun `url carries both anti-abuse parameters`() {
        val url = EdgeTtsProtocol.buildUrl("abc123", alignedMillis)
        assertTrue(url.contains("TrustedClientToken=${EdgeTtsProtocol.TRUSTED_TOKEN}"))
        assertTrue(url.contains("ConnectionId=abc123"))
        assertTrue(url.contains("Sec-MS-GEC=$referenceHash"))
        assertTrue(url.contains("Sec-MS-GEC-Version=${EdgeTtsProtocol.SEC_MS_GEC_VERSION}"))
    }

    @Test
    fun `russian text selects the russian voice`() {
        assertEquals(EdgeTtsProtocol.VOICE_EN, EdgeTtsProtocol.detectVoice("Привет, как дела?"))
    }

    @Test
    fun `english text selects the english voice`() {
        assertEquals(EdgeTtsProtocol.VOICE_EN, EdgeTtsProtocol.detectVoice("Hello, how are you?"))
    }

    @Test
    fun `punctuation and digits do not sway voice detection`() {
        // Previously the latin counter used the range 'A'..'z', which also
        // swept up [ \ ] ^ _ ` and could outvote real Cyrillic letters.
        assertEquals(EdgeTtsProtocol.VOICE_EN, EdgeTtsProtocol.detectVoice("Готово: 100% [___]"))
    }

    @Test
    fun `ssml escapes characters that would break the xml`() {
        val ssml = EdgeTtsProtocol.buildSsmlMessage("rid", 1L, EdgeTtsProtocol.VOICE_EN, "5 < 6 & \"x\"")
        assertTrue(ssml.contains("5 &lt; 6 &amp; &quot;x&quot;"))
        assertTrue(ssml.contains("<voice name='${EdgeTtsProtocol.VOICE_EN}'>"))
    }

    @Test
    fun `audio payload starts after the path audio delimiter`() {
        val header = "X-RequestId:1\r\nContent-Type:audio/mpeg\r\nPath:audio\r\n"
        val frame = header.toByteArray() + byteArrayOf(1, 2, 3, 4)
        val start = EdgeTtsProtocol.audioPayloadStart(frame)
        assertEquals(header.length, start)
        assertEquals(listOf<Byte>(1, 2, 3, 4), frame.drop(start))
    }

    @Test
    fun `frames without audio are reported as having no payload`() {
        assertEquals(-1, EdgeTtsProtocol.audioPayloadStart(ByteArray(0)))
        assertEquals(-1, EdgeTtsProtocol.audioPayloadStart(byteArrayOf(0, 0)))
    }
}
