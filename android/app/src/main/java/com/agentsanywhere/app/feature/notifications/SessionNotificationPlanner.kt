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
 * The dashboard snapshot carries every session in the workspace and repeats it
 * whenever any session changes, so a notification is keyed on the session's
 * status *edge*, not on the status itself. A session announces each transition
 * once:
 *
 * - it stops running while it was running before: the turn finished;
 * - it starts waiting for input: the turn cannot continue until someone replies.
 *
 * The two waits are distinct event types: "waiting" is the turn asking a
 * question, and "waiting_approval" is the turn asking whether a tool call may
 * run. Each has its own body text and each fires once, on the edge into it.
 *
 * Modes: "off" posts nothing; "current" posts only for the session the user is
 * viewing; "all" posts for every session.
 */
internal class SessionNotificationPlanner(
    var mode: String = SessionNotificationMode.CURRENT_SESSION,
    var lastViewedSessionId: String? = null,
) {
    /** Last known status of every session in the last snapshot. */
    private val lastStatusById = mutableMapOf<String, String>()

    var seeded: Boolean = false
        private set

    fun onSnapshot(sessions: List<NotifiableSession>): List<SessionNotificationEvent> {
        val events = mutableListOf<SessionNotificationEvent>()
        if (mode != SessionNotificationMode.OFF) {
            for (session in sessions) {
                eventFor(session)?.let(events::add)
            }
        }
        forget(sessions)
        sessions.forEach { lastStatusById[it.id] = it.status }
        seeded = true
        return events
    }

    /**
     * Record the current statuses without notifying. Called on the first snapshot
     * so a session that is already idle or waiting does not fire on connect.
     */
    fun seed(sessions: List<NotifiableSession>) {
        forget(sessions)
        sessions.forEach { lastStatusById[it.id] = it.status }
        seeded = true
    }

    /** Drop sessions that left the snapshot so a reappearing one re-baselines. */
    private fun forget(sessions: List<NotifiableSession>) {
        val present = sessions.mapTo(HashSet()) { it.id }
        lastStatusById.keys.retainAll(present)
    }

    private fun eventFor(session: NotifiableSession): SessionNotificationEvent? {
        val previous = lastStatusById[session.id]
        // One event per status edge. A repeated snapshot keeps the status equal
        // and stays quiet, so a pending wait cannot re-fire while it persists.
        if (previous == session.status) return null
        val visible = when (mode) {
            SessionNotificationMode.OFF -> false
            SessionNotificationMode.ALL_SESSIONS -> true
            else -> session.id == lastViewedSessionId
        }
        if (!visible) return null
        val body = when (session.status) {
            STATUS_WAITING -> WAITING_BODY
            STATUS_WAITING_APPROVAL -> APPROVAL_BODY
            in FINISHED_STATUSES -> FINISHED_BODY.takeIf { previous == STATUS_RUNNING }
            else -> null
        } ?: return null
        return SessionNotificationEvent(
            sessionId = session.id,
            title = session.title?.takeIf { it.isNotBlank() } ?: session.workspaceLabel,
            body = body,
            waitingForInput = session.status == STATUS_WAITING,
            runtimeName = session.runtimeName,
        )
    }

    companion object {
        private const val STATUS_RUNNING = "running"
        private const val STATUS_WAITING = "waiting"
        private const val STATUS_WAITING_APPROVAL = "waiting_approval"

        /** Statuses that end the turn, each announced once when running precedes it. */
        private val FINISHED_STATUSES = setOf("idle", "waiting_approval", "error", "blocked")

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
