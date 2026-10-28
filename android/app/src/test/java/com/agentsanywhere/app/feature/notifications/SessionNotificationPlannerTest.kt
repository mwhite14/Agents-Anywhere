package com.agentsanywhere.app.feature.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationPlannerTest {
    private fun session(
        id: String,
        status: String,
        title: String? = "Fix login",
    ) = NotifiableSession(
        id = id,
        status = status,
        title = title,
        workspaceLabel = "cairn",
        runtimeName = "dsh",
    )

    @Test
    fun firstSnapshotIsBaselineAndPostsNothing() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "idle"), session("b", "running")))
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "idle"), session("b", "running"))))
    }

    @Test
    fun runningToIdlePostsOneEventInAllMode() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running")))
        val events = planner.onSnapshot(listOf(session("a", "idle")))
        assertEquals(1, events.size)
        assertEquals("a", events.single().sessionId)
        assertEquals("Fix login", events.single().title)
        assertTrue(!events.single().waitingForInput)
    }

    @Test
    fun idleSessionStaysQuietAcrossSnapshots() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "idle")))
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "idle"))))
    }

    @Test
    fun currentModeOnlyNotifiesViewedSession() {
        val planner = SessionNotificationPlanner(
            mode = SessionNotificationMode.CURRENT_SESSION,
            lastViewedSessionId = "viewed",
        )
        planner.seed(listOf(session("viewed", "running"), session("other", "running")))
        val events = planner.onSnapshot(listOf(session("viewed", "idle"), session("other", "idle")))
        assertEquals(listOf("viewed"), events.map { it.sessionId })
    }

    @Test
    fun waitingForInputAlwaysNotifiesEvenInCurrentMode() {
        val planner = SessionNotificationPlanner(
            mode = SessionNotificationMode.CURRENT_SESSION,
            lastViewedSessionId = "viewed",
        )
        planner.seed(listOf(session("viewed", "running"), session("approval", "running")))
        val events = planner.onSnapshot(listOf(session("viewed", "running"), session("approval", "waiting")))
        assertEquals(listOf("approval"), events.map { it.sessionId })
        assertTrue(events.single().waitingForInput)
    }

    @Test
    fun runningToWaitingApprovalSaysApprovalNeeded() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("approval", "running")))
        val events = planner.onSnapshot(listOf(session("approval", "waiting_approval")))
        assertEquals(1, events.size)
        assertEquals("needs your approval to continue", events.single().body)
        assertTrue(events.single().waitingForInput)
    }

    @Test
    fun waitingApprovalWithoutPriorRunStillNotifies() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("approval", "idle")))
        val events = planner.onSnapshot(listOf(session("approval", "waiting_approval")))
        assertEquals(1, events.size)
        assertEquals("needs your approval to continue", events.single().body)
        assertTrue(events.single().waitingForInput)
    }

    @Test
    fun waitingWithoutPriorRunStillNotifies() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("approval", "idle")))
        val events = planner.onSnapshot(listOf(session("approval", "waiting")))
        assertEquals(1, events.size)
        assertTrue(events.single().waitingForInput)
    }

    @Test
    fun offModePostsNothing() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.OFF)
        planner.seed(listOf(session("a", "running")))
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "idle"))))
    }

    @Test
    fun untitledSessionFallsBackToWorkspace() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running", title = null)))
        val events = planner.onSnapshot(listOf(session("a", "idle", title = null)))
        assertEquals("cairn", events.single().title)
    }

    @Test
    fun unknownModeNormalizesToCurrent() {
        assertEquals(SessionNotificationMode.CURRENT_SESSION, SessionNotificationMode.normalize("bogus"))
        assertEquals(SessionNotificationMode.OFF, SessionNotificationMode.normalize("off"))
        assertEquals(SessionNotificationMode.ALL_SESSIONS, SessionNotificationMode.normalize("all"))
    }
}
