package com.example.shutdownprotection.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class UnlockGatedDiagnosticsRepositoryTest {

    @Test
    fun confirmedLockedStateNeverTouchesCredentialProtectedStore() = runBlocking {
        val delegate = RecordingDiagnosticsRepository()
        val gated = UnlockGatedDiagnosticsRepository(delegate) { false }

        gated.record("before-unlock", 1, "ignored")
        gated.writeObservation(sampleObservation())
        assertThrows(IllegalStateException::class.java) { runBlocking { gated.readAll() } }
        assertNull(gated.readObservation())
        assertThrows(IllegalStateException::class.java) { runBlocking { gated.exportText() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { gated.clear() } }

        assertEquals(0, delegate.calls)
    }

    @Test
    fun unknownUnlockStateNeverReadsCredentialProtectedStoreOrPretendsItIsEmpty() = runBlocking {
        val delegate = RecordingDiagnosticsRepository()
        val gated = UnlockGatedDiagnosticsRepository(delegate) { null }

        gated.record("pre-unlock", 1, "ignored")
        gated.writeObservation(sampleObservation())
        assertThrows(IllegalStateException::class.java) { runBlocking { gated.readAll() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { gated.readObservation() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { gated.exportText() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { gated.clear() } }

        assertEquals("unknown reads must not be fabricated as empty diagnostics", 0, delegate.calls)
    }

    @Test
    fun confirmedUnlockedStateDelegatesEveryOperation() = runBlocking {
        val delegate = RecordingDiagnosticsRepository()
        val gated = UnlockGatedDiagnosticsRepository(delegate) { true }

        gated.record("test", 7, "message")
        gated.writeObservation(sampleObservation())
        assertEquals(listOf(DiagnosticEvent(123L, "test", 7L, "message")), gated.readAll())
        assertEquals(123L, gated.readObservation()?.observedAtEpochMillis)
        assertEquals("export", gated.exportText())
        gated.clear()

        assertEquals(6, delegate.calls)
    }

    private class RecordingDiagnosticsRepository : DiagnosticsRepository {
        var calls = 0
        private val event = DiagnosticEvent(123L, "test", 7L, "message")

        override suspend fun record(kind: String, revision: Long, message: String) { calls++ }
        override suspend fun readAll(): List<DiagnosticEvent> { calls++; return listOf(event) }
        override suspend fun clear() { calls++ }
        override suspend fun exportText(): String { calls++; return "export" }
        override suspend fun readObservation(): RuntimeObservation? { calls++; return sampleObservation() }
        override suspend fun writeObservation(observation: RuntimeObservation) { calls++ }
    }
}

private fun sampleObservation() = RuntimeObservation(
    observedAtEpochMillis = 123L,
    stateName = "Unknown",
    settingsRevision = 7L,
    deviceOwner = null,
    lockTaskState = null,
    requestedFeatures = null,
    effectiveFeatures = null,
    effectivePackages = null,
    exactAlarmCapability = null,
    bootCompleted = null,
    userUnlocked = null,
    nextStartEpochMillis = null,
    nextEndEpochMillis = null,
    fallbackEpochMillis = null,
    lastPolicyResultCode = null,
    lastPolicyObservedAtEpochMillis = null,
    recoveryStatus = "unknown",
)
