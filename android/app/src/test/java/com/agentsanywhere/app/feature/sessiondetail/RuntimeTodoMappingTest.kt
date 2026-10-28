package com.agentsanywhere.app.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTodoMappingTest {
    @Test
    fun parsesTheWholeListAndKeepsWriteOrder() {
        val metadata = mapOf(
            "todos" to listOf(
                mapOf("content" to "Cut worktree", "status" to "completed"),
                mapOf("content" to "Add CI workflow", "status" to "in_progress"),
                mapOf("content" to "Publish APK", "status" to "pending"),
            ),
        )

        val todos = metadata.toRuntimeTodos()

        assertEquals(
            listOf(
                RuntimeTodo("Cut worktree", RuntimeTodoStatus.Completed),
                RuntimeTodo("Add CI workflow", RuntimeTodoStatus.InProgress),
                RuntimeTodo("Publish APK", RuntimeTodoStatus.Pending),
            ),
            todos,
        )
    }

    @Test
    fun missingOrEmptyListProducesNoCard() {
        assertTrue(emptyMap<String, Any?>().toRuntimeTodos().isEmpty())
        assertTrue(mapOf("todos" to emptyList<Any?>()).toRuntimeTodos().isEmpty())
        assertTrue(mapOf("todos" to null).toRuntimeTodos().isEmpty())
        assertTrue(mapOf("todos" to "not-a-list").toRuntimeTodos().isEmpty())
    }

    @Test
    fun malformedRowsAreSkippedWithoutDroppingTheRest() {
        val metadata = mapOf(
            "todos" to listOf(
                mapOf("content" to "Keep me", "status" to "pending"),
                mapOf("content" to "Unknown status", "status" to "blocked"),
                mapOf("content" to "   ", "status" to "pending"),
                mapOf("status" to "pending"),
                "not-a-map",
            ),
        )

        assertEquals(listOf(RuntimeTodo("Keep me", RuntimeTodoStatus.Pending)), metadata.toRuntimeTodos())
    }
}
