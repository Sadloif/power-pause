package com.example.shutdownprotection.data

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Disk-backed regressions for the repository's safety-critical DataStore representation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreRepositorySafetyTest {

    @Test
    fun original_datastore_v1_fixture_remains_readable_and_ambiguous_across_reopen() = runTest {
        val legacyBytes = javaClass.getResourceAsStream(
            "/com/example/shutdownprotection/data/legacy-datastore-1.0.0.preferences_pb",
        )!!.use { it.readBytes() }
        assertEquals(364, legacyBytes.size)
        assertEquals(
            "0CBDA99BBD45A11158A28B15CECBB7F8D5E15B0BE44E6F85CFA2F787CA354E7A",
            MessageDigest.getInstance("SHA-256").digest(legacyBytes)
                .joinToString("") { "%02X".format(it.toInt() and 0xFF) },
        )

        withFixture(initialFileBytes = legacyBytes) {
            val bytesBeforeRead = persistedBytes()
            val settings = ProtectionSettings(
                enabled = true,
                startMinuteOfDay = 120,
                endMinuteOfDay = 300,
                revision = 7L,
                allowedPackages = setOf(APP_ID),
                recoveryRequired = true,
            )
            val originalBaseline = PolicyBaseline(
                capturedAtEpochMillis = 42L,
                lockTaskPackages = setOf("com.example.original"),
                lockTaskFeatures = 16,
                applicationId = APP_ID,
            )

            assertEquals(SettingsReadResult.Success(settings), repository.readSettings())
            assertEquals(originalBaseline, repository.readBaseline())
            assertArrayEquals("read-only decoding must not rewrite the original 1.0.0 bytes", bytesBeforeRead, persistedBytes())
            val decodedKeys = snapshot()
            assertFalse("legacy fixture predates provenance", decodedKeys.containsKey(SettingsKeys.BASELINE_PROVENANCE.name))
            assertFalse("legacy fixture predates lifecycle", decodedKeys.containsKey(SettingsKeys.BASELINE_LIFECYCLE.name))

            val preparation = repository.prepareSession(
                baseline(capturedAt = 43L, packages = setOf("com.example.new"), features = 9),
                APP_ID,
            )
            assertTrue("ambiguous deployed baseline must not be overwritten", preparation is SessionPreparationResult.InvalidStoredBaseline)
            val afterRefusal = snapshot()
            assertEquals(true, afterRefusal[SettingsKeys.ENABLED.name])
            assertEquals(true, afterRefusal[SettingsKeys.RECOVERY_REQUIRED.name])
            assertEquals(42L, afterRefusal[SettingsKeys.BASELINE_CAPTURED_AT.name])
            assertEquals(setOf("com.example.original"), afterRefusal[SettingsKeys.BASELINE_PACKAGES.name])
            assertEquals(16, afterRefusal[SettingsKeys.BASELINE_FEATURES.name])
            assertFalse(afterRefusal.containsKey(SettingsKeys.BASELINE_PROVENANCE.name))
            assertFalse(afterRefusal.containsKey(SettingsKeys.BASELINE_LIFECYCLE.name))

            reopen()

            assertEquals("settings must survive a stopped-store reopen", SettingsReadResult.Success(settings), repository.readSettings())
            assertEquals("old baseline values remain available but untrusted", originalBaseline, repository.readBaseline())
            assertEquals(BaselineProvenance.LEGACY_UNVERIFIED, repository.readBaseline()?.provenance)
            assertEquals(BaselineLifecycleState.LEGACY_UNKNOWN, repository.readBaseline()?.lifecycle)
            val reopenedKeys = snapshot()
            assertFalse(reopenedKeys.containsKey(SettingsKeys.BASELINE_PROVENANCE.name))
            assertFalse(reopenedKeys.containsKey(SettingsKeys.BASELINE_LIFECYCLE.name))
        }
    }

    @Test
    fun `prepared cleanup and temporary session commits survive stopped store reopen`() = runTest {
        withFixture {
            val firstOriginal = baseline(capturedAt = 701L, packages = setOf("external.first"), features = 16)
            val firstPreparation = repository.prepareSession(firstOriginal, APP_ID)
            assertTrue(firstPreparation is SessionPreparationResult.Prepared)
            val preparedKeys = snapshot()
            assertEquals(true, preparedKeys[SettingsKeys.BASELINE_PRESENT.name])
            assertEquals(701L, preparedKeys[SettingsKeys.BASELINE_CAPTURED_AT.name])
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION.name, preparedKeys[SettingsKeys.BASELINE_PROVENANCE.name])
            assertEquals(BaselineLifecycleState.PREPARED.name, preparedKeys[SettingsKeys.BASELINE_LIFECYCLE.name])
            assertEquals(false, preparedKeys[SettingsKeys.ENABLED.name])
            assertEquals(true, preparedKeys[SettingsKeys.RECOVERY_REQUIRED.name])
            assertEquals(1L, preparedKeys[SettingsKeys.REVISION.name])

            reopen()
            assertEquals(
                ProtectionSettings(enabled = false, revision = 1L, recoveryRequired = true),
                (repository.readSettings() as SettingsReadResult.Success).settings,
            )
            assertEquals(
                firstOriginal.copy(
                    provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                    lifecycle = BaselineLifecycleState.PREPARED,
                ),
                repository.readBaseline(),
            )
            val reopenedPreparedKeys = snapshot()
            assertEquals(1L, reopenedPreparedKeys[SettingsKeys.REVISION.name])
            assertEquals(true, reopenedPreparedKeys[SettingsKeys.RECOVERY_REQUIRED.name])

            val cleanup = repository.commitCleanup(
                CleanupCommit(baselineLifecycle = BaselineLifecycle.MARK_RESTORED),
            )
            assertEquals(SettingsWriteResult.Success, cleanup)

            reopen()
            assertEquals(false, (repository.readSettings() as SettingsReadResult.Success).settings.recoveryRequired)
            assertEquals(BaselineLifecycleState.RESTORED, repository.readBaseline()?.lifecycle)
            val restoredKeys = snapshot()
            assertEquals(BaselineLifecycleState.RESTORED.name, restoredKeys[SettingsKeys.BASELINE_LIFECYCLE.name])
            assertEquals(false, restoredKeys[SettingsKeys.RECOVERY_REQUIRED.name])

            val secondOriginal = baseline(capturedAt = 702L, packages = setOf("external.second"), features = 47)
            val marker = TemporaryTestMarker(
                startedAtEpochMillis = 800L,
                releaseAtEpochMillis = 1_100L,
                revision = 999L,
                sessionId = "reopened-temp-session-uuid",
            )
            val secondPreparation = repository.prepareSession(secondOriginal, APP_ID, temporaryMarker = marker)
            assertTrue(secondPreparation is SessionPreparationResult.Prepared)
            val temporaryKeys = snapshot()
            assertEquals(702L, temporaryKeys[SettingsKeys.BASELINE_CAPTURED_AT.name])
            assertEquals(setOf("external.second"), temporaryKeys[SettingsKeys.BASELINE_PACKAGES.name])
            assertEquals(47, temporaryKeys[SettingsKeys.BASELINE_FEATURES.name])
            assertEquals(BaselineLifecycleState.PREPARED.name, temporaryKeys[SettingsKeys.BASELINE_LIFECYCLE.name])
            assertEquals(true, temporaryKeys[SettingsKeys.TEMP_MARKER_PRESENT.name])
            assertEquals(800L, temporaryKeys[SettingsKeys.TEMP_MARKER_STARTED_AT.name])
            assertEquals(1_100L, temporaryKeys[SettingsKeys.TEMP_MARKER_RELEASE_AT.name])
            assertEquals("reopened-temp-session-uuid", temporaryKeys[SettingsKeys.TEMP_MARKER_SESSION_ID.name])

            reopen()
            val reopenedMarker = repository.readTemporaryTestMarker()
            assertEquals(marker.copy(revision = 1L), reopenedMarker)
            assertEquals(
                secondOriginal.copy(
                    provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                    lifecycle = BaselineLifecycleState.PREPARED,
                ),
                repository.readBaseline(),
            )
            val reopenedTemporaryKeys = snapshot()
            assertEquals(true, reopenedTemporaryKeys[SettingsKeys.TEMP_MARKER_PRESENT.name])
            assertEquals(1_100L, reopenedTemporaryKeys[SettingsKeys.TEMP_MARKER_RELEASE_AT.name])
            assertEquals("reopened-temp-session-uuid", reopenedTemporaryKeys[SettingsKeys.TEMP_MARKER_SESSION_ID.name])
            assertEquals(false, (repository.readSettings() as SettingsReadResult.Success).settings.recoveryRequired)
        }
    }

    @Test
    fun `old baseline without provenance remains explicitly ambiguous`() = runTest {
        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.BASELINE_PRESENT] = true
                prefs[SettingsKeys.BASELINE_CAPTURED_AT] = 12L
                prefs[SettingsKeys.BASELINE_PACKAGES] = setOf("com.example.original")
                prefs[SettingsKeys.BASELINE_FEATURES] = 9
                prefs[SettingsKeys.BASELINE_APP_ID] = APP_ID
                // This intentionally models an authentic pre-provenance row: neither new key exists.
            }
            val storedBeforeRead = snapshot()
            assertFalse(storedBeforeRead.containsKey(SettingsKeys.BASELINE_PROVENANCE.name))
            assertFalse(storedBeforeRead.containsKey(SettingsKeys.BASELINE_LIFECYCLE.name))

            val decoded = repository.readBaseline()

            assertEquals(BaselineProvenance.LEGACY_UNVERIFIED, decoded?.provenance)
            assertEquals(BaselineLifecycleState.LEGACY_UNKNOWN, decoded?.lifecycle)
            assertEquals(9, decoded?.lockTaskFeatures)
            assertEquals(setOf("com.example.original"), decoded?.lockTaskPackages)
            assertEquals("decoding legacy state must not invent persisted trust", storedBeforeRead, snapshot())
        }
    }

    @Test
    fun `daily preparation atomically persists trusted baseline and recovery journal`() = runTest {
        withFixture {
            val candidate = baseline(capturedAt = 101L, packages = setOf("com.example.original"), features = 9)

            val result = repository.prepareSession(candidate, APP_ID)

            assertTrue(result is SessionPreparationResult.Prepared)
            val prepared = result as SessionPreparationResult.Prepared
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION, prepared.baseline.provenance)
            assertEquals(BaselineLifecycleState.PREPARED, prepared.baseline.lifecycle)
            val stored = snapshot()
            assertEquals(true, stored[SettingsKeys.BASELINE_PRESENT.name])
            assertEquals(101L, stored[SettingsKeys.BASELINE_CAPTURED_AT.name])
            assertEquals(setOf("com.example.original"), stored[SettingsKeys.BASELINE_PACKAGES.name])
            assertEquals(9, stored[SettingsKeys.BASELINE_FEATURES.name])
            assertEquals(APP_ID, stored[SettingsKeys.BASELINE_APP_ID.name])
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION.name, stored[SettingsKeys.BASELINE_PROVENANCE.name])
            assertEquals(BaselineLifecycleState.PREPARED.name, stored[SettingsKeys.BASELINE_LIFECYCLE.name])
            assertEquals(false, stored[SettingsKeys.ENABLED.name])
            assertEquals(true, stored[SettingsKeys.RECOVERY_REQUIRED.name])
            assertEquals(1L, stored[SettingsKeys.REVISION.name])
        }
    }

    @Test
    fun `temporary preparation commits baseline marker and session UUID together`() = runTest {
        withFixture {
            val candidate = baseline(capturedAt = 202L, packages = emptySet(), features = 5)
            val marker = TemporaryTestMarker(
                startedAtEpochMillis = 300L,
                releaseAtEpochMillis = 900L,
                revision = 999L,
                sessionId = "4bbf34a7-75f0-46b9-9b12-fdb92776a130",
            )

            val result = repository.prepareSession(candidate, APP_ID, temporaryMarker = marker)

            assertTrue(result is SessionPreparationResult.Prepared)
            val stored = snapshot()
            assertEquals(true, stored[SettingsKeys.BASELINE_PRESENT.name])
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION.name, stored[SettingsKeys.BASELINE_PROVENANCE.name])
            assertEquals(BaselineLifecycleState.PREPARED.name, stored[SettingsKeys.BASELINE_LIFECYCLE.name])
            assertEquals(true, stored[SettingsKeys.TEMP_MARKER_PRESENT.name])
            assertEquals(300L, stored[SettingsKeys.TEMP_MARKER_STARTED_AT.name])
            assertEquals(900L, stored[SettingsKeys.TEMP_MARKER_RELEASE_AT.name])
            assertEquals(0L, stored[SettingsKeys.TEMP_MARKER_REVISION.name])
            assertEquals(marker.sessionId, stored[SettingsKeys.TEMP_MARKER_SESSION_ID.name])
            assertFalse("a temporary marker is its own durable recovery context", stored[SettingsKeys.RECOVERY_REQUIRED.name] == true)
            assertEquals(marker.sessionId, repository.readTemporaryTestMarker()?.sessionId)
        }
    }

    @Test
    fun `failed and cancelled preparation transactions leave every staged field uncommitted`() = runTest {
        withFixture {
            val candidate = baseline(capturedAt = 303L, packages = setOf("com.example.original"), features = 11)
            val marker = TemporaryTestMarker(400L, 800L, 0L, "session-atomicity")
            val before = snapshot()

            dataStore.failNextUpdateAfterTransform(IOException("simulated file write failure"))
            val failed = repository.prepareSession(candidate, APP_ID, temporaryMarker = marker)

            assertTrue(failed is SessionPreparationResult.Failed)
            assertTrue((failed as SessionPreparationResult.Failed).cause is IOException)
            assertEquals(1, dataStore.transformedUpdateCount.get())
            val stagedFailure = assertNotNullAndReturn(dataStore.lastStagedSnapshot)
            assertEquals(true, stagedFailure[SettingsKeys.BASELINE_PRESENT.name])
            assertEquals(true, stagedFailure[SettingsKeys.TEMP_MARKER_PRESENT.name])
            assertEquals(marker.sessionId, stagedFailure[SettingsKeys.TEMP_MARKER_SESSION_ID.name])
            assertEquals(false, stagedFailure[SettingsKeys.ENABLED.name])
            assertEquals("disk state must remain unchanged after the transform was staged", before, snapshot())

            dataStore.failNextUpdateAfterTransform(CancellationException("simulated transaction cancellation"))
            val cancellation = captureCancellation {
                repository.prepareSession(candidate, APP_ID, temporaryMarker = marker)
            }

            assertNotNull("cancellation must escape the repository", cancellation)
            assertEquals("the injected cancellation must remain identifiable", "simulated transaction cancellation", cancellation?.message)
            assertEquals(2, dataStore.transformedUpdateCount.get())
            val stagedCancellation = assertNotNullAndReturn(dataStore.lastStagedSnapshot)
            assertEquals(true, stagedCancellation[SettingsKeys.BASELINE_PRESENT.name])
            assertEquals(marker.sessionId, stagedCancellation[SettingsKeys.TEMP_MARKER_SESSION_ID.name])
            assertEquals("cancelled staged state must not reach the preference file", before, snapshot())
            assertNull(repository.readBaseline())
            assertNull(repository.readTemporaryTestMarker())
        }
    }

    @Test
    fun `prepared baseline is reused while legacy provenance cannot be overwritten`() = runTest {
        withFixture {
            val original = baseline(capturedAt = 41L, packages = setOf("com.example.original"), features = 7)
            val candidate = baseline(capturedAt = 42L, packages = setOf("com.example.changed"), features = 13)
            assertTrue(repository.prepareSession(original, APP_ID) is SessionPreparationResult.Prepared)
            val originalFields = baselineFields(snapshot())

            val reused = repository.prepareSession(candidate, APP_ID)

            assertTrue("expected trusted PREPARED baseline reuse, got $reused", reused is SessionPreparationResult.ReusedExisting)
            assertEquals(originalFields, baselineFields(snapshot()))
            assertEquals(
                original.copy(
                    provenance = BaselineProvenance.CAPTURED_BEFORE_PREPARATION,
                    lifecycle = BaselineLifecycleState.PREPARED,
                ),
                (reused as SessionPreparationResult.ReusedExisting).baseline,
            )
        }

        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.BASELINE_PRESENT] = true
                prefs[SettingsKeys.BASELINE_CAPTURED_AT] = 51L
                prefs[SettingsKeys.BASELINE_PACKAGES] = setOf("com.example.legacy")
                prefs[SettingsKeys.BASELINE_FEATURES] = 3
                prefs[SettingsKeys.BASELINE_APP_ID] = APP_ID
                // Provenance is missing on disk while a PREPARED lifecycle is present.
                prefs[SettingsKeys.BASELINE_LIFECYCLE] = BaselineLifecycleState.PREPARED.name
            }
            val legacy = repository.readBaseline()
            assertEquals(BaselineProvenance.LEGACY_UNVERIFIED, legacy?.provenance)
            assertEquals(BaselineLifecycleState.PREPARED, legacy?.lifecycle)
            val before = snapshot()

            val result = repository.prepareSession(
                baseline(capturedAt = 52L, packages = setOf("com.example.new"), features = 6),
                APP_ID,
            )

            assertTrue("an ambiguous prepared row must refuse replacement", result is SessionPreparationResult.InvalidStoredBaseline)
            assertEquals("refusal must leave every durable preference unchanged", before, snapshot())
            assertEquals(legacy, repository.readBaseline())
        }
    }

    @Test
    fun `verified cleanup marks retained baseline restored and next preparation captures current truth`() = runTest {
        withFixture {
            val firstTruth = baseline(capturedAt = 61L, packages = setOf("com.example.first"), features = 6)
            val prepared = repository.prepareSession(firstTruth, APP_ID)
            assertTrue(prepared is SessionPreparationResult.Prepared)

            val cleanup = repository.commitCleanup(
                CleanupCommit(baselineLifecycle = BaselineLifecycle.MARK_RESTORED),
            )

            assertTrue("verified cleanup transaction failed: $cleanup", cleanup is SettingsWriteResult.Success)
            val restored = repository.readBaseline()
            assertEquals(firstTruth.capturedAtEpochMillis, restored?.capturedAtEpochMillis)
            assertEquals(firstTruth.lockTaskPackages, restored?.lockTaskPackages)
            assertEquals(firstTruth.lockTaskFeatures, restored?.lockTaskFeatures)
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION, restored?.provenance)
            assertEquals(BaselineLifecycleState.RESTORED, restored?.lifecycle)
            assertEquals(BaselineLifecycleState.RESTORED.name, snapshot()[SettingsKeys.BASELINE_LIFECYCLE.name])
            assertEquals(false, snapshot()[SettingsKeys.RECOVERY_REQUIRED.name])

            val currentTruth = baseline(capturedAt = 62L, packages = setOf("com.example.current"), features = 15)
            val next = repository.prepareSession(currentTruth, APP_ID)

            assertTrue("a restored old baseline must be replaced by a fresh capture", next is SessionPreparationResult.Prepared)
            val persisted = repository.readBaseline()
            assertEquals(62L, persisted?.capturedAtEpochMillis)
            assertEquals(setOf("com.example.current"), persisted?.lockTaskPackages)
            assertEquals(15, persisted?.lockTaskFeatures)
            assertEquals(BaselineProvenance.CAPTURED_BEFORE_PREPARATION, persisted?.provenance)
            assertEquals(BaselineLifecycleState.PREPARED, persisted?.lifecycle)
            val stored = snapshot()
            assertEquals(62L, stored[SettingsKeys.BASELINE_CAPTURED_AT.name])
            assertEquals(setOf("com.example.current"), stored[SettingsKeys.BASELINE_PACKAGES.name])
            assertEquals(15, stored[SettingsKeys.BASELINE_FEATURES.name])
            assertEquals(BaselineLifecycleState.PREPARED.name, stored[SettingsKeys.BASELINE_LIFECYCLE.name])
        }
    }

    @Test
    fun `baseline presence and every required field must agree`() = runTest {
        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.BASELINE_PRESENT] = true
                prefs[SettingsKeys.BASELINE_CAPTURED_AT] = 71L
                prefs[SettingsKeys.BASELINE_PACKAGES] = emptySet()
                // BASELINE_FEATURES is mandatory and deliberately absent.
                prefs[SettingsKeys.BASELINE_APP_ID] = APP_ID
            }
            val failure = captureFailure { repository.readBaseline() }
            assertTrue("presence with missing features is corruption, not a zero-valued baseline", failure is IllegalStateException)
            assertTrue(snapshot().containsKey(SettingsKeys.BASELINE_PRESENT.name))
            assertFalse(snapshot().containsKey(SettingsKeys.BASELINE_FEATURES.name))
        }

        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.BASELINE_PACKAGES] = setOf("orphan.package")
            }
            val before = snapshot()
            val absentMarkerFailure = captureFailure { repository.readBaseline() }
            assertTrue("orphan baseline keys without a marker must fail", absentMarkerFailure is IllegalStateException)
            assertEquals(before, snapshot())
        }

        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.BASELINE_PRESENT] = false
                prefs[SettingsKeys.BASELINE_FEATURES] = 3
            }
            val before = snapshot()
            val falseMarkerFailure = captureFailure { repository.readBaseline() }
            assertTrue("a false marker with orphan baseline keys must fail", falseMarkerFailure is IllegalStateException)
            assertEquals(before, snapshot())
        }
    }

    @Test
    fun `temporary marker and recovery incident reject incomplete or orphan groups`() = runTest {
        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.TEMP_MARKER_PRESENT] = true
                prefs[SettingsKeys.TEMP_MARKER_STARTED_AT] = 81L
                // The release boundary is mandatory; session identity is optional for legacy markers.
                prefs[SettingsKeys.TEMP_MARKER_REVISION] = 4L
            }
            val missingMarkerField = captureFailure { repository.readTemporaryTestMarker() }
            assertTrue(missingMarkerField is IllegalStateException)
        }

        withFixture {
            rawStore.edit { prefs -> prefs[SettingsKeys.TEMP_MARKER_SESSION_ID] = "orphan-session" }
            val before = snapshot()
            val orphanMarkerField = captureFailure { repository.readTemporaryTestMarker() }
            assertTrue("orphan temp-marker identity must not become a no-test result", orphanMarkerField is IllegalStateException)
            assertEquals(before, snapshot())
        }

        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.TEMP_MARKER_PRESENT] = false
                prefs[SettingsKeys.TEMP_MARKER_RELEASE_AT] = 82L
            }
            val falseMarkerOrphan = captureFailure { repository.readTemporaryTestMarker() }
            assertTrue("a false temp marker with leftover fields must fail", falseMarkerOrphan is IllegalStateException)
        }

        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.INCIDENT_PRESENT] = true
                prefs[SettingsKeys.INCIDENT_ID] = "incomplete-incident"
                prefs[SettingsKeys.INCIDENT_OPENED_AT] = 91L
                // The attempt count and unresolved bit are mandatory.
            }
            val missingIncidentField = captureFailure { repository.readRecoveryIncident() }
            assertTrue(missingIncidentField is IllegalStateException)
        }

        withFixture {
            rawStore.edit { prefs -> prefs[SettingsKeys.INCIDENT_ATTEMPTS] = 1 }
            val before = snapshot()
            val orphanIncidentField = captureFailure { repository.readRecoveryIncident() }
            assertTrue("orphan incident data must not be treated as no pending recovery", orphanIncidentField is IllegalStateException)
            assertEquals(before, snapshot())
        }

        withFixture {
            rawStore.edit { prefs ->
                prefs[SettingsKeys.INCIDENT_PRESENT] = false
                prefs[SettingsKeys.INCIDENT_ID] = "orphan-incident"
            }
            val falseIncidentOrphan = captureFailure { repository.readRecoveryIncident() }
            assertTrue("a false incident marker with leftover fields must fail", falseIncidentOrphan is IllegalStateException)
        }
    }

    @Test
    fun `store IO errors and cancellation never collapse into safe-looking defaults`() = runTest {
        withFixture {
            val ioFailure = IOException("simulated settings read failure")
            dataStore.readFailure = ioFailure

            val settingsRead = repository.readSettings()
            assertTrue("settings IO must be an explicit corrupt result", settingsRead is SettingsReadResult.Corrupt)
            assertSame(ioFailure, (settingsRead as SettingsReadResult.Corrupt).cause)
            assertSame("settingsFlow must not emit defaults after a failed read", ioFailure, captureFailure { repository.settingsFlow.first() })
            assertTrue("readBaseline must not turn IO failure into null baseline", captureFailure { repository.readBaseline() } === ioFailure)
            assertTrue("readScheduleReceipt must not turn IO failure into null receipt", captureFailure { repository.readScheduleReceipt() } === ioFailure)
            assertTrue("readTemporaryTestMarker must not turn IO failure into null marker", captureFailure { repository.readTemporaryTestMarker() } === ioFailure)
            assertTrue("readRecoveryIncident must not turn IO failure into null incident", captureFailure { repository.readRecoveryIncident() } === ioFailure)
            assertTrue("readBootGeneration must not turn IO failure into generation zero", captureFailure { repository.readBootGeneration() } === ioFailure)
            assertTrue("corruption-state read must propagate IO", captureFailure { repository.isCorruptionFlagged() } === ioFailure)
            assertTrue("owner-history read must propagate IO", captureFailure { repository.isOwnerEstablished() } === ioFailure)

            val before = snapshot()
            dataStore.readFailure = null
            dataStore.failNextUpdateAfterTransform(ioFailure)
            val failedWrite = repository.writeSettings(ProtectionSettings(enabled = true, allowedPackages = setOf(APP_ID)))
            assertTrue(failedWrite is SettingsWriteResult.Failure)
            val writeCause = (failedWrite as SettingsWriteResult.Failure).cause
            assertTrue("write failure must preserve the injected I/O type and cause: $writeCause", writeCause is IOException)
            assertEquals("write failure must preserve the injected I/O message", ioFailure.message, writeCause.message)
            assertEquals("failed write must not leave a partial settings snapshot", before, snapshot())

            val cancelledReadCause = IOException("original read cancellation cause")
            val cancelledRead = CancellationException("simulated read cancellation").apply {
                initCause(cancelledReadCause)
            }
            dataStore.readFailure = cancelledRead
            assertCancellationPreserved(
                cancelledRead,
                cancelledReadCause,
                captureCancellation { repository.readSettings() },
                "readSettings cancellation",
            )
            assertCancellationPreserved(
                cancelledRead,
                cancelledReadCause,
                captureCancellation { repository.settingsFlow.first() },
                "settingsFlow cancellation",
            )
            assertCancellationPreserved(
                cancelledRead,
                cancelledReadCause,
                captureCancellation { repository.readBaseline() },
                "readBaseline cancellation",
            )
            assertCancellationPreserved(
                cancelledRead,
                cancelledReadCause,
                captureCancellation { repository.readRecoveryIncident() },
                "readRecoveryIncident cancellation",
            )

            val beforeCancelledWrite = snapshot()
            val cancelledWriteCause = IOException("original write cancellation cause")
            val cancelledWrite = CancellationException("simulated write cancellation").apply {
                initCause(cancelledWriteCause)
            }
            dataStore.failNextUpdateAfterTransform(cancelledWrite)
            assertCancellationPreserved(
                cancelledWrite,
                cancelledWriteCause,
                captureCancellation { repository.writeSettings(ProtectionSettings(enabled = true)) },
                "writeSettings cancellation",
            )
            assertEquals("cancelled write must leave persisted preferences unchanged", beforeCancelledWrite, snapshot())
        }
    }

    private fun baseline(
        capturedAt: Long,
        packages: Set<String>,
        features: Int,
    ) = PolicyBaseline(
        capturedAtEpochMillis = capturedAt,
        lockTaskPackages = packages,
        lockTaskFeatures = features,
        applicationId = APP_ID,
    )

    private class Fixture(initialFileBytes: ByteArray? = null) {
        private val directory = Files.createTempDirectory("spm-datastore-safety-").toFile()
        private val file = File(directory, "settings.preferences_pb")
        private var storeJob = SupervisorJob()
        private var storeScope = CoroutineScope(storeJob + Dispatchers.IO)
        lateinit var rawStore: DataStore<Preferences>
            private set
        lateinit var dataStore: FaultInjectingDataStore
            private set
        lateinit var repository: DataStoreSettingsRepository
            private set

        init {
            initialFileBytes?.let(file::writeBytes)
            startStore()
        }

        private fun startStore() {
            rawStore = PreferenceDataStoreFactory.create(
                scope = storeScope,
                produceFile = { file },
            )
            dataStore = FaultInjectingDataStore(rawStore)
            repository = DataStoreSettingsRepository(dataStore)
        }

        suspend fun reopen() {
            storeJob.cancelAndJoin()
            storeJob = SupervisorJob()
            storeScope = CoroutineScope(storeJob + Dispatchers.IO)
            startStore()
        }

        fun persistedBytes(): ByteArray = file.readBytes()

        suspend fun snapshot(): Map<String, Any> = rawStore.data.first().asMap()
            .entries
            .associate { (key, value) -> key.name to value }

        suspend fun close() {
            storeJob.cancelAndJoin()
            val target = directory.canonicalFile
            val canonicalTemp = File(System.getProperty("java.io.tmpdir")).canonicalFile
            val canonicalParent = target.parentFile?.canonicalPath
            require(canonicalParent != null && canonicalParent.equals(canonicalTemp.canonicalPath, ignoreCase = true)) {
                "refusing recursive cleanup outside the direct temporary-directory child: $target"
            }
            require(target.name.startsWith("spm-datastore-safety-")) {
                "refusing recursive cleanup for an unexpected directory: $target"
            }
            target.deleteRecursively()
        }
    }

    /** A test-only decorator; mutations still pass through real PreferenceDataStoreFactory storage. */
    private class FaultInjectingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        @Volatile
        var readFailure: Throwable? = null

        @Volatile
        private var nextTransactionFailure: Throwable? = null

        val transformedUpdateCount = AtomicInteger(0)

        @Volatile
        var lastStagedSnapshot: Map<String, Any>? = null
            private set

        override val data: Flow<Preferences>
            get() = flow {
                readFailure?.let { throw it }
                emitAll(delegate.data)
            }

        fun failNextUpdateAfterTransform(failure: Throwable) {
            nextTransactionFailure = failure
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val injected = nextTransactionFailure
            if (injected == null) return delegate.updateData(transform)
            nextTransactionFailure = null
            return delegate.updateData { current ->
                val staged = transform(current)
                transformedUpdateCount.incrementAndGet()
                lastStagedSnapshot = staged.asMap().entries.associate { (key, value) -> key.name to value }
                throw injected
            }
        }
    }

    private suspend fun <T> withFixture(
        initialFileBytes: ByteArray? = null,
        block: suspend Fixture.() -> T,
    ): T {
        assertEquals("the Android atomic-move API branch must be active", 36, Build.VERSION.SDK_INT)
        val fixture = Fixture(initialFileBytes)
        try {
            return fixture.block()
        } finally {
            fixture.close()
        }
    }

    private suspend fun captureFailure(block: suspend () -> Any?): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }

    private suspend fun <T> captureCancellation(block: suspend () -> T): CancellationException? = supervisorScope {
        val operation = async { block() }
        try {
            operation.await()
            null
        } catch (cancellation: CancellationException) {
            cancellation
        }
    }

    private fun assertCancellationPreserved(
        expected: CancellationException,
        originalCause: Throwable,
        actual: CancellationException?,
        operation: String,
    ) {
        assertNotNull("$operation must propagate cancellation", actual)
        val propagated = actual!!
        assertEquals("$operation must retain cancellation type", expected::class.java, propagated::class.java)
        assertEquals("$operation must retain cancellation message", expected.message, propagated.message)
        assertSame("$operation fixture must carry the original cause", originalCause, expected.cause)
        val preservedOriginalCause = generateSequence(propagated as Throwable) { it.cause }
            .any { it === originalCause }
        assertTrue("$operation must retain the original cause in its cause chain", preservedOriginalCause)
    }

    private fun assertNotNullAndReturn(value: Map<String, Any>?): Map<String, Any> {
        assertNotNull(value)
        return value!!
    }

    private fun baselineFields(snapshot: Map<String, Any>): Map<String, Any> = snapshot.filterKeys {
        it.startsWith("baseline_")
    }

    private companion object {
        const val APP_ID = "com.example.shutdownprotection"
    }
}
