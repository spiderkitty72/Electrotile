package com.electrotile

import android.content.Context

/** Which levels the player has completed, kept in shared preferences. */
class Progress(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun completedLevels(): Set<Int> =
        prefs.getStringSet(KEY_COMPLETED, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    fun markCompleted(level: Int) {
        val updated = completedLevels() + level
        prefs.edit().putStringSet(KEY_COMPLETED, updated.map { it.toString() }.toSet()).apply()
    }

    private companion object {
        const val KEY_COMPLETED = "completed"
    }
}
