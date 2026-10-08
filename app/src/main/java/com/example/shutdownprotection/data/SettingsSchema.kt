package com.example.shutdownprotection.data

/**
 * Schema handling for the durable settings store (brief section 17: after an app replacement,
 * "do not assume old alarms, class names, or stored schema are automatically valid. Add migration
 * handling and a test that active state does not silently change during update.").
 *
 * The rule this type exists to enforce: an unknown schema is **never** silently reinterpreted.
 * An older schema is upgraded in one place; a *newer* schema (a downgrade) is a recovery
 * condition, because guessing at state written by a future version is exactly how a stale
 * restrictive configuration would survive unnoticed.
 */
object SettingsSchema {

    sealed interface Result {
        /** The stored schema is the one this build understands. */
        data class Current(val settings: ProtectionSettings) : Result

        /**
         * The stored schema was older and has been upgraded. User intent is preserved exactly:
         * `enabled`, the interval, the revision, and the allowlist are untouched, because none of
         * them changed meaning. Only `schemaVersion` moves.
         */
        data class Upgraded(val settings: ProtectionSettings, val fromVersion: Int) : Result

        /** The stored schema is newer than this build understands. Recovery condition. */
        data class Unsupported(val foundVersion: Int) : Result
    }

    fun resolve(settings: ProtectionSettings): Result = when {
        settings.schemaVersion == ProtectionSettings.SCHEMA_VERSION -> Result.Current(settings)

        settings.schemaVersion < ProtectionSettings.SCHEMA_VERSION ->
            Result.Upgraded(upgrade(settings), settings.schemaVersion)

        else -> Result.Unsupported(settings.schemaVersion)
    }

    /**
     * Upgrades an older schema to the current one.
     *
     * There is only one schema version today, so there is no field transformation to apply yet.
     * The seam exists so a future version has a single migration point, and so the
     * "intent is preserved across an upgrade" rule can be pinned by a test now rather than
     * discovered later.
     */
    private fun upgrade(settings: ProtectionSettings): ProtectionSettings =
        settings.copy(schemaVersion = ProtectionSettings.SCHEMA_VERSION)
}
