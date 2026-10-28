package com.agentsanywhere.app.feature.notifications

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.agentsanywhere.app.feature.auth.AuthSessionReader

/**
 * Keeps the background listener in step with its two inputs: the saved mode and
 * whether the user is signed in.
 *
 * "Current session" mode needs no service: the in-app dashboard listener posts
 * while the app is open. "All sessions" needs the foreground service so finished
 * turns arrive after the user leaves the app. On Android 13+ the notification
 * permission is requested the first time the mode is on, so the prompt follows a
 * choice the user just made.
 */
@Composable
fun SessionNotificationListenerEffect(
    authStore: AuthSessionReader,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val canAsk = context is Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    LaunchedEffect(authStore) {
        val signedIn = authStore.readAccessToken().isNotBlank()
        val mode = SessionNotificationSettings.mode(context)
        if (signedIn && mode == SessionNotificationMode.ALL_SESSIONS) {
            SessionNotificationForegroundService.start(context)
            if (canAsk && !SessionNotificationSettings.permissionRequested(context)) {
                SessionNotificationSettings.markPermissionRequested(context)
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            SessionNotificationForegroundService.stop(context)
        }
    }
}

/**
 * Posts in-app notifications from the dashboard snapshots the main screen already
 * receives. Only "current session" mode uses this path; "all sessions" is served
 * by the foreground service, and running both would double-notify.
 */
@Composable
fun InAppSessionNotificationEffect(
    authStore: AuthSessionReader,
    snapshotSessions: List<NotifiableSession>,
    lastViewedSessionId: String?,
) {
    val context = LocalContext.current
    val planner = remember { SessionNotificationPlanner() }

    LaunchedEffect(snapshotSessions) {
        planner.mode = SessionNotificationSettings.mode(context)
        planner.lastViewedSessionId = lastViewedSessionId
        if (planner.mode != SessionNotificationMode.CURRENT_SESSION) return@LaunchedEffect
        if (snapshotSessions.isEmpty()) return@LaunchedEffect
        if (planner.seeded) {
            planner.onSnapshot(snapshotSessions).forEach { SessionNotifications.post(context, it) }
        } else {
            planner.seed(snapshotSessions)
        }
    }
}
