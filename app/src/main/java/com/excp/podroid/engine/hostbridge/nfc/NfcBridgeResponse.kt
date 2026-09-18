/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Hard framing limits shared by the NFC reader and its framework-free tests.
 */
package com.excp.podroid.engine.hostbridge.nfc

import com.excp.podroid.engine.hostbridge.HostProtocol

internal object NfcBridgeResponse {
    // hostd uses an 8192-byte buffer. One byte is reserved for NUL and another
    // for the LF that must be observed by its line reader.
    const val MAX_HOST_LINE_BYTES = 8_190
    const val MAX_EVENT_JSON_BYTES = 5_000

    fun eventFits(json: String): Boolean =
        json.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_JSON_BYTES

    /** Returns a complete safe line, never a silently truncated response. */
    fun okJson(json: String): String {
        val response = HostProtocol.ok(HostProtocol.enc(json))
        return if (response.toByteArray(Charsets.UTF_8).size <= MAX_HOST_LINE_BYTES) {
            response
        } else {
            HostProtocol.err("nfc response exceeds bridge limit")
        }
    }
}
