package com.example.shutdownprotection.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostics encoding, sanitising, and retention-bound tests (brief section 20). */
class DiagnosticCodecTest {

    private fun event(timestamp: Long, kind: String = "TEST", revision: Long = 1L, message: String = "ok") =
        DiagnosticEvent(timestamp, kind, revision, message)

    @Test
    fun `sanitize removes record separators and control characters`() {
        val sanitized = DiagnosticCodec.sanitize("first\nsecond\rthird${DiagnosticCodec.FIELD_SEPARATOR}fourth")
        assertFalse(sanitized.contains('\n'))
        assertFalse(sanitized.contains('\r'))
        assertFalse(sanitized.contains(DiagnosticCodec.FIELD_SEPARATOR))
        assertTrue(sanitized.contains("first"))
        assertTrue(sanitized.contains("fourth"))
    }

    @Test
    fun `sanitize redacts long digit runs`() {
        val sanitized = DiagnosticCodec.sanitize("call 02079460000 now")
        assertFalse(sanitized.contains("02079460000"))
        assertTrue(sanitized.contains(DiagnosticCodec.REDACTED))
        // Short numbers that carry no identifying weight are left alone.
        assertEquals("revision 42", DiagnosticCodec.sanitize("revision 42"))
    }

    @Test
    fun `sanitize caps the message length`() {
        val sanitized = DiagnosticCodec.sanitize("x".repeat(5000))
        assertEquals(DiagnosticCodec.MAX_MESSAGE_LENGTH, sanitized.length)
    }

    @Test
    fun `encode and decode round-trip preserves every field`() {
        val events = listOf(
            event(1L, "A", 1L, "one"),
            event(2L, "B", 2L, "two with spaces"),
            event(3L, "C", 3L, ""),
        )
        val decoded = DiagnosticCodec.decode(DiagnosticCodec.encode(events))
        assertEquals(events, decoded)
    }

    @Test
    fun `decode tolerates malformed lines instead of throwing`() {
        val sep = DiagnosticCodec.FIELD_SEPARATOR
        val raw = buildString {
            appendLine("not-a-number${sep}KIND${sep}1${sep}message")
            appendLine("2${sep}KIND${sep}not-a-long${sep}message")
            appendLine("too${sep}few${sep}fields")
            appendLine("")
            appendLine("4${sep}KIND${sep}4${sep}good")
        }
        val decoded = DiagnosticCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals(4L, decoded[0].timestampMillis)
        assertEquals("good", decoded[0].message)
    }

    @Test
    fun `decode of an empty string yields no events`() {
        assertTrue(DiagnosticCodec.decode("").isEmpty())
    }

    @Test
    fun `trim keeps the encoded payload within the record bound`() {
        val events = (1..(DiagnosticCodec.MAX_EVENTS + 500)).map { event(it.toLong()) }
        val trimmed = DiagnosticCodec.trim(events)
        assertEquals(DiagnosticCodec.MAX_EVENTS, trimmed.size)
        // The newest survive; the oldest are dropped.
        assertEquals((DiagnosticCodec.MAX_EVENTS + 500).toLong(), trimmed.last().timestampMillis)
        assertEquals(501L, trimmed.first().timestampMillis)
        // And the shipped configuration stays inside the byte bound too.
        assertTrue(
            DiagnosticCodec.encode(trimmed).toByteArray(Charsets.UTF_8).size <= DiagnosticCodec.MAX_BYTES,
        )
    }

    @Test
    fun `the byte bound drops the oldest events when it is the binding constraint`() {
        // Exercised with explicit bounds: with the shipped constants the record bound always
        // trips first, because 1,000 messages of at most MAX_MESSAGE_LENGTH cannot reach 1 MiB.
        val fat = "y".repeat(DiagnosticCodec.MAX_MESSAGE_LENGTH)
        val events = (1..DiagnosticCodec.MAX_EVENTS).map { event(it.toLong(), message = fat) }
        val byteBudget = 4000
        val trimmed = DiagnosticCodec.trim(events, maxEvents = DiagnosticCodec.MAX_EVENTS, maxBytes = byteBudget)

        assertTrue("the byte bound must drop events", trimmed.size < DiagnosticCodec.MAX_EVENTS)
        assertTrue(
            DiagnosticCodec.encode(trimmed).toByteArray(Charsets.UTF_8).size <= byteBudget,
        )
        // The newest event is always retained.
        assertEquals(DiagnosticCodec.MAX_EVENTS.toLong(), trimmed.last().timestampMillis)
    }

    @Test
    fun `a message containing the field separator cannot forge extra fields`() {
        // Sanitised on write, so a caller that forgets still cannot corrupt the log.
        val sanitized = DiagnosticCodec.sanitize("a${DiagnosticCodec.FIELD_SEPARATOR}b")
        val encoded = DiagnosticCodec.encode(listOf(event(1L, message = sanitized)))
        val decoded = DiagnosticCodec.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(sanitized, decoded[0].message)
    }
}
