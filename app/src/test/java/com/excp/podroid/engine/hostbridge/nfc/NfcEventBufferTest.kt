package com.excp.podroid.engine.hostbridge.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcEventBufferTest {
    @Test fun nextIsNonDestructiveForIndependentConsumers() {
        val buffer = NfcEventBuffer(capacity = 4, sessionId = "test-session")
        buffer.append { sequence, _ -> "{\"sequence\":$sequence}" }
        buffer.append { sequence, _ -> "{\"sequence\":$sequence}" }

        val firstConsumer = buffer.next("-")
        val secondConsumer = buffer.next("-")
        assertEquals(1L, firstConsumer.event?.sequence)
        assertEquals(1L, secondConsumer.event?.sequence)

        val firstNext = buffer.next(firstConsumer.cursor)
        val secondNext = buffer.next(secondConsumer.cursor)
        assertEquals(2L, firstNext.event?.sequence)
        assertEquals(2L, secondNext.event?.sequence)
        assertEquals("test-session:2", firstNext.cursor)
    }

    @Test fun noNewEventReturnsNullWithoutAdvancingCursor() {
        val buffer = NfcEventBuffer(capacity = 4, sessionId = "test-session")
        buffer.append { sequence, _ -> "{\"sequence\":$sequence}" }

        val result = buffer.next("test-session:1")
        assertNull(result.event)
        assertEquals("test-session:1", result.cursor)
        assertFalse(result.gap)
        assertFalse(result.reset)
    }

    @Test fun overflowReportsGapAndReturnsOldestRetainedEvent() {
        val buffer = NfcEventBuffer(capacity = 2, sessionId = "test-session")
        repeat(3) { buffer.append { sequence, _ -> "{\"sequence\":$sequence}" } }

        val result = buffer.next("test-session:0")
        assertEquals(2L, result.event?.sequence)
        assertTrue(result.gap)
        assertEquals(1L, result.dropped)
        assertEquals("test-session:2", result.cursor)
    }

    @Test fun foreignSessionResetsAtOldestRetainedEvent() {
        val buffer = NfcEventBuffer(capacity = 4, sessionId = "new-session")
        buffer.append { sequence, _ -> "{\"sequence\":$sequence}" }

        val result = buffer.next("old-session:9")
        assertEquals(1L, result.event?.sequence)
        assertTrue(result.reset)
        assertFalse(result.gap)
        assertEquals("new-session:1", result.cursor)
    }

    @Test fun decimalCursorUsesCurrentSession() {
        val buffer = NfcEventBuffer(capacity = 4, sessionId = "test-session")
        buffer.append { sequence, _ -> "{\"sequence\":$sequence}" }

        val result = buffer.next("0")
        assertEquals(1L, result.event?.sequence)
        assertEquals("test-session:1", result.cursor)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedCursorIsRejected() {
        NfcEventBuffer(capacity = 4, sessionId = "test-session").next("not a cursor")
    }

    @Test fun clearDropsRetainedEventsButKeepsMonotonicSequence() {
        val buffer = NfcEventBuffer(capacity = 4, sessionId = "test-session")
        buffer.append { sequence, _ -> "{\"sequence\":$sequence}" }
        buffer.clear()
        assertNull(buffer.current())

        val event = buffer.append { sequence, _ -> "{\"sequence\":$sequence}" }
        assertEquals(2L, event.sequence)
        assertEquals("test-session:2", buffer.cursorForLatest())
    }
}
