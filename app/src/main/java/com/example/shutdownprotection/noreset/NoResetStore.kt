package com.example.shutdownprotection.noreset

import android.content.Context
import android.content.SharedPreferences

class NoResetStore(context: Context) {
    val preferences: SharedPreferences = context.applicationContext.getSharedPreferences("no_reset_schedule", Context.MODE_PRIVATE)

    fun read(): NoResetConfig = try {
        NoResetConfig(preferences.getBoolean("enabled", false), preferences.getInt("start", 120),
            preferences.getInt("end", 300), preferences.getLong("revision", 0))
    } catch (_: ClassCastException) {
        // Invalid durable input never activates either scheduling or a temporary trial.
        NoResetConfig(startMinute = -1)
    }

    fun save(enabled: Boolean, start: Int, end: Int): Boolean {
        val value = NoResetConfig(enabled, start, end)
        if (!value.valid) return false
        return preferences.edit().putBoolean("enabled", enabled).putInt("start", start)
            .putInt("end", end).putLong("revision", read().revision + 1).commit()
    }

    fun disable(): Boolean = preferences.edit().putBoolean("enabled", false)
        .putLong("revision", read().revision + 1).commit()
}
