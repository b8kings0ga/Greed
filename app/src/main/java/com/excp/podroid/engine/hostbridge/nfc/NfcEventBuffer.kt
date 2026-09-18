/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * A small, framework-free event ring used by the NFC host bridge. Events are
 * non-destructive and cursor-addressed so multiple guest services can consume
 * the same tag stream independently.
 */
package com.excp.podroid.engine.hostbridge.nfc

import java.util.ArrayDeque
import java.util.UUID

internal data class BufferedNfcEvent(
    val sequence: Long,
    val json: String,
)

internal data class NfcBufferStatus(
    val sessionId: String,
    val capacity: Int,
    val buffered: Int,
    val dropped: Long,
    val oldestSequence: Long?,
    val latestSequence: Long,
)

internal data class NfcNextEvent(
    val cursor: String,
    val event: BufferedNfcEvent?,
    val dropped: Long,
    val gap: Boolean,
    val reset: Boolean,
)

internal class NfcEventBuffer(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val sessionId: String = UUID.randomUUID().toString(),
) {
    private val events = ArrayDeque<BufferedNfcEvent>(capacity)
    private var latestSequence = 0L
    private var dropped = 0L

    init {
        require(capacity > 0) { "capacity must be positive" }
        require(sessionId.isNotBlank() && ':' !in sessionId) { "invalid session id" }
    }

    @Synchronized
    fun append(jsonFor: (sequence: Long, sessionId: String) -> String): BufferedNfcEvent {
        val sequence = ++latestSequence
        val event = BufferedNfcEvent(sequence, jsonFor(sequence, sessionId))
        if (events.size == capacity) {
            events.removeFirst()
            dropped++
        }
        events.addLast(event)
        return event
    }

    @Synchronized
    fun current(): BufferedNfcEvent? = events.peekLast()

    /**
     * Returns the first buffered event after [cursor] without removing it.
     *
     * `-` starts at the oldest retained event. A decimal cursor is accepted as
     * shorthand for the current session (useful for `next 0` in simple tools).
     * Opaque cursors have the form `<session>:<sequence>` and should otherwise
     * be treated as an implementation detail by callers.
     */
    @Synchronized
    fun next(cursor: String?): NfcNextEvent {
        val oldest = events.peekFirst()?.sequence
        val parsed = parseCursor(cursor, oldest)
        var after = parsed.sequence
        var reset = parsed.reset

        // A cursor ahead of this process cannot ever catch a future event if it
        // came from a previous manager session. Normalize it instead of leaving
        // the consumer stuck forever.
        if (after > latestSequence) {
            after = (oldest ?: (latestSequence + 1L)) - 1L
            reset = true
        }

        val gap = !parsed.fresh && oldest != null && after < oldest - 1L
        val event = events.firstOrNull { it.sequence > after }
        val nextSequence = event?.sequence ?: after.coerceAtMost(latestSequence).coerceAtLeast(0L)

        return NfcNextEvent(
            cursor = cursorFor(nextSequence),
            event = event,
            dropped = dropped,
            gap = gap,
            reset = reset,
        )
    }

    @Synchronized
    fun clear() {
        events.clear()
    }

    @Synchronized
    fun status(): NfcBufferStatus = NfcBufferStatus(
        sessionId = sessionId,
        capacity = capacity,
        buffered = events.size,
        dropped = dropped,
        oldestSequence = events.peekFirst()?.sequence,
        latestSequence = latestSequence,
    )

    @Synchronized
    fun cursorForLatest(): String = cursorFor(latestSequence)

    @Synchronized
    fun cursorForOldest(): String? = events.peekFirst()?.sequence?.let(::cursorFor)

    fun cursorFor(sequence: Long): String = "$sessionId:$sequence"

    private fun parseCursor(raw: String?, oldest: Long?): ParsedCursor {
        val cursor = raw?.trim().orEmpty()
        if (cursor.isEmpty() || cursor == "-") {
            return ParsedCursor(
                sequence = (oldest ?: (latestSequence + 1L)) - 1L,
                fresh = true,
                reset = false,
            )
        }

        cursor.toLongOrNull()?.let { sequence ->
            require(sequence >= 0L) { "invalid cursor" }
            return ParsedCursor(sequence = sequence, fresh = false, reset = false)
        }

        val separator = cursor.lastIndexOf(':')
        require(separator in 1 until cursor.lastIndex) { "invalid cursor" }
        val cursorSession = cursor.substring(0, separator)
        val sequence = cursor.substring(separator + 1).toLongOrNull()
            ?: throw IllegalArgumentException("invalid cursor")
        require(sequence >= 0L) { "invalid cursor" }

        if (cursorSession != sessionId) {
            return ParsedCursor(
                sequence = (oldest ?: (latestSequence + 1L)) - 1L,
                fresh = true,
                reset = true,
            )
        }
        return ParsedCursor(sequence = sequence, fresh = false, reset = false)
    }

    private data class ParsedCursor(
        val sequence: Long,
        val fresh: Boolean,
        val reset: Boolean,
    )

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
