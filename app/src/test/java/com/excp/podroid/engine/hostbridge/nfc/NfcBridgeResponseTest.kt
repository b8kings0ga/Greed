package com.excp.podroid.engine.hostbridge.nfc

import com.excp.podroid.engine.hostbridge.HostProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcBridgeResponseTest {
    @Test fun smallJsonRoundTrips() {
        val json = "{\"event\":null}"
        val response = NfcBridgeResponse.okJson(json)

        assertTrue(response.startsWith("OK "))
        assertEquals(json, HostProtocol.dec(response.removePrefix("OK ")))
    }

    @Test fun utf8EventLimitIsMeasuredInBytes() {
        assertFalse(NfcBridgeResponse.eventFits("界".repeat(2_000)))
        assertTrue(NfcBridgeResponse.eventFits("a".repeat(5_000)))
    }

    @Test fun oversizedEscapedPayloadFailsAsACompleteSmallErrorLine() {
        val adversarialJson = buildString {
            append("{\"records\":[")
            repeat(16) { index ->
                if (index > 0) append(',')
                append("{\"mimeType\":\"")
                repeat(128) { append("\\u0000") }
                append("\"}")
            }
            append("]}")
        }

        val response = NfcBridgeResponse.okJson(adversarialJson)
        assertTrue(response.startsWith("ERR "))
        assertTrue(response.toByteArray(Charsets.UTF_8).size <= NfcBridgeResponse.MAX_HOST_LINE_BYTES)
    }
}
