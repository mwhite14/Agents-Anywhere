package com.agentsanywhere.app.feature.notifications

import android.content.Context

/**
 * The one notification center for the app process. The dashboard listener posts
 * through it and the user's own actions (opening a session) mute through it, so
 * the in-app and background paths share the same mute state.
 */
object SessionNotifications {
    @Volatile
    private var center: SessionNotificationCenter? = null

    fun center(context: Context): SessionNotificationCenter =
        center ?: synchronized(this) {
            center ?: SessionNotificationCenter(context.applicationContext).also { created ->
                created.loadMutedSessions()
                center = created
            }
        }

    /** Opening the session clears its mute so a later turn notifies again. */
    fun onSessionOpened(context: Context, sessionId: String) {
        center(context).unmute(sessionId)
    }

    /** The in-app dashboard listener posts through the same center as the service. */
    fun post(context: Context, event: SessionNotificationEvent) {
        center(context).post(event)
    }
}
