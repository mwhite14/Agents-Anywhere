package com.agentsanywhere.app.feature.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationPlannerTest {
    private fun session(
        id: String,
        status: String,
        title: String? = "Fix login",
        waitKind: SessionWaitingKind? = null,
    ) = NotifiableSession(
        id = id,
        status = status,
        title = title,
        workspaceLabel = "cairn",
        runtimeName = "dsh",
        waitKind = waitKind,
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
    fun waitingForInputAlertsInCurrentModeOnlyForTheViewedSession() {
        val planner = SessionNotificationPlanner(
            mode = SessionNotificationMode.CURRENT_SESSION,
            lastViewedSessionId = "viewed",
        )
        planner.seed(listOf(session("viewed", "running"), session("other", "running")))
        val events = planner.onSnapshot(listOf(session("viewed", "running"), session("other", "waiting")))
        assertEquals(emptyList<SessionNotificationEvent>(), events)
    }

    @Test
    fun pendingInputWaitFiresOnceAcrossRepeatedSnapshots() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running")))
        val first = planner.onSnapshot(listOf(session("a", "waiting")))
        assertEquals(1, first.size)
        assertTrue(first.single().waitingForInput)
        // The dashboard repeats the snapshot whenever any session changes.
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "waiting"))))
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "waiting"))))
        // Answering the question returns the session to running: no event.
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "running"))))
        // A second question in the same turn is a new edge and alerts again.
        assertEquals(1, planner.onSnapshot(listOf(session("a", "waiting"))).size)
    }

    @Test
    fun finishedTurnFiresOnce() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running")))
        assertEquals(1, planner.onSnapshot(listOf(session("a", "idle"))).size)
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "idle"))))
    }

    @Test
    fun waitThatAppearsAfterTheTurnStillFiresOnce() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running")))
        assertEquals(1, planner.onSnapshot(listOf(session("a", "idle"))).size)
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "idle"))))
        val wait = planner.onSnapshot(listOf(session("a", "waiting")))
        assertEquals(1, wait.size)
        assertTrue(wait.single().waitingForInput)
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "waiting"))))
    }

    @Test
    fun seededWaitingSessionDoesNotFireOnConnect() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "waiting")))
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(listOf(session("a", "waiting"))))
    }

    @Test
    fun sessionThatLeavesAndReturnsRebaselines() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "waiting")))
        assertEquals(emptyList<SessionNotificationEvent>(), planner.onSnapshot(emptyList()))
        assertEquals(1, planner.onSnapshot(listOf(session("a", "waiting"))).size)
    }

    @Test
    fun runningToWaitingApprovalSaysApprovalNeeded() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("approval", "running")))
        val events = planner.onSnapshot(listOf(session("approval", "waiting_approval")))
        assertEquals(1, events.size)
        assertEquals("needs your approval to continue", events.single().body)
        assertFalse(events.single().waitingForInput)
    }

    @Test
    fun waitingApprovalWithoutPriorRunStillNotifies() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("approval", "idle")))
        val events = planner.onSnapshot(listOf(session("approval", "waiting_approval")))
        assertEquals(1, events.size)
        assertEquals("needs your approval to continue", events.single().body)
        assertFalse(events.single().waitingForInput)
    }

    @Test
    fun waitingWithoutPriorRunStillNotifies() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("approval", "idle")))
        val events = planner.onSnapshot(listOf(session("approval", "waiting")))
        assertEquals(1, events.size)
        assertTrue(events.single().waitingForInput)
        assertEquals("is waiting for your input", events.single().body)
    }

    @Test
    fun inputWaitSaysInputNotApproval() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running")))
        val events = planner.onSnapshot(listOf(session("a", "waiting")))
        assertEquals("is waiting for your input", events.single().body)
        assertTrue(events.single().waitingForInput)
    }

    @Test
    fun serverReportedInputRequestSaysInputEvenWhenStatusIsWaitingApproval() {
        // DSH publishes a blocking question as "waiting_approval" too.
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running")))
        val events = planner.onSnapshot(
            listOf(session("a", "waiting_approval", waitKind = SessionWaitingKind.INPUT)),
        )
        assertEquals(1, events.size)
        assertEquals("is waiting for your input", events.single().body)
        assertTrue(events.single().waitingForInput)
    }

    @Test
    fun serverReportedApprovalSaysApprovalEvenWhenStatusIsWaiting() {
        val planner = SessionNotificationPlanner(mode = SessionNotificationMode.ALL_SESSIONS)
        planner.seed(listOf(session("a", "running")))
        val events = planner.onSnapshot(
            listOf(session("a", "waiting", waitKind = SessionWaitingKind.APPROVAL)),
        )
        assertEquals("needs your approval to continue", events.single().body)
        assertFalse(events.single().waitingForInput)
    }

    @Test
    fun waitReasonWireValuesMapToKinds() {
        assertEquals(SessionWaitingKind.INPUT, SessionWaitingKind.fromWireValue("input"))
        assertEquals(SessionWaitingKind.APPROVAL, SessionWaitingKind.fromWireValue("approval"))
        assertEquals(null, SessionWaitingKind.fromWireValue(null))
        assertEquals(null, SessionWaitingKind.fromWireValue("something_else"))
    }

    @Test
    fun missingKindFallsBackToTheStatus() {
        assertEquals(SessionWaitingKind.INPUT, SessionWaitingKind.fromStatus("waiting", null))
        assertEquals(SessionWaitingKind.APPROVAL, SessionWaitingKind.fromStatus("waiting_approval", null))
        assertEquals(null, SessionWaitingKind.fromStatus("idle", SessionWaitingKind.INPUT))
        assertEquals(
            SessionWaitingKind.INPUT,
            SessionWaitingKind.fromStatus("waiting_approval", SessionWaitingKind.INPUT),
        )
    }

    @Test
    fun interactionTypesMapToWaitingKinds() {
        assertEquals(SessionWaitingKind.INPUT, SessionWaitingKind.fromInteractionType("input_request"))
        assertEquals(SessionWaitingKind.INPUT, SessionWaitingKind.fromInteractionType("confirmation"))
        assertEquals(SessionWaitingKind.APPROVAL, SessionWaitingKind.fromInteractionType("approval"))
        assertEquals(null, SessionWaitingKind.fromInteractionType("execution_error"))
        assertEquals(null, SessionWaitingKind.fromInteractionType(null))
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
