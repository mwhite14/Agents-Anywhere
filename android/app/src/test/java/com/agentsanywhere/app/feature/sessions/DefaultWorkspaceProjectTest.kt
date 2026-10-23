package com.agentsanywhere.app.feature.sessions

import com.agentsanywhere.app.model.AgentProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultWorkspaceProjectTest {
    private fun project(
        id: String,
        connectorId: String = "device-a",
        pinned: Boolean = false,
        pinnedAt: String? = null,
        lastActivityAt: String? = null,
        updatedAt: String = "2026-01-01T00:00:00Z",
    ) = AgentProject(
        id = id,
        userId = "user",
        connectorId = connectorId,
        name = id,
        workspacePath = "/workspace/$id",
        pinned = pinned,
        pinnedAt = pinnedAt,
        activeSessionCount = 0,
        lastActivityAt = lastActivityAt,
        createdAt = "",
        updatedAt = updatedAt,
    )

    @Test
    fun picksMostRecentlyActiveProject() {
        val older = project("older", lastActivityAt = "2026-01-01T00:00:00Z")
        val newer = project("newer", lastActivityAt = "2026-05-01T00:00:00Z")
        assertEquals(newer, defaultWorkspaceProject(listOf(older, newer), "device-a"))
    }

    @Test
    fun pinnedProjectWinsOverNewerActivity() {
        val pinned = project("pinned", pinned = true, pinnedAt = "2026-01-01T00:00:00Z")
        val newer = project("newer", lastActivityAt = "2026-05-01T00:00:00Z")
        assertEquals(pinned, defaultWorkspaceProject(listOf(newer, pinned), "device-a"))
    }

    @Test
    fun ignoresProjectsFromOtherDevices() {
        val other = project("other", connectorId = "device-b", lastActivityAt = "2026-05-01T00:00:00Z")
        val local = project("local", lastActivityAt = "2026-01-01T00:00:00Z")
        assertEquals(local, defaultWorkspaceProject(listOf(other, local), "device-a"))
    }

    @Test
    fun noProjectsMeansNoDefault() {
        assertNull(defaultWorkspaceProject(emptyList(), "device-a"))
        assertNull(defaultWorkspaceProject(listOf(project("other", connectorId = "device-b")), "device-a"))
    }

    @Test
    fun unknownDeviceFallsBackToAnyProject() {
        val only = project("only", connectorId = "device-b")
        assertEquals(only, defaultWorkspaceProject(listOf(only), null))
    }
}
