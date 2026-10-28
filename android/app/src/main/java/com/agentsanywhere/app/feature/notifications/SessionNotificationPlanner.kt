package com.agentsanywhere.app.feature.notifications

import com.agentsanywhere.app.api.RemoteDashboardSnapshot
import com.agentsanywhere.app.api.RemoteSession

/** How much the user wants to be told about finished turns. */
object SessionNotificationMode {
    const val OFF = "off"
    const val CURRENT_SESSION = "current"
    const val ALL_SESSIONS = "all"

    fun normalize(value: String?): String = when (value) {
        OFF -> OFF
        ALL_SESSIONS -> ALL_SESSIONS
        else -> CURRENT_SESSION
    }
}

/**
 * Decides which dashboard transitions deserve a system notification.
 *
 * The dashboard snapshot carries every session in the workspace. A session only
 * notifies when it stops running while it was running before, which is the moment
 * a turn finished and the reply is ready to read. A session that starts waiting
 * for input or for approval also notifies, because the turn cannot continue
 * until someone answers, even if it never passed through "running" in this
 * snapshot.
 *
 * Modes: "off" posts nothing; "current" posts only for the session the user is
 * viewing, plus waiting-for-input; "all" posts for every session.
 */
internal class SessionNotificationPlanner(
    var mode: String = SessionNotificationMode.CURRENT_SESSION,
    var lastViewedSessionId: String? = null,
) {
    private val runningSessionIds = mutableSetOf<String>()

    var seeded: Boolean = false
        private set

    fun onSnapshot(sessions: List<NotifiableSession>): List<SessionNotificationEvent> {
        val events = mutableListOf<SessionNotificationEvent>()
        if (mode != SessionNotificationMode.OFF) {
            for (session in sessions) {
                if (session.status != RUNNING) {
                    eventFor(session)?.let(events::add)
                }
            }
        }
        runningSessionIds.clear()
        sessions.filter { it.status == RUNNING }.forEach { runningSessionIds.add(it.id) }
        seeded = true
        return events
    }

    /**
     * Record which sessions are already running without notifying. Called on the
     * first snapshot so a session that was already idle does not fire on connect.
     */
    fun seed(sessions: List<NotifiableSession>) {
        runningSessionIds.clear()
        sessions.filter { it.status == RUNNING }.forEach { runningSessionIds.add(it.id) }
        seeded = true
    }

    private fun eventFor(session: NotifiableSession): SessionNotificationEvent? {
        val approvalNeeded = session.status == WAITING_APPROVAL
        val waiting = approvalNeeded || session.status == WAITING
        if (!runningSessionIds.contains(session.id) && !waiting) return null
        val visible = when (mode) {
            SessionNotificationMode.OFF -> false
            SessionNotificationMode.ALL_SESSIONS -> true
            else -> session.id == lastViewedSessionId || waiting
        }
        if (!visible) return null
        return SessionNotificationEvent(
            sessionId = session.id,
            title = session.title?.takeIf { it.isNotBlank() } ?: session.workspaceLabel,
            body = when {
                approvalNeeded -> APPROVAL_BODY
                waiting -> WAITING_BODY
                else -> FINISHED_BODY
            },
            waitingForInput = waiting,
            runtimeName = session.runtimeName,
        )
    }

    companion object {
        private const val RUNNING = "running"
        private const val WAITING = "waiting"
        private const val WAITING_APPROVAL = "waiting_approval"
        private const val FINISHED_BODY = "finished this turn"
        private const val WAITING_BODY = "is waiting for your input"
        private const val APPROVAL_BODY = "needs your approval to continue"
    }
}

/** The session fields the planner needs, kept separate from the wire type. */
data class NotifiableSession(
    val id: String,
    val status: String,
    val title: String?,
    val workspaceLabel: String,
    val runtimeName: String,
)

data class SessionNotificationEvent(
    val sessionId: String,
    val title: String,
    val body: String,
    val waitingForInput: Boolean,
    val runtimeName: String,
)

/**
 * Map the dashboard snapshot to the fields the planner reads. Projects supply the
 * fallback title so a notification for an untitled session still says where it is.
 */
fun List<RemoteSession>.toNotifiableSessions(snapshot: RemoteDashboardSnapshot): List<NotifiableSession> {
    val projectNames = snapshot.projects.associate { it.id to it.name }
    return map { session ->
        NotifiableSession(
            id = session.id,
            status = session.status,
            title = session.title,
            workspaceLabel = projectNames[session.projectId]?.takeIf { it.isNotBlank() } ?: "Session",
            runtimeName = session.runtimeName,
        )
    }
}
