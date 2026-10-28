package com.agentsanywhere.app.feature.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.agentsanywhere.app.MainActivity
import com.agentsanywhere.app.R

/**
 * Posts one system notification per finished turn.
 *
 * A session keeps a single notification id, so a second finished turn in the same
 * session replaces the first instead of stacking. Opening the session clears its
 * mute, so it notifies again on the next finished turn.
 */
class SessionNotificationCenter(private val context: Context) {
    private val mutedSessionIds = mutableSetOf<String>()

    fun post(event: SessionNotificationEvent) {
        if (mutedSessionIds.contains(event.sessionId)) return
        if (!canNotify()) return
        ensureChannel()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val title = if (event.runtimeName.isBlank()) event.title else "${event.runtimeName} · ${event.title}"
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationIdFor(event.sessionId),
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_SESSION
                putExtra(EXTRA_SESSION_ID, event.sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(event.body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(
                if (event.waitingForInput) NotificationCompat.CATEGORY_REMINDER
                else NotificationCompat.CATEGORY_MESSAGE,
            )
            .setPriority(
                if (event.waitingForInput) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
        manager.notify(notificationIdFor(event.sessionId), builder.build())
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (channelReady) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notifications_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notifications_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        channelReady = true
    }

    /** Opening a session makes it eligible to notify again. */
    fun unmute(sessionId: String) {
        if (!mutedSessionIds.remove(sessionId)) return
        SessionNotificationSettings.removeMutedSession(context, sessionId)
    }

    fun loadMutedSessions() {
        mutedSessionIds.addAll(SessionNotificationSettings.mutedSessions(context))
    }

    companion object {
        const val ACTION_OPEN_SESSION = "com.agentsanywhere.app.notifications.OPEN_SESSION"
        const val EXTRA_SESSION_ID = "sessionId"
        private const val CHANNEL_ID = "session_activity"
        private const val ID_BASE = 3000

        @Volatile
        private var channelReady = false

        private fun notificationIdFor(sessionId: String): Int =
            ID_BASE + (sessionId.hashCode() % 1000 + 1000) % 1000
    }
}
