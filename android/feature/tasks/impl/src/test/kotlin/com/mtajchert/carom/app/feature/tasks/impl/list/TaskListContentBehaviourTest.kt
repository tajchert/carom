package com.mtajchert.carom.app.feature.tasks.impl.list

import com.mtajchert.carom.app.feature.tasks.impl.TaskUiError
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.mtajchert.carom.app.core.model.Task
import com.mtajchert.carom.app.core.model.TaskPriority
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

// =============================================================================
// COMPOSE BEHAVIOUR TESTS ON THE JVM
// =============================================================================
// These are the same Compose test APIs used for instrumented tests, executed
// locally through Robolectric. They live in `test` rather than `androidTest`,
// so they run in CI on every pull request instead of waiting for an emulator.
//
// Split of responsibilities:
//
// - Screenshot tests  - what the UI looks like
// - These tests       - how the UI reacts to state and to user actions
// - Device tests      - the few journeys that genuinely need real hardware
//
// They drive the stateless `TaskListContent`, so no ViewModel, Hilt graph,
// database or network is involved and each case is a pure state-in/behaviour-out
// assertion.
// =============================================================================

@RunWith(RobolectricTestRunner::class)
class TaskListContentBehaviourTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tasks = listOf(
        task(id = "1", title = "First task"),
        task(id = "2", title = "Second task", isCompleted = true),
    )

    @Test
    fun `empty state explains how to start`() {
        composeRule.setContent {
            TaskListContent(
                uiState = TaskListUiState.Empty,
                onTaskClick = {},
                onTaskCheckedChange = { _, _ -> },
                onDeleteTask = {},
                onCreateClick = {},
            )
        }

        composeRule.onNodeWithText("No tasks yet").assertIsDisplayed()
    }

    @Test
    fun `populated state shows every task`() {
        composeRule.setContent {
            TaskListContent(
                uiState = TaskListUiState.Success(tasks),
                onTaskClick = {},
                onTaskCheckedChange = { _, _ -> },
                onDeleteTask = {},
                onCreateClick = {},
            )
        }

        composeRule.onNodeWithText("First task").assertIsDisplayed()
        composeRule.onNodeWithText("Second task").assertIsDisplayed()
    }

    @Test
    fun `tapping a task reports its id`() {
        var clicked: String? = null
        composeRule.setContent {
            TaskListContent(
                uiState = TaskListUiState.Success(tasks),
                onTaskClick = { clicked = it },
                onTaskCheckedChange = { _, _ -> },
                onDeleteTask = {},
                onCreateClick = {},
            )
        }

        composeRule.onNodeWithText("Second task").performClick()

        assertEquals("2", clicked)
    }

    @Test
    fun `a write error is shown without hiding the loaded tasks`() {
        // A failed write must not blank the screen. The user keeps their data
        // and learns that the change did not stick.
        composeRule.setContent {
            TaskListContent(
                uiState = TaskListUiState.Success(
                    tasks = tasks,
                    errorMessage = TaskUiError.SaveFailed,
                ),
                onTaskClick = {},
                onTaskCheckedChange = { _, _ -> },
                onDeleteTask = {},
                onCreateClick = {},
            )
        }

        composeRule.onNodeWithText("Couldn't save the change. Please try again.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("First task").assertIsDisplayed()
    }

    @Test
    fun `the error state offers a retry that is wired up`() {
        var retried = false
        composeRule.setContent {
            TaskListContent(
                uiState = TaskListUiState.Error(TaskUiError.LoadTasksFailed),
                onTaskClick = {},
                onTaskCheckedChange = { _, _ -> },
                onDeleteTask = {},
                onRetry = { retried = true },
                onCreateClick = {},
            )
        }

        composeRule.onNodeWithText("Retry").performClick()

        assertEquals(true, retried)
    }

    @Test
    fun `demo import requires confirmation and cancel preserves the list`() {
        var imports = 0
        composeRule.setContent {
            TaskListContent(TaskListUiState.Success(tasks), {}, { _, _ -> }, {},
                onCreateClick = {}, onImportDemoTasks = { imports++ })
        }
        composeRule.onNodeWithContentDescription("Import demo tasks").performClick()
        assertEquals(0, imports)
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNodeWithText("First task").assertIsDisplayed()
        assertEquals(0, imports)
        composeRule.onNodeWithContentDescription("Import demo tasks").performClick()
        composeRule.onNodeWithText("Replace tasks").performClick()
        assertEquals(1, imports)
    }

    private fun task(
        id: String,
        title: String,
        isCompleted: Boolean = false,
    ): Task = Task(
        id = id,
        title = title,
        description = "Description for $title",
        isCompleted = isCompleted,
        createdAt = Instant.parse("2026-01-15T10:30:00Z"),
        priority = TaskPriority.MEDIUM,
    )
}
