package com.agentsanywhere.app.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The dashboard's waitReason is the only way to tell a question from an approval. */
class RemoteSessionWaitReasonTest {
    private fun parseSession(extra: String): RemoteSession =
        SessionsApi().parseSession(
            JSONObject(
                """{"id":"s1","connectorId":"c1","runtime":"dsh","status":"waiting_approval",
                   "updatedSeq":3$extra}""",
            ),
        )

    @Test
    fun readsAnInputWait() {
        val session = parseSession(""","waitReason":"input"""")
        assertEquals("input", session.waitReason)
    }

    @Test
    fun readsAnApprovalWait() {
        val session = parseSession(""","waitReason":"approval"""")
        assertEquals("approval", session.waitReason)
    }

    @Test
    fun anOlderServerWithoutTheFieldIsHarmless() {
        assertNull(parseSession("").waitReason)
    }
}
