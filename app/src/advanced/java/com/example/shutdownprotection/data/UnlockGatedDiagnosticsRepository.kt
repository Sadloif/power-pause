package com.example.shutdownprotection.data

/**
 * Keeps credential-protected diagnostics off the pre-unlock path.
 *
 * Brief section 7.8 is explicit: "Do not access a credential-protected DataStore before
 * unlock." The diagnostics store *is* credential-protected by design - it is not needed to make
 * a protection decision - but the `LOCKED_BOOT_COMPLETED` path still reconciles, and that
 * reconcile writes a runtime observation. Without this gate, Direct Boot would touch
 * credential-protected storage on every boot.
 *
 * Gating in one decorator rather than at each call site means every caller - the coordinator, the
 * recovery manager, the broadcast receivers, and the ViewModel - is covered, and a future caller
 * cannot reintroduce the problem by forgetting a check.
 *
 * While the device is confirmed locked, writes are skipped and reads report that diagnostics are
 * unavailable. If the unlock-state read itself fails, reads throw so the UI can show unknown
 * instead of claiming that the store is empty.
 *
 * The consequence is honest and deliberate: **diagnostics from before the first unlock are not
 * retained.** They are not required by the brief, and losing them is strictly better than
 * depending on storage the Direct Boot path is forbidden to touch.
 */
class UnlockGatedDiagnosticsRepository(
    private val delegate: DiagnosticsRepository,
    /** Null means the UserManager read failed; it must not be treated as confirmed unlocked. */
    private val isUserUnlocked: () -> Boolean?,
) : DiagnosticsRepository {

    override suspend fun record(kind: String, revision: Long, message: String) {
        if (isUserUnlocked() == true) delegate.record(kind, revision, message)
    }

    override suspend fun readAll(): List<DiagnosticEvent> = when (isUserUnlocked()) {
        true -> delegate.readAll()
        false -> throw IllegalStateException("Credential-protected diagnostics are unavailable before user unlock")
        null -> throw IllegalStateException("User unlock state is unknown; diagnostics were not read")
    }

    override suspend fun clear() {
        when (isUserUnlocked()) {
            true -> delegate.clear()
            false -> throw IllegalStateException("Credential-protected diagnostics are unavailable before user unlock")
            null -> throw IllegalStateException("User unlock state is unknown; diagnostics were not cleared")
        }
    }

    override suspend fun exportText(): String = when (isUserUnlocked()) {
        true -> delegate.exportText()
        false -> throw IllegalStateException("Credential-protected diagnostics are unavailable before user unlock")
        null -> throw IllegalStateException("User unlock state is unknown; diagnostics were not exported")
    }

    override suspend fun readObservation(): RuntimeObservation? = when (isUserUnlocked()) {
        true -> delegate.readObservation()
        false -> null
        null -> throw IllegalStateException("User unlock state is unknown; diagnostics were not read")
    }

    override suspend fun writeObservation(observation: RuntimeObservation) {
        if (isUserUnlocked() == true) delegate.writeObservation(observation)
    }
}
