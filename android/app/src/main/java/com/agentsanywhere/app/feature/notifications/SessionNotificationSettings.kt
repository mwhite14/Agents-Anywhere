package com.agentsanywhere.app.feature.notifications

import android.content.Context

/**
 * Persisted listener preferences and per-session notification memory.
 *
 * The listener runs in a foreground service, outside any Compose tree, so it reads
 * and writes the same SharedPreferences file the settings drawer uses.
 *
 * Muted sessions are those the user dismissed or opened. They stay quiet until the
 * session runs again, which clears the mute.
 */
object SessionNotificationSettings {
    private const val PREFERENCES_NAME = "ui_preferences"
    private const val KEY_MODE = "session_notifications_mode"
    private const val KEY_REQUESTED = "session_notifications_permission_requested"
    private const val KEY_MUTED = "session_notifications_muted"
    private const val MAX_MUTED = 200

    fun mode(context: Context): String =
        SessionNotificationMode.normalize(prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_MODE, SessionNotificationMode.normalize(mode)).apply()
    }

    fun permissionRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUESTED, false)

    fun markPermissionRequested(context: Context) {
        prefs(context).edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    fun mutedSessions(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MUTED, emptySet()) ?: emptySet()

    fun addMutedSession(context: Context, sessionId: String) {
        val current = mutedSessions(context).toMutableSet()
        current.add(sessionId)
        // Keep the set bounded; drop the oldest insertions first.
        val trimmed = if (current.size > MAX_MUTED) current.toList().takeLast(MAX_MUTED).toSet() else current
        prefs(context).edit().putStringSet(KEY_MUTED, trimmed).apply()
    }

    fun removeMutedSession(context: Context, sessionId: String) {
        val current = mutedSessions(context).toMutableSet()
        if (!current.remove(sessionId)) return
        prefs(context).edit().putStringSet(KEY_MUTED, current).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
