package com.mtajchert.carom.app.feature.tasks.api

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Exercises the real URI matcher, argument validation, and synthetic stacks. */
@RunWith(RobolectricTestRunner::class)
class TasksDeepLinksTest {

    private fun resolveSegments(segments: List<String>) = TasksDeepLinks.resolve(
        Uri.Builder().scheme("https").authority("tajchert.github.io")
            .appendPath("tasks").apply { segments.forEach { appendPath(it) } }.build(),
    )

    @Test
    fun `tasks root opens the list`() {
        assertEquals(
            listOf(TaskListRoute),
            resolveSegments(emptyList()),
        )
    }

    @Test
    fun `new opens the list beneath the create screen`() {
        assertEquals(
            listOf(TaskListRoute, CreateTaskRoute),
            resolveSegments(listOf("new")),
        )
    }

    @Test
    fun `a task id opens the list beneath the detail screen`() {
        assertEquals(
            listOf(TaskListRoute, TaskDetailRoute(taskId = "abc-123")),
            resolveSegments(listOf("abc-123")),
        )
    }

    @Test
    fun `deep linked detail keeps the list underneath so back stays in the app`() {
        val backStack = resolveSegments(listOf("abc-123"))

        // The synthetic entry below the detail is the whole point: without it,
        // Back from a deep-linked detail leaves the app entirely.
        assertEquals(2, backStack?.size)
        assertEquals(TaskListRoute, backStack?.first())
    }

    @Test
    fun `an unknown path is rejected rather than guessed`() {
        // Any single well-formed segment is an identifier by design, so an
        // unknown path means a shape the contract does not define at all.
        assertNull(resolveSegments(listOf("abc-123", "edit")))
        assertNull(resolveSegments(listOf("new", "extra")))
        assertNull(resolveSegments(listOf("abc", "def", "ghi")))
    }

    @Test
    fun `identifiers outside the allowed shape are rejected`() {
        // Traversal-shaped, separator-bearing and oversized values never reach
        // a route argument.
        assertNull(resolveSegments(listOf("..")))
        assertNull(resolveSegments(listOf("id with spaces")))
        assertNull(resolveSegments(listOf("id'or'1=1")))
        assertNull(resolveSegments(listOf("a".repeat(65))))
    }

    @Test
    fun `a maximum length identifier is still accepted`() {
        val id = "a".repeat(64)
        assertEquals(
            listOf(TaskListRoute, TaskDetailRoute(taskId = id)),
            resolveSegments(listOf(id)),
        )
    }
}
