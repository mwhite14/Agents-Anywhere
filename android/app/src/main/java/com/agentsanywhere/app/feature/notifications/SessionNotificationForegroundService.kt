package com.agentsanywhere.app.feature.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RealtimeApi
import com.agentsanywhere.app.feature.auth.AuthSessionStore
import com.agentsanywhere.app.feature.realtime.DashboardRealtimeController
import com.agentsanywhere.app.feature.realtime.RealtimeClientIdStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps the dashboard event stream open while the app is not in front, so a turn
 * that finishes announces itself.
 *
 * Android only keeps a process alive outside the foreground with a foreground
 * service. This one holds the existing dashboard WebSocket and runs the same
 * snapshot parsing the in-app listener uses; it does not add a second transport.
 */
class SessionNotificationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connection: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startListenerForeground()

        val sessionStore = AuthSessionStore(this)
        val clientId = RealtimeClientIdStore(this).readOrCreate()
        val controller = DashboardRealtimeController(RealtimeApi(), sessionStore, clientId)
        val center = SessionNotifications.center(this)
        val planner = SessionNotificationPlanner(mode = SessionNotificationSettings.mode(this))
        connection = controller.start(
            scope = scope,
            onSnapshot = { snapshot ->
                val sessions = snapshot.sessions.toNotifiableSessions(snapshot)
                if (planner.seeded) {
                    planner.onSnapshot(sessions).forEach { event -> center.post(event) }
                } else {
                    planner.seed(sessions)
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startListenerForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notifications_listener_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notifications_listener_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notifications_listener_title))
            .setContentText(getString(R.string.notifications_listener_body))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "session_notification_listener"
        private const val NOTIFICATION_ID = 2002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SessionNotificationForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionNotificationForegroundService::class.java))
        }
    }
}

